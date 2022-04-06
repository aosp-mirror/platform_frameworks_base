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
 * limitations under the License.
 */
package android.util;

import static android.os.Trace.TRACE_TAG_APP;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.matches;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.os.Trace;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.MockedVoidMethod;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link TimingsTraceLog}.
 *
 * <p>Usage: {@code atest FrameworksMockingCoreTests:android.util.TimingsTraceLogTest}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TimingsTraceLogTest {

    private static final String TAG = "TEST";

    private MockitoSession mSession;

    @Before
    public final void startMockSession() {
        mSession = mockitoSession()
                .spyStatic(Slog.class)
                .spyStatic(Trace.class)
                .startMocking();
    }

    @After
    public final void finishMockSession() {
        mSession.finishMocking();
    }

    @Test
    public void testDifferentThreads() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog(TAG, TRACE_TAG_APP);
        // Should be able to log on the same thread
        log.traceBegin("test");
        log.traceEnd();
        final List<String> errors = new ArrayList<>();
        // Calling from a different thread should fail
        Thread t = new Thread(() -> {
            try {
                log.traceBegin("test");
                errors.add("traceBegin should fail on a different thread");
            } catch (IllegalStateException expected) {
            }
            try {
                log.traceEnd();
                errors.add("traceEnd should fail on a different thread");
            } catch (IllegalStateException expected) {
            }
            // Verify that creating a new log will work
            TimingsTraceLog log2 = new TimingsTraceLog(TAG, TRACE_TAG_APP);
            log2.traceBegin("test");
            log2.traceEnd();

        });
        t.start();
        t.join();
        assertThat(errors).isEmpty();
    }

    @Test
    public void testGetUnfinishedTracesForDebug() {
        TimingsTraceLog log = new TimingsTraceLog("TEST", Trace.TRACE_TAG_APP);
        assertThat(log.getUnfinishedTracesForDebug()).isEmpty();

        log.traceBegin("One");
        assertThat(log.getUnfinishedTracesForDebug()).containsExactly("One").inOrder();

        log.traceBegin("Two");
        assertThat(log.getUnfinishedTracesForDebug()).containsExactly("One", "Two").inOrder();

        log.traceEnd();
        assertThat(log.getUnfinishedTracesForDebug()).containsExactly("One").inOrder();

        log.traceEnd();
        assertThat(log.getUnfinishedTracesForDebug()).isEmpty();
    }

    @Test
    public void testLogDuration() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog(TAG, TRACE_TAG_APP, 10);
        log.logDuration("logro", 42);
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), contains("logro took to complete: 42ms")));
    }

    @Test
    public void testOneLevel() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog(TAG, TRACE_TAG_APP, 10);
        log.traceBegin("test");
        log.traceEnd();

        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "test"));
        verify((MockedVoidMethod) () -> Trace.traceEnd(TRACE_TAG_APP));
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("test took to complete: \\dms")));
    }

    @Test
    public void testMultipleLevels() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog(TAG, Trace.TRACE_TAG_APP, 10);
        log.traceBegin("L1");
        log.traceBegin("L2");
        log.traceEnd();
        log.traceEnd();

        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "L1"));
        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "L2"));
        verify((MockedVoidMethod) () -> Trace.traceEnd(TRACE_TAG_APP), times(2)); // L1 and L2

        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("L2 took to complete: \\d+ms")));
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("L1 took to complete: \\d+ms")));
    }

    @Test
    public void testTooManyLevels() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog(TAG, Trace.TRACE_TAG_APP, 2);

        log.traceBegin("L1"); // ok
        log.traceBegin("L2"); // ok
        log.traceBegin("L3"); // logging ignored ( > 2)

        log.traceEnd();
        log.traceEnd();
        log.traceEnd();

        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "L1"));
        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "L2"));
        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "L3"));
        verify((MockedVoidMethod) () -> Trace.traceEnd(TRACE_TAG_APP), times(3));

        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("L2 took to complete: \\d+ms")));
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("L1 took to complete: \\d+ms")));
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("L3 took to complete: \\d+ms")),
                never());

        verify((MockedVoidMethod) () -> Slog.w(TAG, "not tracing duration of 'L3' "
                + "because already reached 2 levels"));
    }

    @Test
    public void testEndNoBegin() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog(TAG, TRACE_TAG_APP);
        log.traceEnd();
        verify((MockedVoidMethod) () -> Trace.traceEnd(TRACE_TAG_APP));
        verify((MockedVoidMethod) () -> Slog.d(eq(TAG), anyString()), never());
        verify((MockedVoidMethod) () -> Slog.w(TAG, "traceEnd called more times than traceBegin"));
    }
}
