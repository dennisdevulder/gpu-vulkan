/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * LIFO-ordered cleanup stack. Register destroy actions in creation
 * order; {@link #close()} pops and runs them in reverse, which is what
 * the Vulkan spec requires (children before parents). Exceptions from
 * individual destroy actions are collected and rethrown so one bad
 * handle doesn't skip the rest.
 */
final class Disposables implements AutoCloseable
{
    private final Deque<Runnable> stack = new ArrayDeque<>();

    void add(Runnable destroy)
    {
        stack.push(destroy);
    }

    @Override
    public void close()
    {
        Throwable first = null;
        while (!stack.isEmpty())
        {
            try
            {
                stack.pop().run();
            }
            catch (Throwable t)
            {
                if (first == null) first = t;
                else first.addSuppressed(t);
            }
        }
        if (first instanceof RuntimeException) throw (RuntimeException) first;
        if (first instanceof Error) throw (Error) first;
        if (first != null) throw new RuntimeException(first);
    }
}
