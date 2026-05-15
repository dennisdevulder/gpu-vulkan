/**
 * Rendering device layer. A thin Vulkan abstraction designed to (a) hide the
 * boilerplate that fills the rest of this plugin and (b) provide an API
 * shape that two independent renderers (this plugin + something like 117HD)
 * could both target.
 *
 * <p>Public types are interfaces; everything package-private is the
 * implementation, currently a wrapper over the existing
 * {@code VulkanInstance}/{@code VulkanDevice}/{@code Swapchain}/{@code FrameSync}
 * machinery. Future work folds the existing types into this layer outright;
 * for now the wrapper lets us migrate consumers one at a time without
 * breaking the working plugin.
 *
 * <p>API shape is intentionally close to WebGPU's
 * (Renderer / Frame / RenderEncoder / BindGroup / Pipeline). That model has
 * converged after wgpu, Dawn, Veldrid all settled on similar surfaces; if
 * we ever propose this for RuneLite core, the prior art is already there.
 *
 * <p>Out of scope for the proof-it-works phase: render graphs, headless mode,
 * bindless via VK_EXT_descriptor_indexing, compute pipelines. All planned
 * but added when a consumer asks for them.
 */
package net.runelite.client.plugins.gpuvulkan.gfx;
