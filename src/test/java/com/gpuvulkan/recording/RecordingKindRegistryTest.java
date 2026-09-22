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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RecordingKindRegistryTest
{
	@Test
	public void builtInsAreRegistered()
	{
		RecordingKindRegistry registry = new RecordingKindRegistry();
		assertSame(RecordingKindRegistry.LOOT, registry.find("loot"));
		assertSame(RecordingKindRegistry.DEATH, registry.find("death"));
		assertTrue(registry.all().size() >= 9);
	}

	@Test
	public void registeringIsIdempotentAndFirstWriterWins()
	{
		RecordingKindRegistry registry = new RecordingKindRegistry();
		RecordingKind first = registry.register("fight_cave", "fight_cave", "Fight Cave");
		RecordingKind second = registry.register("fight_cave", "elsewhere", "Renamed");
		assertSame(first, second);
		assertEquals("fight_cave", registry.find("fight_cave").folder());
	}

	@Test
	public void unknownIdResolvesInsteadOfFailing()
	{
		RecordingKindRegistry registry = new RecordingKindRegistry();
		assertNull(registry.find("gone"));
		RecordingKind resolved = registry.resolve("gone");
		assertNotNull(resolved);
		assertEquals("gone", resolved.id());
		// Resolving must not register: a removed extension's kind should not
		// reappear in the filter list.
		assertNull(registry.find("gone"));
	}

	@Test
	public void folderNamesCannotEscapeTheRoot()
	{
		RecordingKind kind = new RecordingKind("evil", "../../etc", "Evil");
		assertEquals("etc", kind.folder());

		RecordingKind slashes = new RecordingKind("evil2", "a/b/c", "Evil2");
		assertTrue(slashes.folder().indexOf('/') < 0);
	}

	@Test
	public void idsAreCaseInsensitive()
	{
		RecordingKindRegistry registry = new RecordingKindRegistry();
		registry.register("Boss_Wave", "boss_waves", "Boss wave");
		assertNotNull(registry.find("BOSS_WAVE"));
	}
}
