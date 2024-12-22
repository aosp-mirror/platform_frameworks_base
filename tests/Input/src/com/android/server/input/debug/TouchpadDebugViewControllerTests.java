/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.InputDevice;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.input.InputManagerService;
import com.android.server.input.TouchpadHardwareProperties;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Build/Install/Run:
 * atest TouchpadDebugViewControllerTests
 */

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TouchpadDebugViewControllerTests {
    private static final int DEVICE_ID = 1000;
    private static final String TAG = "TouchpadDebugViewController";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private Context mContext;
    private TouchpadDebugViewController mTouchpadDebugViewController;
    @Mock
    private InputManager mInputManagerMock;
    @Mock
    private InputManagerService mInputManagerServiceMock;
    @Mock
    private WindowManager mWindowManagerMock;
    private TestableLooper mTestableLooper;

    @Before
    public void setup() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        TestableContext mTestableContext = new TestableContext(mContext);
        mTestableContext.addMockSystemService(WindowManager.class, mWindowManagerMock);

        Rect bounds = new Rect(0, 0, 2560, 1600);
        WindowMetrics metrics = new WindowMetrics(bounds, new WindowInsets(bounds), 1.0f);

        when(mWindowManagerMock.getCurrentWindowMetrics()).thenReturn(metrics);

        unMockTouchpad();

        mTestableLooper = TestableLooper.get(this);

        mTestableContext.addMockSystemService(InputManager.class, mInputManagerMock);
        when(mInputManagerServiceMock.getTouchpadHardwareProperties(DEVICE_ID)).thenReturn(
                new TouchpadHardwareProperties.Builder(-100f, 100f, -100f, 100f, 45f, 45f, -5f, 5f,
                        (short) 10, true, false).build());

        mTouchpadDebugViewController = new TouchpadDebugViewController(mTestableContext,
                mTestableLooper.getLooper(), mInputManagerServiceMock);
    }

    private InputDevice createTouchpadInputDevice(int id) {
        return new InputDevice.Builder()
                .setId(id)
                .setSources(InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_MOUSE)
                .setName("Test Device " + id)
                .build();
    }

    private void mockTouchpad() {
        when(mInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{DEVICE_ID});
        when(mInputManagerMock.getInputDevice(eq(DEVICE_ID))).thenReturn(
                createTouchpadInputDevice(DEVICE_ID));
    }

    private void unMockTouchpad() {
        when(mInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{});
        when(mInputManagerMock.getInputDevice(eq(DEVICE_ID))).thenReturn(null);
    }

    @Test
    public void touchpadConnectedWhileSettingDisabled() throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(false);

        mockTouchpad();
        mTouchpadDebugViewController.onInputDeviceAdded(DEVICE_ID);

        verify(mWindowManagerMock, never()).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());
    }

    @Test
    public void settingEnabledWhileNoTouchpadConnected() throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(true);

        verify(mWindowManagerMock, never()).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());
    }

    @Test
    public void touchpadConnectedWhileSettingEnabled() throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(true);

        mockTouchpad();
        mTouchpadDebugViewController.onInputDeviceAdded(DEVICE_ID);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());
    }

    @Test
    public void touchpadConnectedWhileSettingEnabledThenDisabled() throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(true);

        mockTouchpad();
        mTouchpadDebugViewController.onInputDeviceAdded(DEVICE_ID);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());

        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(false);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, times(1)).removeView(any());
    }

    @Test
    public void touchpadConnectedWhileSettingDisabledThenEnabled() throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(false);

        mockTouchpad();
        mTouchpadDebugViewController.onInputDeviceAdded(DEVICE_ID);

        verify(mWindowManagerMock, never()).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());

        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(true);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());
    }

    @Test
    public void touchpadConnectedWhileSettingDisabledThenTouchpadDisconnected() throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(false);

        mockTouchpad();
        mTouchpadDebugViewController.onInputDeviceAdded(DEVICE_ID);

        verify(mWindowManagerMock, never()).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());

        unMockTouchpad();
        mTouchpadDebugViewController.onInputDeviceRemoved(DEVICE_ID);

        verify(mWindowManagerMock, never()).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());
    }

    @Test
    public void touchpadConnectedWhileSettingEnabledThenTouchpadDisconnectedThenSettingDisabled()
            throws Exception {
        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(true);

        mockTouchpad();
        mTouchpadDebugViewController.onInputDeviceAdded(DEVICE_ID);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, never()).removeView(any());

        unMockTouchpad();
        mTouchpadDebugViewController.onInputDeviceRemoved(DEVICE_ID);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, times(1)).removeView(any());

        mTouchpadDebugViewController.updateTouchpadVisualizerEnabled(false);

        verify(mWindowManagerMock, times(1)).addView(any(), any());
        verify(mWindowManagerMock, times(1)).removeView(any());
    }
}
