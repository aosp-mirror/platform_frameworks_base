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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;

import org.junit.Before;
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
        MockitoAnnotations.initMocks(this);
        mInputManagerMockHelper = new InputManagerMockHelper(
                TestableLooper.get(this), mNativeWrapperMock, mIInputManagerMock);

        doReturn(true).when(mInputManagerInternalMock).setVirtualMousePointerDisplayId(anyInt());
        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternalMock);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.uniqueId = "uniqueId";
        doReturn(displayInfo).when(mDisplayManagerInternalMock).getDisplayInfo(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        // Allow virtual devices to be created on the looper thread for testing.
        final InputController.DeviceCreationThreadVerifier threadVerifier = () -> true;
        mInputController = new InputController(new Object(), mNativeWrapperMock,
                new Handler(TestableLooper.get(this).getLooper()),
                InstrumentationRegistry.getTargetContext().getSystemService(WindowManager.class),
                threadVerifier);
    }

    @Test
    public void registerInputDevice_deviceCreation_hasDeviceId() {
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


        int[] deviceIds = InputManager.getInstance().getInputDeviceIds();
        assertWithMessage("InputManager's deviceIds list should contain id of device 1").that(
                deviceIds).asList().contains(device1Id);
        assertWithMessage("InputManager's deviceIds list should contain id of device 2").that(
                deviceIds).asList().contains(device2Id);

    }

    @Test
    public void unregisterInputDevice_allMiceUnregistered_clearPointerDisplayId() {
        final IBinder deviceToken = new Binder();
        mInputController.createMouse("name", /*vendorId= */ 1, /*productId= */ 1, deviceToken,
                /* displayId= */ 1);
        verify(mNativeWrapperMock).openUinputMouse(eq("name"), eq(1), eq(1), anyString());
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(1));
        doReturn(1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mInputController.unregisterInputDevice(deviceToken);
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(
                eq(Display.INVALID_DISPLAY));
    }

    @Test
    public void unregisterInputDevice_anotherMouseExists_setPointerDisplayIdOverride() {
        final IBinder deviceToken = new Binder();
        mInputController.createMouse("mouse1", /*vendorId= */ 1, /*productId= */ 1, deviceToken,
                /* displayId= */ 1);
        verify(mNativeWrapperMock).openUinputMouse(eq("mouse1"), eq(1), eq(1), anyString());
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(1));
        final IBinder deviceToken2 = new Binder();
        mInputController.createMouse("mouse2", /*vendorId= */ 1, /*productId= */ 1, deviceToken2,
                /* displayId= */ 2);
        verify(mNativeWrapperMock).openUinputMouse(eq("mouse2"), eq(1), eq(1), anyString());
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(2));
        mInputController.unregisterInputDevice(deviceToken);
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(1));
    }

    @Test
    public void createNavigationTouchpad_hasDeviceId() {
        final IBinder deviceToken = new Binder();
        mInputController.createNavigationTouchpad("name", /*vendorId= */ 1, /*productId= */ 1,
                deviceToken, /* displayId= */ 1, /* touchpadHeight= */ 50, /* touchpadWidth= */ 50);

        int deviceId = mInputController.getInputDeviceId(deviceToken);
        int[] deviceIds = InputManager.getInstance().getInputDeviceIds();

        assertWithMessage("InputManager's deviceIds list should contain id of the device").that(
            deviceIds).asList().contains(deviceId);
    }

    @Test
    public void createNavigationTouchpad_setsTypeAssociation() {
        final IBinder deviceToken = new Binder();
        mInputController.createNavigationTouchpad("name", /*vendorId= */ 1, /*productId= */ 1,
                deviceToken, /* displayId= */ 1, /* touchpadHeight= */ 50, /* touchpadWidth= */ 50);

        verify(mInputManagerInternalMock).setTypeAssociation(
                startsWith("virtualNavigationTouchpad:"), eq("touchNavigation"));
    }

    @Test
    public void createAndUnregisterNavigationTouchpad_unsetsTypeAssociation() {
        final IBinder deviceToken = new Binder();
        mInputController.createNavigationTouchpad("name", /*vendorId= */ 1, /*productId= */ 1,
                deviceToken, /* displayId= */ 1, /* touchpadHeight= */ 50, /* touchpadWidth= */ 50);

        mInputController.unregisterInputDevice(deviceToken);

        verify(mInputManagerInternalMock).unsetTypeAssociation(
                startsWith("virtualNavigationTouchpad:"));
    }

    @Test
    public void createKeyboard_addAndRemoveKeyboardLayoutAssociation() {
        final IBinder deviceToken = new Binder("device");

        mInputController.createKeyboard("keyboard", /*vendorId= */2, /*productId= */ 2, deviceToken,
                2, LANGUAGE_TAG, LAYOUT_TYPE);
        verify(mInputManagerInternalMock).addKeyboardLayoutAssociation(anyString(),
                eq(LANGUAGE_TAG), eq(LAYOUT_TYPE));

        mInputController.unregisterInputDevice(deviceToken);
        verify(mInputManagerInternalMock).removeKeyboardLayoutAssociation(anyString());
    }

    @Test
    public void createInputDevice_tooLongNameRaisesException() {
        final IBinder deviceToken = new Binder("device");
        // The underlying uinput implementation only supports device names up to 80 bytes. This
        // string is all ASCII characters, therefore if we have more than 80 ASCII characters we
        // will have more than 80 bytes.
        String deviceName =
                "This.is.a.very.long.device.name.that.exceeds.the.maximum.length.of.80.bytes"
                        + ".by.a.couple.bytes";

        assertThrows(RuntimeException.class, () -> {
            mInputController.createDpad(deviceName, /*vendorId= */3, /*productId=*/3, deviceToken,
                    1);
        });
    }

    @Test
    public void createInputDevice_tooLongDeviceNameRaisesException() {
        final IBinder deviceToken = new Binder("device");
        // The underlying uinput implementation only supports device names up to 80 bytes (including
        // a 0-byte terminator).
        // This string is 79 characters and 80 bytes (including the 0-byte terminator)
        String deviceName =
                "This.is.a.very.long.device.name.that.exceeds.the.maximum.length01234567890123456";

        assertThrows(RuntimeException.class, () -> {
            mInputController.createDpad(deviceName, /*vendorId= */3, /*productId=*/3, deviceToken,
                    1);
        });
    }

    @Test
    public void createInputDevice_stringWithLessThanMaxCharsButMoreThanMaxBytesRaisesException() {
        final IBinder deviceToken = new Binder("device1");

        // Has only 39 characters but is 109 bytes as utf-8
        String device_name =
                "░▄▄▄▄░\n" +
                "▀▀▄██►\n" +
                "▀▀███►\n" +
                "░▀███►░█►\n" +
                "▒▄████▀▀";

        assertThrows(RuntimeException.class, () -> {
            mInputController.createDpad(device_name, /*vendorId= */5, /*productId=*/5,
                    deviceToken, 1);
        });
    }

    @Test
    public void createInputDevice_duplicateNamesAreNotAllowed() {
        final IBinder deviceToken1 = new Binder("deviceToken1");
        final IBinder deviceToken2 = new Binder("deviceToken2");

        final String sharedDeviceName = "DeviceName";

        mInputController.createDpad(sharedDeviceName, /*vendorId= */4, /*productId=*/4,
                deviceToken1, 1);
        assertThrows("Device names need to be unique", RuntimeException.class, () -> {
            mInputController.createDpad(sharedDeviceName, /*vendorId= */5, /*productId=*/5,
                    deviceToken2, 2);
        });
    }
}
