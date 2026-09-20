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
import com.gpuvulkan.recording.RecordingHandle;
import com.gpuvulkan.recording.RecordingKind;
import com.gpuvulkan.recording.RecordingRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * One long recording per Fight Cave or Inferno wave, since a wave is as long
 * as it is. Ends on the next wave, on death, or on leaving the region.
 */
public final class WaveTrigger extends EventBusTrigger
{
	/** "Wave: 12" in the Fight Cave and Inferno. */
	private static final Pattern WAVE = Pattern.compile("^Wave: (\\d+)");

	private static final int FIGHT_CAVE_REGION = 9551;
	private static final int INFERNO_REGION = 9043;

	private static final RecordingKind KIND =
		new RecordingKind("boss_wave", "boss_waves", "Wave");

	private RecordingHandle session;
	private int sessionRegion = -1;

	@Override
	public RecordingKind kind()
	{
		return KIND;
	}

	@Override
	protected boolean configEnabled()
	{
		return context.config().recordBossWaves() && context.config().recordingSessionsEnabled();
	}

	@Override
	public void unbind(RecordingContext context)
	{
		stopSession();
		super.unbind(context);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !enabled())
		{
			return;
		}
		Matcher matcher = WAVE.matcher(Text.removeTags(event.getMessage()).trim());
		if (!matcher.find())
		{
			return;
		}

		// The previous wave ends exactly where this one starts.
		stopSession();

		int region = currentRegion();
		String arena = region == INFERNO_REGION ? "Inferno"
			: region == FIGHT_CAVE_REGION ? "Fight Cave" : "wave";
		String wave = matcher.group(1);

		sessionRegion = region;
		session = context.service().session(withPlayer(RecordingRequest.of(KIND)
			.description(arena + " wave " + wave)
			.session(true)
			.meta("arena", arena)
			.meta("wave", wave))
			.build());
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player local = context == null ? null : context.client().getLocalPlayer();
		if (local != null && event.getActor() == local)
		{
			// Dying ends the run; the death belongs in the wave's recording.
			stopSession();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (session == null)
		{
			return;
		}
		if (!session.active())
		{
			session = null;
			return;
		}
		if (sessionRegion >= 0 && currentRegion() != sessionRegion)
		{
			// Left the arena: teleported out, or the run finished.
			stopSession();
		}
	}

	private int currentRegion()
	{
		Player local = context.client().getLocalPlayer();
		return local == null || local.getWorldLocation() == null
			? -1 : local.getWorldLocation().getRegionID();
	}

	private void stopSession()
	{
		RecordingHandle open = session;
		session = null;
		sessionRegion = -1;
		if (open != null && open.active())
		{
			open.stop();
		}
	}
}
