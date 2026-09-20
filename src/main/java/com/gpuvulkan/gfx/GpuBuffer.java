/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

import java.nio.ByteBuffer;

/**
 * Persistently mapped, host-coherent GPU buffer. CPU writes race GPU reads
 * of in-flight frames — callers either write only data the GPU isn't
 * reading, or sub-range per {@link RenderDevice#currentSlot()}.
 */
public interface GpuBuffer extends AutoCloseable
{
	long size();

	ByteBuffer mapped();

	void flush();

	@Override
	void close();
}
