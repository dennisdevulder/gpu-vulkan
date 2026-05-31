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
package net.runelite.client.plugins.gpuvulkan;

/**
 * Vulkan video encode capabilities exposed by the backend.
 *
 * <p>This is intentionally conservative: the current backend creates only the
 * render device/queue, so encode may be device-capable but not enabled yet.
 * Tracker-style plugins can use this context to decide whether Vulkan encode
 * is worth wiring up and to avoid duplicating physical-device discovery.
 */
public interface VulkanEncodeContext
{
	/**
	 * Returns true when the selected physical device advertises a video encode
	 * queue plus at least one supported encode codec extension. This does not
	 * validate concrete video profiles or image formats yet.
	 */
	boolean isDeviceCapable();

	boolean isAvailable();

	String unavailableReason();

	boolean supportsH264();

	boolean supportsH265();

	boolean supportsAv1();

	int encodeQueueFamily();

	long encodeQueueHandle();

	long deviceHandle();

	long physicalDeviceHandle();
}
