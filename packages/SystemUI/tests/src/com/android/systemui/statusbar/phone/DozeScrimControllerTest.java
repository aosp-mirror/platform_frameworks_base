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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.DozeHost;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class DozeScrimControllerTest extends SysuiTestCase {

    @Mock
    private ScrimController mScrimController;
    @Mock
    private DozeParameters mDozeParameters;
    private DozeScrimController mDozeScrimController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        // Make sure callbacks will be invoked to complete the lifecycle.
        doAnswer(invocationOnMock -> {
            ScrimController.Callback callback = invocationOnMock.getArgument(1);
            callback.onStart();
            callback.onDisplayBlanked();
            callback.onFinished();
            return null;
        }).when(mScrimController).transitionTo(any(ScrimState.class),
                any(ScrimController.Callback.class));

        mDozeScrimController = new DozeScrimController(mScrimController, getContext(),
                mDozeParameters);
        mDozeScrimController.setDozing(true);
    }

    @Test
    public void changesScrimControllerState() {
        mDozeScrimController.pulse(mock(DozeHost.PulseCallback.class), 0);
        verify(mScrimController).transitionTo(eq(ScrimState.PULSING),
                any(ScrimController.Callback.class));
    }

    @Test
    public void callsPulseCallback() {
        DozeHost.PulseCallback callback = mock(DozeHost.PulseCallback.class);
        mDozeScrimController.pulse(callback, 0);

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
