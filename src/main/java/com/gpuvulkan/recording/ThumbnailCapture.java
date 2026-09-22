/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

/**
 * Grabs one frame for a recording's card in the panel.
 *
 * Uses the renderer's existing screenshot readback through DrawManager rather
 * than a second path off the swapchain: it is already fenced correctly and
 * costs one frame, once per recording.
 */
@Slf4j
final class ThumbnailCapture
{
	private static final int MAX_EDGE = 320;

	private final DrawManager drawManager;

	ThumbnailCapture(DrawManager drawManager)
	{
		this.drawManager = drawManager;
	}

	/**
	 * Requests the next frame and writes it to {@code destination}. The
	 * callback runs off the client thread once the image arrives; a recording
	 * that finishes first simply has no thumbnail.
	 */
	void capture(Path destination, Consumer<Boolean> onDone)
	{
		if (drawManager == null)
		{
			onDone.accept(false);
			return;
		}
		try
		{
			drawManager.requestNextFrameListener(image -> onDone.accept(write(image, destination)));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not request a thumbnail frame", e);
			onDone.accept(false);
		}
	}

	private boolean write(Image image, Path destination)
	{
		if (image == null)
		{
			return false;
		}
		try
		{
			BufferedImage scaled = scale(image);
			Path parent = destination.getParent();
			if (parent != null)
			{
				Files.createDirectories(parent);
			}
			return ImageIO.write(scaled, "png", destination.toFile());
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not write thumbnail {}", destination, e);
			return false;
		}
	}

	private BufferedImage scale(Image source)
	{
		int width = source.getWidth(null);
		int height = source.getHeight(null);
		if (width <= 0 || height <= 0)
		{
			throw new IllegalStateException("frame has no dimensions");
		}
		double factor = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
		int outWidth = Math.max(1, (int) Math.round(width * factor));
		int outHeight = Math.max(1, (int) Math.round(height * factor));

		BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(source, 0, 0, outWidth, outHeight, null);
		g.dispose();
		return out;
	}
}
