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
package com.gpuvulkan.recording.triggers;

import com.gpuvulkan.recording.RecordingContext;
import com.gpuvulkan.recording.RecordingRequest;
import com.gpuvulkan.recording.RecordingTrigger;
import net.runelite.api.Player;

/** Event-bus registration and the clip call the built-in triggers share. */
abstract class EventBusTrigger implements RecordingTrigger
{
	protected RecordingContext context;

	@Override
	public void bind(RecordingContext context)
	{
		this.context = context;
		context.eventBus().register(this);
	}

	@Override
	public void unbind(RecordingContext context)
	{
		context.eventBus().unregister(this);
		this.context = null;
	}

	protected boolean enabled()
	{
		return context != null && context.service().available() && configEnabled();
	}

	protected abstract boolean configEnabled();

	protected void clip(String description)
	{
		clip(RecordingRequest.of(kind()).description(description));
	}

	protected void clip(RecordingRequest.Builder request)
	{
		if (context == null)
		{
			return;
		}
		context.service().clip(withPlayer(request).build());
	}

	/** Stamps who and where, so a clip stays identifiable later. */
	protected RecordingRequest.Builder withPlayer(RecordingRequest.Builder request)
	{
		Player local = context.client().getLocalPlayer();
		if (local != null)
		{
			request.meta("player", String.valueOf(local.getName()));
			if (local.getWorldLocation() != null)
			{
				request.meta("region", String.valueOf(local.getWorldLocation().getRegionID()));
			}
		}
		request.meta("world", String.valueOf(context.client().getWorld()));
		return request;
	}
}
