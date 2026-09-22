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
import com.gpuvulkan.recording.RecordingKind;
import com.gpuvulkan.recording.RecordingKindRegistry;
import com.gpuvulkan.recording.RecordingRequest;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Records a level-up. Login fires StatChanged for every skill, so the first
 * observation after one is a baseline, never a level-up.
 */
public final class LevelUpTrigger extends EventBusTrigger
{
	private final Map<Skill, Integer> known = new EnumMap<>(Skill.class);
	private boolean seeded;

	@Override
	public RecordingKind kind()
	{
		return RecordingKindRegistry.LEVEL_UP;
	}

	@Override
	protected boolean configEnabled()
	{
		return context.config().recordLevelUps();
	}

	@Override
	public void unbind(RecordingContext context)
	{
		known.clear();
		seeded = false;
		super.unbind(context);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGING_IN
			|| event.getGameState() == GameState.HOPPING)
		{
			known.clear();
			seeded = false;
		}
	}

	/**
	 * Takes the baseline from the client once, on the first tick after login.
	 *
	 * The login burst of StatChanged arrives before the state reaches
	 * LOGGED_IN, so waiting for a StatChanged to establish the baseline spends
	 * the first XP gain doing it -- and loses the level-up when that gain is
	 * the one that levels.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (seeded || context == null || context.client().getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		for (Skill skill : Skill.values())
		{
			known.put(skill, context.client().getRealSkillLevel(skill));
		}
		seeded = true;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null || context == null
			|| context.client().getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		int level = event.getLevel();
		Integer previous = known.put(skill, level);
		if (previous == null || level <= previous || !enabled())
		{
			return;
		}
		clip(RecordingRequest.of(kind())
			.description(skill.getName() + " " + level)
			.meta("skill", skill.getName())
			.meta("level", String.valueOf(level)));
	}
}
