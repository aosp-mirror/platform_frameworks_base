/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.am;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IApplicationThread;

import androidx.test.filters.SmallTest;

import org.junit.Test;


/**
 * Tests to verify that the ApplicationThreadDeferred properly defers binder calls to paused
 * processes.
 */
@SmallTest
public class ApplicationThreadDeferredTest {
    private static final String TAG = "ApplicationThreadDeferredTest";

    private void callDeferredApis(IApplicationThread thread) throws Exception {
        thread.clearDnsCache();
        thread.updateHttpProxy();
        thread.updateTimeZone();
        thread.scheduleLowMemory();
    }

    // Verify that the special APIs have been called count times.
    private void verifyDeferredApis(IApplicationThread thread, int count) throws Exception {
        verify(thread, times(count)).clearDnsCache();
        verify(thread, times(count)).updateHttpProxy();
        verify(thread, times(count)).updateTimeZone();
        verify(thread, times(count)).scheduleLowMemory();
    }

    // Test the baseline behavior of IApplicationThread.  If this test fails, all other tests are
    // suspect.
    @Test
    public void testBaseline() throws Exception {
        IApplicationThread thread = mock(IApplicationThread.class);
        callDeferredApis(thread);
        verifyDeferredApis(thread, 1);
    }

    // Test the baseline behavior of IApplicationThreadDeferred.  If this test fails, all other
    // tests are suspect.
    @Test
    public void testBaselineDeferred() throws Exception {
        IApplicationThread thread = mock(ApplicationThreadDeferred.class);
        callDeferredApis(thread);
        verifyDeferredApis(thread, 1);
    }

    // Verify that a deferred thread behaves like a regular thread when it is not paused.
    @Test
    public void testDeferredUnpaused() throws Exception {
        IApplicationThread base = mock(IApplicationThread.class);
        ApplicationThreadDeferred thread = new ApplicationThreadDeferred(base, true);
        callDeferredApis(thread);
        verifyDeferredApis(base, 1);
    }

    // Verify that a paused deferred thread thread does not deliver any calls to its parent.  Then
    // unpause the thread and verify that the collapsed calls are forwarded.
    @Test
    public void testDeferredPaused() throws Exception {
        IApplicationThread base = mock(IApplicationThread.class);
        ApplicationThreadDeferred thread = new ApplicationThreadDeferred(base, true);
        thread.onProcessPaused();
        callDeferredApis(thread);
        callDeferredApis(thread);
        verifyDeferredApis(base, 0);
        thread.onProcessUnpaused();
        verifyDeferredApis(base, 1);
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
