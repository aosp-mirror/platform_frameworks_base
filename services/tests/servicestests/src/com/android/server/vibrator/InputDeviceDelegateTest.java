/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link InputDeviceDelegate}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:InputDeviceDelegateTest
 */
@Presubmit
public class InputDeviceDelegateTest {

    private static final int UID = Process.ROOT_UID;
    private static final String PACKAGE_NAME = "package";
    private static final String REASON = "some reason";
    private static final VibrationAttributes VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build();
    private static final CombinedVibration SYNCED_EFFECT =
            CombinedVibration.createParallel(VibrationEffect.createOneShot(100, 255));

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private IInputManager mIInputManagerMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private InputDeviceDelegate mInputDeviceDelegate;
    private IInputDevicesChangedListener mIInputDevicesChangedListener;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        InputManager inputManager = InputManager.resetInstance(mIInputManagerMock);

        when(mContextSpy.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager);
        doAnswer(invocation -> mIInputDevicesChangedListener = invocation.getArgument(0))
                .when(mIInputManagerMock).registerInputDevicesChangedListener(any());

        mInputDeviceDelegate = new InputDeviceDelegate(
                mContextSpy, new Handler(mTestLooper.getLooper()));
        mInputDeviceDelegate.onSystemReady();
    }

    @After
    public void tearDown() throws Exception {
        InputManager.clearInstance();
    }

    @Test
    public void beforeSystemReady_ignoresAnyUpdate() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);
        InputDeviceDelegate inputDeviceDelegate = new InputDeviceDelegate(
                mContextSpy, new Handler(mTestLooper.getLooper()));

        inputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertFalse(inputDeviceDelegate.isAvailable());

        inputDeviceDelegate.onInputDeviceAdded(1);
        assertFalse(inputDeviceDelegate.isAvailable());

        updateInputDevices(new int[]{1});
        assertFalse(inputDeviceDelegate.isAvailable());

        verify(mIInputManagerMock, never()).getInputDevice(anyInt());
    }

    @Test
    public void onInputDeviceAdded_withSettingsDisabled_ignoresNewDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ false);
        assertFalse(mInputDeviceDelegate.isAvailable());

        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        mInputDeviceDelegate.onInputDeviceAdded(1);

        assertFalse(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock, never()).getInputDevice(anyInt());
    }

    @Test
    public void onInputDeviceAdded_withDeviceWithoutVibrator_ignoresNewDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertFalse(mInputDeviceDelegate.isAvailable());

        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[0]);
        when(mIInputManagerMock.getInputDevice(eq(1)))
                .thenReturn(createInputDeviceWithoutVibrator(1));
        updateInputDevices(new int[]{1});

        assertFalse(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock).getInputDevice(eq(1));
    }

    @Test
    public void onInputDeviceAdded_withDeviceWithVibrator_addsNewDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertFalse(mInputDeviceDelegate.isAvailable());

        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        updateInputDevices(new int[]{1});

        assertTrue(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock).getInputDevice(eq(1));
    }

    @Test
    public void onInputDeviceChanged_withSettingsDisabled_ignoresDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ false);

        updateInputDevices(new int[]{1});
        assertFalse(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock, never()).getInputDevice(anyInt());
    }

    @Test
    public void onInputDeviceChanged_deviceLosesVibrator_removesDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1}, new int[0]);
        when(mIInputManagerMock.getInputDevice(eq(1)))
                .thenReturn(createInputDeviceWithVibrator(1), createInputDeviceWithoutVibrator(1));

        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertTrue(mInputDeviceDelegate.isAvailable());

        updateInputDevices(new int[]{1});
        assertFalse(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock, times(2)).getInputDevice(eq(1));
    }

    @Test
    public void onInputDeviceChanged_deviceLost_removesDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1}, new int[0]);
        when(mIInputManagerMock.getInputDevice(eq(1)))
                .thenReturn(createInputDeviceWithVibrator(1), (InputDevice) null);

        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertTrue(mInputDeviceDelegate.isAvailable());

        updateInputDevices(new int[]{1});
        assertFalse(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock, times(2)).getInputDevice(eq(1));
    }

    @Test
    public void onInputDeviceChanged_deviceAddsVibrator_addsDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[0], new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1)))
                .thenReturn(createInputDeviceWithoutVibrator(1), createInputDeviceWithVibrator(1));

        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertFalse(mInputDeviceDelegate.isAvailable());

        updateInputDevices(new int[]{1});
        assertTrue(mInputDeviceDelegate.isAvailable());
        verify(mIInputManagerMock, times(2)).getInputDevice(eq(1));
    }

    @Test
    public void onInputDeviceRemoved_removesDevice() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1, 2});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[0]);
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(
                createInputDeviceWithoutVibrator(1));
        when(mIInputManagerMock.getVibratorIds(eq(2))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(2))).thenReturn(createInputDeviceWithVibrator(2));

        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertTrue(mInputDeviceDelegate.isAvailable());

        updateInputDevices(new int[]{1});
        assertFalse(mInputDeviceDelegate.isAvailable());
    }

    @Test
    public void updateInputDeviceVibrators_usesFlagToLoadDeviceList() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1, 2});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        when(mIInputManagerMock.getVibratorIds(eq(2))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(2))).thenReturn(createInputDeviceWithVibrator(2));

        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertTrue(mInputDeviceDelegate.isAvailable());

        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ false);
        assertFalse(mInputDeviceDelegate.isAvailable());
    }

    @Test
    public void updateInputDeviceVibrators_withDeviceWithoutVibrator_deviceIsIgnored()
            throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[0]);
        when(mIInputManagerMock.getInputDevice(eq(1)))
                .thenReturn(createInputDeviceWithoutVibrator(1));
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);
        assertFalse(mInputDeviceDelegate.isAvailable());
    }

    @Test
    public void vibrateIfAvailable_withNoInputDevice_returnsFalse() {
        assertFalse(mInputDeviceDelegate.isAvailable());
        assertFalse(mInputDeviceDelegate.vibrateIfAvailable(
                UID, PACKAGE_NAME, SYNCED_EFFECT, REASON, VIBRATION_ATTRIBUTES));
    }

    @Test
    public void vibrateIfAvailable_withInputDevices_returnsTrueAndVibratesAllDevices()
            throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1, 2});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        when(mIInputManagerMock.getVibratorIds(eq(2))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(2))).thenReturn(createInputDeviceWithVibrator(2));
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);

        assertTrue(mInputDeviceDelegate.vibrateIfAvailable(
                UID, PACKAGE_NAME, SYNCED_EFFECT, REASON, VIBRATION_ATTRIBUTES));
        verify(mIInputManagerMock).vibrateCombined(eq(1), same(SYNCED_EFFECT), any());
        verify(mIInputManagerMock).vibrateCombined(eq(2), same(SYNCED_EFFECT), any());
    }

    @Test
    public void cancelVibrateIfAvailable_withNoInputDevice_returnsFalse() throws Exception {
        assertFalse(mInputDeviceDelegate.isAvailable());
        assertFalse(mInputDeviceDelegate.cancelVibrateIfAvailable());
        verify(mIInputManagerMock, never()).cancelVibrate(anyInt(), any());
    }

    @Test
    public void cancelVibrateIfAvailable_withInputDevices_returnsTrueAndStopsAllDevices()
            throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1, 2});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        when(mIInputManagerMock.getVibratorIds(eq(2))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(2))).thenReturn(createInputDeviceWithVibrator(2));
        mInputDeviceDelegate.updateInputDeviceVibrators(/* vibrateInputDevices= */ true);

        assertTrue(mInputDeviceDelegate.isAvailable());
        assertTrue(mInputDeviceDelegate.cancelVibrateIfAvailable());
        verify(mIInputManagerMock).cancelVibrate(eq(1), any());
        verify(mIInputManagerMock).cancelVibrate(eq(2), any());
    }

    private void updateInputDevices(int[] deviceIds) throws Exception {
        int[] deviceIdsAndGenerations = new int[deviceIds.length * 2];
        for (int i = 0; i < deviceIdsAndGenerations.length; i += 2) {
            deviceIdsAndGenerations[i] = deviceIds[i / 2];
            deviceIdsAndGenerations[i + 1] = 2; // update by increasing it's generation to 2.
        }
        // Force initialization of mIInputDevicesChangedListener, if it still haven't
        InputManager.getInstance().getInputDeviceIds();
        mIInputDevicesChangedListener.onInputDevicesChanged(deviceIdsAndGenerations);
        // Makes sure all callbacks from InputDeviceDelegate are executed.
        mTestLooper.dispatchAll();
    }

    private InputDevice createInputDeviceWithVibrator(int id) {
        return createInputDevice(id, /* hasVibrator= */ true);
    }

    private InputDevice createInputDeviceWithoutVibrator(int id) {
        return createInputDevice(id, /* hasVibrator= */ false);
    }

    private InputDevice createInputDevice(int id, boolean hasVibrator) {
        return new InputDevice(id, 0, 0, "name", 0, 0, "description", false, 0, 0,
                null, hasVibrator, false, false, false /* hasSensor */, false /* hasBattery */);


    }
}
