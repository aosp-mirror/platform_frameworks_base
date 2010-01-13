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

import android.os.Broadcaster;
import android.os.Handler;
import android.os.Message;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;

public class BroadcasterTest extends TestCase {
    private static final int MESSAGE_A = 23234;
    private static final int MESSAGE_B = 3;
    private static final int MESSAGE_C = 14;
    private static final int MESSAGE_D = 95;

    @MediumTest
    public void test1() throws Exception {
        /*
        * One handler requestes one message, with a translation
        */
        HandlerTester tester = new HandlerTester() {
            Handler h;

            public void go() {
                Broadcaster b = new Broadcaster();
                h = new H();

                b.request(MESSAGE_A, h, MESSAGE_B);

                Message msg = new Message();
                msg.what = MESSAGE_A;

                b.broadcast(msg);
            }

            public void handleMessage(Message msg) {
                if (msg.what == MESSAGE_B) {
                    success();
                } else {
                    failure();
                }
            }
        };
        tester.doTest(1000);
    }

    private static class Tests2and3 extends HandlerTester {
        Tests2and3(int n) {
            N = n;
        }

        int N;
        Handler mHandlers[];
        boolean mSuccess[];

        public void go() {
            Broadcaster b = new Broadcaster();
            mHandlers = new Handler[N];
            mSuccess = new boolean[N];
            for (int i = 0; i < N; i++) {
                mHandlers[i] = new H();
                mSuccess[i] = false;
                b.request(MESSAGE_A, mHandlers[i], MESSAGE_B + i);
            }

            Message msg = new Message();
            msg.what = MESSAGE_A;

            b.broadcast(msg);
        }

        public void handleMessage(Message msg) {
            int index = msg.what - MESSAGE_B;
            if (index < 0 || index >= N) {
                failure();
            } else {
                if (msg.getTarget() == mHandlers[index]) {
                    mSuccess[index] = true;
                }
            }
            boolean winner = true;
            for (int i = 0; i < N; i++) {
                if (!mSuccess[i]) {
                    winner = false;
                }
            }
            if (winner) {
                success();
            }
        }
    }

    @MediumTest
    public void test2() throws Exception {
        /*
        * 2 handlers request the same message, with different translations
        */
        HandlerTester tester = new Tests2and3(2);
        tester.doTest(1000);
    }

    @MediumTest
    public void test3() throws Exception {
        /*
        * 1000 handlers request the same message, with different translations
        */
        HandlerTester tester = new Tests2and3(10);
        tester.doTest(1000);
    }

    @MediumTest
    public void test4() throws Exception {
        /*
        * Two handlers request different messages, with translations, sending
        * only one.  The other one should never get sent.
        */
        HandlerTester tester = new HandlerTester() {
            Handler h1;
            Handler h2;

            public void go() {
                Broadcaster b = new Broadcaster();
                h1 = new H();
                h2 = new H();

                b.request(MESSAGE_A, h1, MESSAGE_C);
                b.request(MESSAGE_B, h2, MESSAGE_D);

                Message msg = new Message();
                msg.what = MESSAGE_A;

                b.broadcast(msg);
            }

            public void handleMessage(Message msg) {
                if (msg.what == MESSAGE_C && msg.getTarget() == h1) {
                    success();
                } else {
                    failure();
                }
            }
        };
        tester.doTest(1000);
    }

    @MediumTest
    public void test5() throws Exception {
        /*
        * Two handlers request different messages, with translations, sending
        * only one.  The other one should never get sent.
        */
        HandlerTester tester = new HandlerTester() {
            Handler h1;
            Handler h2;

            public void go() {
                Broadcaster b = new Broadcaster();
                h1 = new H();
                h2 = new H();

                b.request(MESSAGE_A, h1, MESSAGE_C);
                b.request(MESSAGE_B, h2, MESSAGE_D);

                Message msg = new Message();
                msg.what = MESSAGE_B;

                b.broadcast(msg);
            }

            public void handleMessage(Message msg) {
                if (msg.what == MESSAGE_D && msg.getTarget() == h2) {
                    success();
                } else {
                    failure();
                }
            }
        };
        tester.doTest(1000);
    }

    @MediumTest
    public void test6() throws Exception {
        /*
        * Two handlers request same message. Cancel the request for the
        * 2nd handler, make sure the first still works.
        */
        HandlerTester tester = new HandlerTester() {
            Handler h1;
            Handler h2;

            public void go() {
                Broadcaster b = new Broadcaster();
                h1 = new H();
                h2 = new H();

                b.request(MESSAGE_A, h1, MESSAGE_C);
                b.request(MESSAGE_A, h2, MESSAGE_D);
                b.cancelRequest(MESSAGE_A, h2, MESSAGE_D);

                Message msg = new Message();
                msg.what = MESSAGE_A;

                b.broadcast(msg);
            }

            public void handleMessage(Message msg) {
                if (msg.what == MESSAGE_C && msg.getTarget() == h1) {
                    success();
                } else {
                    failure();
                }
            }
        };
        tester.doTest(1000);
    }
}
