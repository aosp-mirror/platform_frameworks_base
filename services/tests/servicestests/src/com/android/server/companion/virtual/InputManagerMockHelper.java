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
import static org.mockito.Mockito.when;

import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.RemoteException;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.InputDevice;

import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A test utility class used to share the logic for setting up
 * {@link  android.hardware.input.InputManager}'s callback for
 * when a virtual input device being added.
 */
class InputManagerMockHelper {
    private final TestableLooper mTestableLooper;
    private final InputController.NativeWrapper mNativeWrapperMock;
    private final IInputManager mIInputManagerMock;
    private final InputManagerGlobal.TestSession mInputManagerGlobalSession;
    private final List<InputDevice> mDevices = new ArrayList<>();
    private IInputDevicesChangedListener mDevicesChangedListener;
    private final Map<String /* uniqueId */, Integer /* displayId */> mDisplayIdMapping =
            new HashMap<>();
    private final Map<String /* phys */, String /* uniqueId */> mUniqueIdAssociation =
            new HashMap<>();

    InputManagerMockHelper(TestableLooper testableLooper,
            InputController.NativeWrapper nativeWrapperMock, IInputManager iInputManagerMock)
            throws Exception {
        mTestableLooper = testableLooper;
        mNativeWrapperMock = nativeWrapperMock;
        mIInputManagerMock = iInputManagerMock;

        doAnswer(this::handleNativeOpenInputDevice).when(mNativeWrapperMock).openUinputMouse(
                anyString(), anyInt(), anyInt(), anyString());
        doAnswer(this::handleNativeOpenInputDevice).when(mNativeWrapperMock).openUinputDpad(
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
        doAnswer(inv -> mUniqueIdAssociation.put(inv.getArgument(0), inv.getArgument(1))).when(
                mIInputManagerMock).addUniqueIdAssociation(anyString(), anyString());
        doAnswer(inv -> mUniqueIdAssociation.remove(inv.getArgument(0))).when(
                mIInputManagerMock).removeUniqueIdAssociation(anyString());

        // Set a new instance of InputManager for testing that uses the IInputManager mock as the
        // interface to the server.
        mInputManagerGlobalSession = InputManagerGlobal.createTestSession(mIInputManagerMock);
    }

    public void tearDown() {
        if (mInputManagerGlobalSession != null) {
            mInputManagerGlobalSession.close();
        }
    }

    public void addDisplayIdMapping(String uniqueId, int displayId) {
        mDisplayIdMapping.put(uniqueId, displayId);
    }

    private long handleNativeOpenInputDevice(InvocationOnMock inv) {
        Objects.requireNonNull(mDevicesChangedListener,
                "InputController did not register an InputDevicesChangedListener.");

        final String phys = inv.getArgument(3);
        final InputDevice device = new InputDevice.Builder()
                .setId(mDevices.size())
                .setName(inv.getArgument(0))
                .setVendorId(inv.getArgument(1))
                .setProductId(inv.getArgument(2))
                .setDescriptor(phys)
                .setExternal(true)
                .setAssociatedDisplayId(
                        mDisplayIdMapping.getOrDefault(mUniqueIdAssociation.get(phys),
                                Display.INVALID_DISPLAY))
                .build();

        mDevices.add(device);
        try {
            mDevicesChangedListener.onInputDevicesChanged(
                    mDevices.stream().flatMapToInt(
                            d -> IntStream.of(d.getId(), d.getGeneration())).toArray());
        } catch (RemoteException ignored) {
        }
        // Process the device added notification.
        mTestableLooper.processAllMessages();
        // Return a placeholder pointer to the native input device.
        return 1L;
    }
}
