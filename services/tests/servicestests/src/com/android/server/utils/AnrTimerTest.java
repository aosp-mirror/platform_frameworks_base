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

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import android.platform.test.annotations.Presubmit;
import androidx.test.filters.SmallTest;

import com.android.internal.annotations.GuardedBy;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(Parameterized.class)
public class AnrTimerTest {

    // A log tag.
    private static final String TAG = "AnrTimerTest";

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

    /** The test helper is a self-contained object for a single test. */
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
                assertThat(mMessages.size()).isEqualTo(expected);
                return mMessages.toArray(new TestArg[expected]);
            }
        }
    }

    /**
     * Force AnrTimer to use the test parameter for the feature flag.
     */
    private class TestInjector extends AnrTimer.Injector {
        @Override
        boolean serviceEnabled() {
            return mEnabled;
        }
    }

    /**
     * An instrumented AnrTimer.
     */
    private class TestAnrTimer extends AnrTimer<TestArg> {
        private TestAnrTimer(Handler h, int key, String tag) {
            super(h, key, tag, new AnrTimer.Args().injector(new TestInjector()));
        }

        TestAnrTimer(Helper helper) {
            this(helper.mHandler, MSG_TIMEOUT, caller());
        }

        @Override
        public int getPid(TestArg arg) {
            return arg.pid;
        }

        @Override
        public int getUid(TestArg arg) {
            return arg.uid;
        }

        // Return the name of method that called the constructor, assuming that this function is
        // called from inside the constructor.  The calling method is used to name the AnrTimer
        // instance so that logs are easier to understand.
        private static String caller() {
            final int n = 4;
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack.length < n+1) return "test";
            return stack[n].getClassName() + "." + stack[n].getMethodName();
        }
    }

    void validate(TestArg expected, TestArg actual) {
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.what).isEqualTo(MSG_TIMEOUT);
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
        try (TestAnrTimer timer = new TestAnrTimer(helper)) {
            // One-time check that the injector is working as expected.
            assertThat(mEnabled).isEqualTo(timer.serviceEnabled());
            TestArg t = new TestArg(1, 1);
            timer.start(t, 10);
            // Delivery is immediate but occurs on a different thread.
            assertThat(helper.await(5000)).isTrue();
            TestArg[] result = helper.messages(1);
            validate(t, result[0]);
        }
    }

    /**
     * Verify that a restarted timer is delivered exactly once.  The initial timer value is very
     * large, to ensure it does not expire before the timer can be restarted.
     */
    @Test
    public void testTimerRestart() throws Exception {
        Helper helper = new Helper(1);
        try (TestAnrTimer timer = new TestAnrTimer(helper)) {
            TestArg t = new TestArg(1, 1);
            timer.start(t, 10000);
            // Briefly pause.
            assertThat(helper.await(10)).isFalse();
            timer.start(t, 10);
            // Delivery is immediate but occurs on a different thread.
            assertThat(helper.await(5000)).isTrue();
            TestArg[] result = helper.messages(1);
            validate(t, result[0]);
        }
    }

    /**
     * Verify that a restarted timer is delivered exactly once.  The initial timer value is very
     * large, to ensure it does not expire before the timer can be restarted.
     */
    @Test
    public void testTimerZero() throws Exception {
        Helper helper = new Helper(1);
        try (TestAnrTimer timer = new TestAnrTimer(helper)) {
            TestArg t = new TestArg(1, 1);
            timer.start(t, 0);
            // Delivery is immediate but occurs on a different thread.
            assertThat(helper.await(5000)).isTrue();
            TestArg[] result = helper.messages(1);
            validate(t, result[0]);
        }
    }

    /**
     * Verify that if three timers are scheduled on a single AnrTimer, they are delivered in time
     * order.
     */
    @Test
    public void testMultipleTimers() throws Exception {
        // Expect three messages.
        Helper helper = new Helper(3);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        try (TestAnrTimer timer = new TestAnrTimer(helper)) {
            timer.start(t1, 50);
            timer.start(t2, 60);
            timer.start(t3, 40);
            // Delivery is immediate but occurs on a different thread.
            assertThat(helper.await(5000)).isTrue();
            TestArg[] result = helper.messages(3);
            validate(t3, result[0]);
            validate(t1, result[1]);
            validate(t2, result[2]);
        }
    }

    /**
     * Verify that if three timers are scheduled on three separate AnrTimers, they are delivered
     * in time order.
     */
    @Test
    public void testMultipleServices() throws Exception {
        // Expect three messages.
        Helper helper = new Helper(3);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        try (TestAnrTimer x1 = new TestAnrTimer(helper);
             TestAnrTimer x2 = new TestAnrTimer(helper);
             TestAnrTimer x3 = new TestAnrTimer(helper)) {
            x1.start(t1, 50);
            x2.start(t2, 60);
            x3.start(t3, 40);
            // Delivery is immediate but occurs on a different thread.
            assertThat(helper.await(5000)).isTrue();
            TestArg[] result = helper.messages(3);
            validate(t3, result[0]);
            validate(t1, result[1]);
            validate(t2, result[2]);
        }
    }

    /**
     * Verify that a canceled timer is not delivered.
     */
    @Test
    public void testCancelTimer() throws Exception {
        // Expect two messages.
        Helper helper = new Helper(2);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        try (TestAnrTimer timer = new TestAnrTimer(helper)) {
            timer.start(t1, 50);
            timer.start(t2, 60);
            timer.start(t3, 40);
            // Briefly pause.
            assertThat(helper.await(10)).isFalse();
            timer.cancel(t1);
            // Delivery is immediate but occurs on a different thread.
            assertThat(helper.await(5000)).isTrue();
            TestArg[] result = helper.messages(2);
            validate(t3, result[0]);
            validate(t2, result[1]);
        }
    }

    /**
     * Return the dump string.
     */
    private String getDumpOutput() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnrTimer.dump(pw, true, new TestInjector());
        pw.close();
        return sw.getBuffer().toString();
    }

    /**
     * Verify the dump output.  This only applies when native timers are supported.
     */
    @Test
    public void testDumpOutput() throws Exception {
        if (!AnrTimer.nativeTimersSupported()) return;

        // The timers in this class are named "class.method".
        final String timerName = "timer: com.android.server.utils.AnrTimerTest";

        String r1 = getDumpOutput();
        assertThat(r1).doesNotContain(timerName);

        Helper helper = new Helper(2);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        try (TestAnrTimer timer = new TestAnrTimer(helper)) {
            timer.start(t1, 5000);
            timer.start(t2, 5000);
            timer.start(t3, 5000);

            String r2 = getDumpOutput();
            // There are timers in the list if and only if the feature is enabled.
            if (mEnabled) {
                assertThat(r2).contains(timerName);
            } else {
                assertThat(r2).doesNotContain(timerName);
            }
        }

        String r3 = getDumpOutput();
        assertThat(r3).doesNotContain(timerName);
    }

    /**
     * Verify that GC works as expected.  This test will almost certainly be flaky, since it
     * relies on the finalizers running, which is a best-effort on the part of the JVM.
     * Therefore, the test is marked @Ignore.  Remove that annotation to run the test locally.
     */
    @Ignore
    @Test
    public void testGarbageCollection() throws Exception {
        if (!mEnabled) return;

        String r1 = getDumpOutput();
        assertThat(r1).doesNotContain("timer:");

        Helper helper = new Helper(2);
        TestArg t1 = new TestArg(1, 1);
        TestArg t2 = new TestArg(1, 2);
        TestArg t3 = new TestArg(1, 3);
        // The timer is explicitly not closed.  It is, however, scoped to the next block.
        {
            TestAnrTimer timer = new TestAnrTimer(helper);
            timer.start(t1, 5000);
            timer.start(t2, 5000);
            timer.start(t3, 5000);

            String r2 = getDumpOutput();
            // There are timers in the list if and only if the feature is enabled.
            if (mEnabled) {
              assertThat(r2).contains("timer:");
            } else {
              assertThat(r2).doesNotContain("timer:");
            }
        }

        // Try to make finalizers run.  The timer object above should be a candidate.  Finalizers
        // are run on their own thread, so pause this thread to give that thread some time.
        String r3 = getDumpOutput();
        for (int i = 0; i < 10 && r3.contains("timer:"); i++) {
            Log.i(TAG, "requesting finalization " + i);
            System.gc();
            System.runFinalization();
            Thread.sleep(4 * 1000);
            r3 = getDumpOutput();
        }

        // The timer was not explicitly closed but it should have been implicitly closed by GC.
        assertThat(r3).doesNotContain("timer:");
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("servicestestjni");
    }
}
