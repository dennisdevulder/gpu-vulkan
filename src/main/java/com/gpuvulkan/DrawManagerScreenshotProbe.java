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

import java.lang.reflect.Field;
import java.util.Queue;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

/**
 * Peeks at whether a screenshot consumer is waiting on {@link DrawManager},
 * so the renderer only records the GPU→CPU readback copy on frames that
 * actually need it.
 *
 * <p>This is the plugin's single use of reflection, and it reads a private
 * field ({@code DrawManager.nextFrame}). There is no public alternative:
 * the API exposes only {@code requestNextFrameListener} (registers a
 * consumer) and {@code processDrawComplete} (CONSUMES the queue — calling
 * it to test emptiness would eat pending requests with a frame we haven't
 * finished). A {@code DrawManager.hasNextFrameRequest()} accessor upstream
 * would make this class deletable.
 *
 * <p>Fail-safe: if the field disappears in a client update, the probe logs
 * one warning, latches off, and {@link #hasPendingRequest} returns false
 * forever — screenshots silently stop working but nothing else is affected.
 */
@Slf4j
final class DrawManagerScreenshotProbe
{
	private final DrawManager drawManager;
	private Field nextFrameField;
	private boolean failed;

	DrawManagerScreenshotProbe(DrawManager drawManager)
	{
		this.drawManager = drawManager;
	}

	boolean hasPendingRequest()
	{
		if (drawManager == null || failed)
		{
			return false;
		}
		try
		{
			Field field = nextFrameField;
			if (field == null)
			{
				field = DrawManager.class.getDeclaredField("nextFrame");
				field.setAccessible(true);
				nextFrameField = field;
			}
			Object value = field.get(drawManager);
			return value instanceof Queue && !((Queue<?>) value).isEmpty();
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			failed = true;
			log.warn("Unable to inspect DrawManager screenshot queue; Vulkan screenshot readback disabled", e);
			return false;
		}
	}
}
