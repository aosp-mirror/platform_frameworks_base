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

package com.android.server.broadcastradio.hal2;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.after;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.timeout;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.V2_0.Constants;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ITunerCallback;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.IdentifierType;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;
import com.android.server.broadcastradio.RadioServiceUserController;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.verification.VerificationWithTimeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests for HIDL HAL TunerSession.
 */
@RunWith(MockitoJUnitRunner.class)
public final class TunerSessionHidlTest extends ExtendedRadioMockitoTestCase {

    private static final int USER_ID_1 = 11;
    private static final int USER_ID_2 = 12;
    private static final int CALLBACK_TIMEOUT_MS = 200;
    private static final VerificationWithTimeout CALLBACK_TIMEOUT = timeout(CALLBACK_TIMEOUT_MS);
    private static final int SIGNAL_QUALITY = 1;
    private static final long AM_FM_FREQUENCY_SPACING = 500;
    private static final long[] AM_FM_FREQUENCY_LIST = {97_500, 98_100, 99_100};
    private static final RadioManager.FmBandDescriptor FM_BAND_DESCRIPTOR =
            new RadioManager.FmBandDescriptor(RadioManager.REGION_ITU_1, RadioManager.BAND_FM,
                    /* lowerLimit= */ 87_500, /* upperLimit= */ 108_000, /* spacing= */ 100,
                    /* stereo= */ false, /* rds= */ false, /* ta= */ false, /* af= */ false,
                    /* ea= */ false);
    private static final RadioManager.BandConfig FM_BAND_CONFIG =
            new RadioManager.FmBandConfig(FM_BAND_DESCRIPTOR);
    private static final int UNSUPPORTED_CONFIG_FLAG = 0;

    private final ArrayMap<Integer, Boolean> mHalConfigMap = new ArrayMap<>();
    private RadioModule mRadioModule;
    private ITunerCallback mHalTunerCallback;
    private ProgramInfo mHalCurrentInfo;
    private TunerSession[] mTunerSessions;

    @Rule
    public final Expect mExpect = Expect.create();

    @Mock
    private UserHandle mUserHandleMock;
    @Mock
    private IBroadcastRadio mBroadcastRadioMock;
    @Mock
    ITunerSession mHalTunerSessionMock;
    private android.hardware.radio.ITunerCallback[] mAidlTunerCallbackMocks;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(RadioServiceUserController.class).spyStatic(Binder.class);
    }

    @Before
    public void setup() throws Exception {
        doReturn(USER_ID_1).when(mUserHandleMock).getIdentifier();
        doReturn(mUserHandleMock).when(() -> Binder.getCallingUserHandle());
        doReturn(true).when(() -> RadioServiceUserController.isCurrentOrSystemUser());
        doReturn(USER_ID_1).when(() -> RadioServiceUserController.getCurrentUser());

        mRadioModule = new RadioModule(mBroadcastRadioMock,
                TestUtils.makeDefaultModuleProperties());

        doAnswer(invocation -> {
            mHalTunerCallback = (ITunerCallback) invocation.getArguments()[0];
            IBroadcastRadio.openSessionCallback cb = (IBroadcastRadio.openSessionCallback)
                    invocation.getArguments()[1];
            cb.onValues(Result.OK, mHalTunerSessionMock);
            return null;
        }).when(mBroadcastRadioMock).openSession(any(), any());

        doAnswer(invocation -> {
            android.hardware.broadcastradio.V2_0.ProgramSelector halSel =
                    (android.hardware.broadcastradio.V2_0.ProgramSelector)
                            invocation.getArguments()[0];
            mHalCurrentInfo = TestUtils.makeHalProgramInfo(halSel, SIGNAL_QUALITY);
            if (halSel.primaryId.type != IdentifierType.AMFM_FREQUENCY) {
                return Result.NOT_SUPPORTED;
            }
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return Result.OK;
        }).when(mHalTunerSessionMock).tune(any());

        doAnswer(invocation -> {
            if ((boolean) invocation.getArguments()[0]) {
                mHalCurrentInfo.selector.primaryId.value += AM_FM_FREQUENCY_SPACING;
            } else {
                mHalCurrentInfo.selector.primaryId.value -= AM_FM_FREQUENCY_SPACING;
            }
            mHalCurrentInfo.logicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalCurrentInfo.physicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return Result.OK;
        }).when(mHalTunerSessionMock).step(anyBoolean());

        doAnswer(invocation -> {
            if (mHalCurrentInfo == null) {
                android.hardware.broadcastradio.V2_0.ProgramSelector placeHolderSelector =
                        TestUtils.makeHalFmSelector(/* freq= */ 97300);

                mHalTunerCallback.onTuneFailed(Result.TIMEOUT, placeHolderSelector);
                return Result.OK;
            }
            mHalCurrentInfo.selector.primaryId.value = getSeekFrequency(
                    mHalCurrentInfo.selector.primaryId.value,
                    !(boolean) invocation.getArguments()[0]);
            mHalCurrentInfo.logicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalCurrentInfo.physicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return Result.OK;
        }).when(mHalTunerSessionMock).scan(anyBoolean(), anyBoolean());

        doReturn(new ArrayList<Byte>(0)).when(mBroadcastRadioMock).getImage(anyInt());

        doAnswer(invocation -> {
            int configFlag = (int) invocation.getArguments()[0];
            ITunerSession.isConfigFlagSetCallback cb = (ITunerSession.isConfigFlagSetCallback)
                    invocation.getArguments()[1];
            if (configFlag == UNSUPPORTED_CONFIG_FLAG) {
                cb.onValues(Result.NOT_SUPPORTED, false);
                return null;
            }
            cb.onValues(Result.OK, mHalConfigMap.getOrDefault(configFlag, false));
            return null;
        }).when(mHalTunerSessionMock).isConfigFlagSet(anyInt(), any());

        doAnswer(invocation -> {
            int configFlag = (int) invocation.getArguments()[0];
            if (configFlag == UNSUPPORTED_CONFIG_FLAG) {
                return Result.NOT_SUPPORTED;
            }
            mHalConfigMap.put(configFlag, (boolean) invocation.getArguments()[1]);
            return Result.OK;
        }).when(mHalTunerSessionMock).setConfigFlag(anyInt(), anyBoolean());
    }

    @After
    public void cleanUp() {
        mHalConfigMap.clear();
    }

    @Test
    public void openSession_withMultipleSessions() throws Exception {
        int numSessions = 3;

        openAidlClients(numSessions);

        for (int index = 0; index < numSessions; index++) {
            mExpect.withMessage("Session of index %s close state", index)
                    .that(mTunerSessions[index].isClosed()).isFalse();
        }
    }

    @Test
    public void setConfiguration() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setConfiguration(FM_BAND_CONFIG);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onConfigurationChanged(FM_BAND_CONFIG);
    }

    @Test
    public void setConfiguration_forNonCurrentUser_doesNotInvokesCallback() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].setConfiguration(FM_BAND_CONFIG);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onConfigurationChanged(FM_BAND_CONFIG);
    }

    @Test
    public void getConfiguration() throws Exception {
        openAidlClients(/* numClients= */ 1);
        mTunerSessions[0].setConfiguration(FM_BAND_CONFIG);

        RadioManager.BandConfig config = mTunerSessions[0].getConfiguration();

        mExpect.withMessage("Session configuration").that(config)
                .isEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void setMuted_withUnmuted() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ false);

        mExpect.withMessage("Session mute state after setting unmuted")
                .that(mTunerSessions[0].isMuted()).isFalse();
    }

    @Test
    public void setMuted_withMuted() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ true);

        mExpect.withMessage("Session mute state after setting muted")
                .that(mTunerSessions[0].isMuted()).isTrue();
    }

    @Test
    public void close_withOneSession() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].close();

        mExpect.withMessage("Close state of broadcast radio service session")
                .that(mTunerSessions[0].isClosed()).isTrue();
    }

    @Test
    public void close_withOnlyOneSession_withMultipleSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        int closeIdx = 0;

        mTunerSessions[closeIdx].close();

        for (int index = 0; index < numSessions; index++) {
            if (index == closeIdx) {
                mExpect.withMessage(
                        "Close state of broadcast radio service session of index %s", index)
                        .that(mTunerSessions[index].isClosed()).isTrue();
            } else {
                mExpect.withMessage(
                        "Close state of broadcast radio service session of index %s", index)
                        .that(mTunerSessions[index].isClosed()).isFalse();
            }
        }
    }

    @Test
    public void close_withOneSession_withError() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int errorCode = RadioTuner.ERROR_SERVER_DIED;

        mTunerSessions[0].close(errorCode);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onError(errorCode);
        mExpect.withMessage("Close state of broadcast radio service session")
                .that(mTunerSessions[0].isClosed()).isTrue();
    }

    @Test
    public void close_forMultipleTimes() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int errorCode = RadioTuner.ERROR_SERVER_DIED;
        mTunerSessions[0].close(errorCode);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onError(errorCode);

        mTunerSessions[0].close(errorCode);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onError(errorCode);
        mExpect.withMessage("State of closing broadcast radio service session twice")
                .that(mTunerSessions[0].isClosed()).isTrue();
    }

    @Test
    public void closeSessions_withMultipleSessions_withError() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);

        int errorCode = RadioTuner.ERROR_SERVER_DIED;
        mRadioModule.closeSessions(errorCode);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT).onError(errorCode);
            mExpect.withMessage("Close state of broadcast radio service session of index %s", index)
                    .that(mTunerSessions[index].isClosed()).isTrue();
        }
    }

    @Test
    public void tune_withOneSession() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                TestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onCurrentProgramInfoChanged(tuneInfo);
    }

    @Test
    public void tune_withMultipleSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        ProgramSelector initialSel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                TestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onCurrentProgramInfoChanged(tuneInfo);
        }
    }

    @Test
    public void tune_withDeadTunerCallback_removesDeadSession() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector sel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        doThrow(new DeadObjectException()).when(mAidlTunerCallbackMocks[0])
                .onCurrentProgramInfoChanged(any());

        mTunerSessions[0].tune(sel);

        verify(mHalTunerSessionMock, CALLBACK_TIMEOUT).close();
    }

    @Test
    public void tune_withUnsupportedSelector_throwsException() throws Exception {
        ProgramSelector.Identifier dabPrimaryId =
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT,
                        /* value= */ 0xA00111);
        ProgramSelector.Identifier[] dabSecondaryIds =  new ProgramSelector.Identifier[]{
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                        /* value= */ 1337),
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                        /* value= */ 225648)};
        ProgramSelector unsupportedSelector = new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB,
                dabPrimaryId, dabSecondaryIds, /* vendorIds= */ null);
        openAidlClients(/* numClients= */ 1);

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> mTunerSessions[0].tune(unsupportedSelector));

        mExpect.withMessage("Exception for tuning on unsupported program selector")
                .that(thrown).hasMessageThat().contains("tune: NOT_SUPPORTED");
    }

    @Test
    public void tune_forNonCurrentUser_doesNotTune() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());
        ProgramSelector initialSel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                TestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(tuneInfo);
    }

    @Test
    public void tune_withUnknownErrorFromHal_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector sel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        doAnswer(invocation -> Result.UNKNOWN_ERROR).when(mHalTunerSessionMock).tune(any());

        ParcelableException thrown = assertThrows(ParcelableException.class, () -> {
            mTunerSessions[0].tune(sel);
        });

        mExpect.withMessage("Unknown error HAL exception when tuning")
                .that(thrown).hasMessageThat().contains(Result.toString(Result.UNKNOWN_ERROR));
    }

    @Test
    public void tune_withClosedTuner_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector sel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        mTunerSessions[0].close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mTunerSessions[0].tune(sel));

        mExpect.withMessage("Exception for tuning on closed tuner").that(thrown).hasMessageThat()
                .contains("Tuner is closed");
    }

    @Test
    public void step_withDirectionUp() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[1];
        ProgramSelector initialSel = TestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo stepUpInfo = TestUtils.makeProgramInfo(
                TestUtils.makeFmSelector(initFreq + AM_FM_FREQUENCY_SPACING), SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = TestUtils.makeHalProgramInfo(
                Convert.programSelectorToHal(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].step(/* directionDown= */ false, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(stepUpInfo);
    }

    @Test
    public void step_withDirectionDown() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[1];
        ProgramSelector initialSel = TestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo stepDownInfo = TestUtils.makeProgramInfo(
                TestUtils.makeFmSelector(initFreq - AM_FM_FREQUENCY_SPACING),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = TestUtils.makeHalProgramInfo(
                Convert.programSelectorToHal(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(stepDownInfo);
    }

    @Test
    public void step_forNonCurrentUser_doesNotStep() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[1];
        ProgramSelector initialSel = TestUtils.makeFmSelector(initFreq);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = TestUtils.makeHalProgramInfo(
                Convert.programSelectorToHal(initialSel), SIGNAL_QUALITY);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(any());
    }

    @Test
    public void step_withHalInInvalidState_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doAnswer(invocation -> Result.INVALID_STATE).when(mHalTunerSessionMock).step(anyBoolean());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);
        });

        mExpect.withMessage("Exception for stepping when HAL is in invalid state")
                .that(thrown).hasMessageThat().contains(Result.toString(Result.INVALID_STATE));
    }

    @Test
    public void seek_withDirectionUp() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[2];
        ProgramSelector initialSel = TestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo seekUpInfo = TestUtils.makeProgramInfo(
                TestUtils.makeFmSelector(getSeekFrequency(initFreq, /* seekDown= */ false)),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = TestUtils.makeHalProgramInfo(
                Convert.programSelectorToHal(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].seek(/* directionDown= */ false, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(seekUpInfo);
    }

    @Test
    public void seek_callsOnTuneFailedWhenTimeout() throws Exception {
        int numSessions = 2;
        openAidlClients(numSessions);

        mTunerSessions[0].seek(/* directionDown= */ false, /* skipSubChannel= */ false);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onTuneFailed(eq(RadioTuner.TUNER_RESULT_TIMEOUT), any());
        }
    }

    @Test
    public void seek_withDirectionDown() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[2];
        ProgramSelector initialSel = TestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo seekUpInfo = TestUtils.makeProgramInfo(
                TestUtils.makeFmSelector(getSeekFrequency(initFreq, /* seekDown= */ true)),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = TestUtils.makeHalProgramInfo(
                Convert.programSelectorToHal(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].seek(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(seekUpInfo);
    }

    @Test
    public void seek_forNonCurrentUser_doesNotSeek() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[2];
        ProgramSelector initialSel = TestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo seekUpInfo = TestUtils.makeProgramInfo(
                TestUtils.makeFmSelector(getSeekFrequency(initFreq, /* seekDown= */ true)),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = TestUtils.makeHalProgramInfo(
                Convert.programSelectorToHal(initialSel), SIGNAL_QUALITY);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].seek(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(seekUpInfo);
    }

    @Test
    public void seek_withInternalErrorFromHal_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doAnswer(invocation -> Result.INTERNAL_ERROR).when(mHalTunerSessionMock)
                .scan(anyBoolean(), anyBoolean());

        ParcelableException thrown = assertThrows(ParcelableException.class, () -> {
            mTunerSessions[0].seek(/* directionDown= */ true, /* skipSubChannel= */ false);
        });

        mExpect.withMessage("Internal error HAL exception when seeking")
                .that(thrown).hasMessageThat().contains(Result.toString(Result.INTERNAL_ERROR));
    }

    @Test
    public void cancel() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].cancel();

        verify(mHalTunerSessionMock).cancel();
    }

    @Test
    public void cancel_forNonCurrentUser_doesNotCancel() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].cancel();

        verify(mHalTunerSessionMock, never()).cancel();
    }

    @Test
    public void cancel_whenHalThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mHalTunerSessionMock).cancel();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].cancel();
        });

        mExpect.withMessage("Exception for canceling when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void getImage_withInvalidId_throwsIllegalArgumentException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int imageId = Constants.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mTunerSessions[0].getImage(imageId);
        });

        mExpect.withMessage("Get image exception")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }

    @Test
    public void getImage_withValidId() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int imageId = 1;

        Bitmap imageTest = mTunerSessions[0].getImage(imageId);

        mExpect.withMessage("Null image").that(imageTest).isEqualTo(null);
    }

    @Test
    public void getImage_whenHalThrowsException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mBroadcastRadioMock).getImage(anyInt());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].getImage(/* id= */ 1);
        });

        mExpect.withMessage("Exception for getting image when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void startBackgroundScan() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].startBackgroundScan();

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onBackgroundScanComplete();
    }

    @Test
    public void startBackgroundScan_forNonCurrentUser_doesNotInvokesCallback() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].startBackgroundScan();

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onBackgroundScanComplete();
    }

    @Test
    public void startProgramListUpdates_forNonCurrentUser_doesNotStartUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].startProgramListUpdates(filter);

        verify(mHalTunerSessionMock, never()).startProgramListUpdates(any());
    }

    @Test
    public void startProgramListUpdates_withUnknownErrorFromHal_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doAnswer(invocation -> Result.UNKNOWN_ERROR).when(mHalTunerSessionMock)
                .startProgramListUpdates(any());

        ParcelableException thrown = assertThrows(ParcelableException.class, () -> {
            mTunerSessions[0].startProgramListUpdates(/* filter= */ null);
        });

        mExpect.withMessage("Unknown error HAL exception when updating program list")
                .that(thrown).hasMessageThat().contains(Result.toString(Result.UNKNOWN_ERROR));
    }

    @Test
    public void stopProgramListUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter aidlFilter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(aidlFilter);

        mTunerSessions[0].stopProgramListUpdates();

        verify(mHalTunerSessionMock).stopProgramListUpdates();
    }

    @Test
    public void stopProgramListUpdates_forNonCurrentUser_doesNotStopUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter aidlFilter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(aidlFilter);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].stopProgramListUpdates();

        verify(mHalTunerSessionMock, never()).stopProgramListUpdates();
    }

    @Test
    public void isConfigFlagSupported_withUnsupportedFlag_returnsFalse() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mHalTunerSessionMock).isConfigFlagSet(eq(flag), any());
        mExpect.withMessage("Config flag %s is supported", flag).that(isSupported).isFalse();
    }

    @Test
    public void isConfigFlagSupported_withSupportedFlag_returnsTrue() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mHalTunerSessionMock).isConfigFlagSet(eq(flag), any());
        mExpect.withMessage("Config flag %s is supported", flag).that(isSupported).isTrue();
    }

    @Test
    public void setConfigFlag_withUnsupportedFlag_throwsRuntimeException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setConfigFlag(flag, /* value= */ true);
        });

        mExpect.withMessage("Exception for setting unsupported flag %s", flag)
                .that(thrown).hasMessageThat().contains("setConfigFlag: NOT_SUPPORTED");
    }

    @Test
    public void setConfigFlag_withFlagSetToTrue() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        mTunerSessions[0].setConfigFlag(flag, /* value= */ true);

        verify(mHalTunerSessionMock).setConfigFlag(flag, /* value= */ true);
    }

    @Test
    public void setConfigFlag_withFlagSetToFalse() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        mTunerSessions[0].setConfigFlag(flag, /* value= */ false);

        verify(mHalTunerSessionMock).setConfigFlag(flag, /* value= */ false);
    }

    @Test
    public void setConfigFlag_forNonCurrentUser_doesNotSetConfigFlag() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].setConfigFlag(flag, /* value= */ true);

        verify(mHalTunerSessionMock, never()).setConfigFlag(flag, /* value= */ true);
    }


    @Test
    public void isConfigFlagSet_withUnsupportedFlag_throwsRuntimeException()
            throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].isConfigFlagSet(flag);
        });

        mExpect.withMessage("Exception for checking if unsupported flag %s is set", flag)
                .that(thrown).hasMessageThat().contains("isConfigFlagSet: NOT_SUPPORTED");
    }

    @Test
    public void isConfigFlagSet_withSupportedFlag() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        boolean expectedConfigFlagValue = true;
        mTunerSessions[0].setConfigFlag(flag, /* value= */ expectedConfigFlagValue);

        boolean isSet = mTunerSessions[0].isConfigFlagSet(flag);

        mExpect.withMessage("Config flag %s is set", flag)
                .that(isSet).isEqualTo(expectedConfigFlagValue);
    }

    @Test
    public void isConfigFlagSet_whenHalThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        doThrow(new RemoteException()).when(mHalTunerSessionMock).isConfigFlagSet(anyInt(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].isConfigFlagSet(flag);
        });

        mExpect.withMessage("Exception for checking config flag when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains("Failed to check flag");
    }

    @Test
    public void setParameters_withMockParameters() throws Exception {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = Map.of("mockParam1", "mockValue1",
                "mockParam2", "mockValue2");

        mTunerSessions[0].setParameters(parametersSet);

        verify(mHalTunerSessionMock).setParameters(Convert.vendorInfoToHal(parametersSet));
    }

    @Test
    public void setParameters_forNonCurrentUser_doesNotSetParameters() throws Exception {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = Map.of("mockParam1", "mockValue1",
                "mockParam2", "mockValue2");
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].setParameters(parametersSet);

        verify(mHalTunerSessionMock, never()).setParameters(any());
    }

    @Test
    public void setParameters_whenHalThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = Map.of("mockParam1", "mockValue1",
                "mockParam2", "mockValue2");
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mHalTunerSessionMock)
                .setParameters(any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setParameters(parametersSet);
        });

        mExpect.withMessage("Exception for setting parameters when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void getParameters_withMockKeys() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ArrayList<String> parameterKeys = new ArrayList<>(Arrays.asList("mockKey1", "mockKey2"));

        mTunerSessions[0].getParameters(parameterKeys);

        verify(mHalTunerSessionMock).getParameters(parameterKeys);
    }

    @Test
    public void getParameters_whenServiceThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        List<String> parameterKeys = List.of("mockKey1", "mockKey2");
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mHalTunerSessionMock)
                .getParameters(any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].getParameters(parameterKeys);
        });

        mExpect.withMessage("Exception for getting parameters when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void onCurrentProgramInfoChanged_withNonCurrentUser_doesNotInvokeCallback()
            throws Exception {
        openAidlClients(1);
        doReturn(USER_ID_2).when(() -> RadioServiceUserController.getCurrentUser());

        mHalTunerCallback.onCurrentProgramInfoChanged(TestUtils.makeHalProgramInfo(
                TestUtils.makeHalFmSelector(/* freq= */ 97300), SIGNAL_QUALITY));

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(any());
    }

    @Test
    public void onConfigFlagUpdated_forTunerCallback() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);

        mHalTunerCallback.onAntennaStateChange(/* connected= */ false);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onAntennaState(/* connected= */ false);
        }
    }

    @Test
    public void onParametersUpdated_forTunerCallback() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        ArrayList<VendorKeyValue> parametersUpdates = new ArrayList<VendorKeyValue>(Arrays.asList(
                TestUtils.makeVendorKeyValue("com.vendor.parameter1", "value1")));
        Map<String, String> parametersExpected = Map.of("com.vendor.parameter1", "value1");

        mHalTunerCallback.onParametersUpdated(parametersUpdates);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onParametersUpdated(parametersExpected);
        }
    }

    @Test
    public void openSession_withNonNullAntennaState() throws Exception {
        boolean antennaConnected = false;
        android.hardware.radio.ITunerCallback callback =
                mock(android.hardware.radio.ITunerCallback.class);
        openAidlClients(/* numClients= */ 1);
        mHalTunerCallback.onAntennaStateChange(antennaConnected);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onAntennaState(antennaConnected);

        mRadioModule.openSession(callback);

        verify(callback, CALLBACK_TIMEOUT).onAntennaState(antennaConnected);
    }

    @Test
    public void openSession_withNonNullCurrentProgramInfo() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = TestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo = TestUtils.makeProgramInfo(initialSel,
                SIGNAL_QUALITY);
        mTunerSessions[0].tune(initialSel);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onCurrentProgramInfoChanged(tuneInfo);
        android.hardware.radio.ITunerCallback callback =
                mock(android.hardware.radio.ITunerCallback.class);

        mRadioModule.openSession(callback);

        verify(callback, CALLBACK_TIMEOUT).onCurrentProgramInfoChanged(tuneInfo);
    }

    private void openAidlClients(int numClients) throws Exception {
        mAidlTunerCallbackMocks = new android.hardware.radio.ITunerCallback[numClients];
        mTunerSessions = new TunerSession[numClients];
        for (int index = 0; index < numClients; index++) {
            mAidlTunerCallbackMocks[index] = mock(android.hardware.radio.ITunerCallback.class);
            mTunerSessions[index] = mRadioModule.openSession(mAidlTunerCallbackMocks[index]);
        }
    }

    private long getSeekFrequency(long currentFrequency, boolean seekDown) {
        long seekFrequency;
        if (seekDown) {
            seekFrequency = AM_FM_FREQUENCY_LIST[AM_FM_FREQUENCY_LIST.length - 1];
            for (int i = AM_FM_FREQUENCY_LIST.length - 1; i >= 0; i--) {
                if (AM_FM_FREQUENCY_LIST[i] < currentFrequency) {
                    seekFrequency = AM_FM_FREQUENCY_LIST[i];
                    break;
                }
            }
        } else {
            seekFrequency = AM_FM_FREQUENCY_LIST[0];
            for (int index = 0; index < AM_FM_FREQUENCY_LIST.length; index++) {
                if (AM_FM_FREQUENCY_LIST[index] > currentFrequency) {
                    seekFrequency = AM_FM_FREQUENCY_LIST[index];
                    break;
                }
            }
        }
        return seekFrequency;
    }
}
