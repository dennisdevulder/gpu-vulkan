package net.runelite.client.plugins.gpuvulkan;

import java.util.Set;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.client.events.ConfigChanged;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Extension point for renderers that want to share the GPU Vulkan backend.
 *
 * <p>The backend owns RuneLite's {@code DrawCallbacks} slot and fans scene,
 * model, config and command-recording events out through this interface. This
 * keeps platform setup, swapchain handling and frame sync in one place while
 * allowing renderer modules to provide their own pipelines.
 */
public interface VulkanRenderExtension extends AutoCloseable
{
	default void onRegistered(VulkanRenderContext context) {}

	default void onConfigChanged(ConfigChanged event) {}

	default void beginFrame() {}

	default void captureDynamicPending() {}

	default void invalidateCapturedScene() {}

	default void captureScene(Scene scene) {}

	default void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ) {}

	default void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		captureModel(model, orientation, worldX, worldY, worldZ);
	}

	default void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		captureModel(projection, model, orientation, worldX, worldY, worldZ);
	}

	default void setLevelRange(int minLevel, int maxLevel) {}

	default void setHideRoofIds(Set<Integer> hideRoofIds) {}

	default void uploadUiPixels(int[] pixels, int width, int height) {}

	/**
	 * Record commands that must happen before {@code vkCmdBeginRenderPass}.
	 * Typical use: staging-buffer copies and image layout transitions.
	 */
	default void recordBeforeRenderPass(VkCommandBuffer commandBuffer) {}

	/**
	 * Record draw commands inside the backend render pass.
	 */
	default void recordRenderPass(VulkanFrameContext frame) {}

	@Override
	default void close() {}
}
