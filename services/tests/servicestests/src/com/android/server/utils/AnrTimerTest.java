/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import android.platform.test.annotations.Presubmit;
import androidx.test.filters.SmallTest;

import com.android.internal.annotations.GuardedBy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(Parameterized.class)
public class AnrTimerTest {

    // The commonly used message timeout key.
    private static final int MSG_TIMEOUT = 1;

    // The test argument includes a pid and uid, and a tag.  The tag is used to distinguish
    // different message instances.  Additional fields (like what) capture delivery information
    // that is checked by the test.
    private static class TestArg {
        final int pid;
        final int uid;
        int what;

        TestArg(int pid, int uid) {
            this.pid = pid;
            this.uid = uid;
            this.what = 0;
        }
    }

    /**
     * The test handler is a self-contained object for a single test.
     */
    private static class Helper {
        final Object mLock = new Object();

        final Handler mHandler;
        final CountDownLatch mLatch;
        @GuardedBy("mLock")
        final ArrayList<TestArg> mMessages;

        Helper(int expect) {
            mHandler = new Handler(Looper.getMainLooper(), this::expirationHandler);
            mMessages = new ArrayList<>();
            mLatch = new CountDownLatch(expect);
        }

        /**
         * When a timer expires, the object must be a TestArg.  Update the TestArg with
         * expiration metadata and save it.
         */
        private boolean expirationHandler(Message msg) {
            synchronized (mLock) {
                TestArg arg = (TestArg) msg.obj;
                arg.what = msg.what;
                mMessages.add(arg);
                mLatch.countDown();
                return false;
            }
        }

        boolean await(long timeout) throws InterruptedException {
            // No need to synchronize, as the CountDownLatch is already thread-safe.
            return mLatch.await(timeout, TimeUnit.MILLISECONDS);
        }

        /**
         * Fetch the received messages.  Fail if the count of received messages is other than the
         * expected count.
         */
        TestArg[] messages(int expected) {
            synchronized (mLock) {
                assertEquals(expected, mMessages.size());
                return mMessages.toArray(new TestArg[expected]);
            }
        }
    }

    /**
     * Force AnrTimer to use the test parameter for the feature flag.
     */
    class TestInjector extends AnrTimer.Injector {
        @Override
        boolean anrTimerServiceEnabled() {
            return mEnabled;
        }
    }

    /**
     * An instrumented AnrTimer.
     */
    private static class TestAnrTimer extends AnrTimer<TestArg> {
        private TestAnrTimer(Handler h, int key, String tag) {
            super(h, key, tag);
        }

        TestAnrTimer(Helper helper) {
            this(helper.mHandler, MSG_TIMEOUT, caller());
        }

        void start(TestArg arg, long millis) {
            start(arg, arg.pid, arg.uid, millis);
        }

        // Return the name of method that called the constructor, assuming that this function is
        // called from inside the constructor.  The calling method is used to name the AnrTimer
        // instance so that logs are easier to understand.
        private static String caller() {
            final int n = 4;
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack.length < n+1) return "test";
            return stack[n].getMethodName();
        }
    }

    void validate(TestArg expected, TestArg actual) {
        assertEquals(expected, actual);
        assertEquals(actual.what, MSG_TIMEOUT);
    }

    @Parameters(name = "featureEnabled={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {false}, {true} });
    }

    /** True if the feature is enabled. */
    private boolean mEnabled;

    public AnrTimerTest(boolean featureEnabled) {
        mEnabled = featureEnabled;
    }

    /**
     * Verify that a simple expiration succeeds.  The timer is started for 10ms.  The test
     * procedure waits 5s for the expiration message, but under correct operation, the test will
     * only take 10ms
     */
    @Test
    public void testSimpleTimeout() throws Exception {
        Helper helper = new Helper(1);
        TestAnrTimer timer = new TestAnrTimer(helper);
        TestArg t = new TestArg(1, 1);
        timer.start(t, 10);
        // Delivery is immediate but occurs on a different thread.
        assertTrue(helper.await(5000));
        TestArg[] result = helper.messages(1);
        validate(t, result[0]);
    }

    /**
     * Verify that if three timers are scheduled, they are delivered in time order.
     */
    @Test
    public void testMultipleTimers() throws Exception {
        // Expect three messages.
        Helper helper = new Helper(3);
        TestAnrTimer timer = new TestAnrTimer(helper);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        timer.start(t1, 50);
        timer.start(t2, 60);
        timer.start(t3, 40);
        // Delivery is immediate but occurs on a different thread.
        assertTrue(helper.await(5000));
        TestArg[] result = helper.messages(3);
        validate(t3, result[0]);
        validate(t1, result[1]);
        validate(t2, result[2]);
    }

    /**
     * Verify that a canceled timer is not delivered.
     */
    @Test
    public void testCancelTimer() throws Exception {
        // Expect two messages.
        Helper helper = new Helper(2);
        TestAnrTimer timer = new TestAnrTimer(helper);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        timer.start(t1, 50);
        timer.start(t2, 60);
        timer.start(t3, 40);
        // Briefly pause.
        assertFalse(helper.await(10));
        timer.cancel(t1);
        // Delivery is immediate but occurs on a different thread.
        assertTrue(helper.await(5000));
        TestArg[] result = helper.messages(2);
        validate(t3, result[0]);
        validate(t2, result[1]);
    }
}
