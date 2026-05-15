package net.runelite.client.plugins.gpuvulkan.gfx;

/**
 * A 2D image whose pixels are pushed from the CPU once per frame. Internally
 * holds {@code FRAMES_IN_FLIGHT} ping-pong textures plus the staging buffers
 * + descriptor cycle to avoid a write-while-read race; the consumer just
 * calls {@link #uploadPixels} each frame.
 *
 * <p>UI overlay is the canonical use case: RuneLite's
 * {@code BufferProvider.getPixels()} returns the canvas as a flat int[] of
 * ARGB pixels; we hand them here, the layer takes it from there.
 */
public interface StreamingImage extends AutoCloseable
{
	/**
	 * Upload {@code pixels} into the current frame's slot. Caller can re-use
	 * the same array next frame; bytes are memcpy'd into a host-visible
	 * staging region immediately, and the GPU copy is queued by the next
	 * {@link RenderEncoder} that references the bind group containing this
	 * image.
	 *
	 * @param pixels  4-byte-per-pixel data laid out as {@code width * height}
	 *                ints. Caller is responsible for matching the image's
	 *                {@code width}/{@code height} at creation time.
	 */
	void uploadPixels(int[] pixels);

	int width();
	int height();

	@Override
	void close();
}
