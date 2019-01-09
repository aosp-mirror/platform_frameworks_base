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

package com.android.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.util.SharedLog;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SharedLogTest {
    private static final String TIMESTAMP_PATTERN = "\\d{2}:\\d{2}:\\d{2}";
    private static final String TIMESTAMP = "HH:MM:SS";

    @Test
    public void testBasicOperation() {
        final SharedLog logTop = new SharedLog("top");
        logTop.mark("first post!");

        final SharedLog logLevel2a = logTop.forSubComponent("twoA");
        final SharedLog logLevel2b = logTop.forSubComponent("twoB");
        logLevel2b.e("2b or not 2b");
        logLevel2b.e("No exception", null);
        logLevel2b.e("Wait, here's one", new Exception("Test"));
        logLevel2a.w("second post?");

        final SharedLog logLevel3 = logLevel2a.forSubComponent("three");
        logTop.log("still logging");
        logLevel3.log("3 >> 2");
        logLevel2a.mark("ok: last post");

        final String[] expected = {
            " - MARK first post!",
            " - [twoB] ERROR 2b or not 2b",
            " - [twoB] ERROR No exception",
            // No stacktrace in shared log, only in logcat
            " - [twoB] ERROR Wait, here's one: Test",
            " - [twoA] WARN second post?",
            " - still logging",
            " - [twoA.three] 3 >> 2",
            " - [twoA] MARK ok: last post",
        };
        // Verify the logs are all there and in the correct order.
        verifyLogLines(expected, logTop);

        // In fact, because they all share the same underlying LocalLog,
        // every subcomponent SharedLog's dump() is identical.
        verifyLogLines(expected, logLevel2a);
        verifyLogLines(expected, logLevel2b);
        verifyLogLines(expected, logLevel3);
    }

    private static void verifyLogLines(String[] expected, SharedLog log) {
        final ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        final PrintWriter pw = new PrintWriter(ostream, true);
        log.dump(null, pw, null);

        final String dumpOutput = ostream.toString();
        assertTrue(dumpOutput != null);
        assertTrue(!"".equals(dumpOutput));

        final String[] lines = dumpOutput.split("\n");
        assertEquals(expected.length, lines.length);

        for (int i = 0; i < expected.length; i++) {
            String got = lines[i];
            String want = expected[i];
            assertTrue(String.format("'%s' did not contain '%s'", got, want), got.endsWith(want));
            assertTrue(String.format("'%s' did not contain a %s timestamp", got, TIMESTAMP),
                    got.replaceFirst(TIMESTAMP_PATTERN, TIMESTAMP).contains(TIMESTAMP));
        }
    }
}
