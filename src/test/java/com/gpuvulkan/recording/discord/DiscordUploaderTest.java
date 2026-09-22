/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording.discord;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DiscordUploaderTest
{
	private static final String ID_TOKEN = "123456789/abcdefGHIJKL-mnop_qrst";

	@Test
	public void acceptsBothDiscordDomains()
	{
		assertNotNull(DiscordUploader.parseWebhook("https://discord.com/api/webhooks/" + ID_TOKEN));
		assertNotNull(DiscordUploader.parseWebhook("https://discordapp.com/api/webhooks/" + ID_TOKEN));
		assertNotNull(DiscordUploader.parseWebhook("https://ptb.discord.com/api/webhooks/" + ID_TOKEN));
		assertNotNull(DiscordUploader.parseWebhook("  https://discord.com/api/webhooks/" + ID_TOKEN + "  "));
	}

	@Test
	public void acceptsAVersionedApiPath()
	{
		assertNotNull(DiscordUploader.parseWebhook("https://discord.com/api/v10/webhooks/" + ID_TOKEN));
	}

	@Test
	public void rejectsAnyOtherHost()
	{
		// A recording carries a player name; a mistyped host must not become
		// somewhere to send it.
		assertNull(DiscordUploader.parseWebhook("https://evil.example/api/webhooks/" + ID_TOKEN));
		assertNull(DiscordUploader.parseWebhook("https://discord.com.evil.example/api/webhooks/" + ID_TOKEN));
		assertNull(DiscordUploader.parseWebhook("https://notdiscord.com/api/webhooks/" + ID_TOKEN));
	}

	@Test
	public void rejectsPlaintext()
	{
		assertNull(DiscordUploader.parseWebhook("http://discord.com/api/webhooks/" + ID_TOKEN));
	}

	@Test
	public void rejectsSomethingThatIsNotAWebhookEndpoint()
	{
		assertNull(DiscordUploader.parseWebhook("https://discord.com/"));
		assertNull(DiscordUploader.parseWebhook("https://discord.com/api/channels/123/messages"));
		assertNull(DiscordUploader.parseWebhook("https://discord.com/api/webhooks/123"));
		assertNull(DiscordUploader.parseWebhook("https://discord.com/api/webhooks/123/"));
	}

	@Test
	public void rejectsNothingAtAll()
	{
		assertNull(DiscordUploader.parseWebhook(null));
		assertNull(DiscordUploader.parseWebhook(""));
		assertNull(DiscordUploader.parseWebhook("   "));
		assertNull(DiscordUploader.parseWebhook("not a url"));
	}
}
