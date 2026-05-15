package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Captures OSRS scene geometry into one host-visible vertex buffer that's split
 * into six contiguous regions, one per {@link Layer}. The five static layers
 * (TERRAIN..GAME_OBJECTS) are populated by {@link #captureScene} on scene load;
 * the DYNAMIC layer is refilled each frame by {@link #captureModel}.
 *
 * <p>{@link #recordDraw} issues one draw per non-empty layer, picking the fill
 * or line pipeline based on each layer's individual wireframe flag, and binds
 * the OSRS texture array as descriptor set 0.
 *
 * <p>Vertex layout (matches {@link ScenePipeline}):
 * {@code [posX posY posZ colorR colorG colorB u v texLayer]} as 8 floats + 1
 * uint per vertex (36 bytes).
 */
@Slf4j
final class SceneRenderer implements AutoCloseable
{
	enum Layer
	{
		TERRAIN, WALLS, DECORATIVE, GROUND, GAME_OBJECTS, DYNAMIC;
	}
	private static final Layer[] LAYERS = Layer.values();
	private static final int LAYER_COUNT = LAYERS.length;
	private static final Layer LAST_STATIC = Layer.GAME_OBJECTS;

	private static final int MAX_VERTICES = 8_000_000;
	private static final long BUFFER_BYTES = (long) MAX_VERTICES * ScenePipeline.VERTEX_STRIDE;
	private static final int HSL_HIDDEN = 12345678;
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

	private final VulkanDevice device;
	private final FrameSync sync;
	private final ScenePipeline fillPipeline;
	private final ScenePipeline linePipeline;
	private final SceneVertexBuffer vbuf;
	private final ByteBuffer mapped;
	private final long descriptorSet;
	private final int slotBytes;

	private static final int MAX_PLANES = 4;
	private final int[] regionEnds = new int[LAYER_COUNT];
	/** Per-static-layer, per-plane: vertex count after that plane's tiles were emitted.
	 *  Used to clip TERRAIN at {@code planeEnds[TERRAIN][visiblePlane]} — upper-plane
	 *  terrain gets blanket-culled so the player can see down into rooms when
	 *  they're on a lower plane. Walls / decoratives / ground / gameobjects
	 *  render across all planes; fine-grained roof tile hiding (the engine's
	 *  hideRoofIds semantics) belongs on tile geometry and isn't wired yet —
	 *  see {@link #setHideRoofIds}. */
	private final int[][] planeEnds = new int[LAYER_COUNT][MAX_PLANES];
	private final boolean[] wireframe = new boolean[LAYER_COUNT];
	/** Plane range to render this frame. Set per-frame from preSceneDraw's
	 *  (minLevel, maxLevel). Stock GpuPlugin uses these exact bounds in
	 *  {@code Zone.renderOpaque(..., minLevel, level, maxLevel, ...)} —
	 *  geometry on planes outside this range is skipped. This is how the
	 *  engine hides upper-floor structures (the Wintertodt lean-to's roof
	 *  faces sit on plane &gt; 0 and stock skips them because maxLevel=0). */
	private volatile int minPlane = 0;
	private volatile int maxPlane = MAX_PLANES - 1;

	/** Per-frame from {@code preSceneDraw}: the set of roof IDs that the engine
	 *  wants hidden above the player. Values are tile-roof IDs from
	 *  {@link net.runelite.api.Scene#getRoofs()}, NOT GameObject IDs. */
	private volatile java.util.Set<Integer> hideRoofIds = java.util.Collections.emptySet();

	/** Per-tile-range roof tag, recorded at {@link #captureScene} time for every
	 *  {@code SceneTilePaint} / {@code SceneTileModel} whose
	 *  {@code Scene.getRoofs()[plane][tx][tz]} is non-zero. At draw the TERRAIN
	 *  layer is split into "render" and "skip" sub-ranges based on which
	 *  roof IDs land in the current frame's {@link #hideRoofIds}. Stock
	 *  GpuPlugin offloads this filter to the engine's {@code Zone.renderOpaque};
	 *  we capture up front, so we apply it ourselves. */
	private int[] tileRoofIds      = new int[2048];
	private int[] tileRoofStarts   = new int[2048];
	private int[] tileRoofCounts   = new int[2048];
	private int   tileRoofCount;
	/** Sorted [start, end) pairs of vertex sub-ranges to skip in the current
	 *  frame's draw. Grown on demand. */
	private int[] skipScratch = new int[256];

	private int vertexCount;
	private boolean overflowed;

	/** Scratch sorter for dynamic-model face ordering — ported from stock
	 *  {@code FacePrioritySorter}. Used by {@link #captureModelSorted}.
	 *  Single instance is fine: all captures run on the Client thread. */
	private final ModelSorter sorter = new ModelSorter();

	SceneRenderer(VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray)
	{
		this.device = device;
		this.sync = sync;
		this.fillPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL, true, renderPass.samples());
		// linePipeline needs the fillModeNonSolid feature. On devices that
		// don't support it (rare: llvmpipe, a few embedded SoCs) we fall back
		// to null — wireframe toggles then collapse to FILL silently.
		this.linePipeline = device.supportsFillModeNonSolid()
			? new ScenePipeline(device, renderPass, VK_POLYGON_MODE_LINE, true, renderPass.samples())
			: null;
		this.vbuf = new SceneVertexBuffer(device, BUFFER_BYTES, FrameSync.FRAMES_IN_FLIGHT,
			fillPipeline.descriptorSetLayout(), textureArray);
		this.mapped = vbuf.mapped();
		this.descriptorSet = vbuf.descriptorSet();
		this.slotBytes = (int) vbuf.slotBytes();
	}

	void setWireframe(Layer layer, boolean on)
	{
		wireframe[layer.ordinal()] = on;
	}

	/** Set the [minPlane, maxPlane] inclusive render range for this frame,
	 *  forwarded from {@code preSceneDraw}'s (minLevel, maxLevel). Stock
	 *  GpuPlugin uses these as bounds in {@code Zone.renderOpaque}; planes
	 *  outside the range are skipped by the engine. We mirror it across
	 *  every layer. */
	void setLevelRange(int minLevel, int maxLevel)
	{
		minPlane = Math.max(0, Math.min(MAX_PLANES - 1, minLevel));
		maxPlane = Math.max(minPlane, Math.min(MAX_PLANES - 1, maxLevel));
	}

	/** Store the engine's per-frame hideRoofIds set. These are roof IDs
	 *  (values from {@link net.runelite.api.Scene#getRoofs()}, NOT GameObject
	 *  IDs) that should be hidden above the player. Currently a no-op holder:
	 *  proper application requires tagging tile geometry with its roof ID at
	 *  capture time and filtering ranges at draw — wiring that up is the next
	 *  step. Until then, this method exists so {@code preSceneDraw} can keep
	 *  calling it without churn. */
	void setHideRoofIds(java.util.Set<Integer> ids)
	{
		hideRoofIds = ids != null ? ids : java.util.Collections.emptySet();
	}

	/** Per-frame dedupe set: keys (Model identity, worldX, worldZ). If the
	 *  same model is captured at the same world-XZ twice in one frame, the
	 *  second is dropped. Catches the "two heads on the player" symptom no
	 *  matter which path is doing the duplicate capture (captureActors,
	 *  drawDynamic, drawTemp, …). Cleared on beginFrame. */
	private final java.util.HashSet<Long> seenCaptures = new java.util.HashSet<>();

	/**
	 * Drops the dynamic suffix; static layers are preserved. Position the write
	 * cursor at the dynamic-region start of this frame's slot — the static
	 * prefix is already mirrored across all slots by {@link #captureScene}.
	 *
	 * <p>Waits on this slot's in-flight fence before any CPU write. The OSRS
	 * engine calls drawScene → drawDynamic → drawTemp → draw, so the CPU writes
	 * happen before renderer.drawFrame and its own vkWaitForFences. Without
	 * this wait, the CPU overwrites slot[currentFrame] while the GPU is still
	 * reading the same slot's vertices from FRAMES_IN_FLIGHT frames ago —
	 * producing torn-vertex flicker that is wider on macOS because Metal's
	 * deferred submission keeps frames in flight longer.
	 */
	void beginFrame()
	{
		try (MemoryStack stack = stackPush())
		{
			vkWaitForFences(device.handle(), stack.longs(sync.inFlightFence()), true, Long.MAX_VALUE);
		}
		vertexCount = regionEnds[LAST_STATIC.ordinal()];
		mapped.position(sync.currentFrame() * slotBytes + vertexCount * ScenePipeline.VERTEX_STRIDE);
		overflowed = false;
		seenCaptures.clear();
	}

	/** Visits one tile (including any bridge tile underneath) within a layer pass. */
	@FunctionalInterface
	private interface TileCapture
	{
		void capture(Tile tile, int plane, int sx, int sy);
	}

	/**
	 * Walks every tile and emits all static geometry — terrain, walls,
	 * decorative objects, ground objects, and static game objects — into their
	 * respective layer regions.
	 */
	void captureScene(Scene scene)
	{
		// Drain the GPU before rewriting the buffer; captureScene is rare
		// (region change) and the buffer is shared across all in-flight frames.
		vkDeviceWaitIdle(device.handle());

		for (int i = 0; i < LAYER_COUNT; i++)
		{
			regionEnds[i] = 0;
			for (int p = 0; p < MAX_PLANES; p++) planeEnds[i][p] = 0;
		}
		vertexCount = 0;
		mapped.position(0);
		overflowed = false;
		tileRoofCount = 0;

		Tile[][][] tiles = scene.getTiles();
		if (tiles == null) return;
		final int planes = Math.min(tiles.length, MAX_PLANES);
		final int sceneSize = Constants.SCENE_SIZE;

		// Scene.getRoofs() is [level][extended_tile_x][extended_tile_z] — its
		// X/Z dimensions are EXTENDED_SCENE_SIZE (184) for top-level scenes,
		// not SCENE_SIZE (104) like scene.getTiles(). Add SCENE_OFFSET (40)
		// to scene-coord (sx, sy) for top-level worlds; instances aren't
		// extended so the offset is 0.
		final int[][][] roofs = scene.getRoofs();
		final int roofOffset = scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;

		// Pass order: emit each layer plane-by-plane so vertices are sorted
		// (layer-major, plane-minor). recordDraw clips per layer at
		// planeEnds[layer][visiblePlane] to hide roofs above the player.
		captureLayer(Layer.TERRAIN, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				SceneTilePaint paint = cur.getSceneTilePaint();
				if (paint != null) captureTilePaint(scene, paint, p, sx, sy);
				SceneTileModel m = cur.getSceneTileModel();
				if (m != null) captureTileModel(m, sx, sy);
			});

		captureLayer(Layer.WALLS, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				WallObject w = cur.getWallObject();
				if (w == null) return;
				captureRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
				captureRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
			});

		captureLayer(Layer.DECORATIVE, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				DecorativeObject d = cur.getDecorativeObject();
				if (d == null) return;
				captureRenderable(d.getRenderable(),  0,
					d.getX() + d.getXOffset(),  d.getZ(), d.getY() + d.getYOffset());
				captureRenderable(d.getRenderable2(), 0,
					d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2());
			});

		captureLayer(Layer.GROUND, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				GroundObject g = cur.getGroundObject();
				if (g == null) return;
				captureRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
			});

		captureGameObjectsLayer(tiles, planes, sceneSize, roofs, roofOffset);

		// Tile arrays only had `planes` valid entries; pad the rest of
		// planeEnds with the layer's final size so later max-plane lookups
		// still draw everything if requested.
		for (int i = 0; i < LAYER_COUNT; i++)
			for (int p = planes; p < MAX_PLANES; p++)
				planeEnds[i][p] = regionEnds[i];

		copyStaticPrefixToAllSlots(vertexCount * ScenePipeline.VERTEX_STRIDE);

		log.debug("captureScene: {} vertices, {} roof tiles", vertexCount, tileRoofCount);
	}

	/**
	 * After writing the static layers into slot 0, mirror that prefix into
	 * every other slot so all FRAMES_IN_FLIGHT bindings see identical static
	 * geometry. Only the dynamic suffix differs per frame.
	 */
	private void copyStaticPrefixToAllSlots(int staticBytes)
	{
		if (staticBytes <= 0) return;
		long base = MemoryUtil.memAddress(mapped, 0);
		for (int slot = 1; slot < FrameSync.FRAMES_IN_FLIGHT; slot++)
		{
			MemoryUtil.memCopy(base, base + (long) slot * slotBytes, staticBytes);
		}
	}

	/**
	 * Iterates every tile of {@code layer} plane-by-plane, invoking {@code body}
	 * once per tile (and once per bridge tile). Each call's vertex production is
	 * tracked so that any geometry emitted onto a roof tile is recorded into the
	 * roof-skip table — see {@link #recordRoofRange}. Stock recurses into
	 * {@code Tile.getBridge()} in SceneUploader.java:366 so we do too; bridge
	 * tiles hold geometry for stacked structures (arches, decks) that would
	 * otherwise be missed.
	 */
	private void captureLayer(Layer layer,
		Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, int roofOffset,
		TileCapture body)
	{
		for (int p = 0; p < planes; p++)
		{
			for (int sx = 0; sx < sceneSize; sx++)
				for (int sy = 0; sy < sceneSize; sy++)
				{
					Tile t = tiles[p][sx][sy];
					if (t == null) continue;
					int roofId = tileRoofIdAt(roofs, p, sx + roofOffset, sy + roofOffset);
					Tile cur = t;
					while (cur != null)
					{
						int before = vertexCount;
						body.capture(cur, p, sx, sy);
						if (roofId != 0 && vertexCount > before)
							recordRoofRange(roofId, before, vertexCount - before);
						cur = (cur == t) ? t.getBridge() : null;
					}
				}
			planeEnds[layer.ordinal()][p] = vertexCount;
		}
		regionEnds[layer.ordinal()] = vertexCount;
	}

	/**
	 * GameObject capture differs from the other layers: stock emits each object
	 * exactly once on its south-west sceneMinLocation tile (multi-tile objects
	 * naturally dedupe across the tiles they cover), and each object gets its
	 * own roof-range entry rather than one merged range per tile.
	 */
	private void captureGameObjectsLayer(Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, int roofOffset)
	{
		for (int p = 0; p < planes; p++)
		{
			for (int sx = 0; sx < sceneSize; sx++)
				for (int sy = 0; sy < sceneSize; sy++)
				{
					Tile t = tiles[p][sx][sy];
					if (t == null) continue;
					int roofId = tileRoofIdAt(roofs, p, sx + roofOffset, sy + roofOffset);
					Tile cur = t;
					while (cur != null)
					{
						GameObject[] objs = cur.getGameObjects();
						if (objs == null) { cur = (cur == t) ? t.getBridge() : null; continue; }
						net.runelite.api.Point tilePoint = cur.getSceneLocation();
						for (GameObject o : objs)
						{
							if (o == null) continue;
							net.runelite.api.Point min = o.getSceneMinLocation();
							if (min == null || !min.equals(tilePoint)) continue;
							int before = vertexCount;
							// getModelOrientation(), not getOrientation() — the latter folds
							// in animation orient and rotates static arches / walls / fences.
							captureRenderable(o.getRenderable(), o.getModelOrientation(),
								o.getX(), o.getZ(), o.getY());
							if (roofId != 0 && vertexCount > before)
								recordRoofRange(roofId, before, vertexCount - before);
						}
						cur = (cur == t) ? t.getBridge() : null;
					}
				}
			planeEnds[Layer.GAME_OBJECTS.ordinal()][p] = vertexCount;
		}
		regionEnds[Layer.GAME_OBJECTS.ordinal()] = vertexCount;
	}

	private void captureRenderable(Renderable r, int orient, int x, int y, int z)
	{
		if (r == null) return;
		// Mirror stock GpuPlugin's uploadZoneRenderable (SceneUploader.java:412):
		// for DynamicObject, use getModelZbuf() (thread-safe, no animation)
		// rather than the generic getModel() which can return a half-animated
		// model or null while the animation thread is updating.
		Model m;
		if (r instanceof Model)
		{
			m = (Model) r;
		}
		else if (r instanceof net.runelite.api.DynamicObject)
		{
			m = ((net.runelite.api.DynamicObject) r).getModelZbuf();
		}
		else
		{
			m = r.getModel();
		}
		if (m == null) return;
		captureModel(m, orient, x, y, z);
	}

	private void captureTilePaint(Scene scene, SceneTilePaint paint, int plane, int sx, int sy)
	{
		int neColor = paint.getNeColor();
		if (neColor == HSL_HIDDEN) return;

		int swColor = paint.getSwColor();
		int seColor = paint.getSeColor();
		int nwColor = paint.getNwColor();

		int[][][] tileHeights = scene.getTileHeights();
		int ex = sx + SCENE_OFFSET;
		int ey = sy + SCENE_OFFSET;
		int swH = tileHeights[plane][ex    ][ey    ];
		int seH = tileHeights[plane][ex + 1][ey    ];
		int neH = tileHeights[plane][ex + 1][ey + 1];
		int nwH = tileHeights[plane][ex    ][ey + 1];

		int x0 = sx << 7, x1 = x0 + 128;
		int z0 = sy << 7, z1 = z0 + 128;

		// paint.getTexture() returns -1 for no-texture, otherwise the OSRS
		// texture id. Layer 0 is reserved white so +1 maps cleanly.
		int texLayer = paint.getTexture() + 1;

		if (vertexCount + 6 > MAX_VERTICES) { overflow(); return; }

		writeHslVert(x0, swH, z0, swColor, 0f, 0f, texLayer);
		writeHslVert(x1, seH, z0, seColor, 1f, 0f, texLayer);
		writeHslVert(x1, neH, z1, neColor, 1f, 1f, texLayer);

		writeHslVert(x0, swH, z0, swColor, 0f, 0f, texLayer);
		writeHslVert(x1, neH, z1, neColor, 1f, 1f, texLayer);
		writeHslVert(x0, nwH, z1, nwColor, 0f, 1f, texLayer);

		vertexCount += 6;
	}

	private void captureTileModel(SceneTileModel model, int sx, int sy)
	{
		int[] faceX = model.getFaceX();
		int[] faceY = model.getFaceY();
		int[] faceZ = model.getFaceZ();
		if (faceX == null) return;

		int[] vertexX = model.getVertexX();
		int[] vertexY = model.getVertexY();
		int[] vertexZ = model.getVertexZ();
		int[] colorA = model.getTriangleColorA();
		int[] colorB = model.getTriangleColorB();
		int[] colorC = model.getTriangleColorC();
		int[] triangleTextures = model.getTriangleTextureId();

		// Tile origin used to derive UVs from vertex positions (each tile is
		// 128 OSRS units across; UV = local_offset / 128).
		float lx = sx << 7;
		float lz = sy << 7;

		int faces = faceX.length;
		if (vertexCount + faces * 3 > MAX_VERTICES) { overflow(); return; }

		for (int i = 0; i < faces; i++)
		{
			int a = colorA[i];
			if (a == HSL_HIDDEN) continue;
			int b = colorB[i], c = colorC[i];
			int v0 = faceX[i], v1 = faceY[i], v2 = faceZ[i];
			int texLayer = triangleTextures != null && triangleTextures[i] != -1
				? triangleTextures[i] + 1 : 0;
			float u0 = (vertexX[v0] - lx) / 128f, w0 = (vertexZ[v0] - lz) / 128f;
			float u1 = (vertexX[v1] - lx) / 128f, w1 = (vertexZ[v1] - lz) / 128f;
			float u2 = (vertexX[v2] - lx) / 128f, w2 = (vertexZ[v2] - lz) / 128f;
			writeHslVert(vertexX[v0], vertexY[v0], vertexZ[v0], a, u0, w0, texLayer);
			writeHslVert(vertexX[v1], vertexY[v1], vertexZ[v1], b, u1, w1, texLayer);
			writeHslVert(vertexX[v2], vertexY[v2], vertexZ[v2], c, u2, w2, texLayer);
			vertexCount += 3;
		}
	}

	void captureModel(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		if (m == null) return;

		// Dedupe by (Model identity, world XZ). The engine emits the local
		// player through both captureActors() AND drawTemp (with a non-null
		// synthetic GameObject wrapper, so the gameObject != null guard
		// doesn't catch it). Same model at same position twice in one frame
		// = drop the second. Verified visually: cures "two heads on the
		// player". Trees/NPCs unaffected since they're at distinct positions.
		long key = ((long) System.identityHashCode(m) & 0xFFFFFFFFL)
			| ((long) (worldX & 0xFFFF) << 32)
			| ((long) (worldZ & 0xFFFF) << 48);
		if (!seenCaptures.add(key)) return;

		float[] vx = m.getVerticesX();
		float[] vy = m.getVerticesY();
		float[] vz = m.getVerticesZ();
		int[] fa = m.getFaceIndices1();
		int[] fb = m.getFaceIndices2();
		int[] fc = m.getFaceIndices3();
		if (fa == null || fb == null || fc == null) return;

		int[] c1 = m.getFaceColors1();
		int[] c2 = m.getFaceColors2();
		int[] c3 = m.getFaceColors3();

		short[] faceTextures   = m.getFaceTextures();
		byte[]  textureFaces   = m.getTextureFaces();
		int[]   texIndicesA    = m.getTexIndices1();
		int[]   texIndicesB    = m.getTexIndices2();
		int[]   texIndicesC    = m.getTexIndices3();
		byte[]  faceTransparencies = m.getFaceTransparencies();
		byte[]  faceBias = m.getFaceBias();

		double theta = (orient & 0x7FF) * (Math.PI * 2.0 / 2048.0);
		float cos = (float) Math.cos(theta);
		float sin = (float) Math.sin(theta);

		// Use Mesh.getFaceCount() — NOT fa.length. The engine over-allocates
		// face index arrays for assembled actor models (player composition,
		// reused NPC bodies) so the array can hold worst-case-equipped data,
		// then sets getFaceCount() to the actual face count for the current
		// composition. Reading fa.length renders the trailing stale data on
		// top of the live mesh — that's how we ended up with the player
		// rendering twice ("two heads") even when wearing no equipment: the
		// trailing slots still held the previous tick's helmet/body faces.
		int faces = m.getFaceCount();
		if (vertexCount + faces * 3 > MAX_VERTICES) { overflow(); return; }

		float[] uv = uvScratch;

		// Track actual writes — skipped faces (c3 == -2 or transparent) don't
		// produce vertices. Previously this used `faces * 3` regardless, which
		// over-incremented vertexCount by skipped*3 every model. The next
		// captureModel then started writing past the actual data, leaving a
		// gap of stale (previous-frame) bytes that the renderer still drew.
		int wrote = 0;
		for (int f = 0; f < faces; f++)
		{
			int col1 = c1 != null ? c1[f] : 0;
			int col2 = col1, col3 = col1;
			if (c3 != null)
			{
				int raw3 = c3[f];
				if (raw3 == -2) continue;
				if (raw3 != -1)
				{
					col2 = c2[f];
					col3 = raw3;
				}
			}

			// DO NOT skip on transparency at CPU level. Stock GpuPlugin doesn't
			// — it routes transparency!=0 faces to its alpha pass instead. OSRS
			// uses faceTransparencies for cloth drapes, glass, water, banners,
			// and other very-visible-but-translucent geometry. Skipping at e.g.
			// `>= 252` drops tent drape faces, banner cloth, tarp folds, etc.,
			// leaving only the solid scaffolding — which then hides everything
			// the drapes used to "open" onto (interior chests, beds, shelving).
			// We render these as opaque here (no alpha blending yet) which is
			// not perfect but at least the geometry is present.
			//
			// The only thing we DO want to drop is the engine's
			// "essentially invisible" sentinel (transparency == 0xFF), which is
			// used for door cutout fills, portal plug faces, etc. — those would
			// otherwise render as opaque black rectangles. We push that decision
			// to the fragment shader via the packed transparency byte below
			// (frag discards when trans == 255), preserving everything else.

			int texLayer = 0;
			float u0 = 0, v0 = 0, u1 = 0, v1 = 0, u2 = 0, v2 = 0;
			if (faceTextures != null && faceTextures[f] != -1)
			{
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vx, vy, vz, fa[f], fb[f], fc[f],
					textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			// Pack into one uint (matches stock's `alphaBias` int):
			//   bits  0..15 : texture layer index (0 = no texture)
			//   bits 16..23 : per-face depth bias (used by vert.glsl to nudge
			//                 clip-Z and break coplanar z-fighting; stock does
			//                 the same via `(abhsl >> 16) & 0xff` + `bias/128`)
			//   bits 24..31 : face transparency (0 = opaque, 255 = sentinel
			//                 "invisible"; frag discards on 255)
			int bias = faceBias != null ? (faceBias[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);

			writeRotatedVertex(vx[fa[f]], vy[fa[f]], vz[fa[f]], cos, sin, worldX, worldY, worldZ, col1, u0, v0, packedTexLayer);
			writeRotatedVertex(vx[fb[f]], vy[fb[f]], vz[fb[f]], cos, sin, worldX, worldY, worldZ, col2, u1, v1, packedTexLayer);
			writeRotatedVertex(vx[fc[f]], vy[fc[f]], vz[fc[f]], cos, sin, worldX, worldY, worldZ, col3, u2, v2, packedTexLayer);
			wrote += 3;
		}
		vertexCount += wrote;
	}

	/**
	 * Sorted variant of {@link #captureModel} — runs the model through
	 * {@link ModelSorter} (camera-space depth bucketing + back-face cull +
	 * near-plane reject), then emits the model's triangles back-to-front.
	 * Use this from the engine's {@code drawDynamic} / {@code drawTemp}
	 * callbacks where a {@link Projection} is supplied; the existing
	 * unsorted {@link #captureModel} stays in place for the actor walk,
	 * which has no Projection of its own.
	 *
	 * <p>Same dedupe behavior as {@code captureModel} (Model identity +
	 * world XZ).
	 */
	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ)
	{
		if (m == null || proj == null) return;

		long key = ((long) System.identityHashCode(m) & 0xFFFFFFFFL)
			| ((long) (worldX & 0xFFFF) << 32)
			| ((long) (worldZ & 0xFFFF) << 48);
		if (!seenCaptures.add(key)) return;

		// Project + bin. Returns false on near-plane reject / oversized / null arrays
		// — in any of those cases stock skips the model entirely, so do we.
		if (!sorter.sort(proj, m, orient, worldX, worldY, worldZ))
		{
			return;
		}

		int faces = sorter.sortedCount;
		if (faces == 0) return;
		if (vertexCount + faces * 3 > MAX_VERTICES) { overflow(); return; }

		float[] vxs = m.getVerticesX();   // unused on emit (we use post-rotate localX)
		float[] vys = m.getVerticesY();   // ditto
		float[] vzs = m.getVerticesZ();   // ditto
		int[] fa = m.getFaceIndices1();
		int[] fb = m.getFaceIndices2();
		int[] fc = m.getFaceIndices3();

		int[] c1 = m.getFaceColors1();
		int[] c2 = m.getFaceColors2();
		int[] c3 = m.getFaceColors3();

		short[] faceTextures   = m.getFaceTextures();
		byte[]  textureFaces   = m.getTextureFaces();
		int[]   texIndicesA    = m.getTexIndices1();
		int[]   texIndicesB    = m.getTexIndices2();
		int[]   texIndicesC    = m.getTexIndices3();
		byte[]  faceTransparencies = m.getFaceTransparencies();
		byte[]  faceBiasArr = m.getFaceBias();

		float[] uv = uvScratch;

		// localX/Y/Z already have orientation + worldXYZ applied by the sorter.
		float[] lx = sorter.localX;
		float[] ly = sorter.localY;
		float[] lz = sorter.localZ;

		int wrote = 0;
		for (int i = 0; i < faces; i++)
		{
			int f = sorter.sortedFaces[i];

			// Same color resolve as captureModel (sorter has already dropped
			// c3 == -2 faces, so raw3 here is never the skip sentinel).
			int col1 = c1 != null ? c1[f] : 0;
			int col2 = col1, col3 = col1;
			if (c3 != null)
			{
				int raw3 = c3[f];
				if (raw3 != -1)
				{
					col2 = c2[f];
					col3 = raw3;
				}
			}

			int texLayer = 0;
			float u0 = 0, v0 = 0, u1 = 0, v1 = 0, u2 = 0, v2 = 0;
			if (faceTextures != null && faceTextures[f] != -1)
			{
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vxs, vys, vzs, fa[f], fb[f], fc[f],
					textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			int bias = faceBiasArr != null ? (faceBiasArr[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);

			// writeHslVert (no rotation) — sorter's lx/ly/lz are already rotated.
			writeHslVert(lx[fa[f]], ly[fa[f]], lz[fa[f]], col1, u0, v0, packedTexLayer);
			writeHslVert(lx[fb[f]], ly[fb[f]], lz[fb[f]], col2, u1, v1, packedTexLayer);
			writeHslVert(lx[fc[f]], ly[fc[f]], lz[fc[f]], col3, u2, v2, packedTexLayer);
			wrote += 3;
		}
		vertexCount += wrote;
	}

	/** Reusable scratch for {@link #computeFaceUvs} — six floats: u0, v0, u1, v1, u2, v2. */
	private final float[] uvScratch = new float[6];

	/**
	 * Port of stock {@code SceneUploader.computeFaceUvs}. Given a face's three
	 * vertex indices and the optional per-face texture-face mapping, computes
	 * per-vertex UVs in [0, 1] and writes them into {@code out} as
	 * {@code [u0, v0, u1, v1, u2, v2]}.
	 *
	 * <p>If {@code textureFaces[face]} is -1 (or {@code textureFaces} is null)
	 * the face is mapped trivially: vertex A → (0,0), B → (1,0), C → (0,1).
	 */
	private static void computeFaceUvs(float[] out,
									   float[] vx, float[] vy, float[] vz,
									   int triA, int triB, int triC,
									   byte[] textureFaces,
									   int[] texIndicesA, int[] texIndicesB, int[] texIndicesC,
									   int face)
	{
		if (textureFaces == null || textureFaces[face] == -1
			|| texIndicesA == null || texIndicesB == null || texIndicesC == null)
		{
			out[0] = 0f; out[1] = 0f;
			out[2] = 1f; out[3] = 0f;
			out[4] = 0f; out[5] = 1f;
			return;
		}

		int tfaceIdx = textureFaces[face] & 0xff;
		int texA = texIndicesA[tfaceIdx];
		int texB = texIndicesB[tfaceIdx];
		int texC = texIndicesC[tfaceIdx];

		float v1x = vx[texA], v1y = vy[texA], v1z = vz[texA];
		float v2x = vx[texB] - v1x, v2y = vy[texB] - v1y, v2z = vz[texB] - v1z;
		float v3x = vx[texC] - v1x, v3y = vy[texC] - v1y, v3z = vz[texC] - v1z;

		float v4x = vx[triA] - v1x, v4y = vy[triA] - v1y, v4z = vz[triA] - v1z;
		float v5x = vx[triB] - v1x, v5y = vy[triB] - v1y, v5z = vz[triB] - v1z;
		float v6x = vx[triC] - v1x, v6y = vy[triC] - v1y, v6z = vz[triC] - v1z;

		float v7x = v2y * v3z - v2z * v3y;
		float v7y = v2z * v3x - v2x * v3z;
		float v7z = v2x * v3y - v2y * v3x;

		// u: project onto v3-perpendicular axis through v2
		float v8x = v3y * v7z - v3z * v7y;
		float v8y = v3z * v7x - v3x * v7z;
		float v8z = v3x * v7y - v3y * v7x;
		float f = 1f / (v8x * v2x + v8y * v2y + v8z * v2z);
		out[0] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
		out[2] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
		out[4] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;

		// v: project onto v2-perpendicular axis through v3
		v8x = v2y * v7z - v2z * v7y;
		v8y = v2z * v7x - v2x * v7z;
		v8z = v2x * v7y - v2y * v7x;
		f = 1f / (v8x * v3x + v8y * v3y + v8z * v3z);
		out[1] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
		out[3] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
		out[5] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;
	}

	void recordDraw(VkCommandBuffer cmd, float[] mvp, float brightness,
					float cameraX, float cameraZ, float drawDistanceTiles, float fogDepthTiles,
					float fogR, float fogG, float fogB,
					int tick, float textureLightMode,
					int colorBlindMode, float colorBlindIntensity)
	{
		if (vertexCount == 0) return;
		// Slot offset folded into firstVertex below. Binding the buffer
		// at offset 0 and shifting via firstVertex is mathematically
		// equivalent to binding with the slot's byte-offset, but avoids
		// MoltenVK's vertex-buffer-offset translation path which appears
		// to produce no visible geometry for offsets in the hundreds of
		// megabytes on Apple Silicon.
		final int slotFirstVertex = sync.currentFrame() * MAX_VERTICES;
		try (MemoryStack stack = stackPush())
		{
			vkCmdBindVertexBuffers(cmd, 0,
				stack.longs(vbuf.handle()),
				stack.longs(0L));

			// Vertex push (96 bytes): mat4 mvp + vec4 fogVtx + ivec4 (tick + pad).
			ByteBuffer vertPush = stack.malloc(96);
			Mat4Ops.writeTo(vertPush, mvp);
			vertPush.position(64);
			vertPush.putFloat(cameraX);
			vertPush.putFloat(cameraZ);
			vertPush.putFloat(drawDistanceTiles * 128f);
			vertPush.putFloat(fogDepthTiles * 128f);
			vertPush.putInt(tick).putInt(0).putInt(0).putInt(0);
			vertPush.flip();

			// Fragment push (32 bytes at offset 96):
			//   vec4 fogFrag     = (fogR, fogG, fogB, brightness)
			//   vec4 fragExtras  = (textureLightMode, colorBlindMode, colorBlindIntensity, _)
			ByteBuffer fragPush = stack.malloc(32);
			fragPush.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(brightness);
			fragPush.putFloat(textureLightMode);
			fragPush.putFloat((float) colorBlindMode);
			fragPush.putFloat(colorBlindIntensity);
			fragPush.putFloat(0f);
			fragPush.flip();

			// Build skip-list for this frame: tile-roof ranges whose roof ID
			// the engine wants hidden. Empty when RoofRemovalPlugin isn't
			// active (engine passes hideRoofIds={}); kept correct for when
			// it is. recordRoofRange is called in captureScene order, so
			// tileRoofStarts is already sorted by start — no extra sort.
			int skipPairs = 0;
			java.util.Set<Integer> hr = hideRoofIds;
			if (!hr.isEmpty() && tileRoofCount > 0)
			{
				for (int r = 0; r < tileRoofCount; r++)
				{
					if (hr.contains(tileRoofIds[r]))
					{
						if (skipPairs * 2 + 2 > skipScratch.length)
						{
							skipScratch = java.util.Arrays.copyOf(skipScratch, skipScratch.length * 2);
						}
						skipScratch[skipPairs * 2]     = tileRoofStarts[r];
						skipScratch[skipPairs * 2 + 1] = tileRoofStarts[r] + tileRoofCounts[r];
						skipPairs++;
					}
				}
			}

			ScenePipeline boundPipeline = null;
			final int loMin = minPlane;
			final int loMax = maxPlane;
			int layerStart = 0;
			for (int i = 0; i < LAYER_COUNT; i++)
			{
				int regionEnd = i == LAYER_COUNT - 1 ? vertexCount : regionEnds[i];
				// All static layers were emitted plane-by-plane, so
				// [planeEnds[i][loMin-1], planeEnds[i][loMax]) is exactly the
				// run of vertices for planes [loMin..loMax]. DYNAMIC (last
				// layer) renders whole — actors/temps aren't plane-tagged.
				int drawStart, drawEnd;
				if (LAYERS[i] == Layer.DYNAMIC)
				{
					drawStart = layerStart;
					drawEnd = regionEnd;
				}
				else
				{
					int layerLo = (loMin == 0) ? layerStart : planeEnds[i][loMin - 1];
					int layerHi = planeEnds[i][loMax];
					drawStart = layerLo;
					drawEnd = layerHi;
				}
				int count = drawEnd - drawStart;
				if (count > 0)
				{
					ScenePipeline want = (wireframe[i] && linePipeline != null) ? linePipeline : fillPipeline;
					if (want != boundPipeline)
					{
						vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, want.handle());
						vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
							want.layout(), 0, stack.longs(descriptorSet), null);
						boundPipeline = want;
					}
					// Re-push every draw. MoltenVK #2483: push constant values
					// can become undefined across consecutive vkCmdDraw* on the
					// same command buffer when not re-pushed per draw. Cheap on
					// the CPU side; eliminates an entire class of macOS-only
					// glitches where some draws read stale MVP/fog values.
					vkCmdPushConstants(cmd, want.layout(), VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
					vkCmdPushConstants(cmd, want.layout(), VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
					if (skipPairs > 0)
					{
						drawWithSkips(cmd, drawStart, drawEnd, skipScratch, skipPairs, slotFirstVertex,
							want.layout(), vertPush, fragPush);
					}
					else
					{
						vkCmdDraw(cmd, count, 1, slotFirstVertex + drawStart, 0);
					}
				}
				layerStart = regionEnd;
			}
		}
	}

	private static int tileRoofIdAt(int[][][] roofs, int p, int sx, int sy)
	{
		if (roofs == null || p >= roofs.length) return 0;
		int[][] plane = roofs[p];
		if (plane == null || sx >= plane.length) return 0;
		int[] row = plane[sx];
		if (row == null || sy >= row.length) return 0;
		return row[sy];
	}

	private void recordRoofRange(int roofId, int vertexStart, int vertexCount)
	{
		if (tileRoofCount == tileRoofIds.length)
		{
			int newSize = tileRoofIds.length * 2;
			tileRoofIds    = java.util.Arrays.copyOf(tileRoofIds,    newSize);
			tileRoofStarts = java.util.Arrays.copyOf(tileRoofStarts, newSize);
			tileRoofCounts = java.util.Arrays.copyOf(tileRoofCounts, newSize);
		}
		tileRoofIds   [tileRoofCount] = roofId;
		tileRoofStarts[tileRoofCount] = vertexStart;
		tileRoofCounts[tileRoofCount] = vertexCount;
		tileRoofCount++;
	}

	/** Issue {@code vkCmdDraw} calls covering [start, end) but skipping each
	 *  [skips[2k], skips[2k+1]) sub-range. {@code skips} must be sorted by
	 *  start. Overlapping skip ranges are merged on the fly. */
	private static void drawWithSkips(VkCommandBuffer cmd, int start, int end, int[] skips, int pairCount, int slotFirstVertex,
									  long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		int cursor = start;
		for (int k = 0; k < pairCount; k++)
		{
			int s = skips[k * 2];
			int e = skips[k * 2 + 1];
			if (e <= cursor) continue;
			if (s > cursor)
			{
				int n = Math.min(s, end) - cursor;
				if (n > 0)
				{
					vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
					vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
					vkCmdDraw(cmd, n, 1, slotFirstVertex + cursor, 0);
				}
			}
			cursor = Math.max(cursor, e);
			if (cursor >= end) return;
		}
		if (cursor < end)
		{
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
			vkCmdDraw(cmd, end - cursor, 1, slotFirstVertex + cursor, 0);
		}
	}

	int vertexCount() { return vertexCount; }

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		vbuf.close();
		fillPipeline.close();
		if (linePipeline != null) linePipeline.close();
	}

	private void writeRotatedVertex(float lx, float ly, float lz,
									float cos, float sin,
									int wx, int wy, int wz,
									int hsl16, float u, float v, int texLayer)
	{
		float rx = lx * cos + lz * sin;
		float rz = -lx * sin + lz * cos;
		float[] rgb = hslToRgb(hsl16);
		// Pass the packed HSL int as a float (raw bits — value 0..65535).
		// Interpolated linearly across the face by the rasterizer; the frag
		// shader does `int(vLight)` and bit-extracts h/s/l from the result,
		// then either decodes hslToRgb (untextured faces) or uses the
		// lightness alone (textured faces). This matches stock's
		// `smoothBanding = false` default — the "broken-but-banded"
		// interpolation that produces stock's distinctive faceted shading.
		// Pre-decoding HSL→RGB on CPU and letting the GPU linearly
		// interpolate RGB blurs everything into uniform color.
		float light = (float) (hsl16 & 0xFFFF);
		// 12 floats per vertex; vec3 position and vec3 color each padded
		// to vec4 (trailing 0f) to satisfy Metal's 16-byte attribute
		// alignment via MoltenVK. See ScenePipeline.VERTEX_STRIDE comment.
		mapped.putFloat(rx + wx);
		mapped.putFloat(ly + wy);
		mapped.putFloat(rz + wz);
		mapped.putFloat(0f);            // pad
		mapped.putFloat(rgb[0]);
		mapped.putFloat(rgb[1]);
		mapped.putFloat(rgb[2]);
		mapped.putFloat(0f);            // pad
		mapped.putFloat(light);
		mapped.putFloat(u);
		mapped.putFloat(v);
		mapped.putInt(texLayer);
	}

	private void writeHslVert(float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		float[] rgb = hslToRgb(hsl16);
		// Pass the packed HSL int as a float (raw bits — value 0..65535).
		// Interpolated linearly across the face by the rasterizer; the frag
		// shader does `int(vLight)` and bit-extracts h/s/l from the result,
		// then either decodes hslToRgb (untextured faces) or uses the
		// lightness alone (textured faces). This matches stock's
		// `smoothBanding = false` default — the "broken-but-banded"
		// interpolation that produces stock's distinctive faceted shading.
		// Pre-decoding HSL→RGB on CPU and letting the GPU linearly
		// interpolate RGB blurs everything into uniform color.
		float light = (float) (hsl16 & 0xFFFF);
		// 12 floats per vertex; vec3 position and vec3 color each padded
		// to vec4 (trailing 0f) — see ScenePipeline.VERTEX_STRIDE comment.
		mapped.putFloat(x);
		mapped.putFloat(y);
		mapped.putFloat(z);
		mapped.putFloat(0f);            // pad
		mapped.putFloat(rgb[0]);
		mapped.putFloat(rgb[1]);
		mapped.putFloat(rgb[2]);
		mapped.putFloat(0f);            // pad
		mapped.putFloat(light);
		mapped.putFloat(u);
		mapped.putFloat(v);
		mapped.putInt(texLayer);
	}

	private static float[] hslToRgb(int hsl16)
	{
		int h = (hsl16 >> 10) & 0x3F;
		int s = (hsl16 >>  7) & 0x07;
		int l =  hsl16        & 0x7F;
		float hue = h / 64f + 0.0078125f;
		float sat = s / 8f  + 0.0625f;
		float lum = l / 128f;
		float q = lum < 0.5f ? lum * (1f + sat) : lum + sat - lum * sat;
		float p = 2f * lum - q;
		return new float[] {
			hueToChannel(p, q, hue + 1f / 3f),
			hueToChannel(p, q, hue),
			hueToChannel(p, q, hue - 1f / 3f),
		};
	}

	private static float hueToChannel(float p, float q, float t)
	{
		if (t > 1f) t -= 1f;
		else if (t < 0f) t += 1f;
		if (6f * t < 1f) return p + (q - p) * 6f * t;
		if (2f * t < 1f) return q;
		if (3f * t < 2f) return p + (q - p) * (2f / 3f - t) * 6f;
		return p;
	}

	private void overflow()
	{
		if (!overflowed)
		{
			log.warn("Scene vertex buffer full ({} verts), dropping further captures", vertexCount);
			overflowed = true;
		}
	}
}
