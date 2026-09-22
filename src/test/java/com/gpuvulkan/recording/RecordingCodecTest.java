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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecordingCodecTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void roundTripsEveryField() throws IOException
	{
		RecordingEntry entry = RecordingEntry.builder()
			.id("abc")
			.kind(RecordingKindRegistry.LOOT)
			.description("Zulrah")
			.fileName("20260920_120000_zulrah.mp4")
			.thumbnailName("20260920_120000_zulrah.png")
			.triggeredAt(1_700_000_000_000L)
			.durationMs(14_000L)
			.sizeBytes(1234567L)
			.dimensions(1920, 1080)
			.fps(60)
			.session(true)
			.pinned(true)
			.continuationOf("previous-id")
			.meta("player", "Zezima")
			.meta("world", "302")
			.build();

		Path sidecar = tmp.getRoot().toPath().resolve("clip.json");
		RecordingCodec.write(sidecar, entry);
		RecordingEntry back = RecordingCodec.read(sidecar);

		assertEquals(entry.id(), back.id());
		assertEquals(entry.kindId(), back.kindId());
		assertEquals(entry.description(), back.description());
		assertEquals(entry.fileName(), back.fileName());
		assertEquals(entry.thumbnailName(), back.thumbnailName());
		assertEquals(entry.triggeredAt(), back.triggeredAt());
		assertEquals(entry.durationMs(), back.durationMs());
		assertEquals(entry.sizeBytes(), back.sizeBytes());
		assertEquals(entry.width(), back.width());
		assertEquals(entry.height(), back.height());
		assertEquals(entry.fps(), back.fps());
		assertEquals(entry.session(), back.session());
		assertEquals(entry.pinned(), back.pinned());
		assertEquals(entry.continuationOf(), back.continuationOf());
		assertEquals(entry.metadata(), back.metadata());
		assertEquals(RecordingEntry.SCHEMA_VERSION, back.schemaVersion());
	}

	@Test
	public void unreadableSidecarsReturnNullRatherThanThrowing() throws IOException
	{
		Path broken = tmp.getRoot().toPath().resolve("broken.json");
		Files.write(broken, "{ not json".getBytes(StandardCharsets.UTF_8));
		assertNull(RecordingCodec.read(broken));

		Path empty = tmp.getRoot().toPath().resolve("empty.json");
		Files.write(empty, "{}".getBytes(StandardCharsets.UTF_8));
		assertNull(RecordingCodec.read(empty));

		assertNull(RecordingCodec.read(tmp.getRoot().toPath().resolve("missing.json")));
	}

	@Test
	public void writeLeavesNoTempFileBehind() throws IOException
	{
		Path sidecar = tmp.getRoot().toPath().resolve("clip.json");
		RecordingCodec.write(sidecar, minimalEntry());
		assertTrue(Files.exists(sidecar));
		assertTrue(!Files.exists(sidecar.resolveSibling("clip.json.tmp")));
	}

	@Test
	public void unknownFieldsFromANewerSchemaAreIgnored() throws IOException
	{
		Path sidecar = tmp.getRoot().toPath().resolve("future.json");
		Files.write(sidecar, ("{\"id\":\"x\",\"fileName\":\"x.mp4\",\"kindId\":\"loot\","
			+ "\"somethingNew\":42}").getBytes(StandardCharsets.UTF_8));
		RecordingEntry back = RecordingCodec.read(sidecar);
		assertEquals("x", back.id());
		assertEquals("loot", back.kindId());
	}

	private static RecordingEntry minimalEntry()
	{
		return RecordingEntry.builder().id("x").fileName("x.mp4").build();
	}
}
