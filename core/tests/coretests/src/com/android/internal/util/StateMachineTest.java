/**
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

package com.android.internal.util;

import android.os.Debug;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.StateMachine.ProcessedMessageInfo;

import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.TestCase;

/**
 * Test for StateMachine.
 */
public class StateMachineTest extends TestCase {
    private static final int TEST_CMD_1 = 1;
    private static final int TEST_CMD_2 = 2;
    private static final int TEST_CMD_3 = 3;
    private static final int TEST_CMD_4 = 4;
    private static final int TEST_CMD_5 = 5;
    private static final int TEST_CMD_6 = 6;

    private static final boolean DBG = true;
    private static final boolean WAIT_FOR_DEBUGGER = false;
    private static final String TAG = "StateMachineTest";

    /**
     * Tests that we can quit the state machine.
     */
    class StateMachineQuitTest extends StateMachine {
        private int mQuitCount = 0;

        StateMachineQuitTest(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (isQuit(message)) {
                    mQuitCount += 1;
                    if (mQuitCount > 2) {
                        // Returning NOT_HANDLED to actually quit
                        return NOT_HANDLED;
                    } else {
                        // Do NOT quit
                        return HANDLED;
                    }
                } else  {
                    // All other message are handled
                    return HANDLED;
                }
            }
        }

        @Override
        protected void quitting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachineQuitTest mThisSm;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachineQuitTest() throws Exception {
        if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachineQuitTest smQuitTest = new StateMachineQuitTest("smQuitTest");
        smQuitTest.start();
        if (smQuitTest.isDbg()) Log.d(TAG, "testStateMachineQuitTest E");

        synchronized (smQuitTest) {
            // Send 6 messages
            for (int i = 1; i <= 6; i++) {
                smQuitTest.sendMessage(i);
            }

            // First two are ignored
            smQuitTest.quit();
            smQuitTest.quit();

            // Now we will quit
            smQuitTest.quit();

            try {
                // wait for the messages to be handled
                smQuitTest.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachineQuitTest: exception while waiting " + e.getMessage());
            }
        }

        assertTrue(smQuitTest.getProcessedMessagesCount() == 9);

        ProcessedMessageInfo pmi;

        // The first two message didn't quit and were handled by mS1
        pmi = smQuitTest.getProcessedMessageInfo(6);
        assertEquals(StateMachine.SM_QUIT_CMD, pmi.getWhat());
        assertEquals(smQuitTest.mS1, pmi.getState());
        assertEquals(smQuitTest.mS1, pmi.getOriginalState());

        pmi = smQuitTest.getProcessedMessageInfo(7);
        assertEquals(StateMachine.SM_QUIT_CMD, pmi.getWhat());
        assertEquals(smQuitTest.mS1, pmi.getState());
        assertEquals(smQuitTest.mS1, pmi.getOriginalState());

        // The last message was never handled so the states are null
        pmi = smQuitTest.getProcessedMessageInfo(8);
        assertEquals(StateMachine.SM_QUIT_CMD, pmi.getWhat());
        assertEquals(null, pmi.getState());
        assertEquals(null, pmi.getOriginalState());

        if (smQuitTest.isDbg()) Log.d(TAG, "testStateMachineQuitTest X");
    }

    /**
     * Test enter/exit can use transitionTo
     */
    class StateMachineEnterExitTransitionToTest extends StateMachine {
        StateMachineEnterExitTransitionToTest(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);
            addState(mS2);
            addState(mS3);
            addState(mS4);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public void enter() {
                // Test that message is HSM_INIT_CMD
                assertEquals(SM_INIT_CMD, getCurrentMessage().what);

                // Test that a transition in enter and the initial state works
                mS1EnterCount += 1;
                transitionTo(mS2);
                Log.d(TAG, "S1.enter");
            }
            @Override
            public void exit() {
                // Test that message is HSM_INIT_CMD
                assertEquals(SM_INIT_CMD, getCurrentMessage().what);

                mS1ExitCount += 1;
                Log.d(TAG, "S1.exit");
            }
        }

        class S2 extends State {
            @Override
            public void enter() {
                // Test that message is HSM_INIT_CMD
                assertEquals(SM_INIT_CMD, getCurrentMessage().what);

                mS2EnterCount += 1;
                Log.d(TAG, "S2.enter");
            }
            @Override
            public void exit() {
                // Test that message is TEST_CMD_1
                assertEquals(TEST_CMD_1, getCurrentMessage().what);

                // Test transition in exit work
                mS2ExitCount += 1;
                transitionTo(mS4);
                Log.d(TAG, "S2.exit");
            }
            @Override
            public boolean processMessage(Message message) {
                // Start a transition to S3 but it will be
                // changed to a transition to S4 in exit
                transitionTo(mS3);
                Log.d(TAG, "S2.processMessage");
                return HANDLED;
            }
        }

        class S3 extends State {
            @Override
            public void enter() {
                // Test that we can do halting in an enter/exit
                transitionToHaltingState();
                mS3EnterCount += 1;
                Log.d(TAG, "S3.enter");
            }
            @Override
            public void exit() {
                mS3ExitCount += 1;
                Log.d(TAG, "S3.exit");
            }
        }


        class S4 extends State {
            @Override
            public void enter() {
                // Test that we can do halting in an enter/exit
                transitionToHaltingState();
                mS4EnterCount += 1;
                Log.d(TAG, "S4.enter");
            }
            @Override
            public void exit() {
                mS4ExitCount += 1;
                Log.d(TAG, "S4.exit");
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachineEnterExitTransitionToTest mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();
        private S3 mS3 = new S3();
        private S4 mS4 = new S4();
        private int mS1EnterCount = 0;
        private int mS1ExitCount = 0;
        private int mS2EnterCount = 0;
        private int mS2ExitCount = 0;
        private int mS3EnterCount = 0;
        private int mS3ExitCount = 0;
        private int mS4EnterCount = 0;
        private int mS4ExitCount = 0;
    }

    @SmallTest
    public void testStateMachineEnterExitTransitionToTest() throws Exception {
        //if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachineEnterExitTransitionToTest smEnterExitTranstionToTest =
            new StateMachineEnterExitTransitionToTest("smEnterExitTranstionToTest");
        smEnterExitTranstionToTest.start();
        if (smEnterExitTranstionToTest.isDbg()) {
            Log.d(TAG, "testStateMachineEnterExitTransitionToTest E");
        }

        synchronized (smEnterExitTranstionToTest) {
            smEnterExitTranstionToTest.sendMessage(TEST_CMD_1);

            try {
                // wait for the messages to be handled
                smEnterExitTranstionToTest.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachineEnterExitTransitionToTest: exception while waiting "
                    + e.getMessage());
            }
        }

        assertTrue(smEnterExitTranstionToTest.getProcessedMessagesCount() == 1);

        ProcessedMessageInfo pmi;

        // Message should be handled by mS2.
        pmi = smEnterExitTranstionToTest.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(smEnterExitTranstionToTest.mS2, pmi.getState());
        assertEquals(smEnterExitTranstionToTest.mS2, pmi.getOriginalState());

        assertEquals(smEnterExitTranstionToTest.mS1EnterCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS1ExitCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS2EnterCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS2ExitCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS3EnterCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS3ExitCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS3EnterCount, 1);
        assertEquals(smEnterExitTranstionToTest.mS3ExitCount, 1);

        if (smEnterExitTranstionToTest.isDbg()) {
            Log.d(TAG, "testStateMachineEnterExitTransitionToTest X");
        }
    }

    /**
     * Tests that ProcessedMessage works as a circular buffer.
     */
    class StateMachine0 extends StateMachine {
        StateMachine0(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);
            setProcessedMessagesSize(3);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_6) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine0 mThisSm;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachine0() throws Exception {
        //if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachine0 sm0 = new StateMachine0("sm0");
        sm0.start();
        if (sm0.isDbg()) Log.d(TAG, "testStateMachine0 E");

        synchronized (sm0) {
            // Send 6 messages
            for (int i = 1; i <= 6; i++) {
                sm0.sendMessage(sm0.obtainMessage(i));
            }

            try {
                // wait for the messages to be handled
                sm0.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine0: exception while waiting " + e.getMessage());
            }
        }

        assertTrue(sm0.getProcessedMessagesCount() == 6);
        assertTrue(sm0.getProcessedMessagesSize() == 3);

        ProcessedMessageInfo pmi;
        pmi = sm0.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_4, pmi.getWhat());
        assertEquals(sm0.mS1, pmi.getState());
        assertEquals(sm0.mS1, pmi.getOriginalState());

        pmi = sm0.getProcessedMessageInfo(1);
        assertEquals(TEST_CMD_5, pmi.getWhat());
        assertEquals(sm0.mS1, pmi.getState());
        assertEquals(sm0.mS1, pmi.getOriginalState());

        pmi = sm0.getProcessedMessageInfo(2);
        assertEquals(TEST_CMD_6, pmi.getWhat());
        assertEquals(sm0.mS1, pmi.getState());
        assertEquals(sm0.mS1, pmi.getOriginalState());

        if (sm0.isDbg()) Log.d(TAG, "testStateMachine0 X");
    }

    /**
     * This tests enter/exit and transitions to the same state.
     * The state machine has one state, it receives two messages
     * in state mS1. With the first message it transitions to
     * itself which causes it to be exited and reentered.
     */
    class StateMachine1 extends StateMachine {
        StateMachine1(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) Log.d(TAG, "StateMachine1: ctor X");
        }

        class S1 extends State {
            @Override
            public void enter() {
                mEnterCount++;
            }
            @Override
            public void exit() {
                mExitCount++;
            }
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_1) {
                    assertEquals(1, mEnterCount);
                    assertEquals(0, mExitCount);
                    transitionTo(mS1);
                } else if (message.what == TEST_CMD_2) {
                    assertEquals(2, mEnterCount);
                    assertEquals(1, mExitCount);
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine1 mThisSm;
        private S1 mS1 = new S1();

        private int mEnterCount;
        private int mExitCount;
    }

    @MediumTest
    public void testStateMachine1() throws Exception {
        StateMachine1 sm1 = new StateMachine1("sm1");
        sm1.start();
        if (sm1.isDbg()) Log.d(TAG, "testStateMachine1 E");

        synchronized (sm1) {
            // Send two messages
            sm1.sendMessage(TEST_CMD_1);
            sm1.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm1.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine1: exception while waiting " + e.getMessage());
            }
        }

        assertEquals(2, sm1.mEnterCount);
        assertEquals(2, sm1.mExitCount);

        assertTrue(sm1.getProcessedMessagesSize() == 2);

        ProcessedMessageInfo pmi;
        pmi = sm1.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(sm1.mS1, pmi.getState());
        assertEquals(sm1.mS1, pmi.getOriginalState());

        pmi = sm1.getProcessedMessageInfo(1);
        assertEquals(TEST_CMD_2, pmi.getWhat());
        assertEquals(sm1.mS1, pmi.getState());
        assertEquals(sm1.mS1, pmi.getOriginalState());

        assertEquals(2, sm1.mEnterCount);
        assertEquals(2, sm1.mExitCount);

        if (sm1.isDbg()) Log.d(TAG, "testStateMachine1 X");
    }

    /**
     * Test deferring messages and states with no parents. The state machine
     * has two states, it receives two messages in state mS1 deferring them
     * until what == TEST_CMD_2 and then transitions to state mS2. State
     * mS2 then receives both of the deferred messages first TEST_CMD_1 and
     * then TEST_CMD_2.
     */
    class StateMachine2 extends StateMachine {
        StateMachine2(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup the hierarchy
            addState(mS1);
            addState(mS2);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) Log.d(TAG, "StateMachine2: ctor X");
        }

        class S1 extends State {
            @Override
            public void enter() {
                mDidEnter = true;
            }
            @Override
            public void exit() {
                mDidExit = true;
            }
            @Override
            public boolean processMessage(Message message) {
                deferMessage(message);
                if (message.what == TEST_CMD_2) {
                    transitionTo(mS2);
                }
                return HANDLED;
            }
        }

        class S2 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine2 mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();

        private boolean mDidEnter = false;
        private boolean mDidExit = false;
    }

    @MediumTest
    public void testStateMachine2() throws Exception {
        StateMachine2 sm2 = new StateMachine2("sm2");
        sm2.start();
        if (sm2.isDbg()) Log.d(TAG, "testStateMachine2 E");

        synchronized (sm2) {
            // Send two messages
            sm2.sendMessage(TEST_CMD_1);
            sm2.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm2.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine2: exception while waiting " + e.getMessage());
            }
        }

        assertTrue(sm2.getProcessedMessagesSize() == 4);

        ProcessedMessageInfo pmi;
        pmi = sm2.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(sm2.mS1, pmi.getState());

        pmi = sm2.getProcessedMessageInfo(1);
        assertEquals(TEST_CMD_2, pmi.getWhat());
        assertEquals(sm2.mS1, pmi.getState());

        pmi = sm2.getProcessedMessageInfo(2);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(sm2.mS2, pmi.getState());

        pmi = sm2.getProcessedMessageInfo(3);
        assertEquals(TEST_CMD_2, pmi.getWhat());
        assertEquals(sm2.mS2, pmi.getState());

        assertTrue(sm2.mDidEnter);
        assertTrue(sm2.mDidExit);

        if (sm2.isDbg()) Log.d(TAG, "testStateMachine2 X");
    }

    /**
     * Test that unhandled messages in a child are handled by the parent.
     * When TEST_CMD_2 is received.
     */
    class StateMachine3 extends StateMachine {
        StateMachine3(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup the simplest hierarchy of two states
            // mParentState and mChildState.
            // (Use indentation to help visualize hierarchy)
            addState(mParentState);
                addState(mChildState, mParentState);

            // Set the initial state will be the child
            setInitialState(mChildState);
            if (DBG) Log.d(TAG, "StateMachine3: ctor X");
        }

        class ParentState extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        class ChildState extends State {
            @Override
            public boolean processMessage(Message message) {
                return NOT_HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine3 mThisSm;
        private ParentState mParentState = new ParentState();
        private ChildState mChildState = new ChildState();
    }

    @MediumTest
    public void testStateMachine3() throws Exception {
        StateMachine3 sm3 = new StateMachine3("sm3");
        sm3.start();
        if (sm3.isDbg()) Log.d(TAG, "testStateMachine3 E");

        synchronized (sm3) {
            // Send two messages
            sm3.sendMessage(TEST_CMD_1);
            sm3.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm3.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine3: exception while waiting " + e.getMessage());
            }
        }

        assertTrue(sm3.getProcessedMessagesSize() == 2);

        ProcessedMessageInfo pmi;
        pmi = sm3.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(sm3.mParentState, pmi.getState());
        assertEquals(sm3.mChildState, pmi.getOriginalState());

        pmi = sm3.getProcessedMessageInfo(1);
        assertEquals(TEST_CMD_2, pmi.getWhat());
        assertEquals(sm3.mParentState, pmi.getState());
        assertEquals(sm3.mChildState, pmi.getOriginalState());

        if (sm3.isDbg()) Log.d(TAG, "testStateMachine3 X");
    }

    /**
     * Test a hierarchy of 3 states a parent and two children
     * with transition from child 1 to child 2 and child 2
     * lets the parent handle the messages.
     */
    class StateMachine4 extends StateMachine {
        StateMachine4(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup a hierarchy of three states
            // mParentState, mChildState1 & mChildState2
            // (Use indentation to help visualize hierarchy)
            addState(mParentState);
                addState(mChildState1, mParentState);
                addState(mChildState2, mParentState);

            // Set the initial state will be child 1
            setInitialState(mChildState1);
            if (DBG) Log.d(TAG, "StateMachine4: ctor X");
        }

        class ParentState extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        class ChildState1 extends State {
            @Override
            public boolean processMessage(Message message) {
                transitionTo(mChildState2);
                return HANDLED;
            }
        }

        class ChildState2 extends State {
            @Override
            public boolean processMessage(Message message) {
                return NOT_HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine4 mThisSm;
        private ParentState mParentState = new ParentState();
        private ChildState1 mChildState1 = new ChildState1();
        private ChildState2 mChildState2 = new ChildState2();
    }

    @MediumTest
    public void testStateMachine4() throws Exception {
        StateMachine4 sm4 = new StateMachine4("sm4");
        sm4.start();
        if (sm4.isDbg()) Log.d(TAG, "testStateMachine4 E");

        synchronized (sm4) {
            // Send two messages
            sm4.sendMessage(TEST_CMD_1);
            sm4.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm4.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine4: exception while waiting " + e.getMessage());
            }
        }


        assertTrue(sm4.getProcessedMessagesSize() == 2);

        ProcessedMessageInfo pmi;
        pmi = sm4.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(sm4.mChildState1, pmi.getState());
        assertEquals(sm4.mChildState1, pmi.getOriginalState());

        pmi = sm4.getProcessedMessageInfo(1);
        assertEquals(TEST_CMD_2, pmi.getWhat());
        assertEquals(sm4.mParentState, pmi.getState());
        assertEquals(sm4.mChildState2, pmi.getOriginalState());

        if (sm4.isDbg()) Log.d(TAG, "testStateMachine4 X");
    }

    /**
     * Test transition from one child to another of a "complex"
     * hierarchy with two parents and multiple children.
     */
    class StateMachine5 extends StateMachine {
        StateMachine5(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup a hierarchy with two parents and some children.
            // (Use indentation to help visualize hierarchy)
            addState(mParentState1);
                addState(mChildState1, mParentState1);
                addState(mChildState2, mParentState1);

            addState(mParentState2);
                addState(mChildState3, mParentState2);
                addState(mChildState4, mParentState2);
                    addState(mChildState5, mChildState4);

            // Set the initial state will be the child
            setInitialState(mChildState1);
            if (DBG) Log.d(TAG, "StateMachine5: ctor X");
        }

        class ParentState1 extends State {
            @Override
            public void enter() {
                mParentState1EnterCount += 1;
            }
            @Override
            public void exit() {
                mParentState1ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                return HANDLED;
            }
        }

        class ChildState1 extends State {
            @Override
            public void enter() {
                mChildState1EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState1ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(0, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(0, mChildState1ExitCount);
                assertEquals(0, mChildState2EnterCount);
                assertEquals(0, mChildState2ExitCount);
                assertEquals(0, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(0, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(0, mChildState4EnterCount);
                assertEquals(0, mChildState4ExitCount);
                assertEquals(0, mChildState5EnterCount);
                assertEquals(0, mChildState5ExitCount);

                transitionTo(mChildState2);
                return HANDLED;
            }
        }

        class ChildState2 extends State {
            @Override
            public void enter() {
                mChildState2EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState2ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(0, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(0, mChildState2ExitCount);
                assertEquals(0, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(0, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(0, mChildState4EnterCount);
                assertEquals(0, mChildState4ExitCount);
                assertEquals(0, mChildState5EnterCount);
                assertEquals(0, mChildState5ExitCount);

                transitionTo(mChildState5);
                return HANDLED;
            }
        }

        class ParentState2 extends State {
            @Override
            public void enter() {
                mParentState2EnterCount += 1;
            }
            @Override
            public void exit() {
                mParentState2ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(2, mParentState2EnterCount);
                assertEquals(1, mParentState2ExitCount);
                assertEquals(1, mChildState3EnterCount);
                assertEquals(1, mChildState3ExitCount);
                assertEquals(2, mChildState4EnterCount);
                assertEquals(2, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(1, mChildState5ExitCount);

                transitionToHaltingState();
                return HANDLED;
            }
        }

        class ChildState3 extends State {
            @Override
            public void enter() {
                mChildState3EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState3ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(1, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(1, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(1, mChildState4EnterCount);
                assertEquals(1, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(1, mChildState5ExitCount);

                transitionTo(mChildState4);
                return HANDLED;
            }
        }

        class ChildState4 extends State {
            @Override
            public void enter() {
                mChildState4EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState4ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(1, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(1, mChildState3EnterCount);
                assertEquals(1, mChildState3ExitCount);
                assertEquals(2, mChildState4EnterCount);
                assertEquals(1, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(1, mChildState5ExitCount);

                transitionTo(mParentState2);
                return HANDLED;
            }
        }

        class ChildState5 extends State {
            @Override
            public void enter() {
                mChildState5EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState5ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(1, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(0, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(1, mChildState4EnterCount);
                assertEquals(0, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(0, mChildState5ExitCount);

                transitionTo(mChildState3);
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine5 mThisSm;
        private ParentState1 mParentState1 = new ParentState1();
        private ChildState1 mChildState1 = new ChildState1();
        private ChildState2 mChildState2 = new ChildState2();
        private ParentState2 mParentState2 = new ParentState2();
        private ChildState3 mChildState3 = new ChildState3();
        private ChildState4 mChildState4 = new ChildState4();
        private ChildState5 mChildState5 = new ChildState5();

        private int mParentState1EnterCount = 0;
        private int mParentState1ExitCount = 0;
        private int mChildState1EnterCount = 0;
        private int mChildState1ExitCount = 0;
        private int mChildState2EnterCount = 0;
        private int mChildState2ExitCount = 0;
        private int mParentState2EnterCount = 0;
        private int mParentState2ExitCount = 0;
        private int mChildState3EnterCount = 0;
        private int mChildState3ExitCount = 0;
        private int mChildState4EnterCount = 0;
        private int mChildState4ExitCount = 0;
        private int mChildState5EnterCount = 0;
        private int mChildState5ExitCount = 0;
    }

    @MediumTest
    public void testStateMachine5() throws Exception {
        StateMachine5 sm5 = new StateMachine5("sm5");
        sm5.start();
        if (sm5.isDbg()) Log.d(TAG, "testStateMachine5 E");

        synchronized (sm5) {
            // Send 6 messages
            sm5.sendMessage(TEST_CMD_1);
            sm5.sendMessage(TEST_CMD_2);
            sm5.sendMessage(TEST_CMD_3);
            sm5.sendMessage(TEST_CMD_4);
            sm5.sendMessage(TEST_CMD_5);
            sm5.sendMessage(TEST_CMD_6);

            try {
                // wait for the messages to be handled
                sm5.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine5: exception while waiting " + e.getMessage());
            }
        }


        assertTrue(sm5.getProcessedMessagesSize() == 6);

        assertEquals(1, sm5.mParentState1EnterCount);
        assertEquals(1, sm5.mParentState1ExitCount);
        assertEquals(1, sm5.mChildState1EnterCount);
        assertEquals(1, sm5.mChildState1ExitCount);
        assertEquals(1, sm5.mChildState2EnterCount);
        assertEquals(1, sm5.mChildState2ExitCount);
        assertEquals(2, sm5.mParentState2EnterCount);
        assertEquals(2, sm5.mParentState2ExitCount);
        assertEquals(1, sm5.mChildState3EnterCount);
        assertEquals(1, sm5.mChildState3ExitCount);
        assertEquals(2, sm5.mChildState4EnterCount);
        assertEquals(2, sm5.mChildState4ExitCount);
        assertEquals(1, sm5.mChildState5EnterCount);
        assertEquals(1, sm5.mChildState5ExitCount);

        ProcessedMessageInfo pmi;
        pmi = sm5.getProcessedMessageInfo(0);
        assertEquals(TEST_CMD_1, pmi.getWhat());
        assertEquals(sm5.mChildState1, pmi.getState());
        assertEquals(sm5.mChildState1, pmi.getOriginalState());

        pmi = sm5.getProcessedMessageInfo(1);
        assertEquals(TEST_CMD_2, pmi.getWhat());
        assertEquals(sm5.mChildState2, pmi.getState());
        assertEquals(sm5.mChildState2, pmi.getOriginalState());

        pmi = sm5.getProcessedMessageInfo(2);
        assertEquals(TEST_CMD_3, pmi.getWhat());
        assertEquals(sm5.mChildState5, pmi.getState());
        assertEquals(sm5.mChildState5, pmi.getOriginalState());

        pmi = sm5.getProcessedMessageInfo(3);
        assertEquals(TEST_CMD_4, pmi.getWhat());
        assertEquals(sm5.mChildState3, pmi.getState());
        assertEquals(sm5.mChildState3, pmi.getOriginalState());

        pmi = sm5.getProcessedMessageInfo(4);
        assertEquals(TEST_CMD_5, pmi.getWhat());
        assertEquals(sm5.mChildState4, pmi.getState());
        assertEquals(sm5.mChildState4, pmi.getOriginalState());

        pmi = sm5.getProcessedMessageInfo(5);
        assertEquals(TEST_CMD_6, pmi.getWhat());
        assertEquals(sm5.mParentState2, pmi.getState());
        assertEquals(sm5.mParentState2, pmi.getOriginalState());

        if (sm5.isDbg()) Log.d(TAG, "testStateMachine5 X");
    }

    /**
     * Test that the initial state enter is invoked immediately
     * after construction and before any other messages arrive and that
     * sendMessageDelayed works.
     */
    class StateMachine6 extends StateMachine {
        StateMachine6(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) Log.d(TAG, "StateMachine6: ctor X");
        }

        class S1 extends State {
            @Override
            public void enter() {
                sendMessage(TEST_CMD_1);
            }
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_1) {
                    mArrivalTimeMsg1 = SystemClock.elapsedRealtime();
                } else if (message.what == TEST_CMD_2) {
                    mArrivalTimeMsg2 = SystemClock.elapsedRealtime();
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine6 mThisSm;
        private S1 mS1 = new S1();

        private long mArrivalTimeMsg1;
        private long mArrivalTimeMsg2;
    }

    @MediumTest
    public void testStateMachine6() throws Exception {
        long sentTimeMsg2;
        final int DELAY_TIME = 250;
        final int DELAY_FUDGE = 20;

        StateMachine6 sm6 = new StateMachine6("sm6");
        sm6.start();
        if (sm6.isDbg()) Log.d(TAG, "testStateMachine6 E");

        synchronized (sm6) {
            // Send a message
            sentTimeMsg2 = SystemClock.elapsedRealtime();
            sm6.sendMessageDelayed(TEST_CMD_2, DELAY_TIME);

            try {
                // wait for the messages to be handled
                sm6.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine6: exception while waiting " + e.getMessage());
            }
        }

        /**
         * TEST_CMD_1 was sent in enter and must always have been processed
         * immediately after construction and hence the arrival time difference
         * should always >= to the DELAY_TIME
         */
        long arrivalTimeDiff = sm6.mArrivalTimeMsg2 - sm6.mArrivalTimeMsg1;
        long expectedDelay = DELAY_TIME - DELAY_FUDGE;
        if (sm6.isDbg()) Log.d(TAG, "testStateMachine6: expect " + arrivalTimeDiff
                                    + " >= " + expectedDelay);
        assertTrue(arrivalTimeDiff >= expectedDelay);

        if (sm6.isDbg()) Log.d(TAG, "testStateMachine6 X");
    }

    /**
     * Test that enter is invoked immediately after exit. This validates
     * that enter can be used to send a watch dog message for its state.
     */
    class StateMachine7 extends StateMachine {
        private final int SM7_DELAY_TIME = 250;

        StateMachine7(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);
            addState(mS2);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) Log.d(TAG, "StateMachine7: ctor X");
        }

        class S1 extends State {
            @Override
            public void exit() {
                sendMessage(TEST_CMD_2);
            }
            @Override
            public boolean processMessage(Message message) {
                transitionTo(mS2);
                return HANDLED;
            }
        }

        class S2 extends State {
            @Override
            public void enter() {
                // Send a delayed message as a watch dog
                sendMessageDelayed(TEST_CMD_3, SM7_DELAY_TIME);
            }
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    mMsgCount += 1;
                    mArrivalTimeMsg2 = SystemClock.elapsedRealtime();
                } else if (message.what == TEST_CMD_3) {
                    mMsgCount += 1;
                    mArrivalTimeMsg3 = SystemClock.elapsedRealtime();
                }

                if (mMsgCount == 2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine7 mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();

        private int mMsgCount = 0;
        private long mArrivalTimeMsg2;
        private long mArrivalTimeMsg3;
    }

    @MediumTest
    public void testStateMachine7() throws Exception {
        long sentTimeMsg2;
        final int SM7_DELAY_FUDGE = 20;

        StateMachine7 sm7 = new StateMachine7("sm7");
        sm7.start();
        if (sm7.isDbg()) Log.d(TAG, "testStateMachine7 E");

        synchronized (sm7) {
            // Send a message
            sentTimeMsg2 = SystemClock.elapsedRealtime();
            sm7.sendMessage(TEST_CMD_1);

            try {
                // wait for the messages to be handled
                sm7.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachine7: exception while waiting " + e.getMessage());
            }
        }

        /**
         * TEST_CMD_3 was sent in S2.enter with a delay and must always have been
         * processed immediately after S1.exit. Since S1.exit sent TEST_CMD_2
         * without a delay the arrival time difference should always >= to SM7_DELAY_TIME.
         */
        long arrivalTimeDiff = sm7.mArrivalTimeMsg3 - sm7.mArrivalTimeMsg2;
        long expectedDelay = sm7.SM7_DELAY_TIME - SM7_DELAY_FUDGE;
        if (sm7.isDbg()) Log.d(TAG, "testStateMachine7: expect " + arrivalTimeDiff
                                    + " >= " + expectedDelay);
        assertTrue(arrivalTimeDiff >= expectedDelay);

        if (sm7.isDbg()) Log.d(TAG, "testStateMachine7 X");
    }

    /**
     * Test unhandledMessage.
     */
    class StateMachineUnhandledMessage extends StateMachine {
        StateMachineUnhandledMessage(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }
        @Override
        public void unhandledMessage(Message message) {
            mUnhandledMessageCount += 1;
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return NOT_HANDLED;
            }
        }

        @Override
        protected void halting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachineUnhandledMessage mThisSm;
        private int mUnhandledMessageCount;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachineUnhandledMessage() throws Exception {

        StateMachineUnhandledMessage sm = new StateMachineUnhandledMessage("sm");
        sm.start();
        if (sm.isDbg()) Log.d(TAG, "testStateMachineUnhandledMessage E");

        synchronized (sm) {
            // Send 2 messages
            for (int i = 1; i <= 2; i++) {
                sm.sendMessage(i);
            }

            try {
                // wait for the messages to be handled
                sm.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachineUnhandledMessage: exception while waiting "
                        + e.getMessage());
            }
        }

        assertTrue(sm.getProcessedMessagesCount() == 2);
        assertEquals(2, sm.mUnhandledMessageCount);

        if (sm.isDbg()) Log.d(TAG, "testStateMachineUnhandledMessage X");
    }

    /**
     * Test state machines sharing the same thread/looper. Multiple instances
     * of the same state machine will be created. They will all share the
     * same thread and thus each can update <code>sharedCounter</code> which
     * will be used to notify testStateMachineSharedThread that the test is
     * complete.
     */
    class StateMachineSharedThread extends StateMachine {
        StateMachineSharedThread(String name, Looper looper, int maxCount) {
            super(name, looper);
            mMaxCount = maxCount;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_4) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void halting() {
            // Update the shared counter, which is OK since all state
            // machines are using the same thread.
            sharedCounter += 1;
            if (sharedCounter == mMaxCount) {
                synchronized (waitObject) {
                    waitObject.notifyAll();
                }
            }
        }

        private int mMaxCount;
        private S1 mS1 = new S1();
    }
    private static int sharedCounter = 0;
    private static Object waitObject = new Object();

    @MediumTest
    public void testStateMachineSharedThread() throws Exception {
        if (DBG) Log.d(TAG, "testStateMachineSharedThread E");

        // Create and start the handler thread
        HandlerThread smThread = new HandlerThread("testStateMachineSharedThread");
        smThread.start();

        // Create the state machines
        StateMachineSharedThread sms[] = new StateMachineSharedThread[10];
        for (int i = 0; i < sms.length; i++) {
            sms[i] = new StateMachineSharedThread("sm", smThread.getLooper(), sms.length);
            sms[i].start();
        }

        synchronized (waitObject) {
            // Send messages to each of the state machines
            for (StateMachineSharedThread sm : sms) {
                for (int i = 1; i <= 4; i++) {
                    sm.sendMessage(i);
                }
            }

            // Wait for the last state machine to notify its done
            try {
                waitObject.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testStateMachineSharedThread: exception while waiting "
                        + e.getMessage());
            }
        }

        for (StateMachineSharedThread sm : sms) {
            assertTrue(sm.getProcessedMessagesCount() == 4);
            for (int i = 0; i < sm.getProcessedMessagesCount(); i++) {
                ProcessedMessageInfo pmi = sm.getProcessedMessageInfo(i);
                assertEquals(i+1, pmi.getWhat());
                assertEquals(sm.mS1, pmi.getState());
                assertEquals(sm.mS1, pmi.getOriginalState());
            }
        }

        if (DBG) Log.d(TAG, "testStateMachineSharedThread X");
    }

    @MediumTest
    public void testHsm1() throws Exception {
        if (DBG) Log.d(TAG, "testHsm1 E");

        Hsm1 sm = Hsm1.makeHsm1();

        // Send messages
        sm.sendMessage(Hsm1.CMD_1);
        sm.sendMessage(Hsm1.CMD_2);

        synchronized (sm) {
            // Wait for the last state machine to notify its done
            try {
                sm.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "testHsm1: exception while waiting " + e.getMessage());
            }
        }

        assertEquals(7, sm.getProcessedMessagesCount());
        ProcessedMessageInfo pmi = sm.getProcessedMessageInfo(0);
        assertEquals(Hsm1.CMD_1, pmi.getWhat());
        assertEquals(sm.mS1, pmi.getState());
        assertEquals(sm.mS1, pmi.getOriginalState());

        pmi = sm.getProcessedMessageInfo(1);
        assertEquals(Hsm1.CMD_2, pmi.getWhat());
        assertEquals(sm.mP1, pmi.getState());
        assertEquals(sm.mS1, pmi.getOriginalState());

        pmi = sm.getProcessedMessageInfo(2);
        assertEquals(Hsm1.CMD_2, pmi.getWhat());
        assertEquals(sm.mS2, pmi.getState());
        assertEquals(sm.mS2, pmi.getOriginalState());

        pmi = sm.getProcessedMessageInfo(3);
        assertEquals(Hsm1.CMD_3, pmi.getWhat());
        assertEquals(sm.mS2, pmi.getState());
        assertEquals(sm.mS2, pmi.getOriginalState());

        pmi = sm.getProcessedMessageInfo(4);
        assertEquals(Hsm1.CMD_3, pmi.getWhat());
        assertEquals(sm.mP2, pmi.getState());
        assertEquals(sm.mP2, pmi.getOriginalState());

        pmi = sm.getProcessedMessageInfo(5);
        assertEquals(Hsm1.CMD_4, pmi.getWhat());
        assertEquals(sm.mP2, pmi.getState());
        assertEquals(sm.mP2, pmi.getOriginalState());

        pmi = sm.getProcessedMessageInfo(6);
        assertEquals(Hsm1.CMD_5, pmi.getWhat());
        assertEquals(sm.mP2, pmi.getState());
        assertEquals(sm.mP2, pmi.getOriginalState());

        if (DBG) Log.d(TAG, "testStateMachineSharedThread X");
    }
}

class Hsm1 extends StateMachine {
    private static final String TAG = "hsm1";

    public static final int CMD_1 = 1;
    public static final int CMD_2 = 2;
    public static final int CMD_3 = 3;
    public static final int CMD_4 = 4;
    public static final int CMD_5 = 5;

    public static Hsm1 makeHsm1() {
        Log.d(TAG, "makeHsm1 E");
        Hsm1 sm = new Hsm1("hsm1");
        sm.start();
        Log.d(TAG, "makeHsm1 X");
        return sm;
    }

    Hsm1(String name) {
        super(name);
        Log.d(TAG, "ctor E");

        // Add states, use indentation to show hierarchy
        addState(mP1);
            addState(mS1, mP1);
            addState(mS2, mP1);
        addState(mP2);

        // Set the initial state
        setInitialState(mS1);
        Log.d(TAG, "ctor X");
    }

    class P1 extends State {
        @Override
        public void enter() {
            Log.d(TAG, "P1.enter");
        }
        @Override
        public void exit() {
            Log.d(TAG, "P1.exit");
        }
        @Override
        public boolean processMessage(Message message) {
            boolean retVal;
            Log.d(TAG, "P1.processMessage what=" + message.what);
            switch(message.what) {
            case CMD_2:
                // CMD_2 will arrive in mS2 before CMD_3
                sendMessage(CMD_3);
                deferMessage(message);
                transitionTo(mS2);
                retVal = true;
                break;
            default:
                // Any message we don't understand in this state invokes unhandledMessage
                retVal = false;
                break;
            }
            return retVal;
        }
    }

    class S1 extends State {
        @Override
        public void enter() {
            Log.d(TAG, "S1.enter");
        }
        @Override
        public void exit() {
            Log.d(TAG, "S1.exit");
        }
        @Override
        public boolean processMessage(Message message) {
            Log.d(TAG, "S1.processMessage what=" + message.what);
            if (message.what == CMD_1) {
                // Transition to ourself to show that enter/exit is called
                transitionTo(mS1);
                return HANDLED;
            } else {
                // Let parent process all other messages
                return NOT_HANDLED;
            }
        }
    }

    class S2 extends State {
        @Override
        public void enter() {
            Log.d(TAG, "S2.enter");
        }
        @Override
        public void exit() {
            Log.d(TAG, "S2.exit");
        }
        @Override
        public boolean processMessage(Message message) {
            boolean retVal;
            Log.d(TAG, "S2.processMessage what=" + message.what);
            switch(message.what) {
            case(CMD_2):
                sendMessage(CMD_4);
                retVal = true;
                break;
            case(CMD_3):
                deferMessage(message);
                transitionTo(mP2);
                retVal = true;
                break;
            default:
                retVal = false;
                break;
            }
            return retVal;
        }
    }

    class P2 extends State {
        @Override
        public void enter() {
            Log.d(TAG, "P2.enter");
            sendMessage(CMD_5);
        }
        @Override
        public void exit() {
            Log.d(TAG, "P2.exit");
        }
        @Override
        public boolean processMessage(Message message) {
            Log.d(TAG, "P2.processMessage what=" + message.what);
            switch(message.what) {
            case(CMD_3):
                break;
            case(CMD_4):
                break;
            case(CMD_5):
                transitionToHaltingState();
                break;
            }
            return HANDLED;
        }
    }

    @Override
    protected void halting() {
        Log.d(TAG, "halting");
        synchronized (this) {
            this.notifyAll();
        }
    }

    P1 mP1 = new P1();
    S1 mS1 = new S1();
    S2 mS2 = new S2();
    P2 mP2 = new P2();
}
