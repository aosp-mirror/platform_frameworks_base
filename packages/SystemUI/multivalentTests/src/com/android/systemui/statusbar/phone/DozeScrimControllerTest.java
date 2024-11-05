/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.plugins.statusbar.StatusBarStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@SmallTest
public class DozeScrimControllerTest extends SysuiTestCase {

    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    private DozeScrimController mDozeScrimController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDozeScrimController = new DozeScrimController(mDozeParameters, mDozeLog,
                mStatusBarStateController);
        mDozeScrimController.setDozing(true);
    }

    @Test
    public void callsPulseCallback() {
        DozeHost.PulseCallback callback = mock(DozeHost.PulseCallback.class);
        mDozeScrimController.pulse(callback, 0);

        // Manually simulate a scrim lifecycle
        mDozeScrimController.getScrimCallback().onStart();
        mDozeScrimController.getScrimCallback().onDisplayBlanked();
        mDozeScrimController.getScrimCallback().onFinished();

        verify(callback).onPulseStarted();
        mDozeScrimController.pulseOutNow();
        verify(callback).onPulseFinished();
    }

    @Test
    public void secondPulseIsSuppressed() {
        DozeHost.PulseCallback callback1 = mock(DozeHost.PulseCallback.class);
        DozeHost.PulseCallback callback2 = mock(DozeHost.PulseCallback.class);
        mDozeScrimController.pulse(callback1, 0);
        mDozeScrimController.pulse(callback2, 0);

        verify(callback1, never()).onPulseFinished();
        verify(callback2).onPulseFinished();
    }

    @Test
    public void suppressesPulseIfNotDozing() {
        mDozeScrimController.setDozing(false);
        DozeHost.PulseCallback callback = mock(DozeHost.PulseCallback.class);
        mDozeScrimController.pulse(callback, 0);

        verify(callback).onPulseFinished();
    }
}
