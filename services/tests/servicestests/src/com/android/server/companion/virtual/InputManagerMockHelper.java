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
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.testing.TestableLooper;
import android.view.InputDevice;

import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A test utility class used to share the logic for setting up {@link InputManager}'s callback for
 * when a virtual input device being added.
 */
class InputManagerMockHelper {
    private final TestableLooper mTestableLooper;
    private final InputController.NativeWrapper mNativeWrapperMock;
    private final IInputManager mIInputManagerMock;
    private final List<InputDevice> mDevices = new ArrayList<>();
    private IInputDevicesChangedListener mDevicesChangedListener;

    InputManagerMockHelper(TestableLooper testableLooper,
            InputController.NativeWrapper nativeWrapperMock, IInputManager iInputManagerMock)
            throws Exception {
        mTestableLooper = testableLooper;
        mNativeWrapperMock = nativeWrapperMock;
        mIInputManagerMock = iInputManagerMock;

        doAnswer(this::handleNativeOpenInputDevice).when(mNativeWrapperMock).openUinputMouse(
                anyString(), anyInt(), anyInt(), anyString());
        doAnswer(this::handleNativeOpenInputDevice).when(mNativeWrapperMock).openUinputKeyboard(
                anyString(), anyInt(), anyInt(), anyString());
        doAnswer(this::handleNativeOpenInputDevice).when(mNativeWrapperMock).openUinputTouchscreen(
                anyString(), anyInt(), anyInt(), anyString(), anyInt(), anyInt());

        doAnswer(inv -> {
            mDevicesChangedListener = inv.getArgument(0);
            return null;
        }).when(mIInputManagerMock).registerInputDevicesChangedListener(notNull());
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);
        doAnswer(inv -> mDevices.get(inv.getArgument(0)))
                .when(mIInputManagerMock).getInputDevice(anyInt());
        doNothing().when(mIInputManagerMock).addUniqueIdAssociation(anyString(), anyString());
        doNothing().when(mIInputManagerMock).removeUniqueIdAssociation(anyString());

        // Set a new instance of InputManager for testing that uses the IInputManager mock as the
        // interface to the server.
        InputManager.resetInstance(mIInputManagerMock);
    }

    private Void handleNativeOpenInputDevice(InvocationOnMock inv) {
        Objects.requireNonNull(mDevicesChangedListener,
                "InputController did not register an InputDevicesChangedListener.");
        // We only use a subset of the fields of InputDevice in InputController.
        final InputDevice device = new InputDevice(mDevices.size() /*id*/, 1 /*generation*/, 0,
                inv.getArgument(0) /*name*/, inv.getArgument(1) /*vendorId*/,
                inv.getArgument(2) /*productId*/, inv.getArgument(3) /*descriptor*/,
                true /*isExternal*/, 0 /*sources*/, 0 /*keyboardType*/,
                null /*keyCharacterMap*/, false /*hasVibrator*/, false /*hasMic*/,
                false /*hasButtonUnderPad*/, false /*hasSensor*/, false /*hasBattery*/);
        mDevices.add(device);
        try {
            mDevicesChangedListener.onInputDevicesChanged(
                    mDevices.stream().flatMapToInt(
                            d -> IntStream.of(d.getId(), d.getGeneration())).toArray());
        } catch (RemoteException ignored) {
        }
        // Process the device added notification.
        mTestableLooper.processAllMessages();
        return null;
    }
}
