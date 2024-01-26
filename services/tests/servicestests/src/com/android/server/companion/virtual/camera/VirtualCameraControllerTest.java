/*
 * Copyright 2023 The Android Open Source Project
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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.camera.VirtualCameraConfig.SENSOR_ORIENTATION_0;
import static android.companion.virtual.camera.VirtualCameraConfig.SENSOR_ORIENTATION_90;
import static android.graphics.ImageFormat.YUV_420_888;
import static android.graphics.PixelFormat.RGBA_8888;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_BACK;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.companion.virtual.camera.VirtualCameraCallback;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableLooper;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@Presubmit
@RunWith(JUnitParamsRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class VirtualCameraControllerTest {

    private static final String CAMERA_NAME_1 = "Virtual camera 1";
    private static final int CAMERA_WIDTH_1 = 100;
    private static final int CAMERA_HEIGHT_1 = 200;
    private static final int CAMERA_FORMAT_1 = YUV_420_888;
    private static final int CAMERA_MAX_FPS_1 = 30;
    private static final int CAMERA_SENSOR_ORIENTATION_1 = SENSOR_ORIENTATION_0;
    private static final int CAMERA_LENS_FACING_1 = LENS_FACING_BACK;

    private static final String CAMERA_NAME_2 = "Virtual camera 2";
    private static final int CAMERA_WIDTH_2 = 400;
    private static final int CAMERA_HEIGHT_2 = 600;
    private static final int CAMERA_FORMAT_2 = RGBA_8888;
    private static final int CAMERA_MAX_FPS_2 = 60;
    private static final int CAMERA_SENSOR_ORIENTATION_2 = SENSOR_ORIENTATION_90;
    private static final int CAMERA_LENS_FACING_2 = LENS_FACING_FRONT;

    @Mock
    private IVirtualCameraService mVirtualCameraServiceMock;
    @Mock
    private VirtualCameraCallback mVirtualCameraCallbackMock;

    private VirtualCameraController mVirtualCameraController;
    private final HandlerExecutor mCallbackHandler =
            new HandlerExecutor(new Handler(Looper.getMainLooper()));

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mVirtualCameraController = new VirtualCameraController(mVirtualCameraServiceMock,
                DEVICE_POLICY_CUSTOM);
        when(mVirtualCameraServiceMock.registerCamera(any(), any())).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        mVirtualCameraController.close();
    }

    @Parameters(method = "getAllLensFacingDirections")
    @Test
    public void registerCamera_registersCamera(int lensFacing) throws Exception {
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1, CAMERA_NAME_1,
                CAMERA_SENSOR_ORIENTATION_1, lensFacing));

        ArgumentCaptor<VirtualCameraConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(VirtualCameraConfiguration.class);
        verify(mVirtualCameraServiceMock).registerCamera(any(), configurationCaptor.capture());
        VirtualCameraConfiguration virtualCameraConfiguration = configurationCaptor.getValue();
        assertThat(virtualCameraConfiguration.supportedStreamConfigs.length).isEqualTo(1);
        assertVirtualCameraConfiguration(virtualCameraConfiguration, CAMERA_WIDTH_1,
                CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1, CAMERA_SENSOR_ORIENTATION_1,
                lensFacing);
    }

    @Test
    public void unregisterCamera_unregistersCamera() throws Exception {
        VirtualCameraConfig config = createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1, CAMERA_NAME_1,
                CAMERA_SENSOR_ORIENTATION_1, CAMERA_LENS_FACING_1);
        mVirtualCameraController.registerCamera(config);

        mVirtualCameraController.unregisterCamera(config);

        verify(mVirtualCameraServiceMock).unregisterCamera(any());
    }

    @Test
    public void close_unregistersAllCameras() throws Exception {
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1, CAMERA_NAME_1,
                CAMERA_SENSOR_ORIENTATION_1, CAMERA_LENS_FACING_1));
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_2, CAMERA_HEIGHT_2, CAMERA_FORMAT_2, CAMERA_MAX_FPS_2, CAMERA_NAME_2,
                CAMERA_SENSOR_ORIENTATION_2, CAMERA_LENS_FACING_2));

        mVirtualCameraController.close();

        ArgumentCaptor<VirtualCameraConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(VirtualCameraConfiguration.class);
        verify(mVirtualCameraServiceMock, times(2)).registerCamera(any(),
                configurationCaptor.capture());
        List<VirtualCameraConfiguration> virtualCameraConfigurations =
                configurationCaptor.getAllValues();
        assertThat(virtualCameraConfigurations).hasSize(2);
        assertVirtualCameraConfiguration(virtualCameraConfigurations.get(0), CAMERA_WIDTH_1,
                CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1, CAMERA_SENSOR_ORIENTATION_1,
                CAMERA_LENS_FACING_1);
        assertVirtualCameraConfiguration(virtualCameraConfigurations.get(1), CAMERA_WIDTH_2,
                CAMERA_HEIGHT_2, CAMERA_FORMAT_2, CAMERA_MAX_FPS_2, CAMERA_SENSOR_ORIENTATION_2,
                CAMERA_LENS_FACING_2);
    }

    @Parameters(method = "getAllLensFacingDirections")
    @Test
    public void registerMultipleSameLensFacingCameras_withCustomCameraPolicy_throwsException(
            int lensFacing) {
        mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1, CAMERA_NAME_1,
                CAMERA_SENSOR_ORIENTATION_1, lensFacing));
        assertThrows(IllegalArgumentException.class,
                () -> mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                        CAMERA_WIDTH_2, CAMERA_HEIGHT_2, CAMERA_FORMAT_2, CAMERA_MAX_FPS_2,
                        CAMERA_NAME_2, CAMERA_SENSOR_ORIENTATION_2, lensFacing)));
    }

    @Parameters(method = "getAllLensFacingDirections")
    @Test
    public void registerCamera_withDefaultCameraPolicy_throwsException(int lensFacing) {
        mVirtualCameraController.close();
        mVirtualCameraController = new VirtualCameraController(
                mVirtualCameraServiceMock, DEVICE_POLICY_DEFAULT);

        assertThrows(IllegalArgumentException.class,
                () -> mVirtualCameraController.registerCamera(createVirtualCameraConfig(
                        CAMERA_WIDTH_1, CAMERA_HEIGHT_1, CAMERA_FORMAT_1, CAMERA_MAX_FPS_1,
                        CAMERA_NAME_1, CAMERA_SENSOR_ORIENTATION_1, lensFacing)));
    }

    private VirtualCameraConfig createVirtualCameraConfig(
            int width, int height, int format, int maximumFramesPerSecond,
            String name, int sensorOrientation, int lensFacing) {
        return new VirtualCameraConfig.Builder(name)
                .addStreamConfig(width, height, format, maximumFramesPerSecond)
                .setVirtualCameraCallback(mCallbackHandler, mVirtualCameraCallbackMock)
                .setSensorOrientation(sensorOrientation)
                .setLensFacing(lensFacing)
                .build();
    }

    private static void assertVirtualCameraConfiguration(
            VirtualCameraConfiguration configuration, int width, int height, int format,
            int maxFps, int sensorOrientation, int lensFacing) {
        assertThat(configuration.supportedStreamConfigs[0].width).isEqualTo(width);
        assertThat(configuration.supportedStreamConfigs[0].height).isEqualTo(height);
        assertThat(configuration.supportedStreamConfigs[0].pixelFormat).isEqualTo(format);
        assertThat(configuration.supportedStreamConfigs[0].maxFps).isEqualTo(maxFps);
        assertThat(configuration.sensorOrientation).isEqualTo(sensorOrientation);
        assertThat(configuration.lensFacing).isEqualTo(lensFacing);
    }

    private static Integer[] getAllLensFacingDirections() {
        return new Integer[] {
                LENS_FACING_BACK,
                LENS_FACING_FRONT
        };
    }
}
