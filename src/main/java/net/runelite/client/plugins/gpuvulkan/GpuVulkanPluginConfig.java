package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(GpuVulkanPluginConfig.GROUP)
public interface GpuVulkanPluginConfig extends Config
{
	String GROUP = "gpuvulkan";

	@ConfigItem(
		keyName = "validation",
		name = "Validation layers",
		description = "Enable VK_LAYER_KHRONOS_validation. Catches API misuse but adds overhead — leave on during development."
	)
	default boolean validation()
	{
		// Default off because the layer SIGSEGVs on plugin disable on
		// Fedora 43/44 (vulkan-validation-layers 1.4.341). See KNOWN_ISSUES.md
		// issue #2. Opt in via -Dvkgpu.validation=true (vkdev script does this).
		return Boolean.parseBoolean(System.getProperty("vkgpu.validation", "false"));
	}

	/**
	 * Visual style — knob for opting in/out of stock-parity rendering.
	 *
	 * <p>{@link VisualStyle#PICTURE} is the *curated* look. It is not frozen
	 * at any point in time; every new visual feature is included here by
	 * default. The rule is: if a change still looks good (or better), it
	 * stays in {@code PICTURE}. If it makes the picture worse but matches
	 * what stock GpuPlugin does, it gets gated behind {@link VisualStyle#ORIGINAL}.
	 *
	 * <p>{@link VisualStyle#ORIGINAL} is the opt-in stock-parity look — for
	 * users who want the exact stock GpuPlugin output (incl. its quirks like
	 * banded shading at certain face counts, exact alpha sort order, etc.)
	 * or for visual regression testing against the OpenGL reference. Today
	 * it renders identically to {@code PICTURE}; divergence is added as
	 * specific features land that would otherwise degrade the picture.
	 *
	 * <p>Discipline for new visual features: don't add an {@code if
	 * (style == ORIGINAL)} branch unless the new behavior makes the picture
	 * mode strictly worse. Default to including everything in {@code PICTURE}.
	 */
	enum VisualStyle
	{
		PICTURE,
		ORIGINAL,
	}

	@ConfigItem(
		keyName = "visualStyle",
		name = "Visual style",
		description = "PICTURE: curated look (default; includes whatever currently looks best). ORIGINAL: stock GpuPlugin-parity look for users who want it or for visual regression testing. Plugin must be re-enabled for this to take effect."
	)
	default VisualStyle visualStyle() { return VisualStyle.PICTURE; }

	enum FpsMode
	{
		/** Vsync — Vulkan FIFO present mode. Caps render at the display
		 *  refresh rate. Most power-efficient, no tearing. */
		VSYNC,
		/** Triple-buffer — Vulkan MAILBOX present mode. GPU can render
		 *  faster than the refresh rate but only the latest frame is shown.
		 *  Smooth, no tearing, low input latency. Reported FPS is still
		 *  bounded by the refresh rate (the extra renders just get dropped). */
		TRIPLE_BUFFER,
		/** Uncapped — Vulkan IMMEDIATE present mode + engine FPS unlocked.
		 *  GPU renders as fast as possible. Tearing visible. Use for
		 *  benchmarking only — the reported FPS shows raw renderer capacity. */
		UNCAPPED
	}

	@ConfigItem(
		keyName = "fpsMode",
		name = "FPS mode",
		description = "Vsync = capped to refresh, no tearing. Triple-buffer = decoupled render, no tearing. Uncapped = no vsync, tearing visible, max FPS for benchmarking. Plugin must be re-enabled for this to take effect."
	)
	default FpsMode fpsMode() { return FpsMode.TRIPLE_BUFFER; }

	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance (tiles)",
		description = "Maximum draw distance in OSRS tiles. Higher = more visible scene, lower performance."
	)
	default int drawDistance() { return 90; }

	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth (tiles)",
		description = "Distance over which the scene fades to the skybox color. 0 disables fog. Stock default is around 30."
	)
	default int fogDepth() { return 30; }

	enum AntiAliasingMode { DISABLED, MSAA_2, MSAA_4, MSAA_8 }

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Multisample anti-aliasing. Higher = cleaner edges, lower FPS. Stock GPU defaults to 2×. Plugin must be re-enabled to take effect."
	)
	default AntiAliasingMode antiAliasingMode() { return AntiAliasingMode.MSAA_2; }

	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Texture filtering quality (1 = bilinear/off, up to 16). Higher = sharper distant tiles, slightly lower FPS. Stock GPU defaults to 1. Plugin must be re-enabled to take effect."
	)
	default int anisotropicFilteringLevel() { return 1; }

	@ConfigSection(
		name = "Wireframe",
		description = "Render individual scene layers as wireframe. Each toggle is independent.",
		position = 1,
		closedByDefault = true
	)
	String WIREFRAME_SECTION = "wireframe";

	@ConfigItem(
		keyName = "wireframeTerrain",
		name = "Terrain",
		description = "Render flat tile paint and tile-model terrain as wireframe.",
		section = WIREFRAME_SECTION
	)
	default boolean wireframeTerrain() { return false; }

	@ConfigItem(
		keyName = "wireframeWalls",
		name = "Walls",
		description = "Render WallObject geometry as wireframe.",
		section = WIREFRAME_SECTION
	)
	default boolean wireframeWalls() { return false; }

	@ConfigItem(
		keyName = "wireframeDecorative",
		name = "Decorative objects",
		description = "Render DecorativeObject geometry (signs, banners, fixtures) as wireframe.",
		section = WIREFRAME_SECTION
	)
	default boolean wireframeDecorative() { return false; }

	@ConfigItem(
		keyName = "wireframeGround",
		name = "Ground objects",
		description = "Render GroundObject geometry (paintings on the floor, ground decorations) as wireframe.",
		section = WIREFRAME_SECTION
	)
	default boolean wireframeGround() { return false; }

	@ConfigItem(
		keyName = "wireframeGameObjects",
		name = "Game objects",
		description = "Render static game objects (buildings, trees, fences) as wireframe.",
		section = WIREFRAME_SECTION
	)
	default boolean wireframeGameObjects() { return false; }

	@ConfigItem(
		keyName = "wireframeDynamic",
		name = "Dynamic entities",
		description = "Render players, NPCs and other dynamic models as wireframe.",
		section = WIREFRAME_SECTION
	)
	default boolean wireframeDynamic() { return false; }

}
