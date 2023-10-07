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

package com.android.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import android.util.Log;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AnrTimerTest
 */
@SmallTest
@Presubmit
public class AnrTimerTest {

    /**
     * A handler that allows control over when to dispatch messages and callbacks. Because most
     * Handler methods are final, the only thing this handler can intercept is sending messages.
     * This handler allows unit tests to be written without a need to sleep (which leads to flaky
     * tests).
     *
     * This code was cloned from {@link com.android.systemui.utils.os.FakeHandler}.
     */
    static class TestHandler extends Handler {

        private boolean mImmediate = true;
        private ArrayList<Message> mQueuedMessages = new ArrayList<>();

        ArrayList<Long> mDelays = new ArrayList<>();

        TestHandler(Looper looper, Callback callback, boolean immediate) {
            super(looper, callback);
            mImmediate = immediate;
        }

        TestHandler(Looper looper, Callback callback) {
            this(looper, callback, true);
        }

        /**
         * Override sendMessageAtTime.  In immediate mode, the message is immediately dispatched.
         * In non-immediate mode, the message is enqueued to the real handler.  In both cases, the
         * original delay is computed by comparing the target dispatch time with 'now'.  This
         * computation is prone to errors if the code experiences delays.  The computed time is
         * captured in the mDelays list.
         */
        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            long delay = uptimeMillis - SystemClock.uptimeMillis();
            mDelays.add(delay);
            if (mImmediate) {
                mQueuedMessages.add(msg);
                dispatchQueuedMessages();
            } else {
                super.sendMessageAtTime(msg, uptimeMillis);
            }
            return true;
        }

        void setImmediate(boolean immediate) {
            mImmediate = immediate;
        }

        /** Dispatch any messages that have been queued on the calling thread. */
        void dispatchQueuedMessages() {
            ArrayList<Message> messages = new ArrayList<>(mQueuedMessages);
            mQueuedMessages.clear();
            for (Message msg : messages) {
                dispatchMessage(msg);
            }
        }

        /**
         * Compare the captured delays with the input array.  The comparison is fuzzy because the
         * captured delay (see sendMessageAtTime) is affected by process delays.
         */
        void verifyDelays(long[] r) {
            final long FUZZ = 10;
            assertEquals(r.length, mDelays.size());
            for (int i = 0; i < mDelays.size(); i++) {
                long t = r[i];
                long v = mDelays.get(i);
                assertTrue(v >= t - FUZZ && v <= t + FUZZ);
            }
        }
    }

    private Handler mHandler;
    private CountDownLatch mLatch = null;
    private ArrayList<Message> mMessages;

    // The commonly used message timeout key.
    private static final int MSG_TIMEOUT = 1;

    @Before
    public void setUp() {
        mHandler = new Handler(Looper.getMainLooper(), this::expirationHandler);
        mMessages = new ArrayList<>();
        mLatch = new CountDownLatch(1);
        AnrTimer.resetTimerListForHermeticTest();
    }

    @After
    public void tearDown() {
        mHandler = null;
        mMessages = null;
    }

    // When a timer expires, set the expiration time in the message and add it to the queue.
    private boolean expirationHandler(Message msg) {
        mMessages.add(Message.obtain(msg));
        mLatch.countDown();
        return false;
    }

    // The test argument includes a pid and uid, and a tag.  The tag is used to distinguish
    // different message instances.
    private static class TestArg {
        final int pid;
        final int uid;
        final int tag;

        TestArg(int pid, int uid, int tag) {
            this.pid = pid;
            this.uid = uid;
            this.tag = tag;
        }
        @Override
        public String toString() {
            return String.format("pid=%d uid=%d tag=%d", pid, uid, tag);
        }
    }

    /**
     * An instrumented AnrTimer.
     */
    private class TestAnrTimer extends AnrTimer {
        // A local copy of 'what'.  The field in AnrTimer is private.
        final int mWhat;

        TestAnrTimer(Handler h, int key, String tag) {
            super(h, key, tag);
            mWhat = key;
        }

        TestAnrTimer() {
            this(mHandler, MSG_TIMEOUT, caller());
        }

        TestAnrTimer(Handler h, int key, String tag, boolean extend, TestInjector injector) {
            super(h, key, tag, extend, injector);
            mWhat = key;
        }

        TestAnrTimer(boolean extend, TestInjector injector) {
            this(mHandler, MSG_TIMEOUT, caller(), extend, injector);
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

        boolean start(TestArg arg, long millis) {
            return start(arg, arg.pid, arg.uid, millis);
        }

        int what() {
            return mWhat;
        }
    }

    private static class TestTracker extends AnrTimer.CpuTracker {
        long index = 0;
        final int skip;
        TestTracker(int skip) {
            this.skip = skip;
        }
        long delay(int pid) {
            return index++ * skip;
        }
    }

    private class TestInjector extends AnrTimer.Injector {
        final boolean mImmediate;
        final AnrTimer.CpuTracker mTracker;
        TestHandler mTestHandler;

        TestInjector(int skip, boolean immediate) {
            super(mHandler);
            mTracker = new TestTracker(skip);
            mImmediate = immediate;
        }

        TestInjector(int skip) {
            this(skip, true);
        }

        @Override
        Handler getHandler(Handler.Callback callback) {
            if (mTestHandler == null) {
                mTestHandler = new TestHandler(mHandler.getLooper(), callback, mImmediate);
            }
            return mTestHandler;
        }

        /** Fetch the allocated handle. This does not check for nulls. */
        TestHandler getHandler() {
            return mTestHandler;
        }

        @Override
        AnrTimer.CpuTracker getTracker() {
            return mTracker;
        }

        /** For test purposes, always enable the feature. */
        @Override
        boolean getFeatureEnabled() {
            return true;
        }
    }

    // Tests
    // 1. Start a timer and wait for expiration.
    // 2. Start a timer and cancel it.  Verify no expiration.
    // 3. Start a timer.  Shortly thereafter, restart it.  Verify only one expiration.
    // 4. Start a couple of timers.  Verify max active timers.  Discard one and verify the active
    //    count drops by 1.  Accept one and verify the active count drops by 1.

    @Test
    public void testSimpleTimeout() throws Exception {
        // Create an immediate TestHandler.
        TestInjector injector = new TestInjector(0);
        TestAnrTimer timer = new TestAnrTimer(false, injector);
        TestArg t = new TestArg(1, 1, 3);
        assertTrue(timer.start(t, 10));
        // Delivery is immediate but occurs on a different thread.
        assertTrue(mLatch.await(100, TimeUnit.MILLISECONDS));
        assertEquals(1, mMessages.size());
        Message m = mMessages.get(0);
        assertEquals(timer.what(), m.what);
        assertEquals(t, m.obj);

        // Verify that the timer is still present.
        assertEquals(1, AnrTimer.sizeOfTimerList());
        assertTrue(timer.accept(t));
        assertEquals(0, AnrTimer.sizeOfTimerList());

        // Verify that the timer no longer exists.
        assertFalse(timer.accept(t));
    }

    @Test
    public void testCancel() throws Exception {
        // Create an non-immediate TestHandler.
        TestInjector injector = new TestInjector(0, false);
        TestAnrTimer timer = new TestAnrTimer(false, injector);

        Handler handler = injector.getHandler();
        assertNotNull(handler);
        assertTrue(handler instanceof TestHandler);

        // The tests that follow check for a 'what' of 0 (zero), which is the message key used
        // by AnrTimer internally.
        TestArg t = new TestArg(1, 1, 3);
        assertFalse(handler.hasMessages(0));
        assertTrue(timer.start(t, 100));
        assertTrue(handler.hasMessages(0));
        assertTrue(timer.cancel(t));
        assertFalse(handler.hasMessages(0));

        // Verify that no expiration messages were delivered.
        assertEquals(0, mMessages.size());
        assertEquals(0, AnrTimer.sizeOfTimerList());
    }

    @Test
    public void testRestart() throws Exception {
        // Create an non-immediate TestHandler.
        TestInjector injector = new TestInjector(0, false);
        TestAnrTimer timer = new TestAnrTimer(false, injector);

        TestArg t = new TestArg(1, 1, 3);
        assertTrue(timer.start(t, 2500));
        assertTrue(timer.start(t, 1000));

        // Verify that the test handler saw two timeouts.
        injector.getHandler().verifyDelays(new long[] { 2500, 1000 });

        // Verify that there is a single timer.  Then cancel it.
        assertEquals(1, AnrTimer.sizeOfTimerList());
        assertTrue(timer.cancel(t));
        assertEquals(0, AnrTimer.sizeOfTimerList());
    }

    @Test
    public void testExtendNormal() throws Exception {
        // Create an immediate TestHandler.
        TestInjector injector = new TestInjector(5);
        TestAnrTimer timer = new TestAnrTimer(true, injector);
        TestArg t = new TestArg(1, 1, 3);
        assertTrue(timer.start(t, 10));

        assertTrue(mLatch.await(100, TimeUnit.MILLISECONDS));
        assertEquals(1, mMessages.size());
        Message m = mMessages.get(0);
        assertEquals(timer.what(), m.what);
        assertEquals(t, m.obj);

        // Verify that the test handler saw two timeouts: one of 10ms and one of 5ms.
        injector.getHandler().verifyDelays(new long[] { 10, 5 });

        // Verify that the timer is still present. Then remove it and verify that the list is
        // empty.
        assertEquals(1, AnrTimer.sizeOfTimerList());
        assertTrue(timer.accept(t));
        assertEquals(0, AnrTimer.sizeOfTimerList());
    }

    @Test
    public void testExtendOversize() throws Exception {
        // Create an immediate TestHandler.
        TestInjector injector = new TestInjector(25);
        TestAnrTimer timer = new TestAnrTimer(true, injector);
        TestArg t = new TestArg(1, 1, 3);
        assertTrue(timer.start(t, 10));

        assertTrue(mLatch.await(100, TimeUnit.MILLISECONDS));
        assertEquals(1, mMessages.size());
        Message m = mMessages.get(0);
        assertEquals(timer.what(), m.what);
        assertEquals(t, m.obj);

        // Verify that the test handler saw two timeouts: one of 10ms and one of 10ms.
        injector.getHandler().verifyDelays(new long[] { 10, 10 });

        // Verify that the timer is still present. Then remove it and verify that the list is
        // empty.
        assertEquals(1, AnrTimer.sizeOfTimerList());
        assertTrue(timer.accept(t));
        assertEquals(0, AnrTimer.sizeOfTimerList());
    }
}
