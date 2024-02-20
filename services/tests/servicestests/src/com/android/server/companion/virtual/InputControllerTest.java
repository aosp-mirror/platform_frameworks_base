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

package com.android.server.companion.virtual;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.AttributionSource;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

import com.android.input.flags.Flags;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InputControllerTest {
    private static final String LANGUAGE_TAG = "en-US";
    private static final String LAYOUT_TYPE = "qwerty";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private InputManagerInternal mInputManagerInternalMock;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock
    private InputController.NativeWrapper mNativeWrapperMock;
    @Mock
    private IInputManager mIInputManagerMock;

    private InputManagerMockHelper mInputManagerMockHelper;
    private InputController mInputController;

    @Before
    public void setUp() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_POINTER_CHOREOGRAPHER);

        MockitoAnnotations.initMocks(this);
        mInputManagerMockHelper = new InputManagerMockHelper(
                TestableLooper.get(this), mNativeWrapperMock, mIInputManagerMock);

        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternalMock);

        setUpDisplay(1 /* displayId */);
        setUpDisplay(2 /* displayId */);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        // Allow virtual devices to be created on the looper thread for testing.
        final InputController.DeviceCreationThreadVerifier threadVerifier = () -> true;
        mInputController = new InputController(mNativeWrapperMock,
                new Handler(TestableLooper.get(this).getLooper()),
                InstrumentationRegistry.getTargetContext().getSystemService(WindowManager.class),
                AttributionSource.myAttributionSource(),
                threadVerifier);
    }

    void setUpDisplay(int displayId) {
        final String uniqueId = "uniqueId:" + displayId;
        doAnswer((inv) -> {
            final DisplayInfo displayInfo = new DisplayInfo();
            displayInfo.uniqueId = uniqueId;
            return displayInfo;
        }).when(mDisplayManagerInternalMock).getDisplayInfo(eq(displayId));
        mInputManagerMockHelper.addDisplayIdMapping(uniqueId, displayId);
    }

    @After
    public void tearDown() {
        mInputManagerMockHelper.tearDown();
    }

    @Test
    public void registerInputDevice_deviceCreation_hasDeviceId() throws Exception {
        final IBinder device1Token = new Binder("device1");
        mInputController.createMouse("mouse", /*vendorId= */ 1, /*productId= */ 1, device1Token,
                /* displayId= */ 1);
        int device1Id = mInputController.getInputDeviceId(device1Token);

        final IBinder device2Token = new Binder("device2");
        mInputController.createKeyboard("keyboard", /*vendorId= */2, /*productId= */ 2,
                device2Token, 2, LANGUAGE_TAG, LAYOUT_TYPE);
        int device2Id = mInputController.getInputDeviceId(device2Token);

        assertWithMessage("Different devices should have different id").that(
                device1Id).isNotEqualTo(device2Id);


        int[] deviceIds = InputManagerGlobal.getInstance().getInputDeviceIds();
        assertWithMessage("InputManager's deviceIds list should contain id of device 1").that(
                deviceIds).asList().contains(device1Id);
        assertWithMessage("InputManager's deviceIds list should contain id of device 2").that(
                deviceIds).asList().contains(device2Id);

    }

    @Test
    public void unregisterInputDevice_allMiceUnregistered_clearPointerDisplayId() throws Exception {
        final IBinder deviceToken = new Binder();
        mInputController.createMouse("name", /*vendorId= */ 1, /*productId= */ 1, deviceToken,
                /* displayId= */ 1);
        verify(mNativeWrapperMock).openUinputMouse(eq("name"), eq(1), eq(1), anyString());
        mInputController.unregisterInputDevice(deviceToken);
    }

    @Test
    public void unregisterInputDevice_anotherMouseExists_setPointerDisplayIdOverride()
            throws Exception {
        final IBinder deviceToken = new Binder();
        mInputController.createMouse("mouse1", /*vendorId= */ 1, /*productId= */ 1, deviceToken,
                /* displayId= */ 1);
        verify(mNativeWrapperMock).openUinputMouse(eq("mouse1"), eq(1), eq(1), anyString());
        final IBinder deviceToken2 = new Binder();
        mInputController.createMouse("mouse2", /*vendorId= */ 1, /*productId= */ 1, deviceToken2,
                /* displayId= */ 2);
        verify(mNativeWrapperMock).openUinputMouse(eq("mouse2"), eq(1), eq(1), anyString());
        mInputController.unregisterInputDevice(deviceToken);
    }

    @Test
    public void createNavigationTouchpad_hasDeviceId() throws Exception {
        final IBinder deviceToken = new Binder();
        mInputController.createNavigationTouchpad("name", /*vendorId= */ 1, /*productId= */ 1,
                deviceToken, /* displayId= */ 1, /* touchpadHeight= */ 50, /* touchpadWidth= */ 50);

        int deviceId = mInputController.getInputDeviceId(deviceToken);
        int[] deviceIds = InputManagerGlobal.getInstance().getInputDeviceIds();

        assertWithMessage("InputManager's deviceIds list should contain id of the device").that(
            deviceIds).asList().contains(deviceId);
    }

    @Test
    public void createNavigationTouchpad_setsTypeAssociation() throws Exception {
        final IBinder deviceToken = new Binder();
        mInputController.createNavigationTouchpad("name", /*vendorId= */ 1, /*productId= */ 1,
                deviceToken, /* displayId= */ 1, /* touchpadHeight= */ 50, /* touchpadWidth= */ 50);

        verify(mInputManagerInternalMock).setTypeAssociation(
                startsWith("virtualNavigationTouchpad:"), eq("touchNavigation"));
    }

    @Test
    public void createAndUnregisterNavigationTouchpad_unsetsTypeAssociation() throws Exception {
        final IBinder deviceToken = new Binder();
        mInputController.createNavigationTouchpad("name", /*vendorId= */ 1, /*productId= */ 1,
                deviceToken, /* displayId= */ 1, /* touchpadHeight= */ 50, /* touchpadWidth= */ 50);

        mInputController.unregisterInputDevice(deviceToken);

        verify(mInputManagerInternalMock).unsetTypeAssociation(
                startsWith("virtualNavigationTouchpad:"));
    }

    @Test
    public void createKeyboard_addAndRemoveKeyboardLayoutAssociation() throws Exception {
        final IBinder deviceToken = new Binder("device");

        mInputController.createKeyboard("keyboard", /*vendorId= */2, /*productId= */ 2, deviceToken,
                2, LANGUAGE_TAG, LAYOUT_TYPE);
        verify(mInputManagerInternalMock).addKeyboardLayoutAssociation(anyString(),
                eq(LANGUAGE_TAG), eq(LAYOUT_TYPE));

        mInputController.unregisterInputDevice(deviceToken);
        verify(mInputManagerInternalMock).removeKeyboardLayoutAssociation(anyString());
    }

    @Test
    public void createInputDevice_duplicateNamesAreNotAllowed() throws Exception {
        final IBinder deviceToken1 = new Binder("deviceToken1");
        final IBinder deviceToken2 = new Binder("deviceToken2");

        final String sharedDeviceName = "DeviceName";

        mInputController.createDpad(sharedDeviceName, /*vendorId= */4, /*productId=*/4,
                deviceToken1, 1);
        assertThrows("Device names need to be unique",
                InputController.DeviceCreationException.class,
                () -> mInputController.createDpad(
                        sharedDeviceName, /*vendorId= */5, /*productId=*/5, deviceToken2, 2));
    }
}
