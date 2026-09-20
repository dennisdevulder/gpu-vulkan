/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gpuvulkan;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FloatProjection;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * One small {@link SceneRenderer} per sub-worldview (WorldEntity, e.g. ships) —
 * separate vertex arenas so a sub-scene can never clobber the toplevel capture.
 * Clip uses the CPU-composed world*entity matrix; fog reconstructs world XZ in-shader.
 */
@Slf4j
final class SubWorldViewManager implements AutoCloseable
{
	/** Seed only: captureScene grows the static arena to measured demand, so a
	 *  rowboat does not pay a galleon's worst case. */
	private static final int SUB_STATIC_VERTICES = 48_000;
	private static final int SUB_FRAME_VERTICES = 40_000;
	private static final double JAU_PER_RADIAN = 2048.0 / (2.0 * Math.PI);

	private final VulkanDevice device;
	private final FrameSync sync;
	private final ScenePipelines pipelines;
	private final DrawCallbackStats stats;

	private final Map<Integer, SubView> views = new HashMap<>();

	private static final class SubView
	{
		final SceneRenderer renderer;
		final float[] entityMatrix = new float[16];
		/** world * entity for this frame; reused so a pass costs no allocation. */
		final float[] composed = new float[16];
		Scene scene;
		int baseX;
		int baseY;
		boolean visible;
		boolean placed;
		SceneEntity entity = SceneEntity.TOP_LEVEL;

		SubView(SceneRenderer renderer)
		{
			this.renderer = renderer;
		}

		void setEntity(int translateX, int translateZ, int yawJau, int tint)
		{
			if (!entity.is(translateX, translateZ, yawJau, tint))
			{
				entity = new SceneEntity(translateX, translateZ, yawJau, tint);
			}
		}
	}

	SubWorldViewManager(VulkanDevice device, FrameSync sync, ScenePipelines pipelines,
		DrawCallbackStats stats)
	{
		this.device = device;
		this.sync = sync;
		this.pipelines = pipelines;
		this.stats = stats;
	}

	/** Called at the toplevel preSceneDraw — the first scene callback of a frame. */
	void beginFrame()
	{
		for (SubView view : views.values())
		{
			view.visible = false;
			view.placed = false;
			view.renderer.beginFrame();
		}
	}

	void preScene(Scene scene, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		SubView view = views.get(scene.getWorldViewId());
		if (view == null)
		{
			view = new SubView(new SceneRenderer(device, sync, pipelines, stats,
				SUB_STATIC_VERTICES, SUB_FRAME_VERTICES, false));
			views.put(scene.getWorldViewId(), view);
			capture(view, scene);
			log.debug("Sub-worldview {} renderer created", scene.getWorldViewId());
		}
		else if (scene != view.scene || scene.getBaseX() != view.baseX || scene.getBaseY() != view.baseY)
		{
			capture(view, scene);
		}
		view.visible = true;
		view.renderer.setLevelRange(minLevel, level, maxLevel);
		view.renderer.setHideRoofIds(hideRoofIds);
	}

	private void capture(SubView view, Scene scene)
	{
		view.renderer.captureScene(scene);
		view.scene = scene;
		view.baseX = scene.getBaseX();
		view.baseY = scene.getBaseY();
	}

	/** Overwrite the placement on every callback — caching by Projection
	 *  reference identity goes stale. */
	void recordProjection(Projection entityProjection, Scene scene)
	{
		if (!(entityProjection instanceof FloatProjection))
		{
			return;
		}
		SubView view = views.get(scene.getWorldViewId());
		if (view == null)
		{
			return;
		}
		float[] m = ((FloatProjection) entityProjection).getProjection();
		if (m == null || m.length < 16)
		{
			return;
		}
		System.arraycopy(m, 0, view.entityMatrix, 0, 16);
		// Yaw from the rotY cells (column-major: m[0]=cos, m[8]=sin); pitch/roll
		// would only perturb fog, never clip position.
		int yawJau = (int) Math.round(Math.atan2(m[8], m[0]) * JAU_PER_RADIAN) & 2047;
		// The override can change while the worldview lives, so re-read it here
		// rather than at capture.
		view.setEntity(Math.round(m[12]), Math.round(m[14]), yawJau, SceneEntity.packTint(scene));
		view.placed = true;
	}

	void drawPass(Scene scene, int pass)
	{
		SubView view = views.get(scene.getWorldViewId());
		if (view != null)
		{
			view.renderer.drawPass(pass);
		}
	}

	void captureDynamic(Projection projection, Scene scene, Model model,
		int orientation, int x, int y, int z, int renderMode, boolean actorModel)
	{
		SubView view = views.get(scene.getWorldViewId());
		if (view != null)
		{
			view.renderer.captureModelSorted(projection, model, orientation, x, y, z, renderMode, actorModel);
		}
	}

	void invalidateZone(Scene scene, int zx, int zz)
	{
		SubView view = views.get(scene.getWorldViewId());
		if (view != null)
		{
			view.renderer.invalidateZone(scene, zx, zz);
		}
	}

	void rebuildDirtyZones()
	{
		for (SubView view : views.values())
		{
			if (view.scene != null)
			{
				view.renderer.rebuildDirtyZones(view.scene);
			}
		}
	}

	void despawn(int worldViewId)
	{
		SubView view = views.remove(worldViewId);
		if (view != null)
		{
			view.renderer.close();
			log.debug("Sub-worldview {} renderer freed", worldViewId);
		}
	}

	/** Logout/world hop: every sub-worldview is gone with the old world. */
	void invalidateAll()
	{
		for (Iterator<SubView> it = views.values().iterator(); it.hasNext(); )
		{
			it.next().renderer.close();
			it.remove();
		}
	}

	void recordOpaque(VkCommandBuffer cmd, VulkanFrameContext frame)
	{
		record(cmd, frame, true);
	}

	void recordAlpha(VkCommandBuffer cmd, VulkanFrameContext frame)
	{
		record(cmd, frame, false);
	}

	private void record(VkCommandBuffer cmd, VulkanFrameContext frame, boolean opaque)
	{
		for (SubView view : views.values())
		{
			if (!view.visible || !view.placed)
			{
				continue;
			}
			System.arraycopy(frame.sceneMvp(), 0, view.composed, 0, 16);
			Mat4Ops.mul(view.composed, view.entityMatrix);
			if (opaque)
			{
				view.renderer.recordOpaque(cmd, view.composed, frame, view.entity);
			}
			else
			{
				view.renderer.recordAlpha(cmd, view.composed, frame, view.entity);
			}
		}
	}

	void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		for (SubView view : views.values())
		{
			view.renderer.collectDebugMetrics(metrics);
		}
	}

	@Override
	public void close()
	{
		invalidateAll();
	}
}
