/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.common;

import android.content.SharedPreferences;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

public class OperationSchedulerTest extends AndroidTestCase {
    @MediumTest
    public void testScheduler() throws Exception {
        String name = "OperationSchedulerTest.testScheduler";
        SharedPreferences storage = getContext().getSharedPreferences(name, 0);
        storage.edit().clear().commit();

        OperationScheduler scheduler = new OperationScheduler(storage);
        OperationScheduler.Options options = new OperationScheduler.Options();
        assertEquals(Long.MAX_VALUE, scheduler.getNextTimeMillis(options));
        assertEquals(0, scheduler.getLastSuccessTimeMillis());
        assertEquals(0, scheduler.getLastAttemptTimeMillis());

        long beforeTrigger = System.currentTimeMillis();
        scheduler.setTriggerTimeMillis(beforeTrigger + 1000000);
        assertEquals(beforeTrigger + 1000000, scheduler.getNextTimeMillis(options));

        // It will schedule for the later of the trigger and the moratorium...
        scheduler.setMoratoriumTimeMillis(beforeTrigger + 500000);
        assertEquals(beforeTrigger + 1000000, scheduler.getNextTimeMillis(options));
        scheduler.setMoratoriumTimeMillis(beforeTrigger + 1500000);
        assertEquals(beforeTrigger + 1500000, scheduler.getNextTimeMillis(options));

        // Test enable/disable toggle
        scheduler.setEnabledState(false);
        assertEquals(Long.MAX_VALUE, scheduler.getNextTimeMillis(options));
        scheduler.setEnabledState(true);
        assertEquals(beforeTrigger + 1500000, scheduler.getNextTimeMillis(options));

        // Backoff interval after an error
        long beforeError = System.currentTimeMillis();
        scheduler.onTransientError();
        long afterError = System.currentTimeMillis();
        assertEquals(0, scheduler.getLastSuccessTimeMillis());
        assertTrue(beforeError <= scheduler.getLastAttemptTimeMillis());
        assertTrue(afterError >= scheduler.getLastAttemptTimeMillis());
        assertEquals(beforeTrigger + 1500000, scheduler.getNextTimeMillis(options));
        options.backoffFixedMillis = 1000000;
        options.backoffIncrementalMillis = 500000;
        assertTrue(beforeError + 1500000 <= scheduler.getNextTimeMillis(options));
        assertTrue(afterError + 1500000 >= scheduler.getNextTimeMillis(options));

        // Two errors: backoff interval increases
        beforeError = System.currentTimeMillis();
        scheduler.onTransientError();
        afterError = System.currentTimeMillis();
        assertTrue(beforeError <= scheduler.getLastAttemptTimeMillis());
        assertTrue(afterError >= scheduler.getLastAttemptTimeMillis());
        assertTrue(beforeError + 2000000 <= scheduler.getNextTimeMillis(options));
        assertTrue(afterError + 2000000 >= scheduler.getNextTimeMillis(options));

        // Reset transient error: no backoff interval
        scheduler.resetTransientError();
        assertEquals(0, scheduler.getLastSuccessTimeMillis());
        assertEquals(beforeTrigger + 1500000, scheduler.getNextTimeMillis(options));
        assertTrue(beforeError <= scheduler.getLastAttemptTimeMillis());
        assertTrue(afterError >= scheduler.getLastAttemptTimeMillis());

        // Permanent error holds true even if transient errors are reset
        // However, we remember that the transient error was reset...
        scheduler.onPermanentError();
        assertEquals(Long.MAX_VALUE, scheduler.getNextTimeMillis(options));
        scheduler.resetTransientError();
        assertEquals(Long.MAX_VALUE, scheduler.getNextTimeMillis(options));
        scheduler.resetPermanentError();
        assertEquals(beforeTrigger + 1500000, scheduler.getNextTimeMillis(options));

        // Success resets the trigger
        long beforeSuccess = System.currentTimeMillis();
        scheduler.onSuccess();
        long afterSuccess = System.currentTimeMillis();
        assertTrue(beforeSuccess <= scheduler.getLastAttemptTimeMillis());
        assertTrue(afterSuccess >= scheduler.getLastAttemptTimeMillis());
        assertTrue(beforeSuccess <= scheduler.getLastSuccessTimeMillis());
        assertTrue(afterSuccess >= scheduler.getLastSuccessTimeMillis());
        assertEquals(Long.MAX_VALUE, scheduler.getNextTimeMillis(options));

        // The moratorium is not reset by success!
        scheduler.setTriggerTimeMillis(beforeSuccess + 500000);
        assertEquals(beforeTrigger + 1500000, scheduler.getNextTimeMillis(options));
        scheduler.setMoratoriumTimeMillis(0);
        assertEquals(beforeSuccess + 500000, scheduler.getNextTimeMillis(options));

        // Periodic interval after success
        options.periodicIntervalMillis = 250000;
        assertTrue(beforeSuccess + 250000 <= scheduler.getNextTimeMillis(options));
        assertTrue(afterSuccess + 250000 >= scheduler.getNextTimeMillis(options));

        // Trigger minimum is also since the last success
        options.minTriggerMillis = 1000000;
        assertTrue(beforeSuccess + 1000000 <= scheduler.getNextTimeMillis(options));
        assertTrue(afterSuccess + 1000000 >= scheduler.getNextTimeMillis(options));
    }

    @SmallTest
    public void testParseOptions() throws Exception {
         OperationScheduler.Options options = new OperationScheduler.Options();
         assertEquals(
                 "OperationScheduler.Options[backoff=0.0+5.0 max=86400.0 min=0.0 period=3600.0]",
                 OperationScheduler.parseOptions("3600", options).toString());

         assertEquals(
                 "OperationScheduler.Options[backoff=0.0+2.5 max=86400.0 min=0.0 period=3700.0]",
                 OperationScheduler.parseOptions("backoff=+2.5 3700", options).toString());

         assertEquals(
                 "OperationScheduler.Options[backoff=10.0+2.5 max=12345.6 min=7.0 period=3800.0]",
                 OperationScheduler.parseOptions("max=12345.6 min=7 backoff=10 period=3800",
                         options).toString());

         assertEquals(
                "OperationScheduler.Options[backoff=10.0+2.5 max=12345.6 min=7.0 period=3800.0]",
                 OperationScheduler.parseOptions("", options).toString());
    }

    @SmallTest
    public void testMoratoriumWithHttpDate() throws Exception {
        String name = "OperationSchedulerTest.testMoratoriumWithHttpDate";
        SharedPreferences storage = getContext().getSharedPreferences(name, 0);
        storage.edit().clear().commit();

        OperationScheduler scheduler = new OperationScheduler(storage);
        OperationScheduler.Options options = new OperationScheduler.Options();

        long beforeTrigger = System.currentTimeMillis();
        scheduler.setTriggerTimeMillis(beforeTrigger + 1000000);
        assertEquals(beforeTrigger + 1000000, scheduler.getNextTimeMillis(options));

        scheduler.setMoratoriumTimeMillis(beforeTrigger + 2000000);
        assertEquals(beforeTrigger + 2000000, scheduler.getNextTimeMillis(options));

        long beforeMoratorium = System.currentTimeMillis();
        assertTrue(scheduler.setMoratoriumTimeHttp("3000"));
        long afterMoratorium = System.currentTimeMillis();
        assertTrue(beforeMoratorium + 3000000 <= scheduler.getNextTimeMillis(options));
        assertTrue(afterMoratorium + 3000000 >= scheduler.getNextTimeMillis(options));

        options.maxMoratoriumMillis = Long.MAX_VALUE / 2;
        assertTrue(scheduler.setMoratoriumTimeHttp("Fri, 31 Dec 2030 23:59:59 GMT"));
        assertEquals(1924991999000L, scheduler.getNextTimeMillis(options));

        assertFalse(scheduler.setMoratoriumTimeHttp("not actually a date"));
    }
}
