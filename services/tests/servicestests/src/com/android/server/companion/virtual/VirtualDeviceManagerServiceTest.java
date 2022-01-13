/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Point;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualTouchEvent;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualDeviceManagerServiceTest {

    private static final String DEVICE_NAME = "device name";
    private static final int DISPLAY_ID = 2;
    private static final int PRODUCT_ID = 10;
    private static final int VENDOR_ID = 5;
    private static final int HEIGHT = 1800;
    private static final int WIDTH = 900;
    private static final Binder BINDER = new Binder("binder");

    private Context mContext;
    private VirtualDeviceImpl mDeviceImpl;
    private InputController mInputController;
    @Mock
    private InputController.NativeWrapper mNativeWrapperMock;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternalMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        mInputController = new InputController(new Object(), mNativeWrapperMock);
        mDeviceImpl = new VirtualDeviceImpl(mContext,
                /* association info */ null, new Binder(), /* uid */ 0, mInputController,
                (int associationId) -> {}, new VirtualDeviceParams.Builder().build());
    }

    @Test
    public void createVirtualKeyboard_noDisplay_failsSecurityException() {
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualKeyboard(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualMouse_noDisplay_failsSecurityException() {
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualMouse(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualTouchscreen_noDisplay_failsSecurityException() {
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualTouchscreen(DISPLAY_ID, DEVICE_NAME,
                        VENDOR_ID, PRODUCT_ID, BINDER, new Point(WIDTH, HEIGHT)));
    }

    @Test
    public void createVirtualKeyboard_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualKeyboard(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualMouse_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualMouse(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualTouchscreen_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualTouchscreen(DISPLAY_ID, DEVICE_NAME,
                        VENDOR_ID, PRODUCT_ID, BINDER, new Point(WIDTH, HEIGHT)));
    }

    @Test
    public void createVirtualKeyboard_hasDisplay_obtainFileDescriptor() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        mDeviceImpl.createVirtualKeyboard(DISPLAY_ID, DEVICE_NAME, VENDOR_ID, PRODUCT_ID,
                BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.mInputDeviceFds).isNotEmpty();
        verify(mNativeWrapperMock).openUinputKeyboard(DEVICE_NAME, VENDOR_ID, PRODUCT_ID);
    }

    @Test
    public void createVirtualMouse_hasDisplay_obtainFileDescriptor() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        mDeviceImpl.createVirtualMouse(DISPLAY_ID, DEVICE_NAME, VENDOR_ID, PRODUCT_ID,
                BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.mInputDeviceFds).isNotEmpty();
        verify(mNativeWrapperMock).openUinputMouse(DEVICE_NAME, VENDOR_ID, PRODUCT_ID);
    }

    @Test
    public void createVirtualTouchscreen_hasDisplay_obtainFileDescriptor() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        mDeviceImpl.createVirtualTouchscreen(DISPLAY_ID, DEVICE_NAME, VENDOR_ID, PRODUCT_ID,
                BINDER, new Point(WIDTH, HEIGHT));
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.mInputDeviceFds).isNotEmpty();
        verify(mNativeWrapperMock).openUinputTouchscreen(DEVICE_NAME, VENDOR_ID, PRODUCT_ID, HEIGHT,
                WIDTH);
    }

    @Test
    public void sendKeyEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendKeyEvent(BINDER, new VirtualKeyEvent.Builder()
                                .setKeyCode(KeyEvent.KEYCODE_A)
                                .setAction(VirtualKeyEvent.ACTION_DOWN).build()));
    }

    @Test
    public void sendKeyEvent_hasFd_writesEvent() {
        final int fd = 1;
        final int keyCode = KeyEvent.KEYCODE_A;
        final int action = VirtualKeyEvent.ACTION_UP;
        mInputController.mInputDeviceFds.put(BINDER, fd);
        mDeviceImpl.sendKeyEvent(BINDER, new VirtualKeyEvent.Builder().setKeyCode(keyCode)
                .setAction(action).build());
        verify(mNativeWrapperMock).writeKeyEvent(fd, keyCode, action);
    }

    @Test
    public void sendButtonEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendButtonEvent(BINDER,
                                new VirtualMouseButtonEvent.Builder()
                                        .setButtonCode(VirtualMouseButtonEvent.BUTTON_BACK)
                                        .setAction(VirtualMouseButtonEvent.ACTION_BUTTON_PRESS)
                                        .build()));
    }

    @Test
    public void sendButtonEvent_hasFd_writesEvent() {
        final int fd = 1;
        final int buttonCode = VirtualMouseButtonEvent.BUTTON_BACK;
        final int action = VirtualMouseButtonEvent.ACTION_BUTTON_PRESS;
        mInputController.mInputDeviceFds.put(BINDER, fd);
        mDeviceImpl.sendButtonEvent(BINDER, new VirtualMouseButtonEvent.Builder()
                .setButtonCode(buttonCode)
                .setAction(action).build());
        verify(mNativeWrapperMock).writeButtonEvent(fd, buttonCode, action);
    }

    @Test
    public void sendRelativeEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendRelativeEvent(BINDER,
                                new VirtualMouseRelativeEvent.Builder().setRelativeX(
                                        0.0f).setRelativeY(0.0f).build()));
    }

    @Test
    public void sendRelativeEvent_hasFd_writesEvent() {
        final int fd = 1;
        final float x = -0.2f;
        final float y = 0.7f;
        mInputController.mInputDeviceFds.put(BINDER, fd);
        mDeviceImpl.sendRelativeEvent(BINDER, new VirtualMouseRelativeEvent.Builder()
                .setRelativeX(x).setRelativeY(y).build());
        verify(mNativeWrapperMock).writeRelativeEvent(fd, x, y);
    }

    @Test
    public void sendScrollEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendScrollEvent(BINDER,
                                new VirtualMouseScrollEvent.Builder()
                                        .setXAxisMovement(-1f)
                                        .setYAxisMovement(1f).build()));
    }

    @Test
    public void sendScrollEvent_hasFd_writesEvent() {
        final int fd = 1;
        final float x = 0.5f;
        final float y = 1f;
        mInputController.mInputDeviceFds.put(BINDER, fd);
        mDeviceImpl.sendScrollEvent(BINDER, new VirtualMouseScrollEvent.Builder()
                .setXAxisMovement(x)
                .setYAxisMovement(y).build());
        verify(mNativeWrapperMock).writeScrollEvent(fd, x, y);
    }

    @Test
    public void sendTouchEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder()
                                .setX(0.0f)
                                .setY(0.0f)
                                .setAction(VirtualTouchEvent.ACTION_UP)
                                .setPointerId(1)
                                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                                .build()));
    }

    @Test
    public void sendTouchEvent_hasFd_writesEvent_withoutPressureOrMajorAxisSize() {
        final int fd = 1;
        final int pointerId = 5;
        final int toolType = VirtualTouchEvent.TOOL_TYPE_FINGER;
        final float x = 100.5f;
        final float y = 200.5f;
        final int action = VirtualTouchEvent.ACTION_UP;
        mInputController.mInputDeviceFds.put(BINDER, fd);
        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder().setX(x)
                .setY(y).setAction(action).setPointerId(pointerId).setToolType(toolType).build());
        verify(mNativeWrapperMock).writeTouchEvent(fd, pointerId, toolType, action, x, y, Float.NaN,
                Float.NaN);
    }

    @Test
    public void sendTouchEvent_hasFd_writesEvent() {
        final int fd = 1;
        final int pointerId = 5;
        final int toolType = VirtualTouchEvent.TOOL_TYPE_FINGER;
        final float x = 100.5f;
        final float y = 200.5f;
        final int action = VirtualTouchEvent.ACTION_UP;
        final float pressure = 1.0f;
        final float majorAxisSize = 10.0f;
        mInputController.mInputDeviceFds.put(BINDER, fd);
        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder().setX(x)
                .setY(y).setAction(action).setPointerId(pointerId).setToolType(toolType)
                .setPressure(pressure).setMajorAxisSize(majorAxisSize).build());
        verify(mNativeWrapperMock).writeTouchEvent(fd, pointerId, toolType, action, x, y, pressure,
                majorAxisSize);
    }
}
