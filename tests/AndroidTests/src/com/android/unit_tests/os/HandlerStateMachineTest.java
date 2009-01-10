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
import java.util.Vector;

import android.os.Handler;
import android.os.HandlerState;
import android.os.HandlerStateMachine;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.Message;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import android.util.Log;

public class HandlerStateMachineTest extends TestCase {
    private static final int TEST_WHAT_1 = 1;
    private static final int TEST_WHAT_2 = 2;

    private static final boolean DBG = false;
    private static final String TAG = "HandlerStateMachineTest";

    private boolean mDidEnter = false;
    private boolean mDidExit = false;
    private Vector<Integer> mGotMessagesWhat = new Vector<Integer>();

    /**
     * This test statemachine has two states, it receives
     * two messages in state mS1 deferring them until what == TEST_WHAT_2
     * and then transitions to state mS2. State mS2 should then receive
     * both of the deferred messages first TEST_WHAT_1 and then TEST_WHAT_2.
     * When TEST_WHAT_2 is received it invokes notifyAll so the test can
     * conclude.
     */
    class StateMachine1 extends HandlerStateMachine {
        StateMachine1(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);
            setInitialState(mS1);
        }

        class S1 extends HandlerState {
            @Override public void enter(Message message) {
                mDidEnter = true;
            }

            @Override public void processMessage(Message message) {
                deferMessage(message);
                if (message.what == TEST_WHAT_2) {
                    transitionTo(mS2);
                }
            }

            @Override public void exit(Message message) {
                mDidExit = true;
            }
        }

        class S2 extends HandlerState {
            @Override public void processMessage(Message message) {
                mGotMessagesWhat.add(message.what);
                if (message.what == TEST_WHAT_2) {
                    synchronized (mThisSm) {
                        mThisSm.notifyAll();
                    }
                }
            }
        }

        private StateMachine1 mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();
    }

    @SmallTest
    public void testStateMachine1() throws Exception {
        StateMachine1 sm1 = new StateMachine1("sm1");
        if (sm1.isDbg()) Log.d(TAG, "testStateMachine1 E");

        synchronized (sm1) {
            // Send two messages
            sm1.sendMessage(sm1.obtainMessage(TEST_WHAT_1));
            sm1.sendMessage(sm1.obtainMessage(TEST_WHAT_2));

            try {
                // wait for the messages to be handled
                sm1.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine1: exception while waiting " + e.getMessage());
            }
        }

        assertTrue(mDidEnter);
        assertTrue(mDidExit);
        assertTrue(mGotMessagesWhat.size() == 2);
        assertTrue(mGotMessagesWhat.get(0) == TEST_WHAT_1);
        assertTrue(mGotMessagesWhat.get(1) == TEST_WHAT_2);
        if (sm1.isDbg()) Log.d(TAG, "testStateMachine1 X");
    }
}
