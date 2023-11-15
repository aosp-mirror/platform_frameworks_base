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

package com.android.server.companion.virtual.camera;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.camera.VirtualCameraCallback;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtual.camera.VirtualCameraMetadata;
import android.companion.virtual.camera.VirtualCameraStreamConfig;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Surface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class VirtualCameraControllerTest {

    private static final int CAMERA_DISPLAY_NAME_RES_ID_1 = 10;
    private static final int CAMERA_WIDTH_1 = 100;
    private static final int CAMERA_HEIGHT_1 = 200;
    private static final int CAMERA_FORMAT_1 = ImageFormat.RGB_565;

    private static final int CAMERA_DISPLAY_NAME_RES_ID_2 = 11;
    private static final int CAMERA_WIDTH_2 = 400;
    private static final int CAMERA_HEIGHT_2 = 600;
    private static final int CAMERA_FORMAT_2 = ImageFormat.YUY2;

    @Mock
    private IVirtualCameraService mVirtualCameraServiceMock;

    private VirtualCameraController mVirtualCameraController;
    private final HandlerExecutor mCallbackHandler =
            new HandlerExecutor(new Handler(Looper.getMainLooper()));

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mVirtualCameraController = new VirtualCameraController(mVirtualCameraServiceMock);
        when(mVirtualCameraServiceMock.registerCamera(any(), any())).thenReturn(true);
    }

    @Test
    public void registerCamera_registersCamera() throws Exception {
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_DISPLAY_NAME_RES_ID_1));

        ArgumentCaptor<VirtualCameraConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(VirtualCameraConfiguration.class);
        verify(mVirtualCameraServiceMock).registerCamera(any(), configurationCaptor.capture());
        VirtualCameraConfiguration virtualCameraConfiguration = configurationCaptor.getValue();
        assertThat(virtualCameraConfiguration.supportedStreamConfigs.length).isEqualTo(1);
        assertVirtualCameraConfiguration(virtualCameraConfiguration, CAMERA_WIDTH_1,
                CAMERA_HEIGHT_1, CAMERA_FORMAT_1);
    }

    @Test
    public void unregisterCamera_unregistersCamera() throws Exception {
        VirtualCameraConfig config = createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_DISPLAY_NAME_RES_ID_1);
        mVirtualCameraController.unregisterCamera(config);

        verify(mVirtualCameraServiceMock).unregisterCamera(any());
    }

    @Test
    public void close_unregistersAllCameras() throws Exception {
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_DISPLAY_NAME_RES_ID_1));
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_2, CAMERA_HEIGHT_2, CAMERA_FORMAT_2, CAMERA_DISPLAY_NAME_RES_ID_2));

        ArgumentCaptor<VirtualCameraConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(VirtualCameraConfiguration.class);
        mVirtualCameraController.close();
        verify(mVirtualCameraServiceMock, times(2)).registerCamera(any(),
                configurationCaptor.capture());
        List<VirtualCameraConfiguration> virtualCameraConfigurations =
                configurationCaptor.getAllValues();
        assertThat(virtualCameraConfigurations).hasSize(2);
        assertVirtualCameraConfiguration(virtualCameraConfigurations.get(0), CAMERA_WIDTH_1,
                CAMERA_HEIGHT_1, CAMERA_FORMAT_1);
        assertVirtualCameraConfiguration(virtualCameraConfigurations.get(1), CAMERA_WIDTH_2,
                CAMERA_HEIGHT_2, CAMERA_FORMAT_2);
    }

    private VirtualCameraConfig createVirtualCameraConfig(
            int width, int height, int format, int displayNameResId) {
        return new VirtualCameraConfig.Builder()
                .addStreamConfig(width, height, format)
                .setDisplayNameStringRes(displayNameResId)
                .setVirtualCameraCallback(mCallbackHandler, createNoOpCallback())
                .build();
    }

    private static void assertVirtualCameraConfiguration(
            VirtualCameraConfiguration configuration, int width, int height, int format) {
        assertThat(configuration.supportedStreamConfigs[0].width).isEqualTo(width);
        assertThat(configuration.supportedStreamConfigs[0].height).isEqualTo(height);
        assertThat(configuration.supportedStreamConfigs[0].pixelFormat).isEqualTo(format);
    }

    private static VirtualCameraCallback createNoOpCallback() {
        return new VirtualCameraCallback() {

            @Override
            public void onStreamConfigured(
                    int streamId,
                    @NonNull Surface surface,
                    @NonNull VirtualCameraStreamConfig streamConfig) {}

            @Override
            public void onProcessCaptureRequest(
                    int streamId, long frameId, @Nullable VirtualCameraMetadata metadata) {}

            @Override
            public void onStreamClosed(int streamId) {}
        };
    }
}
