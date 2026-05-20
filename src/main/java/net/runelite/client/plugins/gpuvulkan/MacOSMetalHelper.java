package net.runelite.client.plugins.gpuvulkan;

import java.awt.Canvas;
import java.awt.GraphicsConfiguration;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * macOS-only native bridge that attaches a {@code CAMetalLayer} to an AWT
 * {@link Canvas} via JAWT and returns the layer's pointer for use with
 * {@code vkCreateMetalSurfaceEXT}.
 *
 * <p>The actual JAWT work lives in {@code rlmtl.m}, built into
 * {@code librlmtl.dylib} by the {@code vkdev} dev script and staged as a
 * classpath resource. Pure-Java JAWT bindings don't work on macOS — same
 * reason rlawt is native.
 */
@Slf4j
final class MacOSMetalHelper
{
	private static volatile boolean loaded;
	private static volatile double layerScale = 1.0;

	private MacOSMetalHelper() {}

	private static synchronized void ensureLoaded()
	{
		if (loaded)
		{
			return;
		}
		// libjawt isn't auto-loaded by the JVM on macOS — it's only pulled
		// in by code that calls into it. Our native helper links the JAWT
		// symbols with -Wl,-undefined,dynamic_lookup, so they need to
		// resolve from a libjawt that's already in the process. Force-load
		// it first; otherwise JAWT_GetAWT resolves to NULL and the first
		// call crashes with SIGSEGV at PC=0.
		try
		{
			System.loadLibrary("jawt");
		}
		catch (UnsatisfiedLinkError e)
		{
			log.debug("System.loadLibrary(\"jawt\") said: {}", e.toString());
		}

		String resourcePath = "/net/runelite/client/plugins/gpuvulkan/librlmtl.dylib";
		try (InputStream in = MacOSMetalHelper.class.getResourceAsStream(resourcePath))
		{
			if (in == null)
			{
				throw new RuntimeException("librlmtl.dylib not on classpath at "
					+ resourcePath + " — re-run vkdev to build the native helper.");
			}
			File temp = File.createTempFile("librlmtl", ".dylib");
			temp.deleteOnExit();
			Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			System.load(temp.getAbsolutePath());
			loaded = true;
			log.info("Loaded librlmtl.dylib from {}", temp.getAbsolutePath());
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to extract/load librlmtl.dylib", e);
		}
	}

	private static native long nAttachMetalLayer(Canvas canvas, boolean vsync,
		int initialWidthPoints, int initialHeightPoints, double scale);
	private static native void nDetachMetalLayer();
	private static native void nResizeMetalLayer(int widthPoints, int heightPoints, double scale);
	private static native long[] nNextDrawable();
	private static native void nPresentDrawable(long drawable, long mtlQueue);
	private static native void nRetainObject(long ptr);
	private static native void nReleaseObject(long ptr);

	static long attachMetalLayer(Canvas canvas, boolean vsync)
	{
		ensureLoaded();
		// Pass current Canvas size so the native side can initialize the
		// metal layer's frame + drawableSize without depending on AWT to
		// lay it out (LWAWT defers that, leading to the "must resize to
		// start rendering" symptom).
		int w = Math.max(canvas.getWidth(), 1);
		int h = Math.max(canvas.getHeight(), 1);
		layerScale = canvasScale(canvas);
		long ptr = nAttachMetalLayer(canvas, vsync, w, h, layerScale);
		if (ptr == 0L)
		{
			throw new RuntimeException("nAttachMetalLayer returned NULL — "
				+ "JAWT_GetAWT rejected every version, or the canvas was "
				+ "not in a JAWT-lockable state");
		}
		return ptr;
	}

	static void detachMetalLayer()
	{
		if (loaded)
		{
			nDetachMetalLayer();
		}
	}

	static void resizeMetalLayer(Canvas canvas)
	{
		layerScale = canvasScale(canvas);
		resizeMetalLayerSize(canvas.getWidth(), canvas.getHeight());
	}

	static void resizeMetalLayerSize(int widthPoints, int heightPoints)
	{
		if (loaded)
		{
			nResizeMetalLayer(Math.max(widthPoints, 1), Math.max(heightPoints, 1), layerScale);
		}
	}

	private static double canvasScale(Canvas canvas)
	{
		if (canvas == null)
		{
			return 1.0;
		}
		GraphicsConfiguration graphicsConfiguration = canvas.getGraphicsConfiguration();
		if (graphicsConfiguration == null)
		{
			return 1.0;
		}
		AffineTransform transform = graphicsConfiguration.getDefaultTransform();
		return Math.max(transform.getScaleX(), transform.getScaleY());
	}

	/**
	 * Acquires the next {@code CAMetalDrawable} from the attached
	 * {@code CAMetalLayer}. Returns an array of four longs:
	 * <ol>
	 *   <li>{@code id<CAMetalDrawable>} pointer (must be passed to
	 *       {@link #presentDrawable} eventually, even on failure paths —
	 *       otherwise the drawable leaks and {@code nextDrawable} stalls)</li>
	 *   <li>{@code id<MTLTexture>} pointer for the drawable's color texture</li>
	 *   <li>Texture width in pixels</li>
	 *   <li>Texture height in pixels</li>
	 * </ol>
	 * Returns {@code null} if no drawable is available before
	 * {@code CAMetalLayer}'s internal 1-second timeout (treat the same as a
	 * dropped frame on Linux's {@code VK_NOT_READY}).
	 */
	static long[] nextDrawable()
	{
		ensureLoaded();
		return nNextDrawable();
	}

	/**
	 * Schedules {@code [drawable present]} on a tiny one-shot
	 * {@code MTLCommandBuffer} built from {@code mtlQueue}, and releases
	 * the retain held by {@link #nextDrawable}. Must run AFTER the Vulkan
	 * render's {@code vkQueueSubmit} for this drawable has been queued —
	 * otherwise the present scheduling races our render writes.
	 */
	static void presentDrawable(long drawable, long mtlQueue)
	{
		nPresentDrawable(drawable, mtlQueue);
	}

	/** {@code [obj retain]} on an arbitrary Objective-C handle (typically
	 *  an {@code MTLTexture}). Pair with {@link #releaseObject}. */
	static void retainObject(long ptr)
	{
		nRetainObject(ptr);
	}

	/** {@code [obj release]}; pair with {@link #retainObject}. */
	static void releaseObject(long ptr)
	{
		nReleaseObject(ptr);
	}
}
