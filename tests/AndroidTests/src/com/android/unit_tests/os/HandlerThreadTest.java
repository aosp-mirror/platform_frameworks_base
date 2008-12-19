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

package com.android.unit_tests.os;

import junit.framework.TestCase;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

public class HandlerThreadTest extends TestCase {
    private static final int TEST_WHAT = 1;

    private boolean mGotMessage = false;
    private int mGotMessageWhat = -1;
    private volatile boolean mDidSetup = false;
    private volatile int mLooperTid = -1;
    
    @MediumTest
    public void testHandlerThread() throws Exception {
        HandlerThread th1 =  new HandlerThread("HandlerThreadTest") {
            protected void onLooperPrepared() {
                mDidSetup = true;
                mLooperTid = Process.myTid();
            }
        };
        
        assertFalse(th1.isAlive());
        assertNull(th1.getLooper());
        
        th1.start();
        
        assertTrue(th1.isAlive());
        assertNotNull(th1.getLooper());
       
        /* 
         * Since getLooper() will block until the HandlerThread is setup, we are guaranteed
         * that mDidSetup and mLooperTid will have been initalized. If they have not, then 
         * this test should fail
         */
        // Make sure that the onLooperPrepared() was called on a different thread.
        assertNotSame(Process.myTid(), mLooperTid);
        assertTrue(mDidSetup);
        
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
}
