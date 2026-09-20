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

import com.gpuvulkan.GpuVulkanPluginConfig;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;

/** What a {@link RecordingTrigger} is given when it binds. */
public final class RecordingContext
{
	private final RecordingService service;
	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final ItemManager itemManager;
	private final GpuVulkanPluginConfig config;

	public RecordingContext(RecordingService service, Client client, ClientThread clientThread,
		EventBus eventBus, ItemManager itemManager, GpuVulkanPluginConfig config)
	{
		this.service = service;
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.itemManager = itemManager;
		this.config = config;
	}

	public RecordingService service()
	{
		return service;
	}

	public Client client()
	{
		return client;
	}

	public ClientThread clientThread()
	{
		return clientThread;
	}

	public EventBus eventBus()
	{
		return eventBus;
	}

	public ItemManager itemManager()
	{
		return itemManager;
	}

	public GpuVulkanPluginConfig config()
	{
		return config;
	}
}
