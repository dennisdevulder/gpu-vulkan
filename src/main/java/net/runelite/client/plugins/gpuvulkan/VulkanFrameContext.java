package net.runelite.client.plugins.gpuvulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Per-frame state passed to extensions while command recording is active.
 */
public interface VulkanFrameContext
{
	VkCommandBuffer commandBuffer();

	int targetWidth();

	int targetHeight();

	int viewportXOffset();

	int viewportYOffset();

	int viewportWidth();

	int viewportHeight();

	int canvasWidth();

	int canvasHeight();

	int scale();

	float[] sceneMvp();

	float cameraX();

	float cameraZ();

	float brightness();

	int drawDistanceTiles();

	int fogDepthTiles();

	float fogR();

	float fogG();

	float fogB();

	int gameTick();

	float textureLightMode();

	int colorBlindMode();

	float colorBlindIntensity();

	float smoothBanding();

	int overlayColor();
}
