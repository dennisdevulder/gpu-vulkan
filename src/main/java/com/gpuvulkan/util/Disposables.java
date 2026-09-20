/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.util.ArrayDeque;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;

/**
 * LIFO teardown stack: resources register at creation, {@link #close()} runs
 * them in reverse order.
 */
@Slf4j
final class Disposables implements AutoCloseable
{
	private final Deque<AutoCloseable> stack = new ArrayDeque<>();

	void add(AutoCloseable c)
	{
		stack.push(c);
	}

	@Override
	public void close()
	{
		while (!stack.isEmpty())
		{
			AutoCloseable c = stack.pop();
			try
			{
				c.close();
			}
			catch (Exception e)
			{
				// Keep tearing down so a single failure doesn't leak the rest.
				log.warn("dispose of {} threw", c.getClass().getSimpleName(), e);
			}
		}
	}
}
