/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.AudioInputControl;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Tests for {@link AmbientVolumeController}. */
@RunWith(RobolectricTestRunner.class)
public class AmbientVolumeControllerTest {

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private static final String TEST_ADDRESS = "00:00:00:00:11";

    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private VolumeControlProfile mVolumeControlProfile;
    @Mock
    private AmbientVolumeController.AmbientVolumeControlCallback mCallback;
    @Mock
    private BluetoothDevice mDevice;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private AmbientVolumeController mVolumeController;

    @Before
    public void setUp() {
        when(mProfileManager.getVolumeControlProfile()).thenReturn(mVolumeControlProfile);
        when(mDevice.getAddress()).thenReturn(TEST_ADDRESS);
        when(mDevice.isConnected()).thenReturn(true);
        mVolumeController = new AmbientVolumeController(mProfileManager, mCallback);
    }

    @Test
    public void onServiceConnected_notifyCallback() {
        when(mVolumeControlProfile.isProfileReady()).thenReturn(true);

        mVolumeController.onServiceConnected();

        verify(mCallback).onVolumeControlServiceConnected();
    }

    @Test
    public void isAmbientControlAvailable_validControls_assertTrue() {
        prepareValidAmbientControls();

        assertThat(mVolumeController.isAmbientControlAvailable(mDevice)).isTrue();
    }

    @Test
    public void isAmbientControlAvailable_streamingControls_assertFalse() {
        prepareStreamingControls();

        assertThat(mVolumeController.isAmbientControlAvailable(mDevice)).isFalse();
    }

    @Test
    public void isAmbientControlAvailable_automaticAmbientControls_assertFalse() {
        prepareAutomaticAmbientControls();

        assertThat(mVolumeController.isAmbientControlAvailable(mDevice)).isFalse();
    }

    @Test
    public void isAmbientControlAvailable_inactiveAmbientControls_assertFalse() {
        prepareInactiveAmbientControls();

        assertThat(mVolumeController.isAmbientControlAvailable(mDevice)).isFalse();
    }

    @Test
    public void registerCallback_verifyRegisterOnAllControls() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.registerCallback(mContext.getMainExecutor(), mDevice);

        for (AudioInputControl control : controls) {
            verify(control).registerCallback(any(Executor.class), any());
        }
    }

    @Test
    public void unregisterCallback_verifyUnregisterOnAllControls() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.registerCallback(mContext.getMainExecutor(), mDevice);
        mVolumeController.unregisterCallback(mDevice);

        for (AudioInputControl control : controls) {
            verify(control).unregisterCallback(any());
        }
    }

    @Test
    public void getAmbientMax_verifyGetOnFirstControl() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.getAmbientMax(mDevice);

        verify(controls.getFirst()).getGainSettingMax();
    }

    @Test
    public void getAmbientMin_verifyGetOnFirstControl() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.getAmbientMin(mDevice);

        verify(controls.getFirst()).getGainSettingMin();
    }

    @Test
    public void getAmbient_verifyGetOnFirstControl() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.getAmbient(mDevice);

        verify(controls.getFirst()).getGainSetting();
    }

    @Test
    public void setAmbient_verifySetOnAllControls() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.setAmbient(mDevice, 10);

        for (AudioInputControl control : controls) {
            verify(control).setGainSetting(10);
        }
    }

    @Test
    public void getMute_verifyGetOnFirstControl() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.getMute(mDevice);

        verify(controls.getFirst()).getMute();
    }

    @Test
    public void setMuted_true_verifySetOnAllControls() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.setMuted(mDevice, true);

        for (AudioInputControl control : controls) {
            verify(control).setMute(AudioInputControl.MUTE_MUTED);
        }
    }

    @Test
    public void setMuted_false_verifySetOnAllControls() {
        List<AudioInputControl> controls = prepareValidAmbientControls();

        mVolumeController.setMuted(mDevice, false);

        for (AudioInputControl control : controls) {
            verify(control).setMute(AudioInputControl.MUTE_NOT_MUTED);
        }
    }

    @Test
    public void ambientCallback_onGainSettingChanged_verifyCallbackIsCalledWhenStateChange() {
        AmbientVolumeController.AmbientCallback ambientCallback =
                mVolumeController.new AmbientCallback(mDevice, mCallback);
        final int testAmbient = 10;
        List<AudioInputControl> controls = prepareValidAmbientControls();
        when(controls.getFirst().getGainSetting()).thenReturn(testAmbient);

        mVolumeController.refreshAmbientState(mDevice);
        ambientCallback.onGainSettingChanged(testAmbient);
        verify(mCallback, never()).onAmbientChanged(mDevice, testAmbient);

        final int updatedTestAmbient = 20;
        ambientCallback.onGainSettingChanged(updatedTestAmbient);
        verify(mCallback).onAmbientChanged(mDevice, updatedTestAmbient);
    }


    @Test
    public void ambientCallback_onSetAmbientFailed_verifyCallbackIsCalled() {
        AmbientVolumeController.AmbientCallback ambientCallback =
                mVolumeController.new AmbientCallback(mDevice, mCallback);

        ambientCallback.onSetGainSettingFailed();

        verify(mCallback).onCommandFailed(mDevice);
    }

    @Test
    public void ambientCallback_onMuteChanged_verifyCallbackIsCalledWhenStateChange() {
        AmbientVolumeController.AmbientCallback ambientCallback =
                mVolumeController.new AmbientCallback(mDevice, mCallback);
        final int testMute = 0;
        List<AudioInputControl> controls = prepareValidAmbientControls();
        when(controls.getFirst().getMute()).thenReturn(testMute);

        mVolumeController.refreshAmbientState(mDevice);
        ambientCallback.onMuteChanged(testMute);
        verify(mCallback, never()).onMuteChanged(mDevice, testMute);

        final int updatedTestMute = 1;
        ambientCallback.onMuteChanged(updatedTestMute);
        verify(mCallback).onMuteChanged(mDevice, updatedTestMute);
    }

    @Test
    public void ambientCallback_onSetMuteFailed_verifyCallbackIsCalled() {
        AmbientVolumeController.AmbientCallback ambientCallback =
                mVolumeController.new AmbientCallback(mDevice, mCallback);

        ambientCallback.onSetMuteFailed();

        verify(mCallback).onCommandFailed(mDevice);
    }

    private List<AudioInputControl> prepareValidAmbientControls() {
        List<AudioInputControl> controls = new ArrayList<>();
        final int controlsCount = 2;
        for (int i = 0; i < controlsCount; i++) {
            controls.add(prepareAudioInputControl(
                    AudioInputControl.AUDIO_INPUT_TYPE_AMBIENT,
                    AudioInputControl.GAIN_MODE_MANUAL,
                    AudioInputControl.AUDIO_INPUT_STATUS_ACTIVE));
        }
        when(mVolumeControlProfile.getAudioInputControlServices(mDevice)).thenReturn(controls);
        return controls;
    }

    private List<AudioInputControl> prepareStreamingControls() {
        List<AudioInputControl> controls = new ArrayList<>();
        final int controlsCount = 2;
        for (int i = 0; i < controlsCount; i++) {
            controls.add(prepareAudioInputControl(
                    AudioInputControl.AUDIO_INPUT_TYPE_STREAMING,
                    AudioInputControl.GAIN_MODE_MANUAL,
                    AudioInputControl.AUDIO_INPUT_STATUS_ACTIVE));
        }
        when(mVolumeControlProfile.getAudioInputControlServices(mDevice)).thenReturn(controls);
        return controls;
    }

    private List<AudioInputControl> prepareAutomaticAmbientControls() {
        List<AudioInputControl> controls = new ArrayList<>();
        final int controlsCount = 2;
        for (int i = 0; i < controlsCount; i++) {
            controls.add(prepareAudioInputControl(
                    AudioInputControl.AUDIO_INPUT_TYPE_STREAMING,
                    AudioInputControl.GAIN_MODE_AUTOMATIC,
                    AudioInputControl.AUDIO_INPUT_STATUS_ACTIVE));
        }
        when(mVolumeControlProfile.getAudioInputControlServices(mDevice)).thenReturn(controls);
        return controls;
    }

    private List<AudioInputControl> prepareInactiveAmbientControls() {
        List<AudioInputControl> controls = new ArrayList<>();
        final int controlsCount = 2;
        for (int i = 0; i < controlsCount; i++) {
            controls.add(prepareAudioInputControl(
                    AudioInputControl.AUDIO_INPUT_TYPE_STREAMING,
                    AudioInputControl.GAIN_MODE_AUTOMATIC,
                    AudioInputControl.AUDIO_INPUT_STATUS_INACTIVE));
        }
        when(mVolumeControlProfile.getAudioInputControlServices(mDevice)).thenReturn(controls);
        return controls;
    }

    private AudioInputControl prepareAudioInputControl(int type, int mode, int status) {
        AudioInputControl control = mock(AudioInputControl.class);
        when(control.getAudioInputType()).thenReturn(type);
        when(control.getGainMode()).thenReturn(mode);
        when(control.getAudioInputStatus()).thenReturn(status);
        return control;
    }
}
