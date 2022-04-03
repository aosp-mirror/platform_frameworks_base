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
package com.android.server.utils;

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
import android.util.Slog;

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
 * Tests for {@link TimingsTraceAndSlog}.
 *
 * <p>Usage: {@code atest FrameworksMockingServicesTests:TimingsTraceAndSlogTest}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TimingsTraceAndSlogTest {

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
        TimingsTraceAndSlog log = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
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
            TimingsTraceAndSlog log2 = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
            log2.traceBegin("test");
            log2.traceEnd();

        });
        t.start();
        t.join();
        assertThat(errors).isEmpty();
    }

    @Test
    public void testGetUnfinishedTracesForDebug() {
        TimingsTraceAndSlog log = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
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
        TimingsTraceAndSlog log = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
        log.logDuration("logro", 42);
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), contains("logro took to complete: 42ms")));
    }

    @Test
    public void testOneLevel() throws Exception {
        TimingsTraceAndSlog log = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
        log.traceBegin("test");
        log.traceEnd();

        verify((MockedVoidMethod) () -> Trace.traceBegin(TRACE_TAG_APP, "test"));
        verify((MockedVoidMethod) () -> Trace.traceEnd(TRACE_TAG_APP));
        verify((MockedVoidMethod) () -> Slog.v(eq(TAG), matches("test took to complete: \\dms")));
    }

    @Test
    public void testMultipleLevels() throws Exception {
        TimingsTraceAndSlog log = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
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
    public void testEndNoBegin() throws Exception {
        TimingsTraceAndSlog log = new TimingsTraceAndSlog(TAG, TRACE_TAG_APP);
        log.traceEnd();
        verify((MockedVoidMethod) () -> Trace.traceEnd(TRACE_TAG_APP));
        verify((MockedVoidMethod) () -> Slog.d(eq(TAG), anyString()), never());
        verify((MockedVoidMethod) () -> Slog.w(TAG, "traceEnd called more times than traceBegin"));
    }
}
