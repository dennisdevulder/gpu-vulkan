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

import com.gpuvulkan.recording.RecordingKind;
import com.gpuvulkan.recording.RecordingKindRegistry;
import com.gpuvulkan.recording.RecordingRequest;

/** Records a pet drop, from the three chat messages the game uses. */
public final class PetTrigger extends ChatPatternTrigger
{
	private static final String FOLLOWED = "You have a funny feeling like you're being followed";
	private static final String BACKPACK = "You feel something weird sneaking into your backpack";
	private static final String DUPLICATE = "You have a funny feeling like you would have been followed";

	@Override
	public RecordingKind kind()
	{
		return RecordingKindRegistry.PET;
	}

	@Override
	protected boolean configEnabled()
	{
		return context.config().recordPets();
	}

	@Override
	protected void onGameMessage(String message)
	{
		// Check the duplicate wording first: it is a prefix-alike of FOLLOWED.
		if (message.startsWith(DUPLICATE))
		{
			clip(RecordingRequest.of(kind()).description("duplicate pet").meta("duplicate", "true"));
		}
		else if (message.startsWith(FOLLOWED) || message.startsWith(BACKPACK))
		{
			clip(RecordingRequest.of(kind()).description("pet"));
		}
	}
}
