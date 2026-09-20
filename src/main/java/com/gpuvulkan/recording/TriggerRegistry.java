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
package com.gpuvulkan.recording;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Binds and unbinds {@link RecordingTrigger}s as a set. One that throws is
 * dropped rather than allowed to break the others.
 */
@Slf4j
public final class TriggerRegistry
{
	private final RecordingContext context;
	private final List<RecordingTrigger> bound = new ArrayList<>();

	public TriggerRegistry(RecordingContext context)
	{
		this.context = context;
	}

	public void bind(RecordingTrigger trigger)
	{
		if (trigger == null)
		{
			return;
		}
		try
		{
			context.service().kinds().register(trigger.kind());
			trigger.bind(context);
			bound.add(trigger);
		}
		catch (RuntimeException e)
		{
			log.warn("Recording trigger {} failed to bind", trigger.getClass().getSimpleName(), e);
		}
	}

	public void bindAll(Iterable<RecordingTrigger> triggers)
	{
		for (RecordingTrigger trigger : triggers)
		{
			bind(trigger);
		}
	}

	public void unbindAll()
	{
		for (RecordingTrigger trigger : bound)
		{
			try
			{
				trigger.unbind(context);
			}
			catch (RuntimeException e)
			{
				log.warn("Recording trigger {} failed to unbind", trigger.getClass().getSimpleName(), e);
			}
		}
		bound.clear();
	}

	public List<RecordingTrigger> bound()
	{
		return new ArrayList<>(bound);
	}
}
