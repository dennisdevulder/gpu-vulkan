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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingStoreTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private RecordingKindRegistry kinds;
	private RecordingStore store;
	private Path root;

	@Before
	public void setUp()
	{
		kinds = new RecordingKindRegistry();
		root = tmp.getRoot().toPath().resolve("recordings");
		store = new RecordingStore(root, kinds);
	}

	@Test
	public void allocateReservesNothingOnDisk() throws IOException
	{
		RecordingTarget target = store.allocate(RecordingKindRegistry.LOOT, "Zulrah", 1_700_000_000_000L);
		assertTrue(Files.isDirectory(root.resolve("loot")));
		assertFalse(Files.exists(target.file()));
		assertFalse(Files.exists(target.sidecar()));
		assertTrue(target.fileName().endsWith("_zulrah.mp4"));
		assertTrue(target.fileName().startsWith("2023"));
	}

	@Test
	public void allocateAvoidsCollidingWithAnExistingFile() throws IOException
	{
		RecordingTarget first = store.allocate(RecordingKindRegistry.LOOT, "Zulrah", 1_700_000_000_000L);
		Files.write(first.file(), new byte[]{1});
		RecordingTarget second = store.allocate(RecordingKindRegistry.LOOT, "Zulrah", 1_700_000_000_000L);
		assertFalse(first.fileName().equals(second.fileName()));
	}

	@Test
	public void commitIndexesAndSizeComesFromTheFile() throws IOException
	{
		RecordingEntry entry = write(RecordingKindRegistry.DEATH, "Vorkath", 1_000L, new byte[512]);
		assertEquals(512L, entry.sizeBytes());
		assertEquals(1, store.list().size());
		assertTrue(store.find(entry.id()).isPresent());
	}

	@Test
	public void scanRebuildsTheIndexFromSidecars() throws IOException
	{
		write(RecordingKindRegistry.LOOT, "a", 3_000L, new byte[10]);
		write(RecordingKindRegistry.DEATH, "b", 1_000L, new byte[10]);
		write(RecordingKindRegistry.PET, "c", 2_000L, new byte[10]);

		RecordingStore reopened = new RecordingStore(root, kinds);
		reopened.scan();
		List<RecordingEntry> list = reopened.list();
		assertEquals(3, list.size());
		// Newest first.
		assertEquals("a", list.get(0).description());
		assertEquals("c", list.get(1).description());
		assertEquals("b", list.get(2).description());
	}

	@Test
	public void scanDropsSidecarsWhoseVideoWasDeletedByHand() throws IOException
	{
		RecordingEntry entry = write(RecordingKindRegistry.LOOT, "gone", 1_000L, new byte[10]);
		Files.delete(store.videoPath(entry));

		RecordingStore reopened = new RecordingStore(root, kinds);
		reopened.scan();
		assertTrue(reopened.list().isEmpty());
		assertFalse(Files.exists(root.resolve("loot").resolve("19700101_000001_gone.json")));
	}

	@Test
	public void scanSurvivesACorruptSidecar() throws IOException
	{
		write(RecordingKindRegistry.LOOT, "good", 1_000L, new byte[10]);
		Files.write(root.resolve("loot").resolve("junk.json"), "!!!".getBytes(StandardCharsets.UTF_8));

		RecordingStore reopened = new RecordingStore(root, kinds);
		reopened.scan();
		assertEquals(1, reopened.list().size());
	}

	@Test
	public void scanRecoversTheRealSizeOfAFileThatGrewAfterCommit() throws IOException
	{
		RecordingEntry entry = write(RecordingKindRegistry.LOOT, "grown", 1_000L, new byte[10]);
		Files.write(store.videoPath(entry), new byte[4096]);

		RecordingStore reopened = new RecordingStore(root, kinds);
		reopened.scan();
		assertEquals(4096L, reopened.list().get(0).sizeBytes());
	}

	@Test
	public void aScratchThumbnailIsAdoptedByItsRecording() throws IOException
	{
		Path scratch = store.thumbnailScratch(RecordingKindRegistry.LOOT, 1_000L);
		Files.write(scratch, new byte[]{1, 2, 3});
		RecordingTarget target = store.allocate(RecordingKindRegistry.LOOT, "zulrah", 1_000L);

		assertEquals(target.thumbnailName(), store.adoptThumbnail(scratch, target));
		assertFalse("scratch is moved, not copied", Files.exists(scratch));
		assertTrue(Files.isRegularFile(target.thumbnail()));
	}

	@Test
	public void aMissingScratchThumbnailIsNotFatal() throws IOException
	{
		RecordingTarget target = store.allocate(RecordingKindRegistry.LOOT, "zulrah", 1_000L);
		assertEquals(null, store.adoptThumbnail(null, target));
		assertEquals(null, store.adoptThumbnail(
			store.thumbnailScratch(RecordingKindRegistry.LOOT, 999L), target));
	}

	@Test
	public void scanRemovesScratchThumbnailsLeftByAFailedClip() throws IOException
	{
		write(RecordingKindRegistry.LOOT, "kept", 1_000L, new byte[10]);
		Path orphan = store.thumbnailScratch(RecordingKindRegistry.LOOT, 4_000L);
		Files.write(orphan, new byte[]{1});

		RecordingStore reopened = new RecordingStore(root, kinds);
		reopened.scan();

		assertFalse(Files.exists(orphan));
		assertEquals(1, reopened.list().size());
	}

	@Test
	public void deleteRemovesVideoThumbnailAndSidecar() throws IOException
	{
		RecordingTarget target = store.allocate(RecordingKindRegistry.PET, "Herbi", 1_000L);
		Files.write(target.file(), new byte[10]);
		Files.write(target.thumbnail(), new byte[4]);
		store.commit(target.entry().description("Herbi").thumbnailName(target.thumbnailName()).build());

		assertTrue(store.delete(target.id()));
		assertFalse(Files.exists(target.file()));
		assertFalse(Files.exists(target.thumbnail()));
		assertFalse(Files.exists(target.sidecar()));
		assertTrue(store.list().isEmpty());
		assertFalse(store.delete(target.id()));
	}

	@Test
	public void pinningPersistsAcrossAScan() throws IOException
	{
		RecordingEntry entry = write(RecordingKindRegistry.LOOT, "keep", 1_000L, new byte[10]);
		assertTrue(store.setPinned(entry.id(), true).get().pinned());

		RecordingStore reopened = new RecordingStore(root, kinds);
		reopened.scan();
		assertTrue(reopened.list().get(0).pinned());
	}

	@Test
	public void retentionEvictsOldestFirstUntilUnderBudget() throws IOException
	{
		write(RecordingKindRegistry.LOOT, "oldest", 1_000L, new byte[100]);
		write(RecordingKindRegistry.LOOT, "middle", 2_000L, new byte[100]);
		RecordingEntry newest = write(RecordingKindRegistry.LOOT, "newest", 3_000L, new byte[100]);

		List<RecordingEntry> evicted = store.enforceRetention(150, 0);

		assertEquals(2, evicted.size());
		assertEquals("oldest", evicted.get(0).description());
		assertEquals("middle", evicted.get(1).description());
		assertEquals(1, store.list().size());
		assertEquals(newest.id(), store.list().get(0).id());
	}

	@Test
	public void retentionNeverEvictsPinnedRecordings() throws IOException
	{
		RecordingEntry pinned = write(RecordingKindRegistry.LOOT, "pinned", 1_000L, new byte[100]);
		store.setPinned(pinned.id(), true);
		write(RecordingKindRegistry.LOOT, "unpinned", 2_000L, new byte[100]);

		store.enforceRetention(50, 0);

		assertEquals(1, store.list().size());
		assertEquals("pinned", store.list().get(0).description());
		assertTrue(store.totalBytes() > 50);
	}

	@Test
	public void retentionDropsRecordingsPastTheAgeLimit() throws IOException
	{
		long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
		write(RecordingKindRegistry.LOOT, "ancient", old, new byte[10]);
		write(RecordingKindRegistry.LOOT, "fresh", System.currentTimeMillis(), new byte[10]);

		List<RecordingEntry> evicted = store.enforceRetention(0, 7);

		assertEquals(1, evicted.size());
		assertEquals("ancient", evicted.get(0).description());
		assertEquals(1, store.list().size());
	}

	@Test
	public void retentionSparesTheRecordingJustSaved() throws IOException
	{
		// A budget smaller than one recording must not delete the file the
		// save is in the middle of announcing.
		RecordingEntry saved = write(RecordingKindRegistry.LOOT, "just-saved", 3_000L, new byte[100]);
		write(RecordingKindRegistry.LOOT, "older", 1_000L, new byte[100]);

		List<RecordingEntry> evicted = store.enforceRetention(50, 0,
			java.util.Collections.singleton(saved.id()));

		assertEquals(1, evicted.size());
		assertEquals("older", evicted.get(0).description());
		assertEquals(1, store.list().size());
		assertEquals(saved.id(), store.list().get(0).id());
	}

	@Test
	public void retentionSparesTheJustSavedRecordingFromTheAgePassToo() throws IOException
	{
		long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
		RecordingEntry saved = write(RecordingKindRegistry.LOOT, "old-but-saved", old, new byte[10]);

		assertTrue(store.enforceRetention(0, 7, java.util.Collections.singleton(saved.id())).isEmpty());
		assertEquals(1, store.list().size());
	}

	@Test
	public void retentionIsANoOpWhenBothLimitsAreOff() throws IOException
	{
		write(RecordingKindRegistry.LOOT, "a", 1_000L, new byte[100]);
		assertTrue(store.enforceRetention(0, 0).isEmpty());
		assertEquals(1, store.list().size());
	}

	@Test
	public void entriesFromAnUninstalledExtensionStillResolve() throws IOException
	{
		RecordingKind custom = kinds.register("raids_room", "raids_rooms", "Raids room");
		write(custom, "olm", 1_000L, new byte[10]);

		// A fresh registry has never heard of the kind, as if the extension
		// that declared it is gone.
		RecordingStore reopened = new RecordingStore(root, new RecordingKindRegistry());
		reopened.scan();
		assertEquals(1, reopened.list().size());
		assertEquals("raids_room", reopened.list().get(0).kindId());
	}

	private RecordingEntry write(RecordingKind kind, String description, long at, byte[] bytes) throws IOException
	{
		RecordingTarget target = store.allocate(kind, description, at);
		Files.write(target.file(), bytes);
		return store.commit(target.entry().description(description).build());
	}
}
