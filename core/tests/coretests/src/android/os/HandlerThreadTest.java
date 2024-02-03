/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HandlerThreadTest {
    private static final int TEST_WHAT = 1;

    @Rule(order = 1)
    public ExpectedException mThrown = ExpectedException.none();

    @Rule(order = 2)
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private boolean mGotMessage = false;
    private int mGotMessageWhat = -1;
    private volatile boolean mDidSetup = false;
    private volatile int mLooperTid = -1;

    @Test
    @MediumTest
    public void testHandlerThread() throws Exception {
        HandlerThread th1 =  new HandlerThread("HandlerThreadTest") {
            protected void onLooperPrepared() {
                synchronized (HandlerThreadTest.this) {
                    mDidSetup = true;
                    mLooperTid = Process.myTid();
                    HandlerThreadTest.this.notify();
                }
            }
        };
        
        assertFalse(th1.isAlive());
        assertNull(th1.getLooper());
        
        th1.start();
        
        assertTrue(th1.isAlive());
        assertNotNull(th1.getLooper());
       
        // The call to getLooper() internally blocks until the looper is
        // available, but will call onLooperPrepared() after that.  So we
        // need to block here to wait for our onLooperPrepared() to complete
        // and fill in the values we expect.
        synchronized (this) {
            while (!mDidSetup) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }
        
        // Make sure that the process was set.
        assertNotSame(-1, mLooperTid);
        // Make sure that the onLooperPrepared() was called on a different thread.
        assertNotSame(Process.myTid(), mLooperTid);
        
        final Handler h1 = new Handler(th1.getLooper()) {
            public void handleMessage(Message msg) {
                assertEquals(TEST_WHAT, msg.what);
                // Ensure that we are running on the same thread in which the looper was setup on.
                assertEquals(mLooperTid, Process.myTid());
                
                mGotMessageWhat = msg.what;
                mGotMessage = true;
                synchronized(this) {
                    notifyAll();
                }
            }
        };
        
        Message msg = h1.obtainMessage(TEST_WHAT);
        
        synchronized (h1) {
            // wait until we have the lock before sending the message.
            h1.sendMessage(msg);
            try {
                // wait for the message to be handled
                h1.wait();
            } catch (InterruptedException e) {
            }
        }
        
        assertTrue(mGotMessage);
        assertEquals(TEST_WHAT, mGotMessageWhat);
    }

    /**
     * Confirm that a background handler thread throwing an exception during a test results in a
     * test failure being reported.
     */
    @Test
    public void testUncaughtExceptionFails() throws Exception {
        // For the moment we can only test Ravenwood; on a physical device uncaught exceptions
        // are detected, but reported as test failures at a higher level where we can't inspect
        Assume.assumeTrue(false); // TODO: re-enable
        mThrown.expect(IllegalStateException.class);

        final HandlerThread thread = new HandlerThread("HandlerThreadTest");
        thread.start();
        thread.getThreadHandler().post(() -> {
            throw new IllegalStateException();
        });

        // Wait until we've drained past the message above, then terminate test without throwing
        // directly; the test harness should notice and report the uncaught exception
        while (!thread.getThreadHandler().getLooper().getQueue().isIdle()) {
            SystemClock.sleep(10);
        }
    }
}
