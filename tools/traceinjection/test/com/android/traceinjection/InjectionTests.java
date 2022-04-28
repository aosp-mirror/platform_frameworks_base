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

package com.android.traceinjection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class InjectionTests {
    public static final int TRACE_TAG = 42;
    public static final String CUSTOM_TRACE_NAME = "Custom";

    public static final TraceTracker TRACKER = new TraceTracker();

    @After
    public void tearDown() {
        TRACKER.reset();
    }

    @Test
    public void testDefaultLabel() {
        assertTraces(this::tracedMethod, "tracedMethod");
        tracedMethodThrowsAndCatches();
    }

    @Test
    public void testCustomLabel() {
        assertTraces(this::tracedMethodHasCustomName, CUSTOM_TRACE_NAME);
    }

    @Test
    public void testTracedMethodsStillThrow() {
        assertTraces(() -> assertThrows(IllegalArgumentException.class, this::tracedMethodThrows),
                "tracedMethodThrows");
        // Also test that we rethrow exceptions from method calls. This is slightly different from
        // the previous case because the ATHROW instruction is not actually present at all in the
        // bytecode of the instrumented method.
        TRACKER.reset();
        assertTraces(() -> assertThrows(NullPointerException.class,
                        this::tracedMethodCallsThrowingMethod),
                "tracedMethodCallsThrowingMethod");
    }

    @Test
    public void testNestedTracedMethods() {
        assertTraces(this::outerTracedMethod, "outerTracedMethod", "innerTracedMethod");
    }

    @Test
    public void testTracedMethodWithCatchBlock() {
        assertTraces(this::tracedMethodThrowsAndCatches, "tracedMethodThrowsAndCatches");
    }

    @Test
    public void testTracedMethodWithFinallyBlock() {
        assertTraces(() -> assertThrows(IllegalArgumentException.class,
                this::tracedMethodThrowWithFinally), "tracedMethodThrowWithFinally");
    }

    @Test
    public void testNonVoidMethod() {
        assertTraces(this::tracedNonVoidMethod, "tracedNonVoidMethod");
    }

    @Test
    public void testNonVoidMethodReturnsWithinCatches() {
        assertTraces(this::tracedNonVoidMethodReturnsWithinCatches,
                "tracedNonVoidMethodReturnsWithinCatches");
    }

    @Test
    public void testNonVoidMethodReturnsWithinFinally() {
        assertTraces(this::tracedNonVoidMethodReturnsWithinFinally,
                "tracedNonVoidMethodReturnsWithinFinally");
    }

    @Test
    public void testTracedStaticMethod() {
        assertTraces(InjectionTests::tracedStaticMethod, "tracedStaticMethod");
    }

    @Trace(tag = TRACE_TAG)
    public void tracedMethod() {
        assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
    }

    @Trace(tag = TRACE_TAG)
    public void tracedMethodThrows() {
        throw new IllegalArgumentException();
    }

    @Trace(tag = TRACE_TAG)
    public void tracedMethodCallsThrowingMethod() {
        throwingMethod();
    }

    private void throwingMethod() {
        throw new NullPointerException();
    }


    @Trace(tag = TRACE_TAG)
    public void tracedMethodThrowsAndCatches() {
        try {
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException ignored) {
            assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
        }
    }

    @Trace(tag = TRACE_TAG)
    public void tracedMethodThrowWithFinally() {
        try {
            throw new IllegalArgumentException();
        } finally {
            assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
        }
    }

    @Trace(tag = TRACE_TAG, label = CUSTOM_TRACE_NAME)
    public void tracedMethodHasCustomName() {
    }

    @Trace(tag = TRACE_TAG)
    public void outerTracedMethod() {
        innerTracedMethod();
        assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
    }

    @Trace(tag = TRACE_TAG)
    public void innerTracedMethod() {
        assertEquals(2, TRACKER.getTraceCount(TRACE_TAG));
    }

    @Trace(tag = TRACE_TAG)
    public int tracedNonVoidMethod() {
        assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
        return 0;
    }

    @Trace(tag = TRACE_TAG)
    public int tracedNonVoidMethodReturnsWithinCatches() {
        try {
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException ignored) {
            assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
            return 0;
        }
    }

    @Trace(tag = TRACE_TAG)
    public int tracedNonVoidMethodReturnsWithinFinally() {
        try {
            throw new IllegalArgumentException();
        } finally {
            assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
            return 0;
        }
    }

    @Trace(tag = TRACE_TAG)
    public static void tracedStaticMethod() {
        assertEquals(1, TRACKER.getTraceCount(TRACE_TAG));
    }

    public void assertTraces(Runnable r, String... traceLabels) {
        r.run();
        assertEquals(Arrays.asList(traceLabels), TRACKER.getTraceLabels(TRACE_TAG));
        TRACKER.assertAllTracesClosed();
    }

    public static void traceStart(long tag, String name) {
        TRACKER.onTraceStart(tag, name);
    }

    public static void traceEnd(long tag) {
        TRACKER.onTraceEnd(tag);
    }

    static class TraceTracker {
        private final Map<Long, List<String>> mTraceLabelsByTag = new HashMap<>();
        private final Map<Long, Integer> mTraceCountsByTag = new HashMap<>();

        public void onTraceStart(long tag, String name) {
            getTraceLabels(tag).add(name);
            mTraceCountsByTag.put(tag, mTraceCountsByTag.getOrDefault(tag, 0) + 1);
        }

        public void onTraceEnd(long tag) {
            final int newCount = getTraceCount(tag) - 1;
            if (newCount < 0) {
                throw new IllegalStateException("Trace count has gone negative for tag " + tag);
            }
            mTraceCountsByTag.put(tag, newCount);
        }

        public void reset() {
            mTraceLabelsByTag.clear();
            mTraceCountsByTag.clear();
        }

        public List<String> getTraceLabels(long tag) {
            if (!mTraceLabelsByTag.containsKey(tag)) {
                mTraceLabelsByTag.put(tag, new ArrayList<>());
            }
            return mTraceLabelsByTag.get(tag);
        }

        public int getTraceCount(long tag) {
            return mTraceCountsByTag.getOrDefault(tag, 0);
        }

        public void assertAllTracesClosed() {
            for (Map.Entry<Long, Integer> count: mTraceCountsByTag.entrySet()) {
                final String errorMsg = "Tag " + count.getKey() + " is not fully closed (count="
                        + count.getValue() + ")";
                assertEquals(errorMsg, 0, (int) count.getValue());
            }
        }
    }
}
