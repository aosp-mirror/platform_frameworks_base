/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.app.ApplicationErrorReport.CrashInfo;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ApplicationErrorReportTest {

    @Test
    public void testHugeStacktraceLeadsToReasonableReport() {
        Throwable deepStackTrace = deepStackTrace();
        CrashInfo crashInfo = new CrashInfo(deepStackTrace);

        assertTrue("stack trace is longer than 50'000 characters",
                crashInfo.stackTrace.length() < 50000);
    }

    @Test
    public void testHugeExceptionMessageLeadsToReasonableReport() {
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < 1000000; i++) {
            msg.append('x');
        }

        CrashInfo crashInfo = new CrashInfo(new Throwable(msg.toString()));

        assertTrue("message is longer than 50'000 characters",
                crashInfo.exceptionMessage.length() < 50000);
    }

    @Test
    public void testTruncationKeepsStartAndEndIntact() {
        StringBuilder msg = new StringBuilder("start");
        for (int i = 0; i < 1000000; i++) {
            msg.append('x');
        }
        msg.append("end");

        CrashInfo crashInfo = new CrashInfo(new Throwable(msg.toString()));

        String exceptionMessage = crashInfo.exceptionMessage;
        assertEquals("start", exceptionMessage.substring(0, "start".length()));
        assertEquals("end", exceptionMessage.substring(exceptionMessage.length() - "end".length()));
    }

    /**
     * @return a Throwable with a very long stack trace.
     */
    private Throwable deepStackTrace() {
        return stackTraceGenerator__aaaaaaaaa_aaaaaaaaa_aaaaaaaaa_aaaaaaaaa_aaaaaaaaa(1000);
    }

    private Throwable stackTraceGenerator__aaaaaaaaa_aaaaaaaaa_aaaaaaaaa_aaaaaaaaa_aaaaaaaaa(
            int d) {
        if (d > 0) {
            return stackTraceGenerator__aaaaaaaaa_aaaaaaaaa_aaaaaaaaa_aaaaaaaaa_aaaaaaaaa(d - 1);
        } else {
            return new Throwable("here");
        }
    }
}
