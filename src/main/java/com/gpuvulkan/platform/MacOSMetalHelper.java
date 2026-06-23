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
 * macOS-only native bridge (rlmtl.m / librlmtl.dylib) that attaches a
 * CAMetalLayer to the AWT Canvas via JAWT. Pure-Java JAWT bindings don't
 * work on macOS.
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
		// Force-load libjawt first: the helper links JAWT symbols with
		// dynamic_lookup, and without it the first call SIGSEGVs at PC=0.
		try
		{
			System.loadLibrary("jawt");
		}
		catch (UnsatisfiedLinkError e)
		{
			log.debug("System.loadLibrary(\"jawt\") said: {}", e.toString());
		}

		String resourcePath = "/com/gpuvulkan/librlmtl.dylib";
		try (InputStream in = MacOSMetalHelper.class.getResourceAsStream(resourcePath))
		{
			if (in == null)
			{
				throw new RuntimeException("librlmtl.dylib not on classpath at "
					+ resourcePath + " — rebuild the jar on macOS with ./gradlew shadowJar "
					+ "so compileMacOSMetalHelper can package the native helper.");
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
		// Pass the Canvas size: LWAWT defers layout, and an unsized layer
		// doesn't render until the first resize.
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
		long token = ResizeTrace.start("metal.resizeCanvas",
			canvas == null ? "canvas=null" : canvas.getWidth() + "x" + canvas.getHeight());
		layerScale = canvasScale(canvas);
		try
		{
			resizeMetalLayerSize(canvas.getWidth(), canvas.getHeight());
		}
		finally
		{
			ResizeTrace.end(token, "metal.resizeCanvas");
		}
	}

	static void resizeMetalLayerSize(int widthPoints, int heightPoints)
	{
		if (loaded)
		{
			int width = Math.max(widthPoints, 1);
			int height = Math.max(heightPoints, 1);
			long start = System.nanoTime();
			nResizeMetalLayer(width, height, layerScale);
			ResizeTrace.slow("metal.resizeLayer", System.nanoTime() - start,
				width + "x" + height + " scale=" + layerScale);
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

	/** Returns [drawable, MTLTexture, width, height] or null on timeout. The drawable
	 *  MUST reach {@link #presentDrawable} even on failure paths, or nextDrawable stalls. */
	static long[] nextDrawable()
	{
		ensureLoaded();
		long start = System.nanoTime();
		long[] drawable = nNextDrawable();
		long elapsed = System.nanoTime() - start;
		ResizeTrace.slow("metal.nextDrawable", elapsed,
			drawable == null ? "null" : drawable[2] + "x" + drawable[3]);
		return drawable;
	}

	/** Schedules [drawable present] on mtlQueue and drops nextDrawable's retain.
	 *  Must run AFTER the render's vkQueueSubmit on the same queue — Metal's
	 *  in-queue ordering is what sequences the present behind the render. */
	static void presentDrawable(long drawable, long mtlQueue)
	{
		long start = System.nanoTime();
		nPresentDrawable(drawable, mtlQueue);
		ResizeTrace.slow("metal.presentDrawable", System.nanoTime() - start,
			"drawable=0x" + Long.toHexString(drawable));
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
