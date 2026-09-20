/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.encoding;

/** Detach cause meaning the encoder is restarting, not shutting down. */
public final class EncoderRestart extends IllegalStateException
{
    public EncoderRestart()
    {
        super("encoder restarting");
    }
}
