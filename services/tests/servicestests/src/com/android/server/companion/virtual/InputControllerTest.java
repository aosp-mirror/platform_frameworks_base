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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InputControllerTest {

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
                new Handler(TestableLooper.get(this).getLooper()), threadVerifier);
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
        mInputController.createMouse("name", /*vendorId= */ 1, /*productId= */ 1, deviceToken,
                /* displayId= */ 1);
        verify(mNativeWrapperMock).openUinputMouse(eq("name"), eq(1), eq(1), anyString());
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(1));
        final IBinder deviceToken2 = new Binder();
        mInputController.createMouse("name", /*vendorId= */ 1, /*productId= */ 1, deviceToken2,
                /* displayId= */ 2);
        verify(mNativeWrapperMock, times(2)).openUinputMouse(eq("name"), eq(1), eq(1), anyString());
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(2));
        mInputController.unregisterInputDevice(deviceToken);
        verify(mInputManagerInternalMock).setVirtualMousePointerDisplayId(eq(1));
    }
}
