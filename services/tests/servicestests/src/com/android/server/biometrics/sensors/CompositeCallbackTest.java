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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

@Presubmit
@SmallTest
public class CompositeCallbackTest {

    @Mock
    private BaseClientMonitor mClientMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCallbacks() {
        testCallbacks(mock(ClientMonitorCallback.class), mock(ClientMonitorCallback.class));
    }

    @Test
    public void testNullCallbacks() {
        testCallbacks(null, mock(ClientMonitorCallback.class),
                null, mock(ClientMonitorCallback.class));
    }

    private void testCallbacks(ClientMonitorCallback... callbacks) {
        final ClientMonitorCallback[] expected = Arrays.stream(callbacks)
                .filter(Objects::nonNull).toArray(ClientMonitorCallback[]::new);

        ClientMonitorCompositeCallback callback = new ClientMonitorCompositeCallback(callbacks);

        callback.onClientStarted(mClientMonitor);
        final InOrder order = inOrder((Object[]) expected);
        for (ClientMonitorCallback cb : expected) {
            order.verify(cb).onClientStarted(eq(mClientMonitor));
        }

        callback.onClientFinished(mClientMonitor, true /* success */);
        Collections.reverse(Arrays.asList(expected));
        for (ClientMonitorCallback cb : expected) {
            order.verify(cb).onClientFinished(eq(mClientMonitor), eq(true));
        }
    }
}
