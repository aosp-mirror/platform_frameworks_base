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
import android.os.RemoteException;
import android.test.suitebuilder.annotation.MediumTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationWithTimeout;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Tests for v2 HAL RadioModule.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class StartProgramListUpdatesFanoutTest {
    private static final String TAG = "BroadcastRadioTests.hal2.StartProgramListUpdatesFanout";

    private static final VerificationWithTimeout CB_TIMEOUT = timeout(100);

    // Mocks
    @Mock IBroadcastRadio mBroadcastRadioMock;
    @Mock ITunerSession mHalTunerSessionMock;
    private android.hardware.radio.ITunerCallback[] mAidlTunerCallbackMocks;

    private final Object mLock = new Object();
    // RadioModule under test
    private RadioModule mRadioModule;

    // Objects created by mRadioModule
    private ITunerCallback mHalTunerCallback;
    private TunerSession[] mTunerSessions;

    // Data objects used during tests
    private final ProgramSelector.Identifier mAmFmIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, 88500);
    private final RadioManager.ProgramInfo mAmFmInfo = TestUtils.makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_FM, mAmFmIdentifier, 0);
    private final RadioManager.ProgramInfo mModifiedAmFmInfo = TestUtils.makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_FM, mAmFmIdentifier, 1);

    private final ProgramSelector.Identifier mRdsIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_RDS_PI, 15019);
    private final RadioManager.ProgramInfo mRdsInfo = TestUtils.makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_FM, mRdsIdentifier, 0);

    private final ProgramSelector.Identifier mDabEnsembleIdentifier =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE, 1337);
    private final RadioManager.ProgramInfo mDabEnsembleInfo = TestUtils.makeProgramInfo(
            ProgramSelector.PROGRAM_TYPE_DAB, mDabEnsembleIdentifier, 0);

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mRadioModule = new RadioModule(mBroadcastRadioMock, new RadioManager.ModuleProperties(0, "",
                  0, "", "", "", "", 0, 0, false, false, null, false, new int[] {}, new int[] {},
                  null, null), mLock);

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
        updateHalProgramInfo(true, Arrays.asList(mAmFmInfo, mRdsInfo), null);
        for (int i = 0; i < 2; i++) {
            verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[i], true, Arrays.asList(
                    mAmFmInfo, mRdsInfo), null);
        }

        // Repeat with a non-purging update.
        updateHalProgramInfo(false, Arrays.asList(mModifiedAmFmInfo),
                Arrays.asList(mRdsIdentifier));
        for (int i = 0; i < 2; i++) {
            verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[i], false,
                    Arrays.asList(mModifiedAmFmInfo), Arrays.asList(mRdsIdentifier));
        }

        // Now start updates on the 3rd client. Verify the HAL function has not been called again
        // and client receives the appropriate update.
        mTunerSessions[2].startProgramListUpdates(aidlFilter);
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(any());
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[2], true,
                Arrays.asList(mModifiedAmFmInfo), null);
    }

    @Test
    public void testFiltering() throws RemoteException {
        // Open 4 clients that will use the following filters:
        // [0]: ID mRdsIdentifier, modifications excluded
        // [1]: No categories, modifications excluded
        // [2]: Type IDENTIFIER_TYPE_AMFM_FREQUENCY, modifications excluded
        // [3]: Type IDENTIFIER_TYPE_AMFM_FREQUENCY, modifications included
        openAidlClients(4);
        ProgramList.Filter idFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(Arrays.asList(mRdsIdentifier)), true, true);
        ProgramList.Filter categoryFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(), false, true);
        ProgramList.Filter typeFilterWithoutModifications = new ProgramList.Filter(
                new HashSet<Integer>(Arrays.asList(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)),
                new HashSet<ProgramSelector.Identifier>(), true, true);
        ProgramList.Filter typeFilterWithModifications = new ProgramList.Filter(
                new HashSet<Integer>(Arrays.asList(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)),
                new HashSet<ProgramSelector.Identifier>(), true, false);

        // Start updates on the clients in order. The HAL filter should get updated after each
        // client except [2].
        mTunerSessions[0].startProgramListUpdates(idFilter);
        ProgramFilter halFilter = Convert.programFilterToHal(idFilter);
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        mTunerSessions[1].startProgramListUpdates(categoryFilter);
        halFilter.identifiers.clear();
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        mTunerSessions[2].startProgramListUpdates(typeFilterWithoutModifications);
        verify(mHalTunerSessionMock, times(2)).startProgramListUpdates(any());

        mTunerSessions[3].startProgramListUpdates(typeFilterWithModifications);
        halFilter.excludeModifications = false;
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(halFilter);

        // Adding mRdsInfo should update clients [0] and [1].
        updateHalProgramInfo(false, Arrays.asList(mRdsInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], false, Arrays.asList(mRdsInfo),
                null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], false, Arrays.asList(mRdsInfo),
                null);

        // Adding mAmFmInfo should update clients [1], [2], and [3].
        updateHalProgramInfo(false, Arrays.asList(mAmFmInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], false, Arrays.asList(mAmFmInfo),
                null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[2], false, Arrays.asList(mAmFmInfo),
                null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[3], false, Arrays.asList(mAmFmInfo),
                null);

        // Modifying mAmFmInfo to mModifiedAmFmInfo should update only [3].
        updateHalProgramInfo(false, Arrays.asList(mModifiedAmFmInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[3], false,
                Arrays.asList(mModifiedAmFmInfo), null);

        // Adding mDabEnsembleInfo should not update any client.
        updateHalProgramInfo(false, Arrays.asList(mDabEnsembleInfo), null);
        verify(mAidlTunerCallbackMocks[0], CB_TIMEOUT.times(1)).onProgramListUpdated(any());
        verify(mAidlTunerCallbackMocks[1], CB_TIMEOUT.times(2)).onProgramListUpdated(any());
        verify(mAidlTunerCallbackMocks[2], CB_TIMEOUT.times(1)).onProgramListUpdated(any());
        verify(mAidlTunerCallbackMocks[3], CB_TIMEOUT.times(2)).onProgramListUpdated(any());
    }

    @Test
    public void testClientClosing() throws RemoteException {
        // Open 2 clients that use different filters that are both sensitive to mAmFmIdentifier.
        openAidlClients(2);
        ProgramList.Filter idFilter = new ProgramList.Filter(new HashSet<Integer>(),
                new HashSet<ProgramSelector.Identifier>(Arrays.asList(mAmFmIdentifier)), true,
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

        // Update the HAL with mAmFmInfo, and verify both clients are updated.
        updateHalProgramInfo(true, Arrays.asList(mAmFmInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], true, Arrays.asList(mAmFmInfo),
                null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], true, Arrays.asList(mAmFmInfo),
                null);

        // Stop updates on the first client and verify the HAL filter is updated.
        mTunerSessions[0].stopProgramListUpdates();
        verify(mHalTunerSessionMock, times(1)).startProgramListUpdates(Convert.programFilterToHal(
                typeFilter));

        // Update the HAL with mModifiedAmFmInfo, and verify only the remaining client is updated.
        updateHalProgramInfo(true, Arrays.asList(mModifiedAmFmInfo), null);
        verify(mAidlTunerCallbackMocks[0], CB_TIMEOUT.times(1)).onProgramListUpdated(any());
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[1], true,
                Arrays.asList(mModifiedAmFmInfo), null);

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
        updateHalProgramInfo(true, Arrays.asList(mAmFmInfo, mRdsInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], true, Arrays.asList(
                mAmFmInfo, mRdsInfo), null);
        updateHalProgramInfo(false, Arrays.asList(mModifiedAmFmInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], false,
                Arrays.asList(mModifiedAmFmInfo), null);
        updateHalProgramInfo(false, Arrays.asList(mDabEnsembleInfo), null);
        verifyAidlClientReceivedChunk(mAidlTunerCallbackMocks[0], false,
                Arrays.asList(mDabEnsembleInfo), null);

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
            List<ProgramSelector.Identifier> removed) throws RemoteException {
        HashSet<RadioManager.ProgramInfo> modifiedSet = new HashSet<>();
        if (modified != null) {
            modifiedSet.addAll(modified);
        }
        HashSet<ProgramSelector.Identifier> removedSet = new HashSet<>();
        if (removed != null) {
            removedSet.addAll(removed);
        }
        ProgramList.Chunk expectedChunk = new ProgramList.Chunk(purge, true, modifiedSet,
                removedSet);
        verify(clientMock, CB_TIMEOUT).onProgramListUpdated(expectedChunk);
    }
}
