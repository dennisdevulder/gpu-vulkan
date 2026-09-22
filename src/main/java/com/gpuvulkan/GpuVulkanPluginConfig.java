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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

/**
 * Key names are persistent storage keys — never rename one (saved settings
 * die and the onConfigChanged handlers match on them).
 */
@ConfigGroup(GpuVulkanPluginConfig.GROUP)
public interface GpuVulkanPluginConfig extends Config
{
	String GROUP = "gpuvulkan";

	// ---------------------------------------------------------------- sections

	@ConfigSection(
		name = "Graphics",
		description = "Scene rendering quality.",
		position = 4,
		closedByDefault = true
	)
	String GRAPHICS_SECTION = "graphics";

	@ConfigSection(
		name = "Upscaling",
		description = "Render the 3D scene at a lower internal resolution and upscale before drawing UI.",
		position = 5,
		closedByDefault = true
	)
	String UPSCALING_SECTION = "upscaling";

	@ConfigSection(
		name = "Scene",
		description = "What gets loaded and captured into the scene.",
		position = 6,
		closedByDefault = true
	)
	String SCENE_SECTION = "scene";

	@ConfigSection(
		name = "Accessibility",
		description = "Colour-vision correction.",
		position = 7,
		closedByDefault = true
	)
	String ACCESSIBILITY_SECTION = "accessibility";

	@ConfigSection(
		name = "Debug",
		description = "Runtime diagnostics for Vulkan memory and scene capture.",
		position = 12,
		closedByDefault = true
	)
	String DEBUG_SECTION = "debug";

	// The String value persists the collapsed state, so the main section keeps
	// "recordings" rather than stranding everyone's expanded/collapsed choice.
	@ConfigSection(
		name = "Recording",
		description = "Capture clips of your gameplay and keep the library tidy.",
		position = 8,
		closedByDefault = true
	)
	String RECORDINGS_SECTION = "recordings";

	@ConfigSection(
		name = "Recording events",
		description = "Which in-game moments save a clip automatically.",
		position = 9,
		closedByDefault = true
	)
	String RECORDING_EVENTS_SECTION = "recordingEvents";

	@ConfigSection(
		name = "Recording audio",
		description = "Capture desktop sound alongside the video.",
		position = 10,
		closedByDefault = true
	)
	String RECORDING_AUDIO_SECTION = "recordingAudio";

	@ConfigSection(
		name = "Recording sharing",
		description = "Post finished recordings to Discord.",
		position = 11,
		closedByDefault = true
	)
	String RECORDING_SHARING_SECTION = "recordingSharing";

	// --------------------------------------------------------------- top level

	@Range(min = 1, max = 90)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance (tiles)",
		description = "Maximum draw distance in OSRS tiles. Higher = more visible scene, lower performance.",
		position = 0
	)
	default int drawDistance() { return 50; }

	enum FpsMode
	{
		/** Vsync — Vulkan FIFO present mode. Caps render at the display
		 *  refresh rate. Most power-efficient, no tearing. */
		VSYNC,
		/** FIFO_RELAXED: a late frame tears instead of doubling latency.
		 *  Falls back to FIFO when unsupported. */
		ADAPTIVE_VSYNC,
		/** MAILBOX: latest frame wins; reported FPS still bounded by refresh. */
		TRIPLE_BUFFER,
		/** IMMEDIATE + engine FPS unlocked. Tears; benchmarking only. */
		UNCAPPED
	}

	@ConfigItem(
		keyName = "fpsMode",
		name = "FPS mode",
		description = "Vsync = capped to refresh, no tearing. Adaptive vsync = vsync with single-frame tear when behind. Triple-buffer = decoupled render, no tearing. Uncapped = no vsync, tearing visible, max FPS for benchmarking. Plugin must be re-enabled for this to take effect.",
		position = 1
	)
	default FpsMode fpsMode() { return FpsMode.VSYNC; }

	@Range(min = 0, max = 999)
	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Optional engine FPS target. 0 = no target; presentation mode controls pacing. Applies immediately.",
		position = 2
	)
	default int fpsTarget() { return 0; }

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth (tiles)",
		description = "Distance over which the scene fades to the skybox color. 0 disables fog. Matches stock GPU's default.",
		position = 3
	)
	default int fogDepth() { return 0; }

	// ---------------------------------------------------------------- Graphics

	enum AntiAliasingMode { DISABLED, MSAA_2, MSAA_4, MSAA_8, MSAA_16 }

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Multisample anti-aliasing. Higher = cleaner edges, lower FPS. Stock GPU defaults to 2×. Has no effect on macOS (rendered at 1x). Plugin must be re-enabled to take effect.",
		section = GRAPHICS_SECTION,
		position = 0
	)
	default AntiAliasingMode antiAliasingMode() { return AntiAliasingMode.DISABLED; }

	@Range(min = 1, max = 16)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Texture filtering quality (1 = bilinear/off, up to 16). Higher = sharper distant tiles, slightly lower FPS. Stock GPU defaults to 1. Plugin must be re-enabled to take effect.",
		section = GRAPHICS_SECTION,
		position = 1
	)
	default int anisotropicFilteringLevel() { return 1; }

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Smooth banding",
		description = "Interpolate vertex colors smoothly across faces (matches stock GPU's default). When disabled the HSL value is re-decoded per fragment, producing the faceted/banded look on terrain and crystals.",
		section = GRAPHICS_SECTION,
		position = 2
	)
	default boolean smoothBanding() { return true; }

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use the older texture-lighting mode: textured surfaces are tinted by the per-face vertex color instead of pure lightness. Brighter overall look on water, doors, crystals. Matches stock GPU's 'Bright textures' option.",
		section = GRAPHICS_SECTION,
		position = 3
	)
	default boolean brightTextures() { return false; }

	// --------------------------------------------------------------- Upscaling

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

	// ------------------------------------------------------------------- Scene

	@Range(min = 0, max = 5)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Expanded map loading (chunks)",
		description = "Extra 8-tile chunks of map streamed in beyond the default loaded region. Applies immediately; visible geometry may change after the scene reloads.",
		section = SCENE_SECTION,
		position = 0
	)
	default int expandedMapLoadingChunks() { return 3; }

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Strip scene zones that belong to a different game region than the player's, so neighbouring maps don't bleed into the horizon. No effect inside instances. Matches stock GPU's option.",
		section = SCENE_SECTION,
		position = 1
	)
	default boolean hideUnrelatedMaps() { return true; }

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Disable the legacy 1/128-tile vertex snap on animated entities. Applies immediately, but only animated models visibly change.",
		section = SCENE_SECTION,
		position = 2
	)
	default boolean removeVertexSnapping() { return true; }

	// ----------------------------------------------------------- Accessibility

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colour-blind mode",
		description = "Apply Daltonization for red-deficient (protanope), green-deficient (deuteranope), or blue-deficient (tritanope) viewers. Matches stock GPU's colour-blind option.",
		section = ACCESSIBILITY_SECTION,
		position = 0
	)
	default ColorBlindMode colorBlindMode() { return ColorBlindMode.NONE; }

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colour-blind intensity",
		description = "Strength of the colour-blind correction, 0 = no correction, 100 = full correction. Ignored when mode is NONE.",
		section = ACCESSIBILITY_SECTION,
		position = 1
	)
	default int colorBlindIntensity() { return 100; }

	// -------------------------------------------------------- In-flight Encoding


	enum RecordingFps
	{
		FPS_30(30, "30 FPS"),
		FPS_60(60, "60 FPS");

		private final int value;
		private final String label;

		RecordingFps(int value, String label)
		{
			this.value = value;
			this.label = label;
		}

		int value()
		{
			return value;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum RecordingQuality
	{
		STANDARD(10_000_000, 15_000_000, "Standard"),
		HIGH(20_000_000, 30_000_000, "High"),
		VERY_HIGH(35_000_000, 50_000_000, "Very high"),
		MAXIMUM(50_000_000, 75_000_000, "Maximum");

		private final int averageBitrate;
		private final int peakBitrate;
		private final String label;

		RecordingQuality(int averageBitrate, int peakBitrate, String label)
		{
			this.averageBitrate = averageBitrate;
			this.peakBitrate = peakBitrate;
			this.label = label;
		}

		int averageBitrate()
		{
			return averageBitrate;
		}

		int peakBitrate()
		{
			return peakBitrate;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "recordingsEnabled",
		name = "Enable recording",
		description = "Keep recent frames encoded in memory so a clip can be saved after "
			+ "something happens. Needs a GPU with Vulkan H.264 encode.",
		section = RECORDINGS_SECTION,
		position = 0
	)
	default boolean recordingsEnabled() { return false; }

	@ConfigItem(
		keyName = "inFlightEncodingHotkey",
		name = "Save a clip",
		description = "Save the last few seconds of play.",
		section = RECORDINGS_SECTION,
		position = 1
	)
	default Keybind inFlightEncodingHotkey() { return Keybind.NOT_SET; }

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "inFlightEncodingBufferSeconds",
		name = "Seconds before the moment",
		description = "How much of the lead-up a clip includes. Before plus after is capped at 60.",
		section = RECORDINGS_SECTION,
		position = 2
	)
	default int inFlightEncodingBufferSeconds() { return 10; }

	@Range(min = 0, max = 60)
	@ConfigItem(
		keyName = "inFlightEncodingPostWaitSeconds",
		name = "Seconds after the moment",
		description = "How long a clip keeps recording once triggered. Before plus after is capped at 60.",
		section = RECORDINGS_SECTION,
		position = 3
	)
	default int inFlightEncodingPostWaitSeconds() { return 4; }

	@ConfigItem(
		keyName = "inFlightEncodingQuality",
		name = "Quality",
		description = "Higher settings look better and produce larger files.",
		section = RECORDINGS_SECTION,
		position = 4
	)
	default RecordingQuality inFlightEncodingQuality() { return RecordingQuality.STANDARD; }

	@ConfigItem(
		keyName = "inFlightEncodingFps",
		name = "Frame rate",
		description = "Frames per second in saved recordings.",
		section = RECORDINGS_SECTION,
		position = 5
	)
	default RecordingFps inFlightEncodingFps() { return RecordingFps.FPS_30; }

	@ConfigItem(
		keyName = "recordingSessionsEnabled",
		name = "Allow long recordings",
		description = "Let an event record for as long as it lasts instead of a fixed clip. "
			+ "Written straight to disk, so length is bounded by the limit below.",
		section = RECORDINGS_SECTION,
		position = 6
	)
	default boolean recordingSessionsEnabled() { return false; }

	@Range(min = 30, max = 3600)
	@ConfigItem(
		keyName = "recordingSessionMaxSeconds",
		name = "Stop long recordings after",
		description = "Seconds. A long recording is finished and saved when it reaches this.",
		section = RECORDINGS_SECTION,
		position = 7
	)
	default int recordingSessionMaxSeconds() { return 600; }

	@ConfigItem(
		keyName = "recordingSessionHotkey",
		name = "Start/stop a long recording",
		description = "Begin a long recording, or end the one in progress.",
		section = RECORDINGS_SECTION,
		position = 8
	)
	default Keybind recordingSessionHotkey() { return Keybind.NOT_SET; }

	@Range(min = 0, max = 512000)
	@ConfigItem(
		keyName = "recordingDiskBudgetMb",
		name = "Library size limit",
		description = "Delete the oldest unpinned recordings once the library passes this "
			+ "many megabytes. 0 keeps everything.",
		section = RECORDINGS_SECTION,
		position = 9
	)
	default int recordingDiskBudgetMb() { return 10_000; }

	@Range(min = 0, max = 3650)
	@ConfigItem(
		keyName = "recordingRetentionDays",
		name = "Delete recordings after",
		description = "Days. Unpinned recordings older than this are removed. 0 keeps them forever.",
		section = RECORDINGS_SECTION,
		position = 10
	)
	default int recordingRetentionDays() { return 0; }

	@ConfigItem(
		keyName = "recordingChatFeedback",
		name = "Announce saves in chat",
		description = "Post a game message when a recording is saved.",
		section = RECORDINGS_SECTION,
		position = 11
	)
	default boolean recordingChatFeedback() { return true; }

	// Interacts with bitrate and clip length in a way that reads as a bug: lower
	// it and the pre-roll silently gets shorter than the seconds set above.
	@Range(min = 16, max = 512)
	@ConfigItem(
		keyName = "inFlightEncodingRingBudgetMb",
		name = "Buffer memory cap",
		description = "Maximum heap the rolling buffer may use.",
		section = RECORDINGS_SECTION,
		position = 12,
		hidden = true
	)
	default int inFlightEncodingRingBudgetMb() { return 64; }

	// ------------------------------------------------------------ recording audio

	@ConfigItem(
		keyName = "recordingAudioEnabled",
		name = "Record desktop audio",
		description = "Record system audio alongside video, voice chat included.",
		section = RECORDING_AUDIO_SECTION,
		position = 0
	)
	default boolean recordingAudioEnabled() { return false; }

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "recordingAudioGain",
		name = "Input level",
		description = "Volume of the recorded audio, as a percentage. Capture devices are read "
			+ "raw, without the gain your desktop applies, so this often needs to be well "
			+ "above 100. The meter in the Recordings panel shows the result.",
		section = RECORDING_AUDIO_SECTION,
		position = 1
	)
	default int recordingAudioGain() { return 100; }

	// Chosen from the Recordings panel, where the list can be built from the
	// devices that actually exist; a config enum would have to be static.
	@ConfigItem(
		keyName = "recordingAudioDevice",
		name = "Audio device",
		description = "Capture device to record from. Pick one in the Recordings panel.",
		section = RECORDING_AUDIO_SECTION,
		position = 2,
		hidden = true
	)
	default String recordingAudioDevice() { return "default"; }

	// ---------------------------------------------------------- recording sharing

	@ConfigItem(
		keyName = "discordWebhookUrl",
		name = "Discord webhook",
		description = "Post finished recordings to this Discord webhook. Leave empty to post "
			+ "nothing. Create one under Server Settings, Integrations, Webhooks.",
		section = RECORDING_SHARING_SECTION,
		position = 0,
		secret = true
	)
	default String discordWebhookUrl() { return ""; }

	@ConfigItem(
		keyName = "discordAutoUpload",
		name = "Post automatically",
		description = "Post every finished recording to the webhook. With this off, recordings "
			+ "are posted only when you choose Push to Discord in the Recordings panel.",
		section = RECORDING_SHARING_SECTION,
		position = 1
	)
	default boolean discordAutoUpload() { return false; }

	@Range(min = 1, max = 500)
	@ConfigItem(
		keyName = "discordMaxUploadMb",
		name = "Size limit",
		description = "Do not attempt to post recordings larger than this. Discord allows 20MB "
			+ "by default and more on boosted servers, so raise it if yours is boosted.",
		section = RECORDING_SHARING_SECTION,
		position = 2
	)
	default int discordMaxUploadMb() { return 20; }

	// --------------------------------------------------------- recorded events

	@ConfigItem(
		keyName = "recordLevelUps",
		name = "Level ups",
		description = "Save a clip when a skill levels up.",
		section = RECORDING_EVENTS_SECTION,
		position = 0
	)
	default boolean recordLevelUps() { return true; }

	@ConfigItem(
		keyName = "recordDeaths",
		name = "Deaths",
		description = "Save a clip when you die.",
		section = RECORDING_EVENTS_SECTION,
		position = 1
	)
	default boolean recordDeaths() { return true; }

	@ConfigItem(
		keyName = "recordLoot",
		name = "Loot",
		description = "Save a clip when a kill drops loot worth more than the threshold below.",
		section = RECORDING_EVENTS_SECTION,
		position = 2
	)
	default boolean recordLoot() { return true; }

	@Range(min = 0, max = 1_000_000_000)
	@ConfigItem(
		keyName = "recordLootMinimumValue",
		name = "Loot threshold",
		description = "Minimum total drop value, in coins, worth recording.",
		section = RECORDING_EVENTS_SECTION,
		position = 3
	)
	default int recordLootMinimumValue() { return 500_000; }

	@ConfigItem(
		keyName = "recordPets",
		name = "Pets",
		description = "Save a clip on a pet drop.",
		section = RECORDING_EVENTS_SECTION,
		position = 4
	)
	default boolean recordPets() { return true; }

	@ConfigItem(
		keyName = "recordQuests",
		name = "Quest completions",
		description = "Save a clip when a quest is completed.",
		section = RECORDING_EVENTS_SECTION,
		position = 5
	)
	default boolean recordQuests() { return true; }

	@ConfigItem(
		keyName = "recordCollectionLog",
		name = "Collection log slots",
		description = "Save a clip when a new collection log slot is filled.",
		section = RECORDING_EVENTS_SECTION,
		position = 6
	)
	default boolean recordCollectionLog() { return true; }

	@ConfigItem(
		keyName = "recordClueScrolls",
		name = "Clue scroll rewards",
		description = "Save a clip when a clue casket is opened.",
		section = RECORDING_EVENTS_SECTION,
		position = 7
	)
	default boolean recordClueScrolls() { return true; }

	@ConfigItem(
		keyName = "recordBossWaves",
		name = "Wave-based content",
		description = "Record each Fight Cave or Inferno wave in full as a long recording. "
			+ "Does nothing unless Allow long recordings is on, in the Recording section.",
		section = RECORDING_EVENTS_SECTION,
		position = 8
	)
	default boolean recordBossWaves() { return false; }

	// ------------------------------------------------------------------- Debug

	@ConfigItem(
		keyName = "debugOverlay",
		name = "Debug overlay",
		description = "Show Vulkan memory, scene capture, and callback diagnostics on screen.",
		section = DEBUG_SECTION,
		position = 0
	)
	default boolean debugOverlay() { return false; }

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
		keyName = "validation",
		name = "Validation layers",
		description = "Enable VK_LAYER_KHRONOS_validation. Catches API misuse but adds overhead. Plugin must be re-enabled to take effect.",
		section = DEBUG_SECTION,
		position = 2
	)
	default boolean validation()
	{
		// Default off: the layer SIGSEGVs on plugin disable on some hosts
		// (KNOWN_ISSUES #2). Opt in via -Dvkgpu.validation=true.
		return Boolean.parseBoolean(System.getProperty("vkgpu.validation", "false"));
	}

	@ConfigItem(
		keyName = "wireframeTerrain",
		name = "Wireframe: terrain",
		description = "Render flat tile paint and tile-model terrain as wireframe.",
		section = DEBUG_SECTION,
		position = 3
	)
	default boolean wireframeTerrain() { return false; }

	@ConfigItem(
		keyName = "wireframeWalls",
		name = "Wireframe: walls",
		description = "Render WallObject geometry as wireframe.",
		section = DEBUG_SECTION,
		position = 4
	)
	default boolean wireframeWalls() { return false; }

	@ConfigItem(
		keyName = "wireframeDecorative",
		name = "Wireframe: decorative objects",
		description = "Render DecorativeObject geometry (signs, banners, fixtures) as wireframe.",
		section = DEBUG_SECTION,
		position = 5
	)
	default boolean wireframeDecorative() { return false; }

	@ConfigItem(
		keyName = "wireframeGround",
		name = "Wireframe: ground objects",
		description = "Render GroundObject geometry (paintings on the floor, ground decorations) as wireframe.",
		section = DEBUG_SECTION,
		position = 6
	)
	default boolean wireframeGround() { return false; }

	@ConfigItem(
		keyName = "wireframeGameObjects",
		name = "Wireframe: game objects",
		description = "Render static game objects (buildings, trees, fences) as wireframe.",
		section = DEBUG_SECTION,
		position = 7
	)
	default boolean wireframeGameObjects() { return false; }

	@ConfigItem(
		keyName = "wireframeDynamic",
		name = "Wireframe: dynamic entities",
		description = "Render players, NPCs and other dynamic models as wireframe.",
		section = DEBUG_SECTION,
		position = 8
	)
	default boolean wireframeDynamic() { return false; }

}
