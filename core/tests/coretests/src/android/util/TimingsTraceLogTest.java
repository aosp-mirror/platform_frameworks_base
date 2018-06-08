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

import static org.junit.Assert.assertTrue;

import android.os.Trace;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;


/**
 * Tests for {@link TimingsTraceLog}.
 * <p>Usage: bit FrameworksCoreTests:android.util.TimingsTraceLogTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TimingsTraceLogTest {

    @Test
    public void testDifferentThreads() throws Exception {
        TimingsTraceLog log = new TimingsTraceLog("TEST", Trace.TRACE_TAG_APP);
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
            TimingsTraceLog log2 = new TimingsTraceLog("TEST", Trace.TRACE_TAG_APP);
            log2.traceBegin("test");
            log2.traceEnd();

        });
        t.start();
        t.join();
        assertTrue(errors.toString(), errors.isEmpty());
    }

}
