/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.IAudioRoutesObserver;
import android.media.MediaRoute2Info;
import android.os.RemoteException;

import com.android.internal.R;
import com.android.server.audio.AudioService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class AudioPoliciesDeviceRouteControllerTest {

    private static final String ROUTE_NAME_DEFAULT = "default";
    private static final String ROUTE_NAME_DOCK = "dock";
    private static final String ROUTE_NAME_HEADPHONES = "headphones";

    private static final int VOLUME_SAMPLE_1 = 25;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private AudioService mAudioService;
    @Mock
    private DeviceRouteController.OnDeviceRouteChangedListener mOnDeviceRouteChangedListener;

    @Captor
    private ArgumentCaptor<IAudioRoutesObserver.Stub> mAudioRoutesObserverCaptor;

    private AudioPoliciesDeviceRouteController mController;

    private IAudioRoutesObserver.Stub mAudioRoutesObserver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getText(anyInt())).thenReturn(ROUTE_NAME_DEFAULT);

        // Setting built-in speaker as default speaker.
        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_SPEAKER;
        when(mAudioService.startWatchingRoutes(mAudioRoutesObserverCaptor.capture()))
                .thenReturn(audioRoutesInfo);

        mController = new AudioPoliciesDeviceRouteController(
                mContext, mAudioManager, mAudioService, mOnDeviceRouteChangedListener);

        mAudioRoutesObserver = mAudioRoutesObserverCaptor.getValue();
    }

    @Test
    public void getDeviceRoute_noSelectedRoutes_returnsDefaultDevice() {
        MediaRoute2Info route2Info = mController.getDeviceRoute();

        assertThat(route2Info.getName()).isEqualTo(ROUTE_NAME_DEFAULT);
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_BUILTIN_SPEAKER);
    }

    @Test
    public void getDeviceRoute_audioRouteHasChanged_returnsRouteFromAudioService() {
        when(mResources.getText(R.string.default_audio_route_name_headphones))
                .thenReturn(ROUTE_NAME_HEADPHONES);

        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;
        callAudioRoutesObserver(audioRoutesInfo);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getName()).isEqualTo(ROUTE_NAME_HEADPHONES);
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_WIRED_HEADPHONES);
    }

    @Test
    public void getDeviceRoute_selectDevice_returnsSelectedRoute() {
        when(mResources.getText(R.string.default_audio_route_name_dock_speakers))
                .thenReturn(ROUTE_NAME_DOCK);

        mController.selectRoute(MediaRoute2Info.TYPE_DOCK);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getName()).isEqualTo(ROUTE_NAME_DOCK);
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_DOCK);
    }

    @Test
    public void getDeviceRoute_hasSelectedAndAudioServiceRoutes_returnsSelectedRoute() {
        when(mResources.getText(R.string.default_audio_route_name_headphones))
                .thenReturn(ROUTE_NAME_HEADPHONES);
        when(mResources.getText(R.string.default_audio_route_name_dock_speakers))
                .thenReturn(ROUTE_NAME_DOCK);

        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;
        callAudioRoutesObserver(audioRoutesInfo);

        mController.selectRoute(MediaRoute2Info.TYPE_DOCK);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getName()).isEqualTo(ROUTE_NAME_DOCK);
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_DOCK);
    }

    @Test
    public void getDeviceRoute_unselectRoute_returnsAudioServiceRoute() {
        when(mResources.getText(R.string.default_audio_route_name_headphones))
                .thenReturn(ROUTE_NAME_HEADPHONES);
        when(mResources.getText(R.string.default_audio_route_name_dock_speakers))
                .thenReturn(ROUTE_NAME_DOCK);

        mController.selectRoute(MediaRoute2Info.TYPE_DOCK);

        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;
        callAudioRoutesObserver(audioRoutesInfo);

        mController.selectRoute(null);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getName()).isEqualTo(ROUTE_NAME_HEADPHONES);
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_WIRED_HEADPHONES);
    }

    @Test
    public void getDeviceRoute_selectRouteFails_returnsAudioServiceRoute() {
        when(mResources.getText(R.string.default_audio_route_name_headphones))
                .thenReturn(ROUTE_NAME_HEADPHONES);

        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;
        callAudioRoutesObserver(audioRoutesInfo);

        mController.selectRoute(MediaRoute2Info.TYPE_BLUETOOTH_A2DP);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getName()).isEqualTo(ROUTE_NAME_HEADPHONES);
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_WIRED_HEADPHONES);
    }

    @Test
    public void selectRoute_selectWiredRoute_returnsTrue() {
        assertThat(mController.selectRoute(MediaRoute2Info.TYPE_HDMI)).isTrue();
    }

    @Test
    public void selectRoute_selectBluetoothRoute_returnsFalse() {
        assertThat(mController.selectRoute(MediaRoute2Info.TYPE_BLUETOOTH_A2DP)).isFalse();
    }

    @Test
    public void selectRoute_unselectRoute_returnsTrue() {
        assertThat(mController.selectRoute(null)).isTrue();
    }

    @Test
    public void updateVolume_noSelectedRoute_deviceRouteVolumeChanged() {
        when(mResources.getText(R.string.default_audio_route_name_headphones))
                .thenReturn(ROUTE_NAME_HEADPHONES);

        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;
        callAudioRoutesObserver(audioRoutesInfo);

        mController.updateVolume(VOLUME_SAMPLE_1);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_WIRED_HEADPHONES);
        assertThat(route2Info.getVolume()).isEqualTo(VOLUME_SAMPLE_1);
    }

    @Test
    public void updateVolume_connectSelectedRouteLater_selectedRouteVolumeChanged() {
        when(mResources.getText(R.string.default_audio_route_name_headphones))
                .thenReturn(ROUTE_NAME_HEADPHONES);
        when(mResources.getText(R.string.default_audio_route_name_dock_speakers))
                .thenReturn(ROUTE_NAME_DOCK);

        AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
        audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;
        callAudioRoutesObserver(audioRoutesInfo);

        mController.updateVolume(VOLUME_SAMPLE_1);

        mController.selectRoute(MediaRoute2Info.TYPE_DOCK);

        MediaRoute2Info route2Info = mController.getDeviceRoute();
        assertThat(route2Info.getType()).isEqualTo(MediaRoute2Info.TYPE_DOCK);
        assertThat(route2Info.getVolume()).isEqualTo(VOLUME_SAMPLE_1);
    }

    /**
     * Simulates {@link IAudioRoutesObserver.Stub#dispatchAudioRoutesChanged(AudioRoutesInfo)}
     * from {@link AudioService}. This happens when there is a wired route change,
     * like a wired headset being connected.
     *
     * @param audioRoutesInfo updated state of connected wired device
     */
    private void callAudioRoutesObserver(AudioRoutesInfo audioRoutesInfo) {
        try {
            // this is a captured observer implementation
            // from WiredRoutesController's AudioService#startWatchingRoutes call
            mAudioRoutesObserver.dispatchAudioRoutesChanged(audioRoutesInfo);
        } catch (RemoteException exception) {
            // Should not happen since the object is mocked.
            assertWithMessage("An unexpected RemoteException happened.").fail();
        }
    }
}
