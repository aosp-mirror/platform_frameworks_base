/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.broadcastradio.hal2;

import static org.junit.Assert.*;

import android.hardware.broadcastradio.V2_0.ProgramIdentifier;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.UniqueProgramIdentifier;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for ProgramInfoCache
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class ProgramInfoCacheTest extends ExtendedRadioMockitoTestCase {
    private static final int TEST_QUALITY = 1;

    private static final ProgramSelector.Identifier TEST_AM_FM_ID = new ProgramSelector.Identifier(
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 88500);
    private static final ProgramSelector TEST_AM_FM_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM, TEST_AM_FM_ID);
    private static final RadioManager.ProgramInfo TEST_AM_FM_INFO = TestUtils.makeProgramInfo(
            TEST_AM_FM_SELECTOR, TEST_QUALITY);

    private static final ProgramSelector.Identifier TEST_RDS_ID = new ProgramSelector.Identifier(
            ProgramSelector.IDENTIFIER_TYPE_RDS_PI, /* value= */ 15019);
    private static final ProgramSelector TEST_RDS_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM, TEST_RDS_ID);
    private static final RadioManager.ProgramInfo TEST_RDS_INFO = TestUtils.makeProgramInfo(
            TEST_RDS_SELECTOR, TEST_QUALITY);

    private static final ProgramSelector TEST_HD_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM_HD, new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT,
                    /* value= */ 0x17C14100000001L));

    private static final ProgramSelector.Identifier TEST_DAB_SID_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier TEST_DAB_ENSEMBLE_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1001);
    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220_352);
    private static final ProgramSelector TEST_DAB_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_SID_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID});
    private static final UniqueProgramIdentifier TEST_DAB_UNIQUE_ID =
            new UniqueProgramIdentifier(TEST_DAB_SELECTOR);
    private static final RadioManager.ProgramInfo TEST_DAB_INFO = TestUtils.makeProgramInfo(
            TEST_DAB_SELECTOR, TEST_QUALITY);
    private static final ProgramSelector.Identifier TEST_VENDOR_ID = new ProgramSelector.Identifier(
            ProgramSelector.IDENTIFIER_TYPE_VENDOR_START, /* value= */ 9001);
    private static final ProgramSelector TEST_VENDOR_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_VENDOR_START, TEST_VENDOR_ID);
    private static final UniqueProgramIdentifier TEST_VENDOR_UNIQUE_ID =
            new UniqueProgramIdentifier(TEST_VENDOR_SELECTOR);
    private static final RadioManager.ProgramInfo TEST_VENDOR_INFO = TestUtils.makeProgramInfo(
            TEST_VENDOR_SELECTOR, TEST_QUALITY);

    private static final ProgramInfoCache FULL_PROGRAM_INFO_CACHE = new ProgramInfoCache(
            /* filter= */ null, /* complete= */ true, TEST_AM_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO,
            TEST_VENDOR_INFO);

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void testUpdateFromHal() {
        // First test updating an incomplete cache with a purging, complete chunk.
        ProgramInfoCache cache = new ProgramInfoCache(null, false, TEST_AM_FM_INFO);
        ProgramListChunk chunk = new ProgramListChunk();
        chunk.purge = true;
        chunk.complete = true;
        chunk.modified.add(TestUtils.programInfoToHal(TEST_RDS_INFO));
        chunk.modified.add(TestUtils.programInfoToHal(TEST_DAB_INFO));
        cache.updateFromHalProgramListChunk(chunk);
        expect.withMessage("Program info cache updated with a purging complete chunk")
                .that(cache.toProgramInfoList()).containsExactly(TEST_RDS_INFO, TEST_DAB_INFO);
        assertTrue(cache.isComplete());

        // Then test a non-purging, incomplete chunk.
        chunk.purge = false;
        chunk.complete = false;
        chunk.modified.clear();
        RadioManager.ProgramInfo updatedRdsInfo = TestUtils.makeProgramInfo(TEST_RDS_SELECTOR, 1);
        chunk.modified.add(TestUtils.programInfoToHal(updatedRdsInfo));
        chunk.modified.add(TestUtils.programInfoToHal(TEST_VENDOR_INFO));
        chunk.removed.add(Convert.programIdentifierToHal(TEST_DAB_SID_ID));
        cache.updateFromHalProgramListChunk(chunk);
        expect.withMessage("Program info cache updated with non-puring incomplete chunk")
                .that(cache.toProgramInfoList()).containsExactly(updatedRdsInfo, TEST_VENDOR_INFO);
        assertFalse(cache.isComplete());
    }

    @Test
    public void testNullFilter() {
        ProgramInfoCache cache = new ProgramInfoCache(null, true);
        cache.filterAndUpdateFrom(FULL_PROGRAM_INFO_CACHE, false);
        expect.withMessage("Program info cache with null filter")
                .that(cache.toProgramInfoList()).containsExactly(TEST_AM_FM_INFO, TEST_RDS_INFO,
                        TEST_DAB_INFO, TEST_VENDOR_INFO);
    }

    @Test
    public void testEmptyFilter() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                  new HashSet<ProgramSelector.Identifier>(), true, false));
        cache.filterAndUpdateFrom(FULL_PROGRAM_INFO_CACHE, false);
        expect.withMessage("Program info cache with empty filter")
                .that(cache.toProgramInfoList()).containsExactly(TEST_AM_FM_INFO, TEST_RDS_INFO,
                        TEST_DAB_INFO, TEST_VENDOR_INFO);
    }

    @Test
    public void testFilterByType() {
        HashSet<Integer> filterTypes = new HashSet<>();
        filterTypes.add(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY);
        filterTypes.add(ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT);
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(filterTypes,
                  new HashSet<ProgramSelector.Identifier>(), true, false));
        cache.filterAndUpdateFrom(FULL_PROGRAM_INFO_CACHE, false);
        expect.withMessage("Program info cache with type filter")
                .that(cache.toProgramInfoList()).containsExactly(TEST_AM_FM_INFO, TEST_DAB_INFO);
    }

    @Test
    public void testFilterByIdentifier() {
        HashSet<ProgramSelector.Identifier> filterIds = new HashSet<>();
        filterIds.add(TEST_RDS_ID);
        filterIds.add(TEST_VENDOR_ID);
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                  filterIds, true, false));
        cache.filterAndUpdateFrom(FULL_PROGRAM_INFO_CACHE, false);
        expect.withMessage("Program info cache with identifier filter")
                .that(cache.toProgramInfoList()).containsExactly(TEST_RDS_INFO, TEST_VENDOR_INFO);
    }

    @Test
    public void testFilterExcludeCategories() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                  new HashSet<ProgramSelector.Identifier>(), false, false));
        cache.filterAndUpdateFrom(FULL_PROGRAM_INFO_CACHE, false);
        expect.withMessage("Program info cache with filter excluding categories")
                .that(cache.toProgramInfoList()).containsExactly(TEST_AM_FM_INFO, TEST_RDS_INFO,
                        TEST_DAB_INFO);
    }

    @Test
    public void testPurgeUpdateChunks() {
        ProgramInfoCache cache = new ProgramInfoCache(null, false, TEST_AM_FM_INFO);
        List<ProgramList.Chunk> chunks =
                cache.filterAndUpdateFromInternal(FULL_PROGRAM_INFO_CACHE, true, 3, 3);
        assertEquals(2, chunks.size());
        verifyChunkListFlags(chunks, true, true);
        verifyChunkListModified(chunks, 3, TEST_AM_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO,
                TEST_VENDOR_INFO);
        verifyChunkListRemoved(chunks, 0);
    }

    @Test
    public void testDeltaUpdateChunksModificationsIncluded() {
        // Create a cache with a filter that allows modifications, and set its contents to
        // TEST_AM_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO, and TEST_VENDOR_INFO.
        ProgramInfoCache cache = new ProgramInfoCache(null, true, TEST_AM_FM_INFO, TEST_RDS_INFO,
                TEST_DAB_INFO, TEST_VENDOR_INFO);

        // Create a HAL cache that:
        // - Is complete.
        // - Retains TEST_AM_FM_INFO.
        // - Replaces TEST_RDS_INFO with updatedRdsInfo.
        // - Drops TEST_DAB_INFO and TEST_VENDOR_INFO.
        // - Introduces a new HD info.
        RadioManager.ProgramInfo updatedRdsInfo = TestUtils.makeProgramInfo(TEST_RDS_SELECTOR,
                TEST_QUALITY + 1);
        RadioManager.ProgramInfo newHdInfo = TestUtils.makeProgramInfo(TEST_HD_SELECTOR,
                TEST_QUALITY);
        ProgramInfoCache halCache = new ProgramInfoCache(null, true, TEST_AM_FM_INFO,
                updatedRdsInfo, newHdInfo);

        // Update the cache and verify:
        // - The final chunk's complete flag is set.
        // - TEST_AM_FM_INFO is retained and not reported in the chunks.
        // - updatedRdsInfo should appear as an update to TEST_RDS_INFO.
        // - newHdInfo should appear as a new entry.
        // - TEST_DAB_INFO and TEST_VENDOR_INFO should be reported as removed.
        List<ProgramList.Chunk> chunks = cache.filterAndUpdateFromInternal(halCache, false, 5, 1);
        expect.withMessage("Program info cache with modification included")
                .that(cache.toProgramInfoList()).containsExactly(TEST_AM_FM_INFO, updatedRdsInfo,
                        newHdInfo);
        assertEquals(2, chunks.size());
        verifyChunkListFlags(chunks, false, true);
        verifyChunkListModified(chunks, 5, updatedRdsInfo, newHdInfo);
        verifyChunkListRemoved(chunks, 1, TEST_DAB_UNIQUE_ID, TEST_VENDOR_UNIQUE_ID);
    }

    @Test
    public void testDeltaUpdateChunksModificationsExcluded() {
        // Create a cache with a filter that excludes modifications, and set its contents to
        // TEST_AM_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO, and TEST_VENDOR_INFO.
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(), true, true), true,
                TEST_AM_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO, TEST_VENDOR_INFO);

        // Create a HAL cache that:
        // - Is incomplete.
        // - Retains TEST_AM_FM_INFO.
        // - Replaces TEST_RDS_INFO with updatedRdsInfo.
        // - Drops TEST_DAB_INFO and TEST_VENDOR_INFO.
        // - Introduces a new HD info.
        RadioManager.ProgramInfo updatedRdsInfo = TestUtils.makeProgramInfo(TEST_RDS_SELECTOR, 1);
        RadioManager.ProgramInfo newHdInfo = TestUtils.makeProgramInfo(TEST_HD_SELECTOR,
                TEST_QUALITY);
        ProgramInfoCache halCache = new ProgramInfoCache(null, false, TEST_AM_FM_INFO,
                updatedRdsInfo, newHdInfo);

        // Update the cache and verify:
        // - All complete flags are false.
        // - TEST_AM_FM_INFO and TEST_RDS_INFO are retained and not reported in the chunks.
        // - newHdInfo should appear as a new entry.
        // - TEST_DAB_INFO and TEST_VENDOR_INFO should be reported as removed.
        List<ProgramList.Chunk> chunks = cache.filterAndUpdateFromInternal(halCache, false, 5, 1);
        expect.withMessage("Program info cache with modification excluded")
                .that(cache.toProgramInfoList()).containsExactly(TEST_AM_FM_INFO, TEST_RDS_INFO,
                        newHdInfo);
        assertEquals(2, chunks.size());
        verifyChunkListFlags(chunks, false, false);
        verifyChunkListModified(chunks, 5, newHdInfo);
        verifyChunkListRemoved(chunks, 1, TEST_DAB_UNIQUE_ID, TEST_VENDOR_UNIQUE_ID);
    }

    @Test
    public void filterAndApplyChunkInternal_withInvalidIdentifier() {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null, /* complete= */ false,
                TEST_AM_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO, TEST_VENDOR_INFO);
        ArrayList<ProgramIdentifier> halRemoved = new ArrayList<>();
        halRemoved.add(new ProgramIdentifier());
        ProgramListChunk halChunk = new ProgramListChunk();
        halChunk.complete = true;
        halChunk.purge = false;
        halChunk.modified = new ArrayList<>();
        halChunk.removed = halRemoved;

        List<ProgramList.Chunk> programListChunks = cache.filterAndApplyChunkInternal(halChunk,
                /* maxNumModifiedPerChunk= */ 1, /* maxNumRemovedPerChunk= */ 1);

        expect.withMessage("Program list chunk applied with invalid identifier")
                .that(programListChunks).isEmpty();
    }

    // Verifies that:
    // - The first chunk's purge flag matches expectPurge.
    // - The last chunk's complete flag matches expectComplete.
    // - All other flags are false.
    private static void verifyChunkListFlags(List<ProgramList.Chunk> chunks, boolean expectPurge,
            boolean expectComplete) {
        if (chunks.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            ProgramList.Chunk chunk = chunks.get(i);
            assertEquals(i == 0 && expectPurge, chunk.isPurge());
            assertEquals(i == chunks.size() - 1 && expectComplete, chunk.isComplete());
        }
    }

    // Verifies that:
    // - Each chunk's modified array has at most maxModifiedPerChunk elements.
    // - Each chunk's modified array has a similar number of elements.
    // - Each element of expectedProgramInfos appears in a chunk.
    private static void verifyChunkListModified(List<ProgramList.Chunk> chunks,
            int maxModifiedPerChunk, RadioManager.ProgramInfo... expectedProgramInfos) {
        if (chunks.isEmpty()) {
            assertEquals(0, expectedProgramInfos.length);
            return;
        }
        HashSet<RadioManager.ProgramInfo> expectedSet = new HashSet<>();
        for (RadioManager.ProgramInfo programInfo : expectedProgramInfos) {
            expectedSet.add(programInfo);
        }

        HashSet<RadioManager.ProgramInfo> actualSet = new HashSet<>();
        int chunk0NumModified = chunks.get(0).getModified().size();
        for (ProgramList.Chunk chunk : chunks) {
            Set<RadioManager.ProgramInfo> chunkModified = chunk.getModified();
            assertTrue(chunkModified.size() <= maxModifiedPerChunk);
            assertTrue(Math.abs(chunkModified.size() - chunk0NumModified) <= 1);
            actualSet.addAll(chunkModified);
        }
        assertEquals(expectedSet, actualSet);
    }

    // Verifies that:
    // - Each chunk's removed array has at most maxRemovedPerChunk elements.
    // - Each chunk's removed array has a similar number of elements.
    // - Each element of expectedIdentifiers appears in a chunk.
    private static void verifyChunkListRemoved(List<ProgramList.Chunk> chunks,
            int maxRemovedPerChunk,
            UniqueProgramIdentifier... expectedIdentifiers) {
        if (chunks.isEmpty()) {
            assertEquals(0, expectedIdentifiers.length);
            return;
        }
        HashSet<UniqueProgramIdentifier> expectedSet = new HashSet<>();
        for (UniqueProgramIdentifier identifier : expectedIdentifiers) {
            expectedSet.add(identifier);
        }

        HashSet<UniqueProgramIdentifier> actualSet = new HashSet<>();
        int chunk0NumRemoved = chunks.get(0).getRemoved().size();
        for (ProgramList.Chunk chunk : chunks) {
            Set<UniqueProgramIdentifier> chunkRemoved = chunk.getRemoved();
            assertTrue(chunkRemoved.size() <= maxRemovedPerChunk);
            assertTrue(Math.abs(chunkRemoved.size() - chunk0NumRemoved) <= 1);
            actualSet.addAll(chunkRemoved);
        }
        assertEquals(expectedSet, actualSet);
    }
}
