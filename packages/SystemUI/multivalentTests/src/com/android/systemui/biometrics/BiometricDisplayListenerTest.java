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

package com.android.systemui.biometrics;

import static com.android.systemui.biometrics.BiometricDisplayListener.SensorType.SideFingerprint;
import static com.android.systemui.biometrics.BiometricDisplayListener.SensorType.UnderDisplayFingerprint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class BiometricDisplayListenerTest extends SysuiTestCase {

    // Dependencies
    @Mock private DisplayManager mDisplayManager;
    @Mock private Display mDisplay;
    @Mock private Function0<Unit> mOnChangedCallback;
    @Mock private UnderDisplayFingerprint mUdfpsType;
    @Mock private SideFingerprint mSidefpsType;
    private Handler mHandler;
    private Context mContextSpy;

    // Captors
    @Captor private ArgumentCaptor<DisplayManager.DisplayListener> mDisplayListenerCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Set up mocks
        mContextSpy = spy(mContext);
        when(mContextSpy.getDisplay()).thenReturn(mDisplay);

        // Create a real handler with a TestableLooper.
        TestableLooper testableLooper = TestableLooper.get(this);
        mHandler = new Handler(testableLooper.getLooper());
    }

    @Test
    public void registersDisplayListener_whenEnabled() {
        BiometricDisplayListener listener = new BiometricDisplayListener(
                mContextSpy, mDisplayManager, mHandler, mUdfpsType, mOnChangedCallback);

        listener.enable();
        verify(mDisplayManager).registerDisplayListener(any(), same(mHandler),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED));
    }

    @Test
    public void unregistersDisplayListener_whenDisabled() {
        BiometricDisplayListener listener = new BiometricDisplayListener(
                mContextSpy, mDisplayManager, mHandler, mUdfpsType, mOnChangedCallback);

        listener.enable();
        listener.disable();
        verify(mDisplayManager).unregisterDisplayListener(any());
    }

    @Test
    public void callsOnChanged_forSideFingerprint_whenAnythingDisplayChanges() {
        // Any rotation will do for this test, we just need to return something.
        when(mDisplay.getRotation()).thenReturn(Surface.ROTATION_0);

        BiometricDisplayListener listener = new BiometricDisplayListener(
                mContextSpy, mDisplayManager, mHandler, mSidefpsType, mOnChangedCallback);
        listener.enable();

        // The listener should register a display listener.
        verify(mDisplayManager).registerDisplayListener(mDisplayListenerCaptor.capture(),
                same(mHandler), eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED));

        // mOnChangedCallback should be invoked for all calls to onDisplayChanged.
        mDisplayListenerCaptor.getValue().onDisplayChanged(123);
        mDisplayListenerCaptor.getValue().onDisplayChanged(123);
        verify(mOnChangedCallback, times(2)).invoke();
    }
}
