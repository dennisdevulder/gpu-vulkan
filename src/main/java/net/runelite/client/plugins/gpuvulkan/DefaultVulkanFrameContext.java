package net.runelite.client.plugins.gpuvulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

final class DefaultVulkanFrameContext implements VulkanFrameContext
{
	private final VkCommandBuffer commandBuffer;
	private final int targetWidth;
	private final int targetHeight;
	private final int viewportXOffset;
	private final int viewportYOffset;
	private final int viewportWidth;
	private final int viewportHeight;
	private final int canvasWidth;
	private final int canvasHeight;
	private final int scale;
	private final float[] sceneMvp;
	private final float cameraX;
	private final float cameraZ;
	private final float brightness;
	private final int drawDistanceTiles;
	private final int fogDepthTiles;
	private final float fogR;
	private final float fogG;
	private final float fogB;
	private final int gameTick;
	private final float textureLightMode;
	private final int colorBlindMode;
	private final float colorBlindIntensity;
	private final float smoothBanding;
	private final int overlayColor;

	DefaultVulkanFrameContext(VkCommandBuffer commandBuffer,
		int targetWidth, int targetHeight,
		int viewportXOffset, int viewportYOffset,
		int viewportWidth, int viewportHeight,
		int canvasWidth, int canvasHeight, int scale,
		float[] sceneMvp, float cameraX, float cameraZ, float brightness,
		int drawDistanceTiles, int fogDepthTiles,
		float fogR, float fogG, float fogB,
		int gameTick, float textureLightMode,
		int colorBlindMode, float colorBlindIntensity,
		float smoothBanding, int overlayColor)
	{
		this.commandBuffer = commandBuffer;
		this.targetWidth = targetWidth;
		this.targetHeight = targetHeight;
		this.viewportXOffset = viewportXOffset;
		this.viewportYOffset = viewportYOffset;
		this.viewportWidth = viewportWidth;
		this.viewportHeight = viewportHeight;
		this.canvasWidth = canvasWidth;
		this.canvasHeight = canvasHeight;
		this.scale = scale;
		this.sceneMvp = sceneMvp;
		this.cameraX = cameraX;
		this.cameraZ = cameraZ;
		this.brightness = brightness;
		this.drawDistanceTiles = drawDistanceTiles;
		this.fogDepthTiles = fogDepthTiles;
		this.fogR = fogR;
		this.fogG = fogG;
		this.fogB = fogB;
		this.gameTick = gameTick;
		this.textureLightMode = textureLightMode;
		this.colorBlindMode = colorBlindMode;
		this.colorBlindIntensity = colorBlindIntensity;
		this.smoothBanding = smoothBanding;
		this.overlayColor = overlayColor;
	}

	@Override public VkCommandBuffer commandBuffer() { return commandBuffer; }
	@Override public int targetWidth() { return targetWidth; }
	@Override public int targetHeight() { return targetHeight; }
	@Override public int viewportXOffset() { return viewportXOffset; }
	@Override public int viewportYOffset() { return viewportYOffset; }
	@Override public int viewportWidth() { return viewportWidth; }
	@Override public int viewportHeight() { return viewportHeight; }
	@Override public int canvasWidth() { return canvasWidth; }
	@Override public int canvasHeight() { return canvasHeight; }
	@Override public int scale() { return scale; }
	@Override public float[] sceneMvp() { return sceneMvp; }
	@Override public float cameraX() { return cameraX; }
	@Override public float cameraZ() { return cameraZ; }
	@Override public float brightness() { return brightness; }
	@Override public int drawDistanceTiles() { return drawDistanceTiles; }
	@Override public int fogDepthTiles() { return fogDepthTiles; }
	@Override public float fogR() { return fogR; }
	@Override public float fogG() { return fogG; }
	@Override public float fogB() { return fogB; }
	@Override public int gameTick() { return gameTick; }
	@Override public float textureLightMode() { return textureLightMode; }
	@Override public int colorBlindMode() { return colorBlindMode; }
	@Override public float colorBlindIntensity() { return colorBlindIntensity; }
	@Override public float smoothBanding() { return smoothBanding; }
	@Override public int overlayColor() { return overlayColor; }
}
