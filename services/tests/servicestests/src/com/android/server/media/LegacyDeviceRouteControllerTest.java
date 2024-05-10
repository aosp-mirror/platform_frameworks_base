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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.IAudioRoutesObserver;
import android.media.MediaRoute2Info;
import android.os.RemoteException;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.server.audio.AudioService;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Enclosed.class)
public class LegacyDeviceRouteControllerTest {

    private static final String DEFAULT_ROUTE_NAME = "default_route";
    private static final String DEFAULT_HEADPHONES_NAME = "headphone";
    private static final String DEFAULT_HEADSET_NAME = "headset";
    private static final String DEFAULT_DOCK_NAME = "dock";
    private static final String DEFAULT_HDMI_NAME = "hdmi";
    private static final String DEFAULT_USB_NAME = "usb";
    private static final int VOLUME_DEFAULT_VALUE = 0;
    private static final int VOLUME_VALUE_SAMPLE_1 = 10;

    private static AudioRoutesInfo createFakeBluetoothAudioRoute() {
        AudioRoutesInfo btRouteInfo = new AudioRoutesInfo();
        btRouteInfo.mainType = AudioRoutesInfo.MAIN_SPEAKER;
        btRouteInfo.bluetoothName = "bt_device";
        return btRouteInfo;
    }

    @RunWith(JUnit4.class)
    public static class DefaultDeviceRouteValueTest {
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

        @Before
        public void setUp() {
            MockitoAnnotations.initMocks(this);

            when(mContext.getResources()).thenReturn(mResources);
        }

        @Test
        public void initialize_noRoutesInfo_defaultRouteIsNotNull() {
            // Mocking default_audio_route_name.
            when(mResources.getText(R.string.default_audio_route_name))
                    .thenReturn(DEFAULT_ROUTE_NAME);

            // Default route should be initialized even when AudioService returns null.
            when(mAudioService.startWatchingRoutes(any())).thenReturn(null);

            LegacyDeviceRouteController deviceRouteController = new LegacyDeviceRouteController(
                    mContext,
                    mAudioManager,
                    mAudioService,
                    mOnDeviceRouteChangedListener
            );

            MediaRoute2Info actualMediaRoute = deviceRouteController.getSelectedRoute();

            assertThat(actualMediaRoute.getType()).isEqualTo(MediaRoute2Info.TYPE_BUILTIN_SPEAKER);
            assertThat(TextUtils.equals(actualMediaRoute.getName(), DEFAULT_ROUTE_NAME))
                    .isTrue();
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_DEFAULT_VALUE);
        }

        @Test
        public void initialize_bluetoothRouteAvailable_deviceRouteReturnsDefaultRoute() {
            // Mocking default_audio_route_name.
            when(mResources.getText(R.string.default_audio_route_name))
                    .thenReturn(DEFAULT_ROUTE_NAME);

            // This route should be ignored.
            AudioRoutesInfo fakeBluetoothAudioRoute = createFakeBluetoothAudioRoute();
            when(mAudioService.startWatchingRoutes(any())).thenReturn(fakeBluetoothAudioRoute);

            LegacyDeviceRouteController deviceRouteController = new LegacyDeviceRouteController(
                    mContext,
                    mAudioManager,
                    mAudioService,
                    mOnDeviceRouteChangedListener
            );

            MediaRoute2Info actualMediaRoute = deviceRouteController.getSelectedRoute();

            assertThat(actualMediaRoute.getType()).isEqualTo(MediaRoute2Info.TYPE_BUILTIN_SPEAKER);
            assertThat(TextUtils.equals(actualMediaRoute.getName(), DEFAULT_ROUTE_NAME))
                    .isTrue();
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_DEFAULT_VALUE);
        }
    }

    @RunWith(Parameterized.class)
    public static class DeviceRouteInitializationTest {

        @Parameterized.Parameters
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][] {
                    {     /* expected res */
                          com.android.internal.R.string.default_audio_route_name_headphones,
                          /* expected name */
                          DEFAULT_HEADPHONES_NAME,
                          /* expected type */
                          MediaRoute2Info.TYPE_WIRED_HEADPHONES,
                          /* actual audio route type */
                          AudioRoutesInfo.MAIN_HEADPHONES },
                    {   /* expected res */
                        com.android.internal.R.string.default_audio_route_name_headphones,
                        /* expected name */
                        DEFAULT_HEADSET_NAME,
                        /* expected type */
                        MediaRoute2Info.TYPE_WIRED_HEADSET,
                        /* actual audio route type */
                        AudioRoutesInfo.MAIN_HEADSET },
                    {    /* expected res */
                        R.string.default_audio_route_name_dock_speakers,
                        /* expected name */
                        DEFAULT_DOCK_NAME,
                        /* expected type */
                        MediaRoute2Info.TYPE_DOCK,
                        /* actual audio route type */
                        AudioRoutesInfo.MAIN_DOCK_SPEAKERS },
                    {   /* expected res */
                        R.string.default_audio_route_name_external_device,
                        /* expected name */
                        DEFAULT_HDMI_NAME,
                        /* expected type */
                        MediaRoute2Info.TYPE_HDMI,
                        /* actual audio route type */
                        AudioRoutesInfo.MAIN_HDMI },
                    {   /* expected res */
                        R.string.default_audio_route_name_usb,
                        /* expected name */
                        DEFAULT_USB_NAME,
                        /* expected type */
                        MediaRoute2Info.TYPE_USB_DEVICE,
                        /* actual audio route type */
                        AudioRoutesInfo.MAIN_USB }
            });
        }

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

        @IdRes
        private final int mExpectedRouteNameResource;
        private final String mExpectedRouteNameValue;
        private final int mExpectedRouteType;
        private final int mActualAudioRouteType;

        public DeviceRouteInitializationTest(int expectedRouteNameResource,
                String expectedRouteNameValue,
                int expectedMediaRouteType,
                int actualAudioRouteType) {
            this.mExpectedRouteNameResource = expectedRouteNameResource;
            this.mExpectedRouteNameValue = expectedRouteNameValue;
            this.mExpectedRouteType = expectedMediaRouteType;
            this.mActualAudioRouteType = actualAudioRouteType;
        }

        @Before
        public void setUp() {
            MockitoAnnotations.initMocks(this);

            when(mContext.getResources()).thenReturn(mResources);
        }

        @Test
        public void initialize_wiredRouteAvailable_deviceRouteReturnsWiredRoute() {
            // Mocking default_audio_route_name.
            when(mResources.getText(R.string.default_audio_route_name))
                    .thenReturn(DEFAULT_ROUTE_NAME);

            // At first, WiredRouteController should initialize device
            // route based on AudioService response.
            AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
            audioRoutesInfo.mainType = mActualAudioRouteType;
            when(mAudioService.startWatchingRoutes(any())).thenReturn(audioRoutesInfo);

            when(mResources.getText(mExpectedRouteNameResource))
                    .thenReturn(mExpectedRouteNameValue);

            LegacyDeviceRouteController deviceRouteController = new LegacyDeviceRouteController(
                    mContext,
                    mAudioManager,
                    mAudioService,
                    mOnDeviceRouteChangedListener
            );

            MediaRoute2Info actualMediaRoute = deviceRouteController.getSelectedRoute();

            assertThat(actualMediaRoute.getType()).isEqualTo(mExpectedRouteType);
            assertThat(TextUtils.equals(actualMediaRoute.getName(), mExpectedRouteNameValue))
                    .isTrue();
            // Volume did not change, so it should be set to default value (0).
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_DEFAULT_VALUE);
        }
    }

    @RunWith(JUnit4.class)
    public static class VolumeAndDeviceRoutesChangesTest {
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

        private LegacyDeviceRouteController mDeviceRouteController;
        private IAudioRoutesObserver.Stub mAudioRoutesObserver;

        @Before
        public void setUp() {
            MockitoAnnotations.initMocks(this);

            when(mContext.getResources()).thenReturn(mResources);

            when(mResources.getText(R.string.default_audio_route_name))
                    .thenReturn(DEFAULT_ROUTE_NAME);

            // Setting built-in speaker as default speaker.
            AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
            audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_SPEAKER;
            when(mAudioService.startWatchingRoutes(mAudioRoutesObserverCaptor.capture()))
                    .thenReturn(audioRoutesInfo);

            mDeviceRouteController = new LegacyDeviceRouteController(
                    mContext,
                    mAudioManager,
                    mAudioService,
                    mOnDeviceRouteChangedListener
            );

            mAudioRoutesObserver = mAudioRoutesObserverCaptor.getValue();
        }

        @Test
        public void newDeviceConnects_wiredDevice_deviceRouteReturnsWiredDevice() {
            // Connecting wired headset
            AudioRoutesInfo audioRoutesInfo = new AudioRoutesInfo();
            audioRoutesInfo.mainType = AudioRoutesInfo.MAIN_HEADPHONES;

            when(mResources.getText(
                    com.android.internal.R.string.default_audio_route_name_headphones))
                    .thenReturn(DEFAULT_HEADPHONES_NAME);

            // Simulating wired device being connected.
            callAudioRoutesObserver(audioRoutesInfo);

            MediaRoute2Info actualMediaRoute = mDeviceRouteController.getSelectedRoute();

            assertThat(actualMediaRoute.getType()).isEqualTo(MediaRoute2Info.TYPE_WIRED_HEADPHONES);
            assertThat(TextUtils.equals(actualMediaRoute.getName(), DEFAULT_HEADPHONES_NAME))
                    .isTrue();
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_DEFAULT_VALUE);
        }

        @Test
        public void newDeviceConnects_bluetoothDevice_deviceRouteReturnsBluetoothDevice() {
            // Simulating bluetooth speaker being connected.
            AudioRoutesInfo fakeBluetoothAudioRoute = createFakeBluetoothAudioRoute();
            callAudioRoutesObserver(fakeBluetoothAudioRoute);

            MediaRoute2Info actualMediaRoute = mDeviceRouteController.getSelectedRoute();

            assertThat(actualMediaRoute.getType()).isEqualTo(MediaRoute2Info.TYPE_BUILTIN_SPEAKER);
            assertThat(TextUtils.equals(actualMediaRoute.getName(), DEFAULT_ROUTE_NAME))
                    .isTrue();
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_DEFAULT_VALUE);
        }

        @Test
        public void updateVolume_differentValue_updatesDeviceRouteVolume() {
            MediaRoute2Info actualMediaRoute = mDeviceRouteController.getSelectedRoute();
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_DEFAULT_VALUE);

            assertThat(mDeviceRouteController.updateVolume(VOLUME_VALUE_SAMPLE_1)).isTrue();

            actualMediaRoute = mDeviceRouteController.getSelectedRoute();
            assertThat(actualMediaRoute.getVolume()).isEqualTo(VOLUME_VALUE_SAMPLE_1);
        }

        @Test
        public void updateVolume_sameValue_returnsFalse() {
            assertThat(mDeviceRouteController.updateVolume(VOLUME_VALUE_SAMPLE_1)).isTrue();
            assertThat(mDeviceRouteController.updateVolume(VOLUME_VALUE_SAMPLE_1)).isFalse();
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

}
