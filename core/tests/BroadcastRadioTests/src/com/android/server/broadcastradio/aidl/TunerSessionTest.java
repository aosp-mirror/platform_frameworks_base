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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.broadcastradio.ITunerCallback;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.Result;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.ArraySet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.verification.VerificationWithTimeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests for AIDL HAL TunerSession.
 */
@RunWith(MockitoJUnitRunner.class)
public final class TunerSessionTest {
    private static final VerificationWithTimeout CALLBACK_TIMEOUT =
            timeout(/* millis= */ 200);

    private final int mSignalQuality = 1;
    private final long mAmfmFrequencySpacing = 500;
    private final long[] mAmfmFrequencyList = {97500, 98100, 99100};
    private final RadioManager.FmBandDescriptor mFmBandDescriptor =
            new RadioManager.FmBandDescriptor(RadioManager.REGION_ITU_1, RadioManager.BAND_FM,
                    /* lowerLimit= */ 87500, /* upperLimit= */ 108000, /* spacing= */ 100,
                    /* stereo= */ false, /* rds= */ false, /* ta= */ false, /* af= */ false,
                    /* ea= */ false);
    private final RadioManager.BandConfig mFmBandConfig =
            new RadioManager.FmBandConfig(mFmBandDescriptor);

    // Mocks
    @Mock private IBroadcastRadio mBroadcastRadioMock;
    private android.hardware.radio.ITunerCallback[] mAidlTunerCallbackMocks;

    private final Object mLock = new Object();
    // RadioModule under test
    private RadioModule mRadioModule;

    // Objects created by mRadioModule
    private ITunerCallback mHalTunerCallback;
    private ProgramInfo mHalCurrentInfo;
    private final int mUnsupportedConfigFlag = 0;
    private final ArrayMap<Integer, Boolean> mHalConfigMap = new ArrayMap<>();

    private TunerSession[] mTunerSessions;

    @Before
    public void setup() throws RemoteException {
        mRadioModule = new RadioModule(mBroadcastRadioMock, new RadioManager.ModuleProperties(
                /* id= */ 0, /* serviceName= */ "", /* classId= */ 0, /* implementor= */ "",
                /* product= */ "", /* version= */ "", /* serial= */ "", /* numTuners= */ 0,
                /* numAudioSources= */ 0, /* isInitializationRequired= */ false,
                /* isCaptureSupported= */ false, /* bands= */ null, /* isBgScanSupported= */ false,
                new int[] {}, new int[] {},
                /* dabFrequencyTable= */ null, /* vendorInfo= */ null), mLock);

        doAnswer(invocation -> {
            mHalTunerCallback = (ITunerCallback) invocation.getArguments()[0];
            return null;
        }).when(mBroadcastRadioMock).setTunerCallback(any());
        mRadioModule.setInternalHalCallback();

        doAnswer(invocation -> {
            mHalCurrentInfo = AidlTestUtils.makeHalProgramSelector(
                    (android.hardware.broadcastradio.ProgramSelector) invocation.getArguments()[0],
                    mSignalQuality);
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return null;
        }).when(mBroadcastRadioMock).tune(any());

        doAnswer(invocation -> {
            if ((boolean) invocation.getArguments()[0]) {
                mHalCurrentInfo.selector.primaryId.value += mAmfmFrequencySpacing;
            } else {
                mHalCurrentInfo.selector.primaryId.value -= mAmfmFrequencySpacing;
            }
            mHalCurrentInfo.logicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalCurrentInfo.physicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return null;
        }).when(mBroadcastRadioMock).step(anyBoolean());

        doAnswer(invocation -> {
            mHalCurrentInfo.selector.primaryId.value = getSeekFrequency(
                    mHalCurrentInfo.selector.primaryId.value,
                    !(boolean) invocation.getArguments()[0]);
            mHalCurrentInfo.logicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalCurrentInfo.physicallyTunedTo = mHalCurrentInfo.selector.primaryId;
            mHalTunerCallback.onCurrentProgramInfoChanged(mHalCurrentInfo);
            return null;
        }).when(mBroadcastRadioMock).seek(anyBoolean(), anyBoolean());

        when(mBroadcastRadioMock.getImage(anyInt())).thenReturn(null);

        mHalConfigMap.clear();
        doAnswer(invocation -> {
            int configFlag = (int) invocation.getArguments()[0];
            if (configFlag == mUnsupportedConfigFlag) {
                throw new ServiceSpecificException(Result.NOT_SUPPORTED);
            }
            return mHalConfigMap.getOrDefault(configFlag, false);
        }).when(mBroadcastRadioMock).isConfigFlagSet(anyInt());
        doAnswer(invocation -> {
            int configFlag = (int) invocation.getArguments()[0];
            if (configFlag == mUnsupportedConfigFlag) {
                throw new ServiceSpecificException(Result.NOT_SUPPORTED);
            }
            mHalConfigMap.put(configFlag, (boolean) invocation.getArguments()[1]);
            return null;
        }).when(mBroadcastRadioMock).setConfigFlag(anyInt(), anyBoolean());
    }

    @Test
    public void openSession_withMultipleSessions() throws RemoteException {
        int numSessions = 3;

        openAidlClients(numSessions);

        for (int index = 0; index < numSessions; index++) {
            assertWithMessage("Session of index %s close state", index)
                    .that(mTunerSessions[index].isClosed()).isFalse();
        }
    }

    @Test
    public void setConfiguration() throws RemoteException {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setConfiguration(mFmBandConfig);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onConfigurationChanged(mFmBandConfig);
    }

    @Test
    public void getConfiguration() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        mTunerSessions[0].setConfiguration(mFmBandConfig);

        RadioManager.BandConfig config = mTunerSessions[0].getConfiguration();

        assertWithMessage("Session configuration").that(config)
                .isEqualTo(mFmBandConfig);
    }

    @Test
    public void setMuted_withUnmuted() throws RemoteException {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ false);

        assertWithMessage("Session mute state after setting muted %s", false)
                .that(mTunerSessions[0].isMuted()).isFalse();
    }

    @Test
    public void setMuted_withMuted() throws RemoteException {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].setMuted(/* mute= */ true);

        assertWithMessage("Session mute state after setting muted %s", true)
                .that(mTunerSessions[0].isMuted()).isTrue();
    }

    @Test
    public void close_withOneSession() throws RemoteException {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].close();

        assertWithMessage("Close state of broadcast radio service session")
                .that(mTunerSessions[0].isClosed()).isTrue();
    }

    @Test
    public void close_withOnlyOneSession_withMultipleSessions() throws RemoteException {
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
    public void close_withOneSession_withError() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int errorCode = RadioTuner.ERROR_SERVER_DIED;

        mTunerSessions[0].close(errorCode);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onError(errorCode);
        assertWithMessage("Close state of broadcast radio service session")
                .that(mTunerSessions[0].isClosed()).isTrue();
    }

    @Test
    public void closeSessions_withMultipleSessions_withError() throws RemoteException {
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
    public void tune_withOneSession() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(mAmfmFrequencyList[1]);
        RadioManager.ProgramInfo tuneInfo =
                AidlTestUtils.makeProgramInfo(initialSel, mSignalQuality);

        mTunerSessions[0].tune(initialSel);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onCurrentProgramInfoChanged(tuneInfo);
    }

    @Test
    public void tune_withMultipleSessions() throws RemoteException {
        int numSessions = 3;
        openAidlClients(numSessions);
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(mAmfmFrequencyList[1]);
        RadioManager.ProgramInfo tuneInfo =
                AidlTestUtils.makeProgramInfo(initialSel, mSignalQuality);

        mTunerSessions[0].tune(initialSel);

        for (int index = 0; index < numSessions; index++) {
            verify(mAidlTunerCallbackMocks[index], CALLBACK_TIMEOUT)
                    .onCurrentProgramInfoChanged(tuneInfo);
        }
    }

    @Test
    public void step_withDirectionUp() throws RemoteException {
        long initFreq = mAmfmFrequencyList[1];
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(initFreq);
        RadioManager.ProgramInfo stepUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFMSelector(initFreq + mAmfmFrequencySpacing),
                mSignalQuality);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramSelector(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), mSignalQuality);

        mTunerSessions[0].step(/* directionDown= */ false, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(stepUpInfo);
    }

    @Test
    public void step_withDirectionDown() throws RemoteException {
        long initFreq = mAmfmFrequencyList[1];
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(initFreq);
        RadioManager.ProgramInfo stepDownInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFMSelector(initFreq - mAmfmFrequencySpacing),
                mSignalQuality);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramSelector(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), mSignalQuality);

        mTunerSessions[0].step(/* directionDown= */ true, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(stepDownInfo);
    }

    @Test
    public void scan_withDirectionUp() throws RemoteException {
        long initFreq = mAmfmFrequencyList[2];
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(initFreq);
        RadioManager.ProgramInfo scanUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFMSelector(getSeekFrequency(initFreq, /* seekDown= */ false)),
                mSignalQuality);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramSelector(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), mSignalQuality);

        mTunerSessions[0].scan(/* directionDown= */ false, /* skipSubChannel= */ false);

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(scanUpInfo);
    }

    @Test
    public void scan_withDirectionDown() throws RemoteException {
        long initFreq = mAmfmFrequencyList[2];
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(initFreq);
        RadioManager.ProgramInfo scanUpInfo = AidlTestUtils.makeProgramInfo(
                AidlTestUtils.makeFMSelector(getSeekFrequency(initFreq, /* seekDown= */ true)),
                mSignalQuality);
        openAidlClients(/* numClients= */ 1);
        mHalCurrentInfo = AidlTestUtils.makeHalProgramSelector(
                ConversionUtils.programSelectorToHalProgramSelector(initialSel), mSignalQuality);

        mTunerSessions[0].scan(/* directionDown= */ true, /* skipSubChannel= */ false);
        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT)
                .onCurrentProgramInfoChanged(scanUpInfo);
    }

    @Test
    public void cancel() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        ProgramSelector initialSel = AidlTestUtils.makeFMSelector(mAmfmFrequencyList[1]);
        mTunerSessions[0].tune(initialSel);

        mTunerSessions[0].cancel();

        verify(mBroadcastRadioMock).cancel();
    }

    @Test
    public void getImage_withInvalidId_throwsIllegalArgumentException() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int imageId = IBroadcastRadio.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mTunerSessions[0].getImage(imageId);
        });

        assertWithMessage("Exception for getting image with invalid ID")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }

    @Test
    public void getImage_withValidId() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int imageId = 1;

        Bitmap imageTest = mTunerSessions[0].getImage(imageId);

        assertWithMessage("Null image").that(imageTest).isEqualTo(null);
    }

    @Test
    public void startBackgroundScan() throws RemoteException {
        openAidlClients(/* numClients= */ 1);

        mTunerSessions[0].startBackgroundScan();

        verify(mAidlTunerCallbackMocks[0], CALLBACK_TIMEOUT).onBackgroundScanComplete();
    }

    @Test
    public void stopProgramListUpdates() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        ProgramList.Filter aidlFilter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
        mTunerSessions[0].startProgramListUpdates(aidlFilter);

        mTunerSessions[0].stopProgramListUpdates();

        verify(mBroadcastRadioMock).stopProgramListUpdates();
    }

    @Test
    public void isConfigFlagSupported_withUnsupportedFlag_returnsFalse() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mBroadcastRadioMock).isConfigFlagSet(flag);
        assertWithMessage("Config  flag %s is supported", flag).that(isSupported).isFalse();
    }

    @Test
    public void isConfigFlagSupported_withSupportedFlag_returnsTrue() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag + 1;

        boolean isSupported = mTunerSessions[0].isConfigFlagSupported(flag);

        verify(mBroadcastRadioMock).isConfigFlagSet(flag);
        assertWithMessage("Config flag %s is supported", flag).that(isSupported).isTrue();
    }

    @Test
    public void setConfigFlag_withUnsupportedFlag_throwsRuntimeException() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].setConfigFlag(flag, /* value= */ true);
        });

        assertWithMessage("Exception for setting unsupported flag %s", flag)
                .that(thrown).hasMessageThat().contains("setConfigFlag: NOT_SUPPORTED");
    }

    @Test
    public void setConfigFlag_withFlagSetToTrue() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag + 1;

        mTunerSessions[0].setConfigFlag(flag, /* value= */ true);

        verify(mBroadcastRadioMock).setConfigFlag(flag, /* value= */ true);
    }

    @Test
    public void setConfigFlag_withFlagSetToFalse() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag + 1;

        mTunerSessions[0].setConfigFlag(flag, /* value= */ false);

        verify(mBroadcastRadioMock).setConfigFlag(flag, /* value= */ false);
    }

    @Test
    public void isConfigFlagSet_withUnsupportedFlag_throwsRuntimeException()
            throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mTunerSessions[0].isConfigFlagSet(flag);
        });

        assertWithMessage("Exception for check if unsupported flag %s is set", flag)
                .that(thrown).hasMessageThat().contains("isConfigFlagSet: NOT_SUPPORTED");
    }

    @Test
    public void isConfigFlagSet_withSupportedFlag() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        int flag = mUnsupportedConfigFlag + 1;
        boolean expectedConfigFlagValue = true;
        mTunerSessions[0].setConfigFlag(flag, /* value= */ expectedConfigFlagValue);

        boolean isSet = mTunerSessions[0].isConfigFlagSet(flag);

        assertWithMessage("Config flag %s is set", flag)
                .that(isSet).isEqualTo(expectedConfigFlagValue);
    }

    @Test
    public void setParameters_withMockParameters() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        Map<String, String> parametersSet = new ArrayMap<>();
        parametersSet.put("mockParam1", "mockValue1");
        parametersSet.put("mockParam2", "mockValue2");

        mTunerSessions[0].setParameters(parametersSet);

        verify(mBroadcastRadioMock).setParameters(
                ConversionUtils.vendorInfoToHalVendorKeyValues(parametersSet));
    }

    @Test
    public void getParameters_withMockKeys() throws RemoteException {
        openAidlClients(/* numClients= */ 1);
        List<String> parameterKeys = new ArrayList<>(2);
        parameterKeys.add("mockKey1");
        parameterKeys.add("mockKey2");

        mTunerSessions[0].getParameters(parameterKeys);

        verify(mBroadcastRadioMock).getParameters(
                parameterKeys.toArray(new String[0]));
    }

    private void openAidlClients(int numClients) throws RemoteException {
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
            seekFrequency = mAmfmFrequencyList[mAmfmFrequencyList.length - 1];
            for (int i = mAmfmFrequencyList.length - 1; i >= 0; i--) {
                if (mAmfmFrequencyList[i] < currentFrequency) {
                    seekFrequency = mAmfmFrequencyList[i];
                    break;
                }
            }
        } else {
            seekFrequency = mAmfmFrequencyList[0];
            for (int index = 0; index < mAmfmFrequencyList.length; index++) {
                if (mAmfmFrequencyList[index] > currentFrequency) {
                    seekFrequency = mAmfmFrequencyList[index];
                    break;
                }
            }
        }
        return seekFrequency;
    }
}
