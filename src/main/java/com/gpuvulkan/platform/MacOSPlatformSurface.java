/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.awt.Canvas;

/**
 * macOS is not shipped in the Plugin Hub build because it needs native layer
 * integration that Plugin Hub policy does not allow.
 */
final class MacOSPlatformSurface implements PlatformSurface
{
	MacOSPlatformSurface(boolean vsync) {}

	@Override
	public String[] requiredInstanceExtensions()
	{
		return new String[0];
	}

	@Override
	public long createSurface(VulkanInstance instance, Canvas canvas)
	{
		throw new UnsupportedOperationException("GPU (Vulkan) macOS support is not available in this build");
	}
}
