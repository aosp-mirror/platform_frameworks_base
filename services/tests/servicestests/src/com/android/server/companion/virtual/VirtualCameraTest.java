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

package com.android.server.companion.virtual;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.camera.IVirtualCamera;
import android.companion.virtual.camera.IVirtualCameraSession;
import android.companion.virtual.camera.VirtualCamera;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtual.camera.VirtualCameraHalConfig;
import android.companion.virtual.camera.VirtualCameraSession;
import android.companion.virtual.camera.VirtualCameraStreamConfig;
import android.companion.virtual.flags.Flags;
import android.content.ComponentName;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.server.companion.virtual.camera.IVirtualCameraService;
import com.android.server.companion.virtual.camera.VirtualCameraController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class VirtualCameraTest {

    private static final String PKG = "com.android.virtualcamera";
    private static final String CLS = ".VirtualCameraService";
    public static final String CAMERA_DISPLAY_NAME = "testCamera";

    private final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getInstrumentation().getContext());
    private FakeVirtualCameraService mFakeVirtualCameraService;
    private VirtualCameraController mVirtualCameraController;

    @Rule public final VirtualDeviceRule mVirtualDeviceRule = new VirtualDeviceRule(mContext);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule =
            new AdoptShellPermissionsRule(
                    InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                    Manifest.permission.CREATE_VIRTUAL_DEVICE);

    @Before
    public void setUp() {
        mVirtualDeviceRule.withVirtualCameraControllerSupplier(() -> mVirtualCameraController);
        mFakeVirtualCameraService = new FakeVirtualCameraService();
        connectFakeService();
        mVirtualCameraController = new VirtualCameraController(mContext);
    }

    private VirtualDeviceImpl createVirtualDevice() {
        return mVirtualDeviceRule.createVirtualDevice(new VirtualDeviceParams.Builder().build());
    }

    private void connectFakeService() {
        mContext.addMockService(
                ComponentName.createRelative(PKG, CLS), mFakeVirtualCameraService.asBinder());
    }

    @RequiresFlagsEnabled(Flags.FLAG_VIRTUAL_CAMERA)
    @Test
    public void addVirtualCamera() {
        VirtualDeviceImpl virtualDevice = createVirtualDevice();
        VirtualCameraConfig config = createVirtualCameraConfig(null);
        IVirtualCamera.Default camera = new IVirtualCamera.Default();
        virtualDevice.registerVirtualCamera(camera);

        assertThat(mFakeVirtualCameraService.mCameras).contains(camera);
    }

    @RequiresFlagsEnabled(Flags.FLAG_VIRTUAL_CAMERA)
    @Test
    public void addVirtualCamera_serviceNotReady() {
        TestableContext context =
                new TestableContext(InstrumentationRegistry.getInstrumentation().getContext());
        VirtualCameraController virtualCameraController = new VirtualCameraController(context);
        mVirtualDeviceRule.withVirtualCameraControllerSupplier(() -> virtualCameraController);

        VirtualDeviceImpl virtualDevice =
                mVirtualDeviceRule.createVirtualDevice(new VirtualDeviceParams.Builder().build());
        IVirtualCamera.Default camera = new IVirtualCamera.Default();
        VirtualCameraConfig config = createVirtualCameraConfig(null);
        virtualDevice.registerVirtualCamera(camera);
        FakeVirtualCameraService fakeVirtualCameraService = new FakeVirtualCameraService();

        // Only add the service after connecting the camera
        virtualCameraController.onServiceConnected(
                ComponentName.createRelative(PKG, CLS), fakeVirtualCameraService.asBinder());

        assertThat(fakeVirtualCameraService.mCameras).contains(camera);
    }

    @RequiresFlagsEnabled(Flags.FLAG_VIRTUAL_CAMERA)
    @Test
    public void getCameraConfiguration() {
        VirtualDeviceImpl virtualDevice = createVirtualDevice();
        VirtualCameraSession virtualCameraSession = new VirtualCameraSession() {};
        VirtualCameraConfig config =
                new VirtualCameraConfig.Builder()
                        .addStreamConfiguration(10, 10, ImageFormat.RGB_565)
                        .setDisplayName(CAMERA_DISPLAY_NAME)
                        .setCallback(
                                new HandlerExecutor(new Handler(Looper.getMainLooper())),
                                () -> virtualCameraSession)
                        .build();

        VirtualCamera virtualCamera = new VirtualCamera(virtualDevice, config);

        VirtualCameraConfig returnedConfig = virtualCamera.getConfig();
        assertThat(returnedConfig).isNotNull();
        assertThat(returnedConfig.getDisplayName()).isEqualTo(CAMERA_DISPLAY_NAME);
        Set<VirtualCameraStreamConfig> streamConfigs = returnedConfig.getStreamConfigs();
        assertThat(streamConfigs).hasSize(1);
        VirtualCameraStreamConfig streamConfig =
                streamConfigs.toArray(new VirtualCameraStreamConfig[0])[0];
        assertThat(streamConfig.format).isEqualTo(ImageFormat.RGB_565);
        assertThat(streamConfig.width).isEqualTo(10);
        assertThat(streamConfig.height).isEqualTo(10);

        VirtualCameraHalConfig halConfig = virtualCamera.getHalConfig();
        assertThat(halConfig).isNotNull();
        assertThat(halConfig.displayName).isEqualTo(CAMERA_DISPLAY_NAME);
        assertThat(halConfig.streamConfigs).asList().hasSize(1);
        assertThat(halConfig.streamConfigs[0].format).isEqualTo(ImageFormat.RGB_565);
        assertThat(halConfig.streamConfigs[0].width).isEqualTo(10);
        assertThat(halConfig.streamConfigs[0].height).isEqualTo(10);
    }

    @RequiresFlagsEnabled(Flags.FLAG_VIRTUAL_CAMERA)
    @Test
    public void createCameraWithVirtualCameraInstance() {
        VirtualDeviceImpl virtualDevice = createVirtualDevice();

        VirtualCameraSession virtualCameraSession = new VirtualCameraSession() {};
        VirtualCameraConfig config = createVirtualCameraConfig(virtualCameraSession);
        VirtualCamera virtualCamera = new VirtualCamera(virtualDevice, config);

        assertThat(mFakeVirtualCameraService.mCameras).contains(virtualCamera);
        assertThat(virtualCamera.open()).isInstanceOf(IVirtualCameraSession.class);
    }

    @RequiresFlagsDisabled(Flags.FLAG_VIRTUAL_CAMERA)
    @Test
    public void createCameraDoesNothingWhenControllerIsNull() {
        mVirtualDeviceRule.withVirtualCameraControllerSupplier(() -> null);
        VirtualDeviceImpl virtualDevice = createVirtualDevice();
        IVirtualCamera.Default camera = new IVirtualCamera.Default();
        VirtualCameraConfig config = createVirtualCameraConfig(null);
        virtualDevice.registerVirtualCamera(camera);

        assertThat(mFakeVirtualCameraService.mCameras).doesNotContain(camera);
    }

    @NonNull
    private static VirtualCameraConfig createVirtualCameraConfig(
            VirtualCameraSession virtualCameraSession) {
        return new VirtualCameraConfig.Builder()
                .addStreamConfiguration(10, 10, ImageFormat.RGB_565)
                .setDisplayName(CAMERA_DISPLAY_NAME)
                .setCallback(
                        new HandlerExecutor(new Handler(Looper.getMainLooper())),
                        () -> virtualCameraSession)
                .build();
    }

    private static class FakeVirtualCameraService extends IVirtualCameraService.Stub {

        final Set<IVirtualCamera> mCameras = new HashSet<>();

        @Override
        public boolean registerCamera(IVirtualCamera camera) throws RemoteException {
            mCameras.add(camera);
            return true;
        }

        @Override
        public void unregisterCamera(IVirtualCamera camera) throws RemoteException {
            mCameras.remove(camera);
        }
    }
}
