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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.flags.Flags;
import android.content.AttributionSource;
import android.content.Context;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IDisplayManager;
import android.net.MacAddress;
import android.os.Binder;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.companion.virtual.camera.VirtualCameraController;
import com.android.server.input.InputManagerInternal;
import com.android.server.sensors.SensorManagerInternal;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Test rule to generate instances of {@link VirtualDeviceImpl}. */
public class VirtualDeviceRule implements TestRule {

    private static final int DEVICE_OWNER_UID = 50;
    private static final int VIRTUAL_DEVICE_ID = 42;

    private final Context mContext;
    private InputController mInputController;
    private CameraAccessController mCameraAccessController;
    private AssociationInfo mAssociationInfo;
    private VirtualDeviceManagerService mVdms;
    private VirtualDeviceManagerInternal mLocalService;
    private VirtualDeviceLog mVirtualDeviceLog;

    // Mocks
    @Mock private InputController.NativeWrapper mNativeWrapperMock;
    @Mock private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock private IDisplayManager mIDisplayManager;
    @Mock private VirtualDeviceImpl.PendingTrampolineCallback mPendingTrampolineCallback;
    @Mock private DevicePolicyManager mDevicePolicyManagerMock;
    @Mock private InputManagerInternal mInputManagerInternalMock;
    @Mock private SensorManagerInternal mSensorManagerInternalMock;
    @Mock private IVirtualDeviceActivityListener mActivityListener;
    @Mock private IVirtualDeviceSoundEffectListener mSoundEffectListener;
    @Mock private Consumer<ArraySet<Integer>> mRunningAppsChangedCallback;
    @Mock private CameraAccessController.CameraAccessBlockedCallback mCameraAccessBlockedCallback;

    // Test instance suppliers
    private Supplier<VirtualCameraController> mVirtualCameraControllerSupplier;

    /**
     * Create a new {@link VirtualDeviceRule}
     *
     * @param context The context to be used with the rule.
     */
    public VirtualDeviceRule(@NonNull Context context) {
        Objects.requireNonNull(context);
        mContext = context;
    }

    /**
     * Sets a supplier that will supply an instance of {@link VirtualCameraController}. If the
     * supplier returns null, a new instance will be created.
     */
    public VirtualDeviceRule withVirtualCameraControllerSupplier(
            Supplier<VirtualCameraController> virtualCameraControllerSupplier) {
        mVirtualCameraControllerSupplier = virtualCameraControllerSupplier;
        return this;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                init(new TestableContext(mContext));
                base.evaluate();
            }
        };
    }

    private void init(@NonNull TestableContext context) {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        doReturn(true).when(mInputManagerInternalMock).setVirtualMousePointerDisplayId(anyInt());
        doNothing().when(mInputManagerInternalMock).setPointerAcceleration(anyFloat(), anyInt());
        doNothing().when(mInputManagerInternalMock).setPointerIconVisible(anyBoolean(), anyInt());
        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternalMock);

        LocalServices.removeServiceForTest(SensorManagerInternal.class);
        LocalServices.addService(SensorManagerInternal.class, mSensorManagerInternalMock);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.uniqueId = "uniqueId";
        doReturn(displayInfo).when(mDisplayManagerInternalMock).getDisplayInfo(anyInt());
        doReturn(Display.INVALID_DISPLAY).when(mDisplayManagerInternalMock)
                .getDisplayIdToMirror(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        context.addMockSystemService(DevicePolicyManager.class, mDevicePolicyManagerMock);

        // Allow virtual devices to be created on the looper thread for testing.
        final InputController.DeviceCreationThreadVerifier threadVerifier = () -> true;
        mInputController =
                new InputController(
                        mNativeWrapperMock,
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getMainThreadHandler(),
                        context.getSystemService(WindowManager.class),
                        threadVerifier);
        mCameraAccessController =
                new CameraAccessController(context, mLocalService, mCameraAccessBlockedCallback);

        mAssociationInfo =
                new AssociationInfo(
                        /* associationId= */ 1,
                        0,
                        null,
                        null,
                        MacAddress.BROADCAST_ADDRESS,
                        "",
                        null,
                        null,
                        true,
                        false,
                        false,
                        0,
                        0,
                        -1);

        mVdms = new VirtualDeviceManagerService(context);
        mLocalService = mVdms.getLocalServiceInstance();
        mVirtualDeviceLog = new VirtualDeviceLog(context);
    }

    /**
     * Create a {@link VirtualDeviceImpl} with the required mocks
     *
     * @param params See {@link
     *     android.companion.virtual.VirtualDeviceManager#createVirtualDevice(int,
     *     VirtualDeviceParams)}
     */
    public VirtualDeviceImpl createVirtualDevice(VirtualDeviceParams params) {
        VirtualCameraController virtualCameraController = mVirtualCameraControllerSupplier.get();
        if (Flags.virtualCamera()) {
            if (virtualCameraController == null) {
                virtualCameraController = new VirtualCameraController(mContext);
            }
        }

        VirtualDeviceImpl virtualDeviceImpl =
                new VirtualDeviceImpl(
                        mContext,
                        mAssociationInfo,
                        mVdms,
                        mVirtualDeviceLog,
                        new Binder(),
                        new AttributionSource(
                                DEVICE_OWNER_UID,
                                "com.android.virtualdevice.test",
                                "virtualdevicerule"),
                        VIRTUAL_DEVICE_ID,
                        mInputController,
                        mCameraAccessController,
                        mPendingTrampolineCallback,
                        mActivityListener,
                        mSoundEffectListener,
                        mRunningAppsChangedCallback,
                        params,
                        new DisplayManagerGlobal(mIDisplayManager),
                        virtualCameraController);
        mVdms.addVirtualDevice(virtualDeviceImpl);
        return virtualDeviceImpl;
    }
}
