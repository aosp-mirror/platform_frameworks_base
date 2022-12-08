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

package android.hardware.radio.tests.unittests;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.hardware.radio.IRadioService;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public final class TunerAdapterTest {

    private static final int TEST_TARGET_SDK_VERSION = Build.VERSION_CODES.CUR_DEVELOPMENT;

    private static final int CALLBACK_TIMEOUT_MS = 30_000;
    private static final int AM_LOWER_LIMIT_KHZ = 150;

    private static final RadioManager.BandConfig TEST_BAND_CONFIG = createBandConfig();
    private static final ProgramSelector.Identifier FM_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                    /* value= */ 94300);
    private static final ProgramSelector FM_SELECTOR =
            new ProgramSelector(ProgramSelector.PROGRAM_TYPE_FM, FM_IDENTIFIER,
                    /* secondaryIds= */ null, /* vendorIds= */ null);
    private static final RadioManager.ProgramInfo FM_PROGRAM_INFO = createFmProgramInfo();

    private RadioTuner mRadioTuner;
    private ITunerCallback mTunerCallback;
    private final ApplicationInfo mApplicationInfo = new ApplicationInfo();

    @Mock
    private IRadioService mRadioServiceMock;
    @Mock
    private Context mContextMock;
    @Mock
    private ITuner mTunerMock;
    @Mock
    private RadioTuner.Callback mCallbackMock;

    @Before
    public void setUp() throws Exception {
        mApplicationInfo.targetSdkVersion = TEST_TARGET_SDK_VERSION;
        when(mContextMock.getApplicationInfo()).thenReturn(mApplicationInfo);
        RadioManager radioManager = new RadioManager(mContextMock, mRadioServiceMock);

        doAnswer(invocation -> {
            mTunerCallback = (ITunerCallback) invocation.getArguments()[3];
            return mTunerMock;
        }).when(mRadioServiceMock).openTuner(anyInt(), any(), anyBoolean(), any(), anyInt());

        doAnswer(invocation -> {
            ProgramSelector program = (ProgramSelector) invocation.getArguments()[0];
            if (program.getPrimaryId().getType()
                    != ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY) {
                throw new IllegalArgumentException();
            }
            if (program.getPrimaryId().getValue() < AM_LOWER_LIMIT_KHZ) {
                mTunerCallback.onTuneFailed(RadioTuner.TUNER_RESULT_INVALID_ARGUMENTS, program);
            } else {
                mTunerCallback.onCurrentProgramInfoChanged(FM_PROGRAM_INFO);
            }
            return RadioManager.STATUS_OK;
        }).when(mTunerMock).tune(any());

        mRadioTuner = radioManager.openTuner(/* moduleId= */ 0, TEST_BAND_CONFIG,
                /* withAudio= */ true, mCallbackMock, /* handler= */ null);
    }

    @After
    public void cleanUp() throws Exception {
        mRadioTuner.close();
    }

    @Test
    public void close_forTunerAdapter() throws Exception {
        mRadioTuner.close();

        verify(mTunerMock).close();
    }

    @Test
    public void setConfiguration_forTunerAdapter() throws Exception {
        int status = mRadioTuner.setConfiguration(TEST_BAND_CONFIG);

        verify(mTunerMock).setConfiguration(TEST_BAND_CONFIG);
        assertWithMessage("Status for setting configuration")
                .that(status).isEqualTo(RadioManager.STATUS_OK);
    }

    @Test
    public void getConfiguration_forTunerAdapter() throws Exception {
        when(mTunerMock.getConfiguration()).thenReturn(TEST_BAND_CONFIG);
        RadioManager.BandConfig[] bandConfigs = new RadioManager.BandConfig[1];

        int status = mRadioTuner.getConfiguration(bandConfigs);

        assertWithMessage("Status for getting configuration")
                .that(status).isEqualTo(RadioManager.STATUS_OK);
        assertWithMessage("Configuration obtained from radio tuner")
                .that(bandConfigs[0]).isEqualTo(TEST_BAND_CONFIG);
    }

    @Test
    public void setMute_forTunerAdapter() {
        int status = mRadioTuner.setMute(/* mute= */ true);

        assertWithMessage("Status for setting mute")
                .that(status).isEqualTo(RadioManager.STATUS_OK);
    }

    @Test
    public void getMute_forTunerAdapter() throws Exception {
        when(mTunerMock.isMuted()).thenReturn(true);

        boolean muteStatus = mRadioTuner.getMute();

        assertWithMessage("Mute status").that(muteStatus).isTrue();
    }

    @Test
    public void step_forTunerAdapter_succeeds() throws Exception {
        doAnswer(invocation -> {
            mTunerCallback.onCurrentProgramInfoChanged(FM_PROGRAM_INFO);
            return RadioManager.STATUS_OK;
        }).when(mTunerMock).step(anyBoolean(), anyBoolean());

        int scanStatus = mRadioTuner.step(RadioTuner.DIRECTION_DOWN, /* skipSubChannel= */ false);

        verify(mTunerMock).step(/* skipSubChannel= */ true, /* skipSubChannel= */ false);
        assertWithMessage("Status for stepping")
                .that(scanStatus).isEqualTo(RadioManager.STATUS_OK);
        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramInfoChanged(FM_PROGRAM_INFO);
    }

    @Test
    public void scan_forTunerAdapter_succeeds() throws Exception {
        doAnswer(invocation -> {
            mTunerCallback.onCurrentProgramInfoChanged(FM_PROGRAM_INFO);
            return RadioManager.STATUS_OK;
        }).when(mTunerMock).seek(anyBoolean(), anyBoolean());

        int scanStatus = mRadioTuner.scan(RadioTuner.DIRECTION_DOWN, /* skipSubChannel= */ false);

        verify(mTunerMock).seek(/* directionDown= */ true, /* skipSubChannel= */ false);
        assertWithMessage("Status for scaning")
                .that(scanStatus).isEqualTo(RadioManager.STATUS_OK);
        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramInfoChanged(FM_PROGRAM_INFO);
    }

    @Test
    public void seek_forTunerAdapter_succeeds() throws Exception {
        doAnswer(invocation -> {
            mTunerCallback.onCurrentProgramInfoChanged(FM_PROGRAM_INFO);
            return RadioManager.STATUS_OK;
        }).when(mTunerMock).seek(anyBoolean(), anyBoolean());

        int scanStatus = mRadioTuner.scan(RadioTuner.DIRECTION_DOWN, /* skipSubChannel= */ false);

        verify(mTunerMock).seek(/* directionDown= */ true, /* skipSubChannel= */ false);
        assertWithMessage("Status for seeking")
                .that(scanStatus).isEqualTo(RadioManager.STATUS_OK);
        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramInfoChanged(FM_PROGRAM_INFO);
    }

    @Test
    public void seek_forTunerAdapter_invokesOnErrorWhenTimeout() throws Exception {
        doAnswer(invocation -> {
            mTunerCallback.onTuneFailed(RadioTuner.TUNER_RESULT_TIMEOUT, FM_SELECTOR);
            return RadioManager.STATUS_OK;
        }).when(mTunerMock).seek(anyBoolean(), anyBoolean());

        mRadioTuner.scan(RadioTuner.DIRECTION_UP, /* skipSubChannel*/ true);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onTuneFailed(
                RadioTuner.TUNER_RESULT_TIMEOUT, FM_SELECTOR);
    }

    @Test
    public void tune_withChannelsForTunerAdapter_succeeds() {
        int status = mRadioTuner.tune(/* channel= */ 92300, /* subChannel= */ 0);

        assertWithMessage("Status for tuning with channel and sub-channel")
                .that(status).isEqualTo(RadioManager.STATUS_OK);
        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramInfoChanged(FM_PROGRAM_INFO);
    }

    @Test
    public void tune_withValidSelectorForTunerAdapter_succeeds() throws Exception {
        mRadioTuner.tune(FM_SELECTOR);

        verify(mTunerMock).tune(FM_SELECTOR);
        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramInfoChanged(FM_PROGRAM_INFO);
    }


    @Test
    public void tune_withInvalidSelectorForTunerAdapter_invokesOnTuneFailed() {
        ProgramSelector invalidSelector = new ProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                        new ProgramSelector.Identifier(
                                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 100),
                /* secondaryIds= */ null, /* vendorIds= */ null);

        mRadioTuner.tune(invalidSelector);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS))
                .onTuneFailed(RadioTuner.TUNER_RESULT_INVALID_ARGUMENTS, invalidSelector);
    }

    @Test
    public void cancel_forTunerAdapter() throws Exception {
        mRadioTuner.tune(FM_SELECTOR);

        mRadioTuner.cancel();

        verify(mTunerMock).cancel();
    }

    @Test
    public void cancelAnnouncement_forTunerAdapter() throws Exception {
        mRadioTuner.cancelAnnouncement();

        verify(mTunerMock).cancelAnnouncement();
    }

    @Test
    public void getProgramInfo_beforeProgramInfoSetForTunerAdapter() {
        RadioManager.ProgramInfo[] programInfoArray = new RadioManager.ProgramInfo[1];

        int status = mRadioTuner.getProgramInformation(programInfoArray);

        assertWithMessage("Status for getting null program info")
                .that(status).isEqualTo(RadioManager.STATUS_INVALID_OPERATION);
    }

    @Test
    public void getProgramInfo_afterTuneForTunerAdapter() {
        mRadioTuner.tune(FM_SELECTOR);
        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramInfoChanged(FM_PROGRAM_INFO);
        RadioManager.ProgramInfo[] programInfoArray = new RadioManager.ProgramInfo[1];

        int status = mRadioTuner.getProgramInformation(programInfoArray);

        assertWithMessage("Status for getting program info")
                .that(status).isEqualTo(RadioManager.STATUS_OK);
        assertWithMessage("Program info obtained from radio tuner")
                .that(programInfoArray[0]).isEqualTo(FM_PROGRAM_INFO);
    }

    @Test
    public void getMetadataImage_forTunerAdapter() throws Exception {
        Bitmap bitmapExpected = Mockito.mock(Bitmap.class);
        when(mTunerMock.getImage(anyInt())).thenReturn(bitmapExpected);
        int imageId = 1;

        Bitmap image = mRadioTuner.getMetadataImage(/* id= */ imageId);

        assertWithMessage("Image obtained from id %s", imageId)
                .that(image).isEqualTo(bitmapExpected);
    }

    @Test
    public void startBackgroundScan_forTunerAdapter() throws Exception {
        when(mTunerMock.startBackgroundScan()).thenReturn(false);

        boolean scanStatus = mRadioTuner.startBackgroundScan();

        verify(mTunerMock).startBackgroundScan();
        assertWithMessage("Status for starting background scan").that(scanStatus).isFalse();
    }

    @Test
    public void isAnalogForced_forTunerAdapter() throws Exception {
        when(mTunerMock.isConfigFlagSet(RadioManager.CONFIG_FORCE_ANALOG)).thenReturn(true);

        boolean isAnalogForced = mRadioTuner.isAnalogForced();

        assertWithMessage("Forced analog playback switch")
                .that(isAnalogForced).isTrue();
    }

    @Test
    public void setAnalogForced_forTunerAdapter() throws Exception {
        boolean analogForced = true;

        mRadioTuner.setAnalogForced(analogForced);

        verify(mTunerMock).setConfigFlag(RadioManager.CONFIG_FORCE_ANALOG, analogForced);
    }

    @Test
    public void isConfigFlagSupported_forTunerAdapter() throws Exception {
        when(mTunerMock.isConfigFlagSupported(RadioManager.CONFIG_DAB_DAB_LINKING))
                .thenReturn(true);

        boolean dabFmSoftLinking =
                mRadioTuner.isConfigFlagSupported(RadioManager.CONFIG_DAB_DAB_LINKING);

        assertWithMessage("Support for DAB-DAB linking config flag")
                .that(dabFmSoftLinking).isTrue();
    }

    @Test
    public void isConfigFlagSet_forTunerAdapter() throws Exception {
        when(mTunerMock.isConfigFlagSet(RadioManager.CONFIG_DAB_FM_SOFT_LINKING))
                .thenReturn(true);

        boolean dabFmSoftLinking =
                mRadioTuner.isConfigFlagSet(RadioManager.CONFIG_DAB_FM_SOFT_LINKING);

        assertWithMessage("DAB-FM soft linking config flag")
                .that(dabFmSoftLinking).isTrue();
    }

    @Test
    public void setConfigFlag_forTunerAdapter() throws Exception {
        boolean dabFmLinking = true;

        mRadioTuner.setConfigFlag(RadioManager.CONFIG_DAB_FM_LINKING, dabFmLinking);

        verify(mTunerMock).setConfigFlag(RadioManager.CONFIG_DAB_FM_LINKING, dabFmLinking);
    }

    @Test
    public void getParameters_forTunerAdapter() throws Exception {
        List<String> parameterKeys = Arrays.asList("ParameterKeyMock");
        Map<String, String> parameters = Map.of("ParameterKeyMock", "ParameterValueMock");
        when(mTunerMock.getParameters(parameterKeys)).thenReturn(parameters);

        assertWithMessage("Parameters obtained from radio tuner")
                .that(mRadioTuner.getParameters(parameterKeys)).isEqualTo(parameters);
    }

    @Test
    public void setParameters_forTunerAdapter() throws Exception {
        Map<String, String> parameters = Map.of("ParameterKeyMock", "ParameterValueMock");
        when(mTunerMock.setParameters(parameters)).thenReturn(parameters);

        assertWithMessage("Parameters set for radio tuner")
                .that(mRadioTuner.setParameters(parameters)).isEqualTo(parameters);
    }

    @Test
    public void isAntennaConnected_forTunerAdapter() throws Exception {
        mTunerCallback.onAntennaState(/* connected= */ false);

        assertWithMessage("Antenna connection status")
                .that(mRadioTuner.isAntennaConnected()).isFalse();
    }

    @Test
    public void hasControl_forTunerAdapter() throws Exception {
        when(mTunerMock.isClosed()).thenReturn(true);

        assertWithMessage("Control on tuner").that(mRadioTuner.hasControl()).isFalse();
    }

    @Test
    public void onConfigurationChanged_forTunerCallbackAdapter() throws Exception {
        mTunerCallback.onConfigurationChanged(TEST_BAND_CONFIG);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS))
                .onConfigurationChanged(TEST_BAND_CONFIG);
    }

    @Test
    public void onTrafficAnnouncement_forTunerCallbackAdapter() throws Exception {
        mTunerCallback.onTrafficAnnouncement(/* active= */ true);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS))
                .onTrafficAnnouncement(/* active= */ true);
    }

    @Test
    public void onEmergencyAnnouncement_forTunerCallbackAdapter() throws Exception {
        mTunerCallback.onEmergencyAnnouncement(/* active= */ true);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS))
                .onEmergencyAnnouncement(/* active= */ true);
    }

    @Test
    public void onBackgroundScanAvailabilityChange_forTunerCallbackAdapter() throws Exception {
        mTunerCallback.onBackgroundScanAvailabilityChange(/* isAvailable= */ false);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS))
                .onBackgroundScanAvailabilityChange(/* isAvailable= */ false);
    }

    @Test
    public void onProgramListChanged_forTunerCallbackAdapter() throws Exception {
        mTunerCallback.onProgramListChanged();

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onProgramListChanged();
    }

    @Test
    public void onConfigFlagUpdated_forTunerCallbackAdapter() throws Exception {
        int configFlag = RadioManager.CONFIG_RDS_AF;
        boolean configFlagValue = true;

        mTunerCallback.onConfigFlagUpdated(configFlag, configFlagValue);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS))
                .onConfigFlagUpdated(configFlag, configFlagValue);
    }

    @Test
    public void onParametersUpdated_forTunerCallbackAdapter() throws Exception {
        Map<String, String> parametersExpected = Map.of("ParameterKeyMock", "ParameterValueMock");

        mTunerCallback.onParametersUpdated(parametersExpected);

        verify(mCallbackMock, timeout(CALLBACK_TIMEOUT_MS)).onParametersUpdated(parametersExpected);
    }

    private static RadioManager.ProgramInfo createFmProgramInfo() {
        return new RadioManager.ProgramInfo(FM_SELECTOR, FM_IDENTIFIER, FM_IDENTIFIER,
                /* relatedContent= */ null, /* infoFlags= */ 0b110001,
                /* signalQuality= */ 1, createRadioMetadata(), /* vendorInfo= */ null);
    }

    private static RadioManager.FmBandConfig createBandConfig() {
        return new RadioManager.FmBandConfig(new RadioManager.FmBandDescriptor(
                RadioManager.REGION_ITU_1, RadioManager.BAND_FM, /* lowerLimit= */ 87500,
                /* upperLimit= */ 108000, /* spacing= */ 200, /* stereo= */ true,
                /* rds= */ false, /* ta= */ false, /* af= */ false, /* es= */ false));
    }

    private static RadioMetadata createRadioMetadata() {
        RadioMetadata.Builder metadataBuilder = new RadioMetadata.Builder();
        return metadataBuilder.putString(RadioMetadata.METADATA_KEY_ARTIST, "artistMock").build();
    }
}
