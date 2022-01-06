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

package com.android.server.biometrics.sensors;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@Presubmit
@SmallTest
public class CompositeCallbackTest {

    @Test
    public void testNullCallback() {
        BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);
        BaseClientMonitor.Callback callback2 = mock(BaseClientMonitor.Callback.class);
        BaseClientMonitor.Callback callback3 = null;

        BaseClientMonitor.CompositeCallback callback = new BaseClientMonitor.CompositeCallback(
                callback1, callback2, callback3);

        BaseClientMonitor clientMonitor = mock(BaseClientMonitor.class);

        callback.onClientStarted(clientMonitor);
        verify(callback1).onClientStarted(eq(clientMonitor));
        verify(callback2).onClientStarted(eq(clientMonitor));

        callback.onClientFinished(clientMonitor, true /* success */);
        verify(callback1).onClientFinished(eq(clientMonitor), eq(true));
        verify(callback2).onClientFinished(eq(clientMonitor), eq(true));
    }
}
