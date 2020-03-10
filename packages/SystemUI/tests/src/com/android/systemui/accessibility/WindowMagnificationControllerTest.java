/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.accessibility;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class WindowMagnificationControllerTest extends SysuiTestCase {

    @Mock
    MirrorWindowControl mMirrorWindowControl;
    @Mock
    WindowMagnifierCallback mWindowMagnifierCallback;
    private WindowMagnificationController mWindowMagnificationController;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mWindowMagnificationController = new WindowMagnificationController(getContext(),
                mMirrorWindowControl, mWindowMagnifierCallback);
        verify(mMirrorWindowControl).setWindowDelegate(
                any(MirrorWindowControl.MirrorWindowDelegate.class));
    }

    @After
    public void tearDown() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.deleteWindowMagnification();
        });
        mInstrumentation.waitForIdleSync();
    }

    @Test
    public void createWindowMagnification_showControl() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        mInstrumentation.waitForIdleSync();
        verify(mMirrorWindowControl).showControl(any(IBinder.class));
    }

    @Test
    public void deleteWindowMagnification_destroyControl() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        mInstrumentation.waitForIdleSync();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.deleteWindowMagnification();
        });
        mInstrumentation.waitForIdleSync();

        verify(mMirrorWindowControl).destroyControl();
    }
}
