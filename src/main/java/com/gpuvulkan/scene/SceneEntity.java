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

import net.runelite.api.Scene;

/**
 * Per-scene draw state, as opposed to the frame-wide state in
 * {@link VulkanFrameContext}: where a sub-worldview's local space sits in the
 * top-level world, and the engine's HSL override for that worldview. The
 * top-level scene is {@link #TOP_LEVEL}.
 */
final class SceneEntity
{
	static final SceneEntity TOP_LEVEL = new SceneEntity(0, 0, 0, 0);

	/** Top-level scene units; the shader rebuilds world XZ from these for fog. */
	final int translateX;
	final int translateZ;
	final int yawJau;
	/** The four override bytes packed for the vertex push: hue, sat, lum, amount. */
	final int tint;

	SceneEntity(int translateX, int translateZ, int yawJau, int tint)
	{
		this.translateX = translateX;
		this.translateZ = translateZ;
		this.yawJau = yawJau;
		this.tint = tint;
	}

	/** The engine tints a whole worldview to mark it out — other players' ships
	 *  render as a flat silhouette. Stock hands the same four bytes to its
	 *  entityTint uniform sign-extended, which the shader reproduces. */
	static int packTint(Scene scene)
	{
		return ((scene.getOverrideHue() & 0xFF) << 24)
			| ((scene.getOverrideSaturation() & 0xFF) << 16)
			| ((scene.getOverrideLuminance() & 0xFF) << 8)
			| (scene.getOverrideAmount() & 0xFF);
	}

	boolean is(int translateX, int translateZ, int yawJau, int tint)
	{
		return this.translateX == translateX && this.translateZ == translateZ
			&& this.yawJau == yawJau && this.tint == tint;
	}
}
