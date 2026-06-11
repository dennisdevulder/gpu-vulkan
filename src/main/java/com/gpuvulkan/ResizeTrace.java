/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ResizeTrace
{
	static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vkgpu.resizeTrace", "false"));
	private static final long SLOW_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
	private static final AtomicLong sequence = new AtomicLong();
	private static final AtomicLong nextFrameLogNanos = new AtomicLong();

	private ResizeTrace()
	{
	}

	static long start(String event)
	{
		return start(event, "");
	}

	static long start(String event, String detail)
	{
		if (!ENABLED)
		{
			return 0L;
		}

		long id = sequence.incrementAndGet();
		log.info("resize-trace #{} begin {} {}", id, event, detail);
		return (id << 32) | (System.nanoTime() & 0xffffffffL);
	}

	static void end(long token, String event)
	{
		end(token, event, "");
	}

	static void end(long token, String event, String detail)
	{
		if (!ENABLED || token == 0L)
		{
			return;
		}

		long id = token >>> 32;
		long startLow = token & 0xffffffffL;
		long now = System.nanoTime();
		long elapsed = (now & 0xffffffffL) - startLow;
		if (elapsed < 0)
		{
			elapsed += 0x1_0000_0000L;
		}
		log.info("resize-trace #{} end {} {}ms {}", id, event,
			TimeUnit.NANOSECONDS.toMillis(elapsed), detail);
	}

	static void slow(String event, long nanos, String detail)
	{
		if (ENABLED && nanos >= SLOW_NANOS)
		{
			log.warn("resize-trace slow {} {}ms {}", event,
				TimeUnit.NANOSECONDS.toMillis(nanos), detail);
		}
	}

	static void mark(String event, String detail)
	{
		if (ENABLED)
		{
			log.info("resize-trace {} {}", event, detail);
		}
	}

	// (int, int) so per-frame callers don't build a string when disabled —
	// ENABLED is static final, the JIT erases the whole call.
	static void frame(int width, int height)
	{
		if (!ENABLED)
		{
			return;
		}

		long now = System.nanoTime();
		long next = nextFrameLogNanos.get();
		if (now >= next && nextFrameLogNanos.compareAndSet(next, now + TimeUnit.MILLISECONDS.toNanos(500)))
		{
			log.info("resize-trace plugin.draw heartbeat {}x{}", width, height);
		}
	}
}
