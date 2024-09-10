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

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Suppress  // Failing.
@RunWith(AndroidJUnit4.class)
public class MessageQueueTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private static class BaseTestHandler extends TestHandlerThread {
        Handler mHandler;
        int mLastMessage;
        int mCount;

        public BaseTestHandler() {
        }

        public void go() {
            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    BaseTestHandler.this.handleMessage(msg);
                }
            };
        }

        public void handleMessage(Message msg) {
            if (!msg.isInUse()) {
                failure(new RuntimeException(
                        "msg.isInuse is false, should always be true, #" + msg.what));
            }
            if (mCount <= mLastMessage) {
                if (msg.what != mCount) {
                    failure(new RuntimeException(
                            "Expected message #" + mCount
                                    + ", received #" + msg.what));
                } else if (mCount == mLastMessage) {
                    success();
                }
                mCount++;
            } else {
                failure(new RuntimeException(
                        "Message received after done, #" + msg.what));
            }
        }
    }

    @Test
    @MediumTest
    public void testMessageOrder() throws Exception {
        TestHandlerThread tester = new BaseTestHandler() {
            public void go() {
                super.go();
                long now = SystemClock.uptimeMillis() + 200;
                mLastMessage = 4;
                mCount = 0;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(2), now + 1);
                mHandler.sendMessageAtTime(mHandler.obtainMessage(3), now + 2);
                mHandler.sendMessageAtTime(mHandler.obtainMessage(4), now + 2);
                mHandler.sendMessageAtTime(mHandler.obtainMessage(0), now + 0);
                mHandler.sendMessageAtTime(mHandler.obtainMessage(1), now + 0);
            }
        };

        tester.doTest(1000);
    }

    @Test
    @MediumTest
    public void testAtFrontOfQueue() throws Exception {
        TestHandlerThread tester = new BaseTestHandler() {
            public void go() {
                super.go();
                long now = SystemClock.uptimeMillis() + 200;
                mLastMessage = 3;
                mCount = 0;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(3), now);
                mHandler.sendMessageAtFrontOfQueue(mHandler.obtainMessage(2));
                mHandler.sendMessageAtFrontOfQueue(mHandler.obtainMessage(0));
            }

            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == 0) {
                    mHandler.sendMessageAtFrontOfQueue(mHandler.obtainMessage(1));
                }
            }
        };

        tester.doTest(1000);
    }

    private static class TestFieldIntegrityHandler extends TestHandlerThread {
        Handler mHandler;
        int mLastMessage;
        int mCount;

        public TestFieldIntegrityHandler() {
        }

        public void go() {
            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    TestFieldIntegrityHandler.this.handleMessage(msg);
                }
            };
        }

        public void handleMessage(Message msg) {
            if (!msg.isInUse()) {
                failure(new RuntimeException(
                        "msg.isInuse is false, should always be true, #" + msg.what));
            }
            if (mCount <= mLastMessage) {
                if (msg.what != mCount) {
                    failure(new RuntimeException(
                            "Expected message #" + mCount
                                    + ", received #" + msg.what));
                } else if (mCount == mLastMessage) {
                    success();
                }
                mCount++;
            } else {
                failure(new RuntimeException(
                        "Message received after done, #" + msg.what));
            }
        }
    }

    @Test
    @MediumTest
    public void testFieldIntegrity() throws Exception {

        TestHandlerThread tester = new TestFieldIntegrityHandler() {
            Bundle mBundle;

            public void go() {
                super.go();
                mLastMessage = 1;
                mCount = 0;
                mHandler.sendMessage(mHandler.obtainMessage(0));
            }

            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == 0) {
                    msg.flags = Message.FLAGS_TO_CLEAR_ON_COPY_FROM;
                    msg.what = 1;
                    msg.arg1 = 456;
                    msg.arg2 = 789;
                    msg.obj = this;
                    msg.replyTo = null;
                    mBundle = new Bundle();
                    msg.data = mBundle;
                    msg.data.putString("key", "value");

                    Message newMsg = mHandler.obtainMessage();
                    newMsg.copyFrom(msg);
                    if (newMsg.isInUse() != false) {
                        failure(new RuntimeException(
                                "newMsg.isInUse is true should be false after copyFrom"));
                    }
                    if (newMsg.flags != 0) {
                        failure(new RuntimeException(String.format(
                        "newMsg.flags is %d should be 0 after copyFrom", newMsg.flags)));
                    }
                    if (newMsg.what != 1) {
                        failure(new RuntimeException(String.format(
                                "newMsg.what is %d should be %d after copyFrom", newMsg.what, 1)));
                    }
                    if (newMsg.arg1 != 456) {
                        failure(new RuntimeException(String.format(
                                "newMsg.arg1 is %d should be %d after copyFrom", msg.arg1, 456)));
                    }
                    if (newMsg.arg2 != 789) {
                        failure(new RuntimeException(String.format(
                                "newMsg.arg2 is %d should be %d after copyFrom", msg.arg2, 789)));
                    }
                    if (newMsg.obj != this) {
                        failure(new RuntimeException(
                                "newMsg.obj should be 'this' after copyFrom"));
                    }
                    if (newMsg.replyTo != null) {
                        failure(new RuntimeException(
                                "newMsg.replyTo should be null after copyFrom"));
                    }
                    if (newMsg.data == mBundle) {
                        failure(new RuntimeException(
                                "newMsg.data should NOT be mBundle after copyFrom"));
                    }
                    if (!newMsg.data.getString("key").equals(mBundle.getString("key"))) {
                        failure(new RuntimeException(String.format(
                                "newMsg.data.getString(\"key\") is %s and does not equal" +
                                " mBundle.getString(\"key\") which is %s after copyFrom",
                                newMsg.data.getString("key"),  mBundle.getString("key"))));
                    }
                    if (newMsg.when != 0) {
                        failure(new RuntimeException(String.format(
                                "newMsg.when is %d should be 0 after copyFrom", newMsg.when)));
                    }
                    if (newMsg.target != mHandler) {
                        failure(new RuntimeException(
                                "newMsg.target is NOT mHandler after copyFrom"));
                    }
                    if (newMsg.callback != null) {
                        failure(new RuntimeException(
                                "newMsg.callback is NOT null after copyFrom"));
                    }

                    mHandler.sendMessage(newMsg);
                } else if (msg.what == 1) {
                    if (msg.isInUse() != true) {
                        failure(new RuntimeException(String.format(
                                "msg.isInUse is false should be true after when processing %d",
                                msg.what)));
                    }
                    if (msg.arg1 != 456) {
                        failure(new RuntimeException(String.format(
                                "msg.arg1 is %d should be %d when processing # %d",
                                msg.arg1, 456, msg.what)));
                    }
                    if (msg.arg2 != 789) {
                        failure(new RuntimeException(String.format(
                                "msg.arg2 is %d should be %d when processing # %d",
                                msg.arg2, 789, msg.what)));
                    }
                    if (msg.obj != this) {
                        failure(new RuntimeException(String.format(
                                "msg.obj should be 'this' when processing # %d", msg.what)));
                    }
                    if (msg.replyTo != null) {
                        failure(new RuntimeException(String.format(
                                "msg.replyTo should be null when processing # %d", msg.what)));
                    }
                    if (!msg.data.getString("key").equals(mBundle.getString("key"))) {
                        failure(new RuntimeException(String.format(
                                "msg.data.getString(\"key\") is %s and does not equal" +
                                " mBundle.getString(\"key\") which is %s when processing # %d",
                                msg.data.getString("key"),  mBundle.getString("key"), msg.what)));
                    }
                    if (msg.when != 0) {
                        failure(new RuntimeException(String.format(
                                "msg.when is %d should be 0 when processing # %d",
                                msg.when, msg.what)));
                    }
                    if (msg.target != null) {
                        failure(new RuntimeException(String.format(
                                "msg.target is NOT null when processing # %d", msg.what)));
                    }
                    if (msg.callback != null) {
                        failure(new RuntimeException(String.format(
                                "msg.callback is NOT null when processing # %d", msg.what)));
                    }
                } else {
                    failure(new RuntimeException(String.format(
                            "Unexpected msg.what is %d" + msg.what)));
                }
            }
        };

        tester.doTest(1000);
    }
}
