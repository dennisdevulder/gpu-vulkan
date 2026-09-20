/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

/**
 * A source of interleaved 16-bit little-endian PCM for the audio track.
 *
 * Implementations are polled by the recorder; {@link #read} must not block
 * longer than the buffer it is filling represents.
 */
public interface AudioSource extends AutoCloseable
{
	int sampleRate();

	int channels();

	/** Starts capture. Throws when the device cannot be opened. */
	void start() throws Exception;

	/**
	 * Reads whatever is currently available, up to {@code buffer.length}.
	 *
	 * @return bytes written, or -1 when the source has ended
	 */
	int read(byte[] buffer) throws Exception;

	@Override
	void close();
}
