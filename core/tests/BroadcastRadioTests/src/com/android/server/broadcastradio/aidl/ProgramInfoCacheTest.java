/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.broadcastradio.aidl;

import android.hardware.broadcastradio.ProgramIdentifier;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.UniqueProgramIdentifier;
import android.os.RemoteException;
import android.util.ArraySet;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for AIDL ProgramInfoCache
 */
@RunWith(MockitoJUnitRunner.class)
public class ProgramInfoCacheTest {

    private static final int TEST_SIGNAL_QUALITY = 90;

    private static final int TEST_MAX_NUM_MODIFIED_PER_CHUNK = 2;
    private static final int TEST_MAX_NUM_REMOVED_PER_CHUNK = 2;

    private static final ProgramSelector.Identifier TEST_FM_FREQUENCY_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                    /* value= */ 88_500);
    private static final RadioManager.ProgramInfo TEST_FM_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                    TEST_FM_FREQUENCY_ID), TEST_FM_FREQUENCY_ID, TEST_FM_FREQUENCY_ID,
            TEST_SIGNAL_QUALITY);
    private static final RadioManager.ProgramInfo TEST_FM_INFO_MODIFIED =
            AidlTestUtils.makeProgramInfo(AidlTestUtils.makeProgramSelector(
                    ProgramSelector.PROGRAM_TYPE_FM, TEST_FM_FREQUENCY_ID), TEST_FM_FREQUENCY_ID,
                    TEST_FM_FREQUENCY_ID, /* signalQuality= */ 99);

    private static final ProgramSelector.Identifier TEST_AM_FREQUENCY_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                    /* value= */ 1_700);
    private static final UniqueProgramIdentifier TEST_AM_UNIQUE_ID = new UniqueProgramIdentifier(
            TEST_AM_FREQUENCY_ID);
    private static final RadioManager.ProgramInfo TEST_AM_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                    TEST_AM_FREQUENCY_ID), TEST_AM_FREQUENCY_ID, TEST_AM_FREQUENCY_ID,
            TEST_SIGNAL_QUALITY);

    private static final ProgramSelector.Identifier TEST_RDS_PI_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_RDS_PI,
                    /* value= */ 15_019);
    private static final UniqueProgramIdentifier TEST_RDS_PI_UNIQUE_ID =
            new UniqueProgramIdentifier(TEST_RDS_PI_ID);
    private static final RadioManager.ProgramInfo TEST_RDS_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM, TEST_RDS_PI_ID),
            TEST_RDS_PI_ID, new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 89_500),
            TEST_SIGNAL_QUALITY);

    private static final ProgramSelector.Identifier TEST_DAB_DMB_SID_EXT_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier TEST_DAB_ENSEMBLE_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1001);
    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220_352);
    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID_ALTERNATIVE =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220_064);
    private static final ProgramSelector TEST_DAB_SELECTOR = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_DMB_SID_EXT_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID},
            /* vendorIds= */ null);
    private static final ProgramSelector TEST_DAB_SELECTOR_ALTERNATIVE = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_DMB_SID_EXT_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID_ALTERNATIVE,
                    TEST_DAB_ENSEMBLE_ID}, /* vendorIds= */ null);
    private static final UniqueProgramIdentifier TEST_DAB_UNIQUE_ID = new UniqueProgramIdentifier(
            TEST_DAB_SELECTOR);
    private static final UniqueProgramIdentifier TEST_DAB_UNIQUE_ID_ALTERNATIVE =
            new UniqueProgramIdentifier(TEST_DAB_SELECTOR_ALTERNATIVE);
    private static final RadioManager.ProgramInfo TEST_DAB_INFO =
            AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR, TEST_DAB_DMB_SID_EXT_ID,
                    TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);
    private static final RadioManager.ProgramInfo TEST_DAB_INFO_ALTERNATIVE =
            AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR_ALTERNATIVE, TEST_DAB_DMB_SID_EXT_ID,
                    TEST_DAB_FREQUENCY_ID_ALTERNATIVE, TEST_SIGNAL_QUALITY);

    private static final ProgramSelector.Identifier TEST_VENDOR_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_VENDOR_START,
                    /* value= */ 9_001);
    private static final RadioManager.ProgramInfo TEST_VENDOR_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_VENDOR_START,
                    TEST_VENDOR_ID), TEST_VENDOR_ID, TEST_VENDOR_ID, TEST_SIGNAL_QUALITY);

    private static final ProgramInfoCache FULL_PROGRAM_INFO_CACHE = new ProgramInfoCache(
            /* filter= */ null, /* complete= */ true, TEST_FM_INFO, TEST_AM_INFO, TEST_RDS_INFO,
            TEST_DAB_INFO, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void isComplete_forCompleteProgramInfoCache_returnsTrue() {
        expect.withMessage("Complete program info cache")
                .that(FULL_PROGRAM_INFO_CACHE.isComplete()).isTrue();
    }

    @Test
    public void isComplete_forIncompleteProgramInfoCache_returnsFalse() {
        ProgramInfoCache programInfoCache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ false);
        expect.withMessage("Incomplete program info cache")
                .that(programInfoCache.isComplete()).isFalse();
    }

    @Test
    public void getFilter_forProgramInfoCache() {
        ProgramList.Filter fmFilter = new ProgramList.Filter(
                Set.of(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        ProgramInfoCache fmProgramInfoCache = new ProgramInfoCache(fmFilter);

        expect.withMessage("Program info cache filter")
                .that(fmProgramInfoCache.getFilter()).isEqualTo(fmFilter);
    }

    @Test
    public void updateFromHalProgramListChunk_withPurgingCompleteChunk() {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ false, TEST_FM_INFO);
        ProgramListChunk chunk = AidlTestUtils.makeHalChunk(/* purge= */ true, /* complete= */ true,
                new ProgramInfo[]{AidlTestUtils.programInfoToHalProgramInfo(TEST_RDS_INFO),
                        AidlTestUtils.programInfoToHalProgramInfo(TEST_VENDOR_INFO)},
                new ProgramIdentifier[]{});

        cache.updateFromHalProgramListChunk(chunk);

        expect.withMessage("Program cache updated with purge-enabled and complete chunk")
                .that(cache.toProgramInfoList())
                .containsExactly(TEST_RDS_INFO, TEST_VENDOR_INFO);
        expect.withMessage("Complete program cache").that(cache.isComplete()).isTrue();
    }

    @Test
    public void updateFromHalProgramListChunk_withNonPurgingIncompleteChunk() {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ false, TEST_FM_INFO, TEST_RDS_INFO, TEST_AM_INFO);
        ProgramListChunk chunk = AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ false,
                new ProgramInfo[]{AidlTestUtils.programInfoToHalProgramInfo(TEST_FM_INFO_MODIFIED),
                        AidlTestUtils.programInfoToHalProgramInfo(TEST_VENDOR_INFO)},
                new ProgramIdentifier[]{ConversionUtils.identifierToHalProgramIdentifier(
                        TEST_RDS_PI_ID)});

        cache.updateFromHalProgramListChunk(chunk);

        expect.withMessage("Program cache updated with non-purging and incomplete chunk")
                .that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO_MODIFIED, TEST_VENDOR_INFO, TEST_AM_INFO);
        expect.withMessage("Incomplete program cache").that(cache.isComplete()).isFalse();
    }

    @Test
    public void updateFromHalProgramListChunk_withInvalidChunk() {
        ProgramInfo invalidHalDabInfo = AidlTestUtils.makeHalProgramInfo(
                AidlTestUtils.makeHalSelector(
                        ConversionUtils.identifierToHalProgramIdentifier(TEST_DAB_ENSEMBLE_ID),
                        new ProgramIdentifier[]{}), /* logicallyTunedTo= */ null,
                /* physicallyTunedTo= */ null, TEST_SIGNAL_QUALITY);
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ false);
        ProgramListChunk chunk = AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, new ProgramInfo[]{invalidHalDabInfo},
                new ProgramIdentifier[]{});

        cache.updateFromHalProgramListChunk(chunk);

        expect.withMessage("Program cache updated with invalid chunk")
                .that(cache.toProgramInfoList()).isEmpty();
    }

    @Test
    public void filterAndUpdateFromInternal_withNullFilter() {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ true);

        cache.filterAndUpdateFromInternal(FULL_PROGRAM_INFO_CACHE, /* purge= */ false);

        expect.withMessage("Program cache filtered by null filter")
                .that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO, TEST_AM_INFO, TEST_RDS_INFO, TEST_DAB_INFO,
                        TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
    }

    @Test
    public void  filterAndUpdateFromInternal_withEmptyFilter() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false));

        cache.filterAndUpdateFromInternal(FULL_PROGRAM_INFO_CACHE, /* purge= */ false);

        expect.withMessage("Program cache filtered by empty filter")
                .that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO, TEST_AM_INFO, TEST_RDS_INFO, TEST_DAB_INFO,
                        TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
    }

    @Test
    public void filterAndUpdateFromInternal_withFilterByIdentifierType() {
        ProgramInfoCache cache = new ProgramInfoCache(
                new ProgramList.Filter(Set.of(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                        ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT), new ArraySet<>(),
                        /* includeCategories= */ true, /* excludeModifications= */ false));

        cache.filterAndUpdateFromInternal(FULL_PROGRAM_INFO_CACHE, /* purge= */ false);

        expect.withMessage("Program cache filtered by identifier type")
                .that(cache.toProgramInfoList()).containsExactly(TEST_FM_INFO, TEST_AM_INFO,
                        TEST_DAB_INFO, TEST_DAB_INFO_ALTERNATIVE);
    }

    @Test
    public void filterAndUpdateFromInternal_withFilterByIdentifier() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(
                new ArraySet<>(), Set.of(TEST_FM_FREQUENCY_ID, TEST_DAB_DMB_SID_EXT_ID),
                /* includeCategories= */ true, /* excludeModifications= */ false));

        List<ProgramList.Chunk> programListChunks = cache.filterAndUpdateFromInternal(
                FULL_PROGRAM_INFO_CACHE, /* purge= */ false, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache filtered by identifier")
                .that(cache.toProgramInfoList()).containsExactly(TEST_FM_INFO, TEST_DAB_INFO,
                        TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListPurge(programListChunks, /* purge= */ true);
        verifyChunkListComplete(programListChunks, cache.isComplete());
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_FM_INFO,
                TEST_DAB_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK);
    }

    @Test
    public void filterAndUpdateFromInternal_withPurging() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(), /* includeCategories= */ true, /* excludeModifications= */ false),
                /* complete= */ true, TEST_RDS_INFO, TEST_DAB_INFO);
        ProgramInfoCache otherCache = new ProgramInfoCache(/* filter= */ null, /* complete= */ true,
                TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO_ALTERNATIVE);

        List<ProgramList.Chunk> programListChunks = cache.filterAndUpdateFromInternal(otherCache,
                /* purge= */ true, TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache filtered with purging").that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListPurge(programListChunks, /* purge= */ true);
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_FM_INFO,
                TEST_RDS_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK);
    }

    @Test
    public void filterAndUpdateFromInternal_withoutPurging() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(), /* includeCategories= */ true, /* excludeModifications= */ false),
                /* complete= */ true, TEST_RDS_INFO, TEST_DAB_INFO);
        ProgramInfoCache otherCache = new ProgramInfoCache(/* filter= */ null, /* complete= */ true,
                TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO_ALTERNATIVE);
        int maxNumModifiedPerChunk = 1;

        List<ProgramList.Chunk> programListChunks = cache.filterAndUpdateFromInternal(otherCache,
                /* purge= */ false, maxNumModifiedPerChunk, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache filtered without puring").that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListPurge(programListChunks, /* purge= */ false);
        verifyChunkListComplete(programListChunks, cache.isComplete());
        verifyChunkListModified(programListChunks, maxNumModifiedPerChunk, TEST_FM_INFO,
                TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK,
                TEST_DAB_UNIQUE_ID);
    }

    @Test
    public void filterAndUpdateFromInternal_withFilterExcludingCategories() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(), /* includeCategories= */ false,
                /* excludeModifications= */ false));
        int maxNumModifiedPerChunk = 3;

        List<ProgramList.Chunk> programListChunks = cache.filterAndUpdateFromInternal(
                FULL_PROGRAM_INFO_CACHE, /* purge= */ false, maxNumModifiedPerChunk,
                TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache filtered by excluding categories")
                .that(cache.toProgramInfoList()).containsExactly(TEST_FM_INFO, TEST_AM_INFO,
                        TEST_RDS_INFO, TEST_DAB_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListPurge(programListChunks, /* purge= */ true);
        verifyChunkListComplete(programListChunks, cache.isComplete());
        verifyChunkListModified(programListChunks, maxNumModifiedPerChunk, TEST_FM_INFO,
                TEST_AM_INFO, TEST_RDS_INFO, TEST_DAB_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK);
    }

    @Test
    public void filterAndUpdateFromInternal_withFilterExcludingModifications() {
        ProgramList.Filter filterExcludingModifications = new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(), /* includeCategories= */ true,
                /* excludeModifications= */ true);
        ProgramInfoCache cache = new ProgramInfoCache(filterExcludingModifications,
                /* complete= */ true, TEST_FM_INFO, TEST_RDS_INFO, TEST_AM_INFO, TEST_DAB_INFO);
        ProgramInfoCache halCache = new ProgramInfoCache(/* filter= */ null, /* complete= */ false,
                TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);

        List<ProgramList.Chunk> programListChunks = cache.filterAndUpdateFromInternal(halCache,
                /* purge= */ false, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache filtered by excluding modifications")
                .that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
        verifyChunkListPurge(programListChunks, /* purge= */ false);
        verifyChunkListComplete(programListChunks, halCache.isComplete());
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_VENDOR_INFO, TEST_DAB_INFO_ALTERNATIVE);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK,
                TEST_RDS_PI_UNIQUE_ID, TEST_AM_UNIQUE_ID, TEST_DAB_UNIQUE_ID);
    }

    @Test
    public void filterAndUpdateFromInternal_withPurge() {
        ProgramInfoCache cache = new ProgramInfoCache(new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(), /* includeCategories= */ true,
                /* excludeModifications= */ false),
                /* complete= */ true, TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO);
        ProgramInfoCache halCache = new ProgramInfoCache(/* filter= */ null, /* complete= */ false,
                TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);

        List<ProgramList.Chunk> programListChunks = cache.filterAndUpdateFromInternal(halCache,
                /* purge= */ true, TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Purged program cache").that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE,
                        TEST_VENDOR_INFO);
        verifyChunkListPurge(programListChunks, /* purge= */ true);
        verifyChunkListComplete(programListChunks, halCache.isComplete());
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK);
    }

    @Test
    public void filterAndApplyChunkInternal_withPurgingAndIncompleteChunk() throws RemoteException {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ false, TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO);
        ProgramListChunk halChunk = AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ false, List.of(TEST_FM_INFO_MODIFIED,
                        TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO), new ArrayList<>());

        List<ProgramList.Chunk> programListChunks = cache.filterAndApplyChunkInternal(halChunk,
                TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache applied with purge-enabled and complete chunk")
                .that(cache.toProgramInfoList()).containsExactly(TEST_FM_INFO_MODIFIED,
                        TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
        verifyChunkListPurge(programListChunks, /* purge= */ true);
        verifyChunkListComplete(programListChunks, /* complete= */ false);
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK);
    }

    @Test
    public void filterAndApplyChunk_withNonPurgingAndIncompleteChunk() throws RemoteException {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null, /* complete= */ false,
                TEST_FM_INFO, TEST_RDS_INFO, TEST_AM_INFO, TEST_DAB_INFO);
        ProgramListChunk halChunk = AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ false, List.of(TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE,
                        TEST_VENDOR_INFO), List.of(TEST_RDS_PI_ID, TEST_AM_FREQUENCY_ID));

        List<ProgramList.Chunk> programListChunks = cache.filterAndApplyChunkInternal(halChunk,
                TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache applied with non-purging and incomplete chunk")
                .that(cache.toProgramInfoList()).containsExactly(TEST_DAB_INFO,
                        TEST_DAB_INFO_ALTERNATIVE, TEST_FM_INFO_MODIFIED, TEST_VENDOR_INFO);
        verifyChunkListPurge(programListChunks, /* purge= */ false);
        verifyChunkListComplete(programListChunks, /* complete= */ false);
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALTERNATIVE, TEST_VENDOR_INFO);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK,
                TEST_RDS_PI_UNIQUE_ID, TEST_AM_UNIQUE_ID);
    }

    @Test
    public void filterAndApplyChunk_withNonPurgingAndCompleteChunk() throws RemoteException {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null, /* complete= */ false,
                TEST_FM_INFO, TEST_RDS_INFO, TEST_AM_INFO, TEST_DAB_INFO,
                TEST_DAB_INFO_ALTERNATIVE);
        ProgramListChunk halChunk = AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED, TEST_VENDOR_INFO),
                List.of(TEST_RDS_PI_ID, TEST_AM_FREQUENCY_ID, TEST_DAB_DMB_SID_EXT_ID));

        List<ProgramList.Chunk> programListChunks = cache.filterAndApplyChunkInternal(halChunk,
                TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program cache applied with non-purging and complete chunk")
                .that(cache.toProgramInfoList())
                .containsExactly(TEST_FM_INFO_MODIFIED, TEST_VENDOR_INFO);
        verifyChunkListPurge(programListChunks, /* purge= */ false);
        verifyChunkListComplete(programListChunks, /* complete= */ true);
        verifyChunkListModified(programListChunks, TEST_MAX_NUM_MODIFIED_PER_CHUNK,
                TEST_FM_INFO_MODIFIED, TEST_VENDOR_INFO);
        verifyChunkListRemoved(programListChunks, TEST_MAX_NUM_REMOVED_PER_CHUNK,
                TEST_RDS_PI_UNIQUE_ID, TEST_AM_UNIQUE_ID, TEST_DAB_UNIQUE_ID,
                TEST_DAB_UNIQUE_ID_ALTERNATIVE);
    }

    @Test
    public void filterAndApplyChunkInternal_withInvalidProgramInfoAndIdentifiers()
            throws RemoteException {
        ProgramInfoCache cache = new ProgramInfoCache(/* filter= */ null,
                /* complete= */ false, TEST_FM_INFO, TEST_RDS_INFO, TEST_DAB_INFO);
        ProgramInfo[] halModified = new android.hardware.broadcastradio.ProgramInfo[1];
        halModified[0] = AidlTestUtils.makeHalProgramInfo(
                AidlTestUtils.makeHalSelector(
                        ConversionUtils.identifierToHalProgramIdentifier(TEST_DAB_ENSEMBLE_ID),
                        new ProgramIdentifier[]{}), /* logicallyTunedTo= */ null,
                /* physicallyTunedTo= */ null, TEST_SIGNAL_QUALITY);
        ProgramIdentifier[] halRemoved = new android.hardware.broadcastradio.ProgramIdentifier[1];
        halRemoved[0] = new android.hardware.broadcastradio.ProgramIdentifier();
        ProgramListChunk halChunk = AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, halModified, halRemoved);

        List<ProgramList.Chunk> programListChunks = cache.filterAndApplyChunkInternal(halChunk,
                TEST_MAX_NUM_MODIFIED_PER_CHUNK, TEST_MAX_NUM_REMOVED_PER_CHUNK);

        expect.withMessage("Program list chunk applied with invalid program and identifiers")
                .that(programListChunks).isEmpty();
    }

    private void verifyChunkListPurge(List<ProgramList.Chunk> chunks, boolean purge) {
        if (chunks.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            ProgramList.Chunk chunk = chunks.get(i);
            boolean expectedPurge = (i == 0 && purge);

            expect.withMessage("Purge for chunk %s", i)
                    .that(chunk.isPurge()).isEqualTo(expectedPurge);
        }
    }

    private void verifyChunkListComplete(List<ProgramList.Chunk> chunks, boolean complete) {
        if (chunks.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            ProgramList.Chunk chunk = chunks.get(i);
            boolean expectedComplete = (i == chunks.size() - 1 && complete);

            expect.withMessage("Purge for chunk %s", i)
                    .that(chunk.isComplete()).isEqualTo(expectedComplete);
        }
    }

    private void verifyChunkListModified(List<ProgramList.Chunk> chunks,
            int maxModifiedPerChunk, RadioManager.ProgramInfo... expectedProgramInfos) {
        if (chunks.isEmpty()) {
            expect.withMessage("Empty program info list")
                    .that(expectedProgramInfos.length).isEqualTo(0);
            return;
        }

        ArraySet<RadioManager.ProgramInfo> actualSet = new ArraySet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Set<RadioManager.ProgramInfo> chunkModified = chunks.get(i).getModified();
            actualSet.addAll(chunkModified);

            expect.withMessage("Chunk %s modified program info array size", i)
                    .that(chunkModified.size()).isAtMost(maxModifiedPerChunk);
        }
        expect.withMessage("Program info items")
                .that(actualSet).containsExactlyElementsIn(expectedProgramInfos);
    }

    private void verifyChunkListRemoved(List<ProgramList.Chunk> chunks, int maxRemovedPerChunk,
            UniqueProgramIdentifier... expectedIdentifiers) {
        if (chunks.isEmpty()) {
            expect.withMessage("Empty program info list")
                    .that(expectedIdentifiers.length).isEqualTo(0);
            return;
        }

        ArraySet<UniqueProgramIdentifier> actualSet = new ArraySet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Set<UniqueProgramIdentifier> chunkRemoved = chunks.get(i).getRemoved();
            actualSet.addAll(chunkRemoved);

            expect.withMessage("Chunk %s removed identifier array size ", i)
                    .that(chunkRemoved.size()).isAtMost(maxRemovedPerChunk);
        }
        expect.withMessage("Removed identifier items")
                .that(actualSet).containsExactlyElementsIn(expectedIdentifiers);
    }
}
