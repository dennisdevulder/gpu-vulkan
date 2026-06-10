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
package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(GpuVulkanPluginConfig.GROUP)
public interface GpuVulkanPluginConfig extends Config
{
	String GROUP = "gpuvulkan";

	@ConfigSection(
		name = "Debug",
		description = "Runtime diagnostics for Vulkan memory and scene capture.",
		position = 3,
		closedByDefault = true
	)
	String DEBUG_SECTION = "debug";

	@ConfigSection(
		name = "Benchmark",
		description = "Temporary switches for isolating Vulkan CPU/GPU costs.",
		position = 4,
		closedByDefault = true
	)
	String BENCHMARK_SECTION = "benchmark";

	@ConfigItem(
		keyName = "validation",
		name = "Validation layers",
		description = "Enable VK_LAYER_KHRONOS_validation. Catches API misuse but adds overhead. Plugin must be re-enabled to take effect."
	)
	default boolean validation()
	{
		// Default off because the layer SIGSEGVs on plugin disable on
		// Fedora 43/44 (vulkan-validation-layers 1.4.341). See docs/KNOWN_ISSUES.md
		// issue #2. Opt in via -Dvkgpu.validation=true (vkdev script does this).
		return Boolean.parseBoolean(System.getProperty("vkgpu.validation", "false"));
	}

	enum FpsMode
	{
		/** Vsync — Vulkan FIFO present mode. Caps render at the display
		 *  refresh rate. Most power-efficient, no tearing. */
		VSYNC,
		/** Adaptive vsync — Vulkan FIFO_RELAXED. Like VSYNC, but if a frame
		 *  misses the refresh deadline it tears that one frame instead of
		 *  doubling latency. Falls back to FIFO when the device lacks
		 *  FIFO_RELAXED support. */
		ADAPTIVE_VSYNC,
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
		description = "Vsync = capped to refresh, no tearing. Adaptive vsync = vsync with single-frame tear when behind. Triple-buffer = decoupled render, no tearing. Uncapped = no vsync, tearing visible, max FPS for benchmarking. Plugin must be re-enabled for this to take effect."
	)
	default FpsMode fpsMode() { return FpsMode.VSYNC; }

	@Range(min = 0, max = 999)
	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Optional engine FPS target. 0 = no target; presentation mode controls pacing. Applies immediately."
	)
	default int fpsTarget() { return 0; }

	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance (tiles)",
		description = "Maximum draw distance in OSRS tiles. Higher = more visible scene, lower performance."
	)
	default int drawDistance() { return 50; }

	@Range(min = 0, max = 5)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Expanded map loading (chunks)",
		description = "Extra 8-tile chunks of map streamed in beyond the default loaded region. Applies immediately; visible geometry may change after the scene reloads."
	)
	default int expandedMapLoadingChunks() { return 3; }

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Disable the legacy 1/128-tile vertex snap on animated entities. Applies immediately, but only animated models visibly change."
	)
	default boolean removeVertexSnapping() { return true; }

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Strip scene zones that belong to a different game region than the player's, so neighbouring maps don't bleed into the horizon. No effect inside instances. Matches stock GPU's option."
	)
	default boolean hideUnrelatedMaps() { return true; }

	@ConfigSection(
		name = "Upscaling",
		description = "Render the 3D scene at a lower internal resolution and upscale before drawing UI.",
		position = 1,
		closedByDefault = true
	)
	String UPSCALING_SECTION = "upscaling";

	enum UpscalingMode
	{
		OFF,
		FSR1
	}

	@ConfigItem(
		keyName = "upscalingMode",
		name = "Upscaling mode",
		description = "OFF = native scene rendering. FSR1 = render the 3D scene at the selected scale, upscale, then draw UI at native resolution. Plugin must be re-enabled to take effect.",
		section = UPSCALING_SECTION,
		position = 0
	)
	default UpscalingMode upscalingMode() { return UpscalingMode.OFF; }

	@Range(min = 50, max = 100)
	@ConfigItem(
		keyName = "renderScale",
		name = "Render scale",
		description = "Internal 3D scene resolution when upscaling is enabled. UI remains native resolution. Plugin must be re-enabled to take effect.",
		section = UPSCALING_SECTION,
		position = 1
	)
	default int renderScale() { return 75; }

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "fsrSharpness",
		name = "FSR sharpness",
		description = "Sharpening strength for the FSR1 upscaler.",
		section = UPSCALING_SECTION,
		position = 2
	)
	default int fsrSharpness() { return 60; }

	@ConfigItem(
		keyName = "benchmarkSkipUi",
		name = "Skip UI upload",
		description = "Benchmark only: skip UI texture upload/draw to isolate scene renderer cost.",
		section = BENCHMARK_SECTION,
		position = 0
	)
	default boolean benchmarkSkipUi() { return false; }

	@ConfigItem(
		keyName = "benchmarkSkipScene",
		name = "Skip scene draw",
		description = "Benchmark only: skip 3D scene draw to isolate UI and present cost.",
		section = BENCHMARK_SECTION,
		position = 1
	)
	default boolean benchmarkSkipScene() { return false; }

	@ConfigItem(
		keyName = "benchmarkSkipDynamicCapture",
		name = "Skip dynamic capture",
		description = "Benchmark only: skip dynamic model capture while keeping static scene/UI.",
		section = BENCHMARK_SECTION,
		position = 2
	)
	default boolean benchmarkSkipDynamicCapture() { return false; }

	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth (tiles)",
		description = "Distance over which the scene fades to the skybox color. 0 disables fog. Matches stock GPU's default."
	)
	default int fogDepth() { return 0; }

	enum AntiAliasingMode { DISABLED, MSAA_2, MSAA_4, MSAA_8, MSAA_16 }

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Multisample anti-aliasing. Higher = cleaner edges, lower FPS. Stock GPU defaults to 2×. Has no effect on macOS (rendered at 1x). Plugin must be re-enabled to take effect."
	)
	default AntiAliasingMode antiAliasingMode() { return AntiAliasingMode.MSAA_2; }

	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Texture filtering quality (1 = bilinear/off, up to 16). Higher = sharper distant tiles, slightly lower FPS. Stock GPU defaults to 1. Plugin must be re-enabled to take effect."
	)
	default int anisotropicFilteringLevel() { return 1; }

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use the older texture-lighting mode: textured surfaces are tinted by the per-face vertex color instead of pure lightness. Brighter overall look on water, doors, crystals. Matches stock GPU's 'Bright textures' option."
	)
	default boolean brightTextures() { return false; }

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Smooth banding",
		description = "Interpolate vertex colors smoothly across faces (matches stock GPU's default). When disabled the HSL value is re-decoded per fragment, producing the faceted/banded look on terrain and crystals."
	)
	default boolean smoothBanding() { return true; }

	@ConfigItem(
		keyName = "detailedModelStats",
		name = "Detailed model stats",
		description = "Log and time per-model Vulkan capture work. Useful while profiling, but it adds CPU overhead.",
		section = DEBUG_SECTION,
		position = 1
	)
	default boolean detailedModelStats()
	{
		return Boolean.parseBoolean(System.getProperty("vkgpu.modelStats", "false"));
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colour-blind mode",
		description = "Apply Daltonization for red-deficient (protanope), green-deficient (deuteranope), or blue-deficient (tritanope) viewers. Matches stock GPU's colour-blind option."
	)
	default ColorBlindMode colorBlindMode() { return ColorBlindMode.NONE; }

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colour-blind intensity",
		description = "Strength of the colour-blind correction, 0 = no correction, 100 = full correction. Ignored when mode is NONE."
	)
	default int colorBlindIntensity() { return 100; }

	@ConfigItem(
		keyName = "debugOverlay",
		name = "Debug overlay",
		description = "Show Vulkan memory, scene capture, and callback diagnostics on screen.",
		section = DEBUG_SECTION,
		position = 0
	)
	default boolean debugOverlay() { return false; }

	@ConfigSection(
		name = "Wireframe",
		description = "Render individual scene layers as wireframe. Each toggle is independent.",
		position = 2,
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
