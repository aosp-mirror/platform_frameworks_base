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

import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.test.suitebuilder.annotation.MediumTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for ProgramInfoCache
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class ProgramInfoCacheTest {
    private static final String TAG = "BroadcastRadioTests.ProgramInfoCache";

    private final ProgramSelector.Identifier mAmFmIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, 88500);
    private final RadioManager.ProgramInfo mAmFmInfo = makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_FM, mAmFmIdentifier, 0);

    private final ProgramSelector.Identifier mRdsIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_RDS_PI, 15019);
    private final RadioManager.ProgramInfo mRdsInfo = makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_FM, mRdsIdentifier, 0);

    private final ProgramSelector.Identifier mDabEnsembleIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE, 1337);
    private final RadioManager.ProgramInfo mDabEnsembleInfo = makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_DAB, mDabEnsembleIdentifier, 0);

    private final ProgramSelector.Identifier mVendorCustomIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_VENDOR_START, 9001);
    private final RadioManager.ProgramInfo mVendorCustomInfo = makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_VENDOR_START, mVendorCustomIdentifier, 0);

    // HAL-side ProgramInfoCache containing all of the above ProgramInfos.
    private final ProgramInfoCache mAllProgramInfos = new ProgramInfoCache(null, mAmFmInfo,
            mRdsInfo, mDabEnsembleInfo, mVendorCustomInfo);

    @Test
    public void testUpdateFromHal() {
        // First test a purging chunk.
        ProgramInfoCache cache = new ProgramInfoCache(null, mAmFmInfo);
        ProgramListChunk chunk = new ProgramListChunk();
        chunk.purge = true;
        chunk.modified.add(programInfoToHal(mRdsInfo));
        chunk.modified.add(programInfoToHal(mDabEnsembleInfo));
        cache.updateFromHalProgramListChunk(chunk);
        chunk.complete = true;
        assertTrue(cache.programInfosAreExactly(mRdsInfo, mDabEnsembleInfo));

        // Then test a non-purging chunk.
        chunk.purge = false;
        chunk.modified.clear();
        RadioManager.ProgramInfo updatedRdsInfo = makeProgramInfo(ProgramSelector.PROGRAM_TYPE_FM,
                mRdsIdentifier, 1);
        chunk.modified.add(programInfoToHal(updatedRdsInfo));
        chunk.modified.add(programInfoToHal(mVendorCustomInfo));
        chunk.removed.add(Convert.programIdentifierToHal(mDabEnsembleIdentifier));
        cache.updateFromHalProgramListChunk(chunk);
        assertTrue(cache.programInfosAreExactly(updatedRdsInfo, mVendorCustomInfo));
    }

    @Test
    public void testNullFilter() {
        ProgramInfoCache cache = new ProgramInfoCache(null);
        cache.filterAndUpdateFrom(mAllProgramInfos, false);
        assertTrue(cache.programInfosAreExactly(mAmFmInfo, mRdsInfo, mDabEnsembleInfo,
                  mVendorCustomInfo));
    }

    @Test
    public void testEmptyFilter() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                  new HashSet<ProgramSelector.Identifier>(), true, false));
        cache.filterAndUpdateFrom(mAllProgramInfos, false);
        assertTrue(cache.programInfosAreExactly(mAmFmInfo, mRdsInfo, mDabEnsembleInfo,
                  mVendorCustomInfo));
    }

    @Test
    public void testFilterByType() {
        HashSet<Integer> filterTypes = new HashSet<>();
        filterTypes.add(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY);
        filterTypes.add(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE);
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(filterTypes,
                  new HashSet<ProgramSelector.Identifier>(), true, false));
        cache.filterAndUpdateFrom(mAllProgramInfos, false);
        assertTrue(cache.programInfosAreExactly(mAmFmInfo, mDabEnsembleInfo));
    }

    @Test
    public void testFilterByIdentifier() {
        HashSet<ProgramSelector.Identifier> filterIds = new HashSet<>();
        filterIds.add(mRdsIdentifier);
        filterIds.add(mVendorCustomIdentifier);
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                  filterIds, true, false));
        cache.filterAndUpdateFrom(mAllProgramInfos, false);
        assertTrue(cache.programInfosAreExactly(mRdsInfo, mVendorCustomInfo));
    }

    @Test
    public void testFilterExcludeCategories() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                  new HashSet<ProgramSelector.Identifier>(), false, false));
        cache.filterAndUpdateFrom(mAllProgramInfos, false);
        assertTrue(cache.programInfosAreExactly(mAmFmInfo, mRdsInfo));
    }

    @Test
    public void testPurgeUpdateChunks() {
        ProgramInfoCache cache = new ProgramInfoCache(null, mAmFmInfo);
        List<ProgramList.Chunk> chunks =
                cache.filterAndUpdateFromInternal(mAllProgramInfos, true, 3, 3);
        assertEquals(2, chunks.size());
        verifyChunkListFlags(chunks, true);
        verifyChunkListModified(chunks, 3, mAmFmInfo, mRdsInfo, mDabEnsembleInfo,
                mVendorCustomInfo);
        verifyChunkListRemoved(chunks, 0);
    }

    @Test
    public void testDeltaUpdateChunksModificationsIncluded() {
        // Create a cache with a filter that allows modifications, and set its contents to
        // mAmFmInfo, mRdsInfo, mDabEnsembleInfo, and mVendorCustomInfo.
        ProgramInfoCache cache = new ProgramInfoCache(null, mAmFmInfo, mRdsInfo, mDabEnsembleInfo,
                mVendorCustomInfo);

        // Create a HAL cache that:
        // - Retains mAmFmInfo.
        // - Replaces mRdsInfo with updatedRdsInfo.
        // - Drops mDabEnsembleInfo and mVendorCustomInfo.
        // - Introduces a new SXM info.
        RadioManager.ProgramInfo updatedRdsInfo = makeProgramInfo(ProgramSelector.PROGRAM_TYPE_FM,
                mRdsIdentifier, 1);
        RadioManager.ProgramInfo newSxmInfo = makeProgramInfo(ProgramSelector.PROGRAM_TYPE_SXM,
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_SXM_CHANNEL, 12345),
                0);
        ProgramInfoCache halCache = new ProgramInfoCache(null, mAmFmInfo, updatedRdsInfo,
                newSxmInfo);

        // Update the cache and verify:
        // - mAmFmInfo is retained and not reported in the chunks.
        // - updatedRdsInfo should appear as an update to mRdsInfo.
        // - newSxmInfo should appear as a new entry.
        // - mDabEnsembleInfo and mVendorCustomInfo should be reported as removed.
        List<ProgramList.Chunk> chunks = cache.filterAndUpdateFromInternal(halCache, false, 5, 1);
        assertTrue(cache.programInfosAreExactly(mAmFmInfo, updatedRdsInfo, newSxmInfo));
        assertEquals(2, chunks.size());
        verifyChunkListFlags(chunks, false);
        verifyChunkListModified(chunks, 5, updatedRdsInfo, newSxmInfo);
        verifyChunkListRemoved(chunks, 1, mDabEnsembleIdentifier, mVendorCustomIdentifier);
    }

    @Test
    public void testDeltaUpdateChunksModificationsExcluded() {
        // Create a cache with a filter that excludes modifications, and set its contents to
        // mAmFmInfo, mRdsInfo, mDabEnsembleInfo, and mVendorCustomInfo.
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(), true, true), mAmFmInfo, mRdsInfo,
                mDabEnsembleInfo, mVendorCustomInfo);

        // Create a HAL cache that:
        // - Retains mAmFmInfo.
        // - Replaces mRdsInfo with updatedRdsInfo.
        // - Drops mDabEnsembleInfo and mVendorCustomInfo.
        // - Introduces a new SXM info.
        RadioManager.ProgramInfo updatedRdsInfo = makeProgramInfo(ProgramSelector.PROGRAM_TYPE_FM,
                mRdsIdentifier, 1);
        RadioManager.ProgramInfo newSxmInfo = makeProgramInfo(ProgramSelector.PROGRAM_TYPE_SXM,
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_SXM_CHANNEL, 12345),
                0);
        ProgramInfoCache halCache = new ProgramInfoCache(null, mAmFmInfo, updatedRdsInfo,
                newSxmInfo);

        // Update the cache and verify:
        // - mAmFmInfo and mRdsInfo are retained and not reported in the chunks.
        // - newSxmInfo should appear as a new entry.
        // - mDabEnsembleInfo and mVendorCustomInfo should be reported as removed.
        List<ProgramList.Chunk> chunks = cache.filterAndUpdateFromInternal(halCache, false, 5, 1);
        assertTrue(cache.programInfosAreExactly(mAmFmInfo, mRdsInfo, newSxmInfo));
        assertEquals(2, chunks.size());
        verifyChunkListFlags(chunks, false);
        verifyChunkListModified(chunks, 5, newSxmInfo);
        verifyChunkListRemoved(chunks, 1, mDabEnsembleIdentifier, mVendorCustomIdentifier);
    }

    private static RadioManager.ProgramInfo makeProgramInfo(int programType,
            ProgramSelector.Identifier identifier, int signalQuality) {
        // Note: If you set new fields, check if programInfoToHal() needs to be updated as well.
        return new RadioManager.ProgramInfo(new ProgramSelector(programType, identifier, null,
                null), null, null, null, 0, signalQuality, new RadioMetadata.Builder().build(),
                new HashMap<String, String>());
    }

    private static ProgramInfo programInfoToHal(RadioManager.ProgramInfo info) {
        // Note that because Convert does not by design provide functions for all conversions, this
        // function only copies fields that are set by makeProgramInfo().
        ProgramInfo hwInfo = new ProgramInfo();
        hwInfo.selector = Convert.programSelectorToHal(info.getSelector());
        hwInfo.signalQuality = info.getSignalStrength();
        return hwInfo;
    }

    // Verifies that:
    // - The first chunk's purge flag matches expectPurge.
    // - The last chunk's complete flag is set.
    // - All other flags are false.
    private static void verifyChunkListFlags(List<ProgramList.Chunk> chunks, boolean expectPurge) {
        if (chunks.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            ProgramList.Chunk chunk = chunks.get(i);
            assertEquals(i == 0 && expectPurge, chunk.isPurge());
            assertEquals(i == chunks.size() - 1, chunk.isComplete());
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
            int maxRemovedPerChunk, ProgramSelector.Identifier... expectedIdentifiers) {
        if (chunks.isEmpty()) {
            assertEquals(0, expectedIdentifiers.length);
            return;
        }
        HashSet<ProgramSelector.Identifier> expectedSet = new HashSet<>();
        for (ProgramSelector.Identifier identifier : expectedIdentifiers) {
            expectedSet.add(identifier);
        }

        HashSet<ProgramSelector.Identifier> actualSet = new HashSet<>();
        int chunk0NumRemoved = chunks.get(0).getRemoved().size();
        for (ProgramList.Chunk chunk : chunks) {
            Set<ProgramSelector.Identifier> chunkRemoved = chunk.getRemoved();
            assertTrue(chunkRemoved.size() <= maxRemovedPerChunk);
            assertTrue(Math.abs(chunkRemoved.size() - chunk0NumRemoved) <= 1);
            actualSet.addAll(chunkRemoved);
        }
        assertEquals(expectedSet, actualSet);
    }
}
