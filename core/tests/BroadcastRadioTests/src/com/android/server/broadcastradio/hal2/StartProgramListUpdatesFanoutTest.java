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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ITunerCallback;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.ProgramFilter;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.UniqueProgramIdentifier;
import android.os.RemoteException;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;
import com.android.server.broadcastradio.RadioServiceUserController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationWithTimeout;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Tests for v2 HAL RadioModule.
 */
public class StartProgramListUpdatesFanoutTest extends ExtendedRadioMockitoTestCase {
    private static final String TAG = "BroadcastRadioTests.hal2.StartProgramListUpdatesFanout";

    private static final VerificationWithTimeout CB_TIMEOUT = timeout(500);

    // Mocks
    @Mock IBroadcastRadio mBroadcastRadioMock;
    @Mock ITunerSession mHalTunerSessionMock;
    private android.hardware.radio.ITunerCallback[] mAidlTunerCallbackMocks;

    // RadioModule under test
    private RadioModule mRadioModule;

    // Objects created by mRadioModule
    private ITunerCallback mHalTunerCallback;
    private TunerSession[] mTunerSessions;

    // Data objects used during tests
    private static final int TEST_QUALITY = 0;
    private static final ProgramSelector.Identifier TEST_AM_FM_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                    /* value= */ 88_500);
    private static final ProgramSelector TEST_AM_FM_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM, TEST_AM_FM_ID);
    private static final RadioManager.ProgramInfo TEST_AM_FM_INFO = TestUtils.makeProgramInfo(
            TEST_AM_FM_SELECTOR, TEST_QUALITY);
    private static final RadioManager.ProgramInfo TEST_AM_FM_MODIFIED_INFO =
            TestUtils.makeProgramInfo(TEST_AM_FM_SELECTOR, TEST_QUALITY + 1);

    private static final ProgramSelector.Identifier TEST_RDS_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_RDS_PI,
                    /* value= */ 15_019);
    private static final ProgramSelector TEST_RDS_SELECTOR = TestUtils.makeProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM, TEST_RDS_ID);

    private static final UniqueProgramIdentifier TEST_RDS_UNIQUE_ID = new UniqueProgramIdentifier(
            TEST_RDS_ID);
    private static final RadioManager.ProgramInfo TEST_RDS_INFO = TestUtils.makeProgramInfo(
            TEST_RDS_SELECTOR, TEST_QUALITY);

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
    private static final RadioManager.ProgramInfo TEST_DAB_INFO = TestUtils.makeProgramInfo(
            TEST_DAB_SELECTOR, TEST_QUALITY);

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(RadioServiceUserController.class);
    }

    @Before
    public void setup() throws RemoteException {
        doReturn(true).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mRadioModule = new RadioModule(mBroadcastRadioMock,
                TestUtils.makeDefaultModuleProperties());

        doAnswer((Answer) invocation -> {
            mHalTunerCallback = (ITunerCallback) invocation.getArguments()[0];
            IBroadcastRadio.openSessionCallback cb = (IBroadcastRadio.openSessionCallback)
                    invocation.getArguments()[1];
            cb.onValues(Result.OK, mHalTunerSessionMock);
            return null;
        }).when(mBroadcastRadioMock).openSession(any(), any());
        when(mHalTunerSessionMock.startProgramListUpdates(any())).thenReturn(Result.OK);
    }

    @Test
    public void testFanout() throws RemoteException {
        // Open 3 clients that will all use the same filter, and start updates on two of them for
        // now. The HAL TunerSession should only see 1 filter update.
        openAidlClients(3);
        ProgramList.Filter aidlFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(), true, false);
        ProgramFilter halFilter = Convert.programFilterToHal(aidlFilter);
        for (int i = 0; i < 2; i++) {
            mTunerSessions[i].startProgramListUpdates(aidlFilter);
        }
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        // Initiate a program list update from the HAL side and verify both connected AIDL clients
        // receive the update.
        updateHalProgramInfo(true, Arrays.asList(TEST_AM_FM_INFO, TEST_RDS_INFO), null);
        for (int i = 0; i < 2; i++) {
            verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[i], true, Arrays.asList(
                    TEST_AM_FM_INFO, TEST_RDS_INFO), null);
        }

        // Repeat with a non-purging update.
        updateHalProgramInfo(false, Arrays.asList(TEST_AM_FM_MODIFIED_INFO),
                Arrays.asList(TEST_RDS_ID));
        for (int i = 0; i < 2; i++) {
            verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[i], false,
                    Arrays.asList(TEST_AM_FM_MODIFIED_INFO), Arrays.asList(TEST_RDS_UNIQUE_ID));
        }

        // Now start updates on the 3rd client. Verify the HAL function has not been called again
        // and client receives the appropriate update.
        mTunerSessions[2].startProgramListUpdates(aidlFilter);
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(any());
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[2], true,
                Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);
    }

    @Test
    public void testFiltering() throws RemoteException {
        // Open 4 clients that will use the following filters:
        // [0]: ID TEST_RDS_ID, modifications excluded
        // [1]: No categories, modifications excluded
        // [2]: Type IDENTIFIER_TYPE_AMFM_FREQUENCY, modifications excluded
        // [3]: Type IDENTIFIER_TYPE_AMFM_FREQUENCY, modifications included
        openAidlClients(4);
        ProgramList.Filter idFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(Arrays.asList(TEST_RDS_ID)), true, true);
        ProgramList.Filter categoryFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(), false, true);
        ProgramList.Filter typeFilterWithoutModifications = new ProgramList.Filter(
                new HashSet<Integer>(Arrays.asList(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)),
                new HashSet<ProgramSelector.Identifier>(), true, true);
        ProgramList.Filter typeFilterWithModifications = new ProgramList.Filter(
                new HashSet<Integer>(Arrays.asList(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)),
                new HashSet<ProgramSelector.Identifier>(), true, false);

        // Start updates on the clients in order. The HAL filter should get updated after each
        // client except [2]. Client [2] should update received chunk with an empty program
        // list
        mTunerSessions[0].startProgramListUpdates(idFilter);
        ProgramFilter halFilter = Convert.programFilterToHal(idFilter);
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        mTunerSessions[1].startProgramListUpdates(categoryFilter);
        halFilter.identifiers.clear();
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        mTunerSessions[2].startProgramListUpdates(typeFilterWithoutModifications);
        verify(mHalTunerSessionMock, times(2)).startProgramListUpdates(any());
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[2], true, Arrays.asList(),
                null);
        verify(mAidlTunerCallbackMocks[2], CB_TIMEOUT.times(1)).onProgramListUpdated(any());

        mTunerSessions[3].startProgramListUpdates(typeFilterWithModifications);
        halFilter.excludeModifications = false;
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        // Adding TEST_RDS_INFO should update clients [0] and [1].
        updateHalProgramInfo(false, Arrays.asList(TEST_RDS_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], false,
                Arrays.asList(TEST_RDS_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], false,
                Arrays.asList(TEST_RDS_INFO), null);

        // Adding TEST_AM_FM_INFO should update clients [1], [2], and [3].
        updateHalProgramInfo(false, Arrays.asList(TEST_AM_FM_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], false,
                Arrays.asList(TEST_AM_FM_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[2], false,
                Arrays.asList(TEST_AM_FM_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[3], false,
                Arrays.asList(TEST_AM_FM_INFO), null);

        // Modifying TEST_AM_FM_INFO to TEST_AM_FM_MODIFIED_INFO should update only [3].
        updateHalProgramInfo(false, Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[3], false,
                Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);

        updateHalProgramInfo(false, Arrays.asList(TEST_DAB_INFO), null);
        verify(mAidlTunerCallbackMocks[0], CB_TIMEOUT.times(1)).onProgramListUpdated(any());
        verify(mAidlTunerCallbackMocks[1], CB_TIMEOUT.times(3)).onProgramListUpdated(any());
        verify(mAidlTunerCallbackMocks[2], CB_TIMEOUT.times(2)).onProgramListUpdated(any());
        verify(mAidlTunerCallbackMocks[3], CB_TIMEOUT.times(2)).onProgramListUpdated(any());
    }

    @Test
    public void testClientClosing() throws RemoteException {
        // Open 2 clients that use different filters that are both sensitive to TEST_AM_FM_ID.
        openAidlClients(2);
        ProgramList.Filter idFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(Arrays.asList(TEST_AM_FM_ID)), true,
                false);
        ProgramList.Filter typeFilter = new ProgramList.Filter(
                new HashSet<Integer>(Arrays.asList(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)),
                new HashSet<ProgramSelector.Identifier>(), true, false);

        // Start updates on the clients, and verify the HAL filter is updated after each one.
        mTunerSessions[0].startProgramListUpdates(idFilter);
        ProgramFilter halFilter = Convert.programFilterToHal(idFilter);
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        mTunerSessions[1].startProgramListUpdates(typeFilter);
        halFilter.identifiers.clear();
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        // Update the HAL with TEST_AM_FM_INFO, and verify both clients are updated.
        updateHalProgramInfo(true, Arrays.asList(TEST_AM_FM_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], true,
                Arrays.asList(TEST_AM_FM_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], true,
                Arrays.asList(TEST_AM_FM_INFO), null);

        // Stop updates on the first client and verify the HAL filter is updated.
        mTunerSessions[0].stopProgramListUpdates();
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(Convert.programFilterToHal(
                typeFilter));

        // Update the HAL with TEST_AM_FM_MODIFIED_INFO, and verify only the remaining client is
        // updated.
        updateHalProgramInfo(true, Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);
        verify(mAidlTunerCallbackMocks[0], CB_TIMEOUT.times(1)).onProgramListUpdated(any());
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], true,
                Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);

        // Close the other client without explicitly stopping updates, and verify HAL updates are
        // stopped as well.
        mTunerSessions[1].close();
        verify(mHalTunerSessionMock).stopProgramListUpdates();
    }

    @Test
    public void testNullAidlFilter() throws RemoteException {
        openAidlClients(1);
        mTunerSessions[0].startProgramListUpdates(null);
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(any());

        // Verify the AIDL client receives all types of updates (e.g. a new program, an update to
        // that program, and a category).
        updateHalProgramInfo(true, Arrays.asList(TEST_AM_FM_INFO, TEST_RDS_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], true, Arrays.asList(
                TEST_AM_FM_INFO, TEST_RDS_INFO), null);
        updateHalProgramInfo(false, Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], false,
                Arrays.asList(TEST_AM_FM_MODIFIED_INFO), null);
        updateHalProgramInfo(false, Arrays.asList(TEST_DAB_INFO), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], false,
                Arrays.asList(TEST_DAB_INFO), null);

        // Verify closing the AIDL session also stops HAL updates.
        mTunerSessions[0].close();
        verify(mHalTunerSessionMock).stopProgramListUpdates();
    }

    private void openAidlClients(int numClients) throws RemoteException {
        mAidlTunerCallbackMocks = new android.hardware.radio.ITunerCallback[numClients];
        mTunerSessions = new TunerSession[numClients];
        for (int i = 0; i < numClients; i++) {
            mAidlTunerCallbackMocks[i] = mock(android.hardware.radio.ITunerCallback.class);
            mTunerSessions[i] = mRadioModule.openSession(mAidlTunerCallbackMocks[i]);
        }
    }

    private void updateHalProgramInfo(boolean purge, List<RadioManager.ProgramInfo> modified,
            List<ProgramSelector.Identifier> removed) throws RemoteException {
        ProgramListChunk programListChunk = new ProgramListChunk();
        programListChunk.purge = purge;
        programListChunk.complete = true;
        if (modified != null) {
            for (RadioManager.ProgramInfo mod : modified) {
                programListChunk.modified.add(TestUtils.programInfoToHal(mod));
            }
        }
        if (removed != null) {
            for (ProgramSelector.Identifier id : removed) {
                programListChunk.removed.add(Convert.programIdentifierToHal(id));
            }
        }
        mHalTunerCallback.onProgramListUpdated(programListChunk);
    }

    private void verifyAidlClientReceivedChunk(android.hardware.radio.ITunerCallback clientMock,
            boolean purge, List<RadioManager.ProgramInfo> modified,
            List<UniqueProgramIdentifier> removed) throws RemoteException {
        HashSet<RadioManager.ProgramInfo> modifiedSet = new HashSet<>();
        if (modified != null) {
            modifiedSet.addAll(modified);
        }
        HashSet<UniqueProgramIdentifier> removedSet = new HashSet<>();
        if (removed != null) {
            removedSet.addAll(removed);
        }
        ProgramList.Chunk expectedChunk = new ProgramList.Chunk(purge, true, modifiedSet,
                removedSet);
        verify(clientMock, CB_TIMEOUT).onProgramListUpdated(expectedChunk);
    }
}
