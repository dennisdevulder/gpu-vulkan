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
import java.util.Collection;
import net.runelite.api.NPCComposition;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;

/** Records a kill whose drop is worth more than the configured threshold. */
public final class LootTrigger extends EventBusTrigger
{
	@Override
	public RecordingKind kind()
	{
		return RecordingKindRegistry.LOOT;
	}

	@Override
	protected boolean configEnabled()
	{
		return context.config().recordLoot();
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!enabled())
		{
			return;
		}
		Collection<ItemStack> items = event.getItems();
		if (items == null || items.isEmpty())
		{
			return;
		}

		long total = 0;
		for (ItemStack stack : items)
		{
			total += (long) context.itemManager().getItemPrice(stack.getId()) * stack.getQuantity();
		}
		if (total < context.config().recordLootMinimumValue())
		{
			return;
		}

		NPCComposition composition = event.getComposition();
		String source = composition == null || composition.getName() == null
			? "unknown" : composition.getName();
		clip(RecordingRequest.of(kind())
			.description(source)
			.meta("source", source)
			.meta("value", String.valueOf(total)));
	}
}
