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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.broadcastradio.ITunerCallback;
import android.hardware.broadcastradio.IdentifierType;
import android.hardware.broadcastradio.ProgramFilter;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.broadcastradio.Result;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.os.Build;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;
import com.android.server.broadcastradio.RadioServiceUserController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.verification.VerificationWithTimeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for AIDL HAL TunerSession.
 */
public final class TunerSessionTest extends ExtendedRadioMockitoTestCase {

    private static final int TARGET_SDK_VERSION = Build.VERSION_CODES.CUR_DEVELOPMENT;
    private static final VerificationWithTimeout CALLBACK_TIMEOUT =
            timeout(/* millis= */ 200);
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
    private static final ProgramSelector.Identifier TEST_RDS_PI_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_RDS_PI,
                    /* value= */ 15_019);

    private static final RadioManager.ProgramInfo TEST_FM_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                    TEST_FM_FREQUENCY_ID), TEST_FM_FREQUENCY_ID, TEST_FM_FREQUENCY_ID,
            SIGNAL_QUALITY);
    private static final RadioManager.ProgramInfo TEST_FM_INFO_MODIFIED =
            AidlTestUtils.makeProgramInfo(AidlTestUtils.makeProgramSelector(
                    ProgramSelector.PROGRAM_TYPE_FM, TEST_FM_FREQUENCY_ID), TEST_FM_FREQUENCY_ID,
                    TEST_FM_FREQUENCY_ID, /* signalQuality= */ 100);
    private static final RadioManager.ProgramInfo TEST_RDS_INFO = AidlTestUtils.makeProgramInfo(
            AidlTestUtils.makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM, TEST_RDS_PI_ID),
            TEST_RDS_PI_ID, new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 89_500),
            SIGNAL_QUALITY);

    // Mocks
    @Mock private IBroadcastRadio mBroadcastRadioMock;
    private android.hardware.radio.ITunerCallback[] mAidlTunerCallbackMocks;

    // RadioModule under test
    private RadioModule mRadioModule;

    // Objects created by mRadioModule
    private ITunerCallback mHalTunerCallback;
    private ProgramInfo mHalCurrentInfo;
    private final ArrayMap<Integer, Boolean> mHalConfigMap = new ArrayMap<>();

    private TunerSession[] mTunerSessions;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(RadioServiceUserController.class);
    }

    @Before
    public void setup() throws Exception {
        doReturn(true).when(() -> RadioServiceUserController.isCurrentOrSystemUser());

        mRadioModule = new RadioModule(mBroadcastRadioMock,
                AidlTestUtils.makeDefaultModuleProperties());

        doAnswer(invocation -> {
            mHalTunerCallback = (ITunerCallback) invocation.getArguments()[0];
            return null;
        }).when(mBroadcastRadioMock).setTunerCallback(any());
        mRadioModule.setInternalHalCallback();

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

        when(mBroadcastRadioMock.getImage(anyInt())).thenReturn(null);

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

    @After
    public void cleanUp() {
        mHalConfigMap.clear();
    }

    @Test
    public void openSession_withMultipleSessions() throws Exception {
        int numSessions = 3;

        openAidlClients(numSessions);

        for (int index = 0; index < numSessions; index++) {
            assertWithMessage("Session of index %s close state", index)
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

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT.times(0))
                .onConfigurationChanged(FM_BAND_CONFIG);
    }

    @Test
    public void getConfiguration() throws Exception {
        openAidlClients(/* numClients= */ 1);
        mTunerSessions[0].setConfiguration(FM_BAND_CONFIG);

        RadioManager.BandConfig config = mTunerSessions[0].getConfiguration();

        assertWithMessage("Session configuration").that(config)
                .isEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void setMuted_withUnmuted() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ false);

        assertWithMessage("Session mute state after setting unmuted")
                .that(mTunerSessions[0].isMuted()).isFalse();
    }

    @Test
    public void setMuted_withMuted() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ true);

        assertWithMessage("Session mute state after setting muted")
                .that(mTunerSessions[0].isMuted()).isTrue();
    }

    @Test
    public void close_withOneSession() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].close();

        assertWithMessage("Close state of broadcast radio service session")
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
                assertWithMessage(
                        "Close state of broadcast radio service session of index %s", index)
                        .that(mTunerSessions[index].isClosed()).isTrue();
            } else {
                assertWithMessage(
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
        assertWithMessage("Close state of broadcast radio service session")
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
            assertWithMessage("Close state of broadcast radio service session of index %s", index)
                    .that(mTunerSessions[index].isClosed()).isTrue();
        }
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
        openAidlClients(/* numClients= */ 1, Build.VERSION_CODES.TIRAMISU);
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
        ProgramSelector.Identifier dabPrimaryId =
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                        /* value= */ 0xA000000111L);
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

        assertWithMessage("Exception for tuning on unsupported program selector")
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

        assertWithMessage("Exception for tuning on DAB selector without DAB_SID_EXT primary id")
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

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT.times(0))
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

        assertWithMessage("Unknown error HAL exception when tuning")
                .that(thrown).hasMessageThat().contains("UNKNOWN_ERROR");
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

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT.times(0))
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

        assertWithMessage("Exception for stepping when HAL is in invalid state")
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

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT.times(0))
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

        assertWithMessage("Internal error HAL exception when seeking")
                .that(thrown).hasMessageThat().contains("INTERNAL_ERROR");
    }

    @Test
    public void cancel() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        mTunerSessions[0].tune(initialSel);

        mTunerSessions[0].cancel();

        verify(mBroadcastRadioMock).cancel();
    }

    @Test
    public void cancel_forNonCurrentUser_doesNotCancel() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = AidlTestUtils.makeFmSelector(AM_FM_FREQUENCY_LIST[1]);
        mTunerSessions[0].tune(initialSel);
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

        assertWithMessage("Exception for canceling when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void getImage_withInvalidId_throwsIllegalArgumentException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int imageId = IBroadcastRadio.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mTunerSessions[0].getImage(imageId);
        });

        assertWithMessage("Get image exception")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }

    @Test
    public void getImage_withValidId() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int imageId = 1;

        Bitmap imageTest = mTunerSessions[0].getImage(imageId);

        assertWithMessage("Null image").that(imageTest).isEqualTo(null);
    }

    @Test
    public void getImage_whenHalThrowsException_fails() throws Exception {
        openAidlClients(/* numClients= */ 1);
        String exceptionMessage = "HAL service died.";
        when(mBroadcastRadioMock.getImage(anyInt()))
                .thenThrow(new RemoteException(exceptionMessage));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].getImage(/* id= */ 1);
        });

        assertWithMessage("Exception for getting image when HAL throws remote exception")
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

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT.times(0)).onBackgroundScanComplete();
    }

    @Test
    public void startProgramListUpdates_withEmptyFilter() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        ProgramFilter halFilter = ConversionUtils.filterToHalProgramFilter(filter);
        List<RadioManager.ProgramInfo> modified = List.of(TEST_FM_INFO, TEST_RDS_INFO);
        List<ProgramSelector.Identifier> removed = new ArrayList<>();
        ProgramListChunk halProgramList = AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, modified, removed);
        ProgramList.Chunk expectedProgramList =
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true, modified, removed);

        mTunerSessions[0].startProgramListUpdates(filter);
        mHalTunerCallback.onProgramListUpdated(halProgramList);

        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onProgramListUpdated(expectedProgramList);
    }

    @Test
    public void startProgramListUpdates_withCallbackCalledForMultipleTimes() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(filter);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true,
                        List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED), List.of(TEST_RDS_PI_ID)));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED), List.of(TEST_RDS_PI_ID)));
    }

    @Test
    public void startProgramListUpdates_withTheSameFilterForMultipleTimes() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(filter);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true,
                        List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED), List.of(TEST_RDS_PI_ID)));
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED), List.of(TEST_RDS_PI_ID)));

        mTunerSessions[0].startProgramListUpdates(filter);

        verify(mBroadcastRadioMock).startProgramListUpdates(any());
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED), new ArrayList<>()));
    }

    @Test
    public void startProgramListUpdates_withNullFilter() throws Exception {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].startProgramListUpdates(/* filter= */ null);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ true,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));

        verify(mBroadcastRadioMock).startProgramListUpdates(any());
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ true, /* complete= */ true,
                        List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED), List.of(TEST_RDS_PI_ID)));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED), List.of(TEST_RDS_PI_ID)));
    }

    @Test
    public void startProgramListUpdates_withIdFilter() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter idFilter = new ProgramList.Filter(new ArraySet<>(),
                Set.of(TEST_RDS_PI_ID), /* includeCategories= */ true,
                /* excludeModifications= */ true);
        ProgramFilter halFilter = ConversionUtils.filterToHalProgramFilter(idFilter);

        mTunerSessions[0].startProgramListUpdates(idFilter);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_RDS_INFO), new ArrayList<>()));

        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_RDS_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(any());
    }

    @Test
    public void startProgramListUpdates_withFilterExcludingModifications() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filterExcludingModifications = new ProgramList.Filter(
                Set.of(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ true);
        ProgramFilter halFilter =
                ConversionUtils.filterToHalProgramFilter(filterExcludingModifications);

        mTunerSessions[0].startProgramListUpdates(filterExcludingModifications);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));

        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED), new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(any());
    }

    @Test
    public void startProgramListUpdates_withFilterIncludingModifications() throws Exception {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter filterIncludingModifications = new ProgramList.Filter(
                Set.of(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        ProgramFilter halFilter =
                ConversionUtils.filterToHalProgramFilter(filterIncludingModifications);

        mTunerSessions[0].startProgramListUpdates(filterIncludingModifications);
        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));

        verify(mBroadcastRadioMock).startProgramListUpdates(halFilter);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO), new ArrayList<>()));

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO_MODIFIED), new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onProgramListUpdated(
                AidlTestUtils.makeChunk(/* purge= */ false, /* complete= */ true,
                        List.of(TEST_FM_INFO_MODIFIED), new ArrayList<>()));
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

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT.times(0)).onProgramListUpdated(any());
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
                        Set.of(ProgramSelector.IDENTIFIER_TYPE_RDS_PI), new ArraySet<>(),
                        /* includeCategories= */ true, /* excludeModifications= */ false),
                new ProgramList.Filter(new ArraySet<>(), Set.of(TEST_FM_FREQUENCY_ID),
                        /* includeCategories= */ false, /* excludeModifications= */ true),
                new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                        /* includeCategories= */ true, /* excludeModifications= */ true));

        for (int index = 0; index < numSessions; index++) {
            mTunerSessions[index].startProgramListUpdates(filters.get(index));
        }

        mHalTunerCallback.onProgramListUpdated(AidlTestUtils.makeHalChunk(/* purge= */ false,
                /* complete= */ true, List.of(TEST_FM_INFO, TEST_RDS_INFO), new ArrayList<>()));

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onProgramListUpdated(AidlTestUtils.makeChunk(/* purge= */ false,
                        /* complete= */ true, List.of(TEST_RDS_INFO), new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[1], CALLBACK_TIMEOUT)
                .onProgramListUpdated(AidlTestUtils.makeChunk(/* purge= */ false,
                        /* complete= */ true, List.of(TEST_FM_INFO), new ArrayList<>()));
        verify(mAidlTunerCallbackMocks[2], CALLBACK_TIMEOUT)
                .onProgramListUpdated(AidlTestUtils.makeChunk(/* purge= */ false,
                        /* complete= */ true, List.of(TEST_RDS_INFO, TEST_FM_INFO),
                        new ArrayList<>()));
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

        assertWithMessage("Unknown error HAL exception when updating program list")
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
        assertWithMessage("Config flag %s is supported", flag).that(isSupported).isFalse();
    }

    @Test
    public void isConfigFlagSupported_withSupportedFlag_returnsTrue() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mBroadcastRadioMock).isConfigFlagSet(flag);
        assertWithMessage("Config flag %s is supported", flag).that(isSupported).isTrue();
    }

    @Test
    public void setConfigFlag_withUnsupportedFlag_throwsRuntimeException() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setConfigFlag(flag, /* value= */ true);
        });

        assertWithMessage("Exception for setting unsupported flag %s", flag)
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

        assertWithMessage("Exception for checking if unsupported flag %s is set", flag)
                .that(thrown).hasMessageThat().contains("isConfigFlagSet: NOT_SUPPORTED");
    }

    @Test
    public void isConfigFlagSet_withSupportedFlag() throws Exception {
        openAidlClients(/* numClients= */ 1);
        int flag = UNSUPPORTED_CONFIG_FLAG + 1;
        boolean expectedConfigFlagValue = true;
        mTunerSessions[0].setConfigFlag(flag, /* value= */ expectedConfigFlagValue);

        boolean isSet = mTunerSessions[0].isConfigFlagSet(flag);

        assertWithMessage("Config flag %s is set", flag)
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

        assertWithMessage("Exception for checking config flag when HAL throws remote exception")
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
        when(mBroadcastRadioMock.setParameters(any()))
                .thenThrow(new RemoteException(exceptionMessage));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setParameters(parametersSet);
        });

        assertWithMessage("Exception for setting parameters when HAL throws remote exception")
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
        when(mBroadcastRadioMock.getParameters(any()))
                .thenThrow(new RemoteException(exceptionMessage));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].getParameters(parameterKeys);
        });

        assertWithMessage("Exception for getting parameters when HAL throws remote exception")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
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
    public void onConfigFlagUpdated_forTunerCallback() throws Exception {
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
    private void openAidlClients(int numClients) throws Exception {
        openAidlClients(numClients, TARGET_SDK_VERSION);
    }

    private void openAidlClients(int numClients, int targetSdkVersion) throws Exception {
        mAidlTunerCallbackMocks = new android.hardware.radio.ITunerCallback[numClients];
        mTunerSessions = new TunerSession[numClients];
        for (int index = 0; index < numClients; index++) {
            mAidlTunerCallbackMocks[index] = mock(android.hardware.radio.ITunerCallback.class);
            mTunerSessions[index] = mRadioModule.openSession(mAidlTunerCallbackMocks[index],
                    targetSdkVersion);
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
