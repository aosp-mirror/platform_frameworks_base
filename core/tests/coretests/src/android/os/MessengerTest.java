/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = ActivityManager.class)
public class MessengerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private Context mContext;
    private Messenger mServiceMessenger;
    
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (MessengerTest.this) {
                mServiceMessenger = new Messenger(service);
                MessengerTest.this.notifyAll();
            }
        }
        public void onServiceDisconnected(ComponentName name) {
            mServiceMessenger = null;
        }
    };
    
    private class TestThread extends TestHandlerThread {
        private Handler mTestHandler;
        private Messenger mTestMessenger;
        
        public void go() {
            synchronized (MessengerTest.this) {
                mTestHandler = new Handler() {
                    public void handleMessage(Message msg) {
                        TestThread.this.handleMessage(msg);
                    }
                };
                mTestMessenger = new Messenger(mTestHandler);
                TestThread.this.executeTest();
            }
        }

        public void executeTest() {
            Message msg = Message.obtain();
            msg.arg1 = 100;
            msg.arg2 = 1000;
            msg.replyTo = mTestMessenger;
            try {
                mServiceMessenger.send(msg);
            } catch (RemoteException e) {
            }
        }
        
        public void handleMessage(Message msg) {
            if (msg.arg1 != 100) {
                failure(new RuntimeException(
                        "Message.arg1 is not 100: " + msg.arg1));
                return;
            }
            if (msg.arg2 != 1000) {
                failure(new RuntimeException(
                        "Message.arg2 is not 1000: " + msg.arg2));
                return;
            }
            if (!mTestMessenger.equals(msg.replyTo)) {
                failure(new RuntimeException(
                        "Message.replyTo is not me: " + msg.replyTo));
                return;
            }
            success();
        }
    };
    
    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mContext.bindService(new Intent(mContext, MessengerService.class),
                mConnection, Context.BIND_AUTO_CREATE);
        synchronized (this) {
            while (mServiceMessenger == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    @After
    public void tearDown() throws Exception {
        mContext.unbindService(mConnection);
    }

    @Test
    @MediumTest
    public void testSend() {
        (new TestThread()).doTest(1000);
    }
}
