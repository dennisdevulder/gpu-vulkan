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
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Records a quest completion, from quest points going up. Varps are pushed in
 * bulk on login, so the first observation is a baseline.
 */
public final class QuestTrigger extends EventBusTrigger
{
	/** The "Quest complete!" scroll; the child holds the name. */
	private static final int QUEST_COMPLETE_GROUP = 153;
	private static final int QUEST_COMPLETE_CHILD = 4;

	private int knownQuestPoints = -1;

	@Override
	public RecordingKind kind()
	{
		return RecordingKindRegistry.QUEST;
	}

	@Override
	protected boolean configEnabled()
	{
		return context.config().recordQuests();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (context == null)
		{
			return;
		}
		int points = context.client().getVarpValue(VarPlayerID.QP);
		int previous = knownQuestPoints;
		knownQuestPoints = points;
		if (previous < 0 || points <= previous || !enabled())
		{
			return;
		}
		String name = questName();
		clip(RecordingRequest.of(kind())
			.description(name == null ? "quest complete" : name)
			.meta("questPoints", String.valueOf(points))
			// The completion scroll is the shot worth keeping.
			.postRollSeconds(8));
	}

	private String questName()
	{
		Widget widget = context.client().getWidget(QUEST_COMPLETE_GROUP, QUEST_COMPLETE_CHILD);
		if (widget == null || widget.isHidden() || widget.getText() == null)
		{
			return null;
		}
		String text = Text.removeTags(widget.getText()).trim();
		return text.isEmpty() ? null : text;
	}
}
