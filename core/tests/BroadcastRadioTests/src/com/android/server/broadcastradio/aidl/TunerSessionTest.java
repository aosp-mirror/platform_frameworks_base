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

import android.Manifest;
import android.app.compat.CompatChanges;
import android.graphics.Bitmap;
import android.hardware.broadcastradio.ConfigFlag;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.broadcastradio.ITunerCallback;
import android.hardware.broadcastradio.IdentifierType;
import android.hardware.broadcastradio.ProgramFilter;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.broadcastradio.Result;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.Flags;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.hardware.radio.UniqueProgramIdentifier;
import android.os.Binder;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;
import com.android.server.broadcastradio.RadioServiceUserController;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.verification.VerificationWithTimeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for AIDL HAL TunerSession.
 */
public final class TunerSessionTest extends ExtendedRadioMockitoTestCase {

    private static final int USER_ID_1 = 11;
    private static final int USER_ID_2 = 12;
    private static final int CALLBACK_TIMEOUT_MS = 200;
    private static final VerificationWithTimeout CALLBACK_TIMEOUT = timeout(CALLBACK_TIMEOUT_MS);
    private static final int SIGNAL_QUALITY = 90;
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

    private static final ProgramSelector.Identifier TEST_FM_FREQUENCY_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                    /* value= */ 88_500);
    private static final RadioManager.ProgramInfo TEST_FM_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                    TEST_FM_FREQUENCY_ID), TEST_FM_FREQUENCY_ID, TEST_FM_FREQUENCY_ID,
            SIGNAL_QUALITY);
    private static final RadioManager.ProgramInfo TEST_FM_INFO_MODIFIED =
            AidlTestUtils.makeProgramInfo(AidlTestUtils.makeProgramSelector(
                    ProgramSelector.PROGRAM_TYPE_FM, TEST_FM_FREQUENCY_ID), TEST_FM_FREQUENCY_ID,
                    TEST_FM_FREQUENCY_ID, /* signalQuality= */ 100);

    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220_352);
    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID_ALT =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220_064);
    private static final ProgramSelector.Identifier TEST_DAB_SID_EXT_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier TEST_DAB_ENSEMBLE_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1001);
    private static final ProgramSelector TEST_DAB_SELECTOR = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_SID_EXT_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID},
            /* vendorIds= */ null);
    private static final ProgramSelector TEST_DAB_SELECTOR_ALT = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_SID_EXT_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID_ALT, TEST_DAB_ENSEMBLE_ID},
            /* vendorIds= */ null);
    private static final UniqueProgramIdentifier TEST_DAB_UNIQUE_ID = new UniqueProgramIdentifier(
            TEST_DAB_SELECTOR);
    private static final UniqueProgramIdentifier TEST_DAB_UNIQUE_ID_ALT =
            new UniqueProgramIdentifier(TEST_DAB_SELECTOR_ALT);
    private static final RadioManager.ProgramInfo TEST_DAB_INFO =
            AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR, TEST_DAB_SID_EXT_ID,
                    TEST_DAB_FREQUENCY_ID, SIGNAL_QUALITY);
    private static final RadioManager.ProgramInfo TEST_DAB_INFO_ALT =
            AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR_ALT, TEST_DAB_SID_EXT_ID,
                    TEST_DAB_FREQUENCY_ID_ALT, SIGNAL_QUALITY);

    // Mocks
    @Mock
    private UserHandle mUserHandleMock;
    @Mock
    private IBroadcastRadio mBroadcastRadioMock;
    private android.hardware.radio.ITunerCallback[] mAidlTunerCallbackMocks;

    // RadioModule under test
    private RadioModule mRadioModule;

    // Objects created by mRadioModule
    private ITunerCallback mHalTunerCallback;
    private ProgramInfo mHalCurrentInfo;
    private final ArrayMap<Integer, Boolean> mHalConfigMap = new ArrayMap<>();

    private TunerSession[] mTunerSessions;

    @Rule
    public final Expect expect = Expect.create();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(RadioServiceUserController.class).spyStatic(CompatChanges.class)
                .spyStatic(Binder.class);
    }

    @Before
    public void setup() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);

        doReturn(true).when(() -> CompatChanges.isChangeEnabled(
                eq(ConversionUtils.RADIO_U_VERSION_REQUIRED), anyInt()));
        doReturn(USER_ID_1).when(mUserHandleMock).getIdentifier();
        doReturn(mUserHandleMock).when(() -> Binder.getCallingUserHandle());
        doReturn(true).when(() -> RadioServiceUserController.isCurrentOrSystemUser());
        doReturn(USER_ID_1).when(() -> RadioServiceUserController.getCurrentUser());

        mRadioModule = new RadioModule(mBroadcastRadioMock,
                AidlTestUtils.makeDefaultModuleProperties());

        doAnswer(invocation -> {
            mHalTunerCallback = (ITunerCallback) invocation.getArguments()[0];
            return null;
        }).when(mBroadcastRadioMock).setTunerCallback(any());
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
            expect.withMessage("Session of index %s close state", index)
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

        expect.withMessage("Session configuration").that(config)
                .isEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void setMuted_withUnmuted() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ false);

        expect.withMessage("Session mute state after setting unmuted")
                .that(mTunerSessions[0].isMuted()).isFalse();
    }

    @Test
    public void setMuted_withMuted() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ true);

        expect.withMessage("Session mute state after setting muted")
                .that(mTunerSessions[0].isMuted()).isTrue();
    }

    @Test
    public void close_withOneSession() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].close();

        expect.withMessage("Close state of broadcast radio service session")
                .that(mTunerSessions[0].isClosed()).isTrue();
        verify(mBroadcastRadioMock).unsetTunerCallback();
    }

    @Test
    public void close_withOnlyOneSession_withMultipleSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        int closeIdx = 0;

        mTunerSessions[closeIdx].close();

        for (int index = 0; index < numSessions; index++) {
            if (index == closeIdx) {
                expect.withMessage(
                        "Close state of broadcast radio service session of index %s", index)
                        .that(mTunerSessions[index].isClosed()).isTrue();
            } else {
                expect.withMessage(
                        "Close state of broadcast radio service session of index %s", index)
                        .that(mTunerSessions[index].isClosed()).isFalse();
            }
        }
        verify(mBroadcastRadioMock, never()).unsetTunerCallback();
    }

    @Test
    public void close_withOneSession_withError() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int errorCode = RadioTuner.ERROR_SERVER_DIED;

        mTunerSessions[0].close(errorCode);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onError(errorCode);
        expect.withMessage("Close state of broadcast radio service session")
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
        expect.withMessage("Close state of broadcast radio service session for multiple times")
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
            expect.withMessage("Close state of broadcast radio service session of index %s", index)
                    .that(mTunerSessions[index].isClosed()).isTrue();
        }
        verify(mBroadcastRadioMock).unsetTunerCallback();
    }

    @Test
    public void tune_withOneSession() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                AidlTestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onCurrentProgramInfoChanged(tuneInfo);
    }

    @Test
    public void tune_withLowerSdkVersion() throws Exception {
        doReturn(false).when(() -> CompatChanges.isChangeEnabled(
                eq(ConversionUtils.RADIO_U_VERSION_REQUIRED), anyInt()));
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                AidlTestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onCurrentProgramInfoChanged(tuneInfo);
    }

    @Test
    public void tune_withMultipleSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                AidlTestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onCurrentProgramInfoChanged(tuneInfo);
        }
    }

    @Test
    public void tune_withUnsupportedSelector_throwsException() throws Exception {
        openAidlClients(/* numClients= */ 1);

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> mTunerSessions[0].tune(TEST_DAB_SELECTOR));

        expect.withMessage("Exception for tuning on unsupported program selector")
                .that(thrown).hasMessageThat().contains("tune: NOT_SUPPORTED");
    }

    @Test
    public void tune_withInvalidSelector_throwsIllegalArgumentException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector.Identifier invalidDabId = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE, /* value= */ 0x1001);
        ProgramSelector invalidSel = new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB,
                invalidDabId, new ProgramSelector.Identifier[0], new long[0]);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mTunerSessions[0].tune(invalidSel));

        expect.withMessage("Exception for tuning on DAB selector without DAB_SID_EXT primary id")
                .that(thrown).hasMessageThat().contains("tune: INVALID_ARGUMENTS");
    }

    @Test
    public void tune_forNonCurrentUser_doesNotTune() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo =
                AidlTestUtils.makeProgramInfo(initialSel, SIGNAL_QUALITY);

        mTunerSessions[0].tune(initialSel);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(tuneInfo);
    }

    @Test
    public void tune_withUnknownErrorFromHal_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector sel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        doThrow(new ServiceSpecificException(Result.UNKNOWN_ERROR))
                .when(mBroadcastRadioMock).tune(any());

        ParcelableException thrown = assertThrows(ParcelableException.class, () -> {
            mTunerSessions[0].tune(sel);
        });

        expect.withMessage("Unknown error HAL exception when tuning")
                .that(thrown).hasMessageThat().contains("UNKNOWN_ERROR");
    }

    @Test
    public void tune_withClosedTuner_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector sel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        mTunerSessions[0].close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mTunerSessions[0].tune(sel));

        expect.withMessage("Exception for tuning on closed tuner").that(thrown).hasMessageThat()
                .contains("Tuner is closed");
    }

    @Test
    public void step_withDirectionUp() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[1];
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo stepUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFmSelector(initFreq + AM_FM_FREQUENCY_SPACING),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].step(/* directionDown= */ false, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(stepUpInfo);
    }

    @Test
    public void step_withDirectionDown() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[1];
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo stepDownInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFmSelector(initFreq - AM_FM_FREQUENCY_SPACING),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(stepDownInfo);
    }

    @Test
    public void step_forNonCurrentUser_doesNotStep() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[1];
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(initFreq);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), SIGNAL_QUALITY);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(any());
    }

    @Test
    public void step_withHalInInvalidState_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doThrow(new ServiceSpecificException(Result.INVALID_STATE))
                .when(mBroadcastRadioMock).step(anyBoolean());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);
        });

        expect.withMessage("Exception for stepping when HAL is in invalid state")
                .that(thrown).hasMessageThat().contains("INVALID_STATE");
    }

    @Test
    public void seek_withDirectionUp() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[2];
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo seekUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFmSelector(getSeekFrequency(initFreq, /* seekDown= */ false)),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), SIGNAL_QUALITY);

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
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo seekUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFmSelector(getSeekFrequency(initFreq, /* seekDown= */ true)),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), SIGNAL_QUALITY);

        mTunerSessions[0].seek(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(seekUpInfo);
    }

    @Test
    public void seek_forNonCurrentUser_doesNotSeek() throws Exception {
        long initFreq = AM_FM_FREQUENCY_LIST[2];
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(initFreq);
        RadioManager.ProgramInfo seekUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFmSelector(getSeekFrequency(initFreq, /* seekDown= */ true)),
                SIGNAL_QUALITY);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), SIGNAL_QUALITY);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].seek(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(seekUpInfo);
    }

    @Test
    public void seek_withInternalErrorFromHal_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doThrow(new ServiceSpecificException(Result.INTERNAL_ERROR))
                .when(mBroadcastRadioMock).seek(anyBoolean(), anyBoolean());

        ParcelableException thrown = assertThrows(ParcelableException.class, () -> {
            mTunerSessions[0].seek(/* directionDown= */ true, /* skipSubChannel= */ false);
        });

        expect.withMessage("Internal error HAL exception when seeking")
                .that(thrown).hasMessageThat().contains("INTERNAL_ERROR");
    }

    @Test
    public void cancel() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].cancel();

        verify(mBroadcastRadioMock).cancel();
    }

    @Test
    public void cancel_forNonCurrentUser_doesNotCancel() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].cancel();

        verify(mBroadcastRadioMock, never()).cancel();
    }

    @Test
    public void cancel_whenHalThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mBroadcastRadioMock).cancel();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].cancel();
        });

        expect.withMessage("Exception for canceling when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void getImage_withInvalidId_throwsIllegalArgumentException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int imageId = IBroadcastRadio.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mTunerSessions[0].getImage(imageId);
        });

        expect.withMessage("Get image exception")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }

    @Test
    public void getImage_withValidId() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int imageId = 1;

        Bitmap imageTest = mTunerSessions[0].getImage(imageId);

        expect.withMessage("Null image").that(imageTest).isEqualTo(null);
    }

    @Test
    public void getImage_whenHalThrowsException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mBroadcastRadioMock)
                .getImage(anyInt());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].getImage(/* id= */ 1);
        });

        expect.withMessage("Exception for getting image when HAL throws remote exception")
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
    public void startProgramListUpdates_withEmptyFilter() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        List<RadioManager.ProgramInfo> modified = List.of(TEST_FM_INFO, TEST_DAB_INFO,
                TEST_DAB_INFO_ALT);
        List<ProgramSelector.Identifier> halRemoved = new ArrayList<>();
        List<UniqueProgramIdentifier> removed = new ArrayList<>();
        ProgramListChunk halProgramList = AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, modified, halRemoved);
        ProgramList.Chunk expectedProgramList =
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true, modified, removed);

        mTunerSessions[0].startProgramListUpdates(filter);
        mHalTunerCallback.onProgramListUpdated(halProgramList);

        verifyHalProgramListUpdatesInvocation(filter);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onProgramListUpdated(expectedProgramList);
    }

    @Test
    public void startProgramListUpdates_withCallbackCalledForMultipleTimes() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        List<RadioManager.ProgramInfo> modifiedInfo = List.of(TEST_FM_INFO, TEST_DAB_INFO,
                TEST_DAB_INFO_ALT);
        mTunerSessions[0].startProgramListUpdates(filter);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, modifiedInfo, new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true, modifiedInfo,
                        new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED),
                List.of(TEST_DAB_SID_EXT_ID)));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED),
                        List.of(TEST_DAB_UNIQUE_ID, TEST_DAB_UNIQUE_ID_ALT)));
    }

    @Test
    public void startProgramListUpdates_withTheSameFilterForMultipleTimes() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        List<RadioManager.ProgramInfo> modifiedInfo = List.of(TEST_FM_INFO, TEST_DAB_INFO,
                TEST_DAB_INFO_ALT);
        mTunerSessions[0].startProgramListUpdates(filter);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, modifiedInfo, new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true, modifiedInfo,
                        new ArrayList<>()));
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED),
                List.of(TEST_DAB_SID_EXT_ID)));
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED),
                        List.of(TEST_DAB_UNIQUE_ID, TEST_DAB_UNIQUE_ID_ALT)));

        mTunerSessions[0].startProgramListUpdates(filter);

        verify(mBroadcastRadioMock).startProgramListUpdates(any());
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED), new ArrayList<>()));
    }

    @Test
    public void startProgramListUpdates_withNullFilter() throws Exception {
        openAidlClients(/* numClients= */ 1);
        List<RadioManager.ProgramInfo> modifiedInfo = List.of(TEST_FM_INFO, TEST_DAB_INFO,
                TEST_DAB_INFO_ALT);

        mTunerSessions[0].startProgramListUpdates(/* filter= */ null);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, modifiedInfo, new ArrayList<>()));

        verify(mBroadcastRadioMock).startProgramListUpdates(any());
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true, modifiedInfo,
                        new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED),
                List.of(TEST_DAB_SID_EXT_ID)));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED),
                        List.of(TEST_DAB_UNIQUE_ID, TEST_DAB_UNIQUE_ID_ALT)));
    }

    @Test
    public void startProgramListUpdates_withIdFilter() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter idFilter = new ProgramList.Filter(new ArraySet<>(),
                Set.of(TEST_DAB_SID_EXT_ID), /* includeCategories= */ true,
                /* excludeModifications= */ true);

        mTunerSessions[0].startProgramListUpdates(idFilter);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_DAB_INFO, TEST_DAB_INFO_ALT),
                new ArrayList<>()));

        verifyHalProgramListUpdatesInvocation(idFilter);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_DAB_INFO, TEST_DAB_INFO_ALT), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(any());
    }

    @Test
    public void startProgramListUpdates_withFilterExcludingModifications() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filterExcludingModifications = new ProgramList.Filter(
                Set.of(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                        ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ true);

        mTunerSessions[0].startProgramListUpdates(filterExcludingModifications);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_DAB_INFO), new ArrayList<>()));

        verifyHalProgramListUpdatesInvocation(filterExcludingModifications);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO, TEST_DAB_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALT),
                new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_DAB_INFO_ALT), new ArrayList<>()));
    }

    @Test
    public void startProgramListUpdates_withFilterIncludingModifications() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filterIncludingModifications = new ProgramList.Filter(
                Set.of(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                        ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);

        mTunerSessions[0].startProgramListUpdates(filterIncludingModifications);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_DAB_INFO), new ArrayList<>()));

        verifyHalProgramListUpdatesInvocation(filterIncludingModifications);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO, TEST_DAB_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALT),
                new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED, TEST_DAB_INFO_ALT), new ArrayList<>()));
    }

    @Test
    public void onProgramListUpdated_afterSessionClosed_doesNotUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(filter);

        mTunerSessions[0].close();

        verify(mBroadcastRadioMock).stopProgramListUpdates();

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onProgramListUpdated(any());
    }

    @Test
    public void startProgramListUpdates_forMultipleSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        ProgramList.Filter fmIdFilter = new ProgramList.Filter(new ArraySet<>(),
                Set.of(TEST_FM_FREQUENCY_ID), /* includeCategories= */ false,
                /* excludeModifications= */ true);
        ProgramList.Filter filterExcludingCategories = new ProgramList.Filter(new ArraySet<>(),
                new ArraySet<>(), /* includeCategories= */ true,
                /* excludeModifications= */ true);
        ProgramList.Filter rdsTypeFilter = new ProgramList.Filter(
                Set.of(ProgramSelector.IDENTIFIER_TYPE_RDS_PI), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);

        mTunerSessions[0].startProgramListUpdates(fmIdFilter);

        ProgramFilter halFilter = ConversionUtils.filterToHalProgramFilter(fmIdFilter);
        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);

        mTunerSessions[1].startProgramListUpdates(filterExcludingCategories);

        halFilter.identifiers = new android.hardware.broadcastradio.ProgramIdentifier[]{};
        halFilter.includeCategories = true;
        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);

        mTunerSessions[2].startProgramListUpdates(rdsTypeFilter);

        halFilter.excludeModifications = false;
        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);
    }

    @Test
    public void onProgramListUpdated_forMultipleSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        List<ProgramList.Filter> filters = List.of(new ProgramList.Filter(
                        Set.of(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT), new ArraySet<>(),
                        /* includeCategories= */ true, /* excludeModifications= */ false),
                new ProgramList.Filter(new ArraySet<>(), Set.of(TEST_FM_FREQUENCY_ID),
                        /* includeCategories= */ false, /* excludeModifications= */ true),
                new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                        /* includeCategories= */ true, /* excludeModifications= */ true));

        for (int index = 0; index < numSessions; index++) {
            mTunerSessions[index].startProgramListUpdates(filters.get(index));
        }

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_DAB_INFO, TEST_DAB_INFO_ALT),
                new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onProgramListUpdated(AidlTestUtils.makeChunk(/* purge= */ false,
                        /* complete= */ true, List.of(TEST_DAB_INFO, TEST_DAB_INFO_ALT),
                        new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[1], CALLBACK_TIMEOUT)
                .onProgramListUpdated(AidlTestUtils.makeChunk(/* purge= */ false,
                        /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[2], CALLBACK_TIMEOUT)
                .onProgramListUpdated(AidlTestUtils.makeChunk(/* purge= */ false,
                        /* complete= */ true, List.of(TEST_DAB_INFO, TEST_DAB_INFO_ALT,
                                TEST_FM_INFO), new ArrayList<>()));
    }

    @Test
    public void startProgramListUpdates_forNonCurrentUser_doesNotStartUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].startProgramListUpdates(filter);

        verify(mBroadcastRadioMock, never()).startProgramListUpdates(any());
    }

    @Test
    public void startProgramListUpdates_withUnknownErrorFromHal_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        doThrow(new ServiceSpecificException(Result.UNKNOWN_ERROR))
                .when(mBroadcastRadioMock).startProgramListUpdates(any());

        ParcelableException thrown = assertThrows(ParcelableException.class, () -> {
            mTunerSessions[0].startProgramListUpdates(/* filter= */ null);
        });

        expect.withMessage("Unknown error HAL exception when updating program list")
                .that(thrown).hasMessageThat().contains("UNKNOWN_ERROR");
    }

    @Test
    public void stopProgramListUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(filter);

        mTunerSessions[0].stopProgramListUpdates();

        verify(mBroadcastRadioMock).stopProgramListUpdates();
    }

    @Test
    public void stopProgramListUpdates_forNonCurrentUser_doesNotStopUpdates() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(filter);
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].stopProgramListUpdates();

        verify(mBroadcastRadioMock, never()).stopProgramListUpdates();
    }

    @Test
    public void isConfigFlagSupported_withUnsupportedFlag_returnsFalse() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mBroadcastRadioMock).isConfigFlagSet(flag);
        expect.withMessage("Config flag %s is supported", flag).that(isSupported).isFalse();
    }

    @Test
    public void isConfigFlagSupported_withSupportedFlag_returnsTrue() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mBroadcastRadioMock).isConfigFlagSet(flag);
        expect.withMessage("Config flag %s is supported", flag).that(isSupported).isTrue();
    }

    @Test
    public void setConfigFlag_withUnsupportedFlag_throwsRuntimeException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setConfigFlag(flag, /* value= */ true);
        });

        expect.withMessage("Exception for setting unsupported flag %s", flag)
                .that(thrown).hasMessageThat().contains("setConfigFlag: NOT_SUPPORTED");
    }

    @Test
    public void setConfigFlag_withFlagSetToTrue() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        mTunerSessions[0].setConfigFlag(flag, /* value= */ true);

        verify(mBroadcastRadioMock).setConfigFlag(flag, /* value= */ true);
    }

    @Test
    public void setConfigFlag_withFlagSetToFalse() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        mTunerSessions[0].setConfigFlag(flag, /* value= */ false);

        verify(mBroadcastRadioMock).setConfigFlag(flag, /* value= */ false);
    }

    @Test
    public void setConfigFlag_forNonCurrentUser_doesNotSetConfigFlag() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].setConfigFlag(flag, /* value= */ true);

        verify(mBroadcastRadioMock, never()).setConfigFlag(flag, /* value= */ true);
    }

    @Test
    public void isConfigFlagSet_withUnsupportedFlag_throwsRuntimeException()
            throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].isConfigFlagSet(flag);
        });

        expect.withMessage("Exception for checking if unsupported flag %s is set", flag)
                .that(thrown).hasMessageThat().contains("isConfigFlagSet: NOT_SUPPORTED");
    }

    @Test
    public void isConfigFlagSet_withSupportedFlag() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        boolean expectedConfigFlagValue = true;
        mTunerSessions[0].setConfigFlag(flag, /* value= */ expectedConfigFlagValue);

        boolean isSet = mTunerSessions[0].isConfigFlagSet(flag);

        expect.withMessage("Config flag %s is set", flag)
                .that(isSet).isEqualTo(expectedConfigFlagValue);
    }

    @Test
    public void isConfigFlagSet_whenHalThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        doThrow(new RemoteException()).when(mBroadcastRadioMock).isConfigFlagSet(anyInt());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].isConfigFlagSet(flag);
        });

        expect.withMessage("Exception for checking config flag when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains("Failed to check flag");
    }

    @Test
    public void setParameters_withMockParameters() throws Exception {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = Map.of("mockParam1", "mockValue1",
                "mockParam2", "mockValue2");

        mTunerSessions[0].setParameters(parametersSet);

        verify(mBroadcastRadioMock).setParameters(
                ConversionUtils.vendorInfoToHalVendorKeyValues(parametersSet));
    }

    @Test
    public void setParameters_forNonCurrentUser_doesNotSetParameters() throws Exception {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = Map.of("mockParam1", "mockValue1",
                "mockParam2", "mockValue2");
        doReturn(false).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mTunerSessions[0].setParameters(parametersSet);

        verify(mBroadcastRadioMock, never()).setParameters(any());
    }

    @Test
    public void setParameters_whenHalThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = Map.of("mockParam1", "mockValue1",
                "mockParam2", "mockValue2");
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mBroadcastRadioMock)
                .setParameters(any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setParameters(parametersSet);
        });

        expect.withMessage("Exception for setting parameters when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void getParameters_withMockKeys() throws Exception {
        openAidlClients(/* numClients= */ 1);
        List<String> parameterKeys = List.of("mockKey1", "mockKey2");

        mTunerSessions[0].getParameters(parameterKeys);

        verify(mBroadcastRadioMock).getParameters(parameterKeys.toArray(new String[0]));
    }

    @Test
    public void getParameters_whenServiceThrowsRemoteException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        List<String> parameterKeys = List.of("mockKey1", "mockKey2");
        String exceptionMessage = "HAL service died.";
        doThrow(new RemoteException(exceptionMessage)).when(mBroadcastRadioMock)
                .getParameters(any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].getParameters(parameterKeys);
        });

        expect.withMessage("Exception for getting parameters when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void onCurrentProgramInfoChanged_withNonCurrentUser_doesNotInvokeCallback()
            throws Exception {
        openAidlClients(1);
        doReturn(USER_ID_2).when(() -> RadioServiceUserController.getCurrentUser());

        mHalTunerCallback.onCurrentProgramInfoChanged(AidlTestUtils.makeHalProgramInfo(
                AidlTestUtils.makeHalFmSelector(AM_FM_FREQUENCY_LIST[1]), SIGNAL_QUALITY));

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).times(0))
                .onCurrentProgramInfoChanged(any());
    }

    @Test
    public void onCurrentProgramInfoChanged_withLowerSdkVersion_doesNotInvokesCallback()
            throws Exception {
        doReturn(false).when(() -> CompatChanges.isChangeEnabled(
                eq(ConversionUtils.RADIO_U_VERSION_REQUIRED), anyInt()));
        openAidlClients(/* numClients= */ 1);

        mHalTunerCallback.onCurrentProgramInfoChanged(
                AidlTestUtils.programInfoToHalProgramInfo(TEST_DAB_INFO));

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).never())
                .onCurrentProgramInfoChanged(any());
    }

    @Test
    public void onTuneFailed_forTunerCallback() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        android.hardware.broadcastradio.ProgramSelector halSel = AidlTestUtils.makeHalFmSelector(
                AM_FM_FREQUENCY_LIST[1]);
        ProgramSelector sel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);

        mHalTunerCallback.onTuneFailed(Result.CANCELED, halSel);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onTuneFailed(RadioTuner.TUNER_RESULT_CANCELED, sel);
        }
    }

    @Test
    public void onTuneFailed_withLowerSdkVersion_doesNotInvokesCallback()
            throws Exception {
        doReturn(false).when(() -> CompatChanges.isChangeEnabled(
                eq(ConversionUtils.RADIO_U_VERSION_REQUIRED), anyInt()));
        openAidlClients(/* numClients= */ 1);

        mHalTunerCallback.onTuneFailed(Result.CANCELED,
                ConversionUtils.programSelectorToHalProgramSelector(TEST_DAB_SELECTOR));

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).never())
                .onTuneFailed(anyInt(), any());
    }

    @Test
    public void onAntennaStateChange_forTunerCallback() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);

        mHalTunerCallback.onAntennaStateChange(/* connected= */ false);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onAntennaState(/* connected= */ false);
        }
    }

    @Test
    public void onConfigFlagUpdated_withRequiredFlagEnabled_invokesCallbacks() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        openAidlClients(/* numClients= */ 1);

        mHalTunerCallback.onConfigFlagUpdated(ConfigFlag.FORCE_ANALOG_FM, true);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onConfigFlagUpdated(RadioManager.CONFIG_FORCE_ANALOG_FM, true);
    }

    @Test
    public void onConfigFlagUpdated_withRequiredFlagDisabled_doesNotInvokeCallbacks()
            throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        openAidlClients(/* numClients= */ 1);

        mHalTunerCallback.onConfigFlagUpdated(ConfigFlag.FORCE_ANALOG_FM, true);

        verify(mAidlTunerCallbackMocks[0], after(CALLBACK_TIMEOUT_MS).never())
                .onConfigFlagUpdated(RadioManager.CONFIG_FORCE_ANALOG_FM, true);
    }

    @Test
    public void onConfigFlagUpdated_withMultipleTunerSessions() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        boolean configFlagValue = true;

        mHalTunerCallback.onConfigFlagUpdated(flag, configFlagValue);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onConfigFlagUpdated(flag, configFlagValue);
        }
    }

    @Test
    public void onParametersUpdated_forTunerCallback() throws Exception {
        int numSessions = 3;
        openAidlClients(numSessions);
        VendorKeyValue[] parametersUpdates = {
                AidlTestUtils.makeVendorKeyValue("com.vendor.parameter1", "value1")};
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
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        RadioManager.ProgramInfo tuneInfo = AidlTestUtils.makeProgramInfo(initialSel,
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
        setupMockedHalTunerSession();
    }

    private void setupMockedHalTunerSession() throws Exception {
        expect.withMessage("Registered HAL tuner callback").that(mHalTunerCallback)
                .isNotNull();

        doAnswer(invocation -> {
            android.hardware.broadcastradio.ProgramSelector halSel =
                    (android.hardware.broadcastradio.ProgramSelector) invocation.getArguments()[0];
            mHalCurrentInfo = AidlTestUtils.makeHalProgramInfo(halSel, SIGNAL_QUALITY);
            if (halSel.primaryId.type != IdentifierType.AMFM_FREQUENCY_KHZ) {
                throw new ServiceSpecificException(Result.NOT_SUPPORTED);
            }
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return Result.OK;
        }).when(mBroadcastRadioMock).tune(any());

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
        }).when(mBroadcastRadioMock).step(anyBoolean());

        doAnswer(invocation -> {
            if (mHalCurrentInfo == null) {
                android.hardware.broadcastradio.ProgramSelector placeHolderSelector =
                        AidlTestUtils.makeHalFmSelector(/* freq= */ 97300);

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
        }).when(mBroadcastRadioMock).seek(anyBoolean(), anyBoolean());

        doReturn(null).when(mBroadcastRadioMock).getImage(anyInt());

        doAnswer(invocation -> {
            int configFlag = (int) invocation.getArguments()[0];
            if (configFlag == UNSUPPORTED_CONFIG_FLAG) {
                throw new ServiceSpecificException(Result.NOT_SUPPORTED);
            }
            return mHalConfigMap.getOrDefault(configFlag, false);
        }).when(mBroadcastRadioMock).isConfigFlagSet(anyInt());

        doAnswer(invocation -> {
            int configFlag = (int) invocation.getArguments()[0];
            if (configFlag == UNSUPPORTED_CONFIG_FLAG) {
                throw new ServiceSpecificException(Result.NOT_SUPPORTED);
            }
            mHalConfigMap.put(configFlag, (boolean) invocation.getArguments()[1]);
            return null;
        }).when(mBroadcastRadioMock).setConfigFlag(anyInt(), anyBoolean());
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

    private void verifyHalProgramListUpdatesInvocation(ProgramList.Filter filter) throws Exception {
        ProgramFilter halFilterExpected = ConversionUtils.filterToHalProgramFilter(filter);
        ArgumentCaptor<ProgramFilter> halFilterCaptor = ArgumentCaptor.forClass(
                ProgramFilter.class);
        verify(mBroadcastRadioMock).startProgramListUpdates(halFilterCaptor.capture());
        ProgramFilter halFilterInvoked = halFilterCaptor.getValue();
        expect.withMessage("Filtered identifier types").that(
                halFilterInvoked.identifierTypes).asList().containsExactlyElementsIn(Arrays.stream(
                        halFilterExpected.identifierTypes).boxed().toArray(Integer[]::new));
        expect.withMessage("Filtered identifiers").that(
                halFilterInvoked.identifiers).asList()
                .containsExactlyElementsIn(halFilterExpected.identifiers);
        expect.withMessage("Categories-included filter")
                .that(halFilterInvoked.includeCategories)
                .isEqualTo(halFilterExpected.includeCategories);
        expect.withMessage("Modifications-excluded filter")
                .that(halFilterInvoked.excludeModifications)
                .isEqualTo(halFilterExpected.excludeModifications);
    }
}
