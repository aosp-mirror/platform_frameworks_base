/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm.utils;

import static com.android.server.wm.utils.StateMachine.isIn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:StateMachineTest
 */
@SmallTest
@Presubmit
public class StateMachineTest {
    static class LoggingHandler implements StateMachine.Handler {
        final int mState;
        final StringBuffer mStringBuffer;
        // True if process #handle
        final boolean mHandleSelf;

        LoggingHandler(int state, StringBuffer sb, boolean handleSelf) {
            mHandleSelf = handleSelf;
            mState = state;
            mStringBuffer = sb;
        }

        LoggingHandler(int state, StringBuffer sb) {
            this(state, sb, true /* handleSelf */);
        }

        @Override
        public void enter() {
            mStringBuffer.append('i');
            mStringBuffer.append(Integer.toHexString(mState));
            mStringBuffer.append(';');
        }

        @Override
        public void exit() {
            mStringBuffer.append('o');
            mStringBuffer.append(Integer.toHexString(mState));
            mStringBuffer.append(';');
        }

        @Override
        public boolean handle(int event, Object param) {
            if (mHandleSelf) {
                mStringBuffer.append('h');
                mStringBuffer.append(Integer.toHexString(mState));
                mStringBuffer.append(';');
            }
            return mHandleSelf;
        }
    }

    static class LoggingHandlerTransferInExit extends LoggingHandler {
        final StateMachine mStateMachine;
        final int mStateToTransit;

        LoggingHandlerTransferInExit(int state, StringBuffer sb, StateMachine stateMachine,
                int stateToTransit) {
            super(state, sb);
            mStateMachine = stateMachine;
            mStateToTransit = stateToTransit;
        }

        @Override
        public void exit() {
            super.exit();
            mStateMachine.transit(mStateToTransit);
        }
    }

    @Test
    public void testStateMachineIsIn() {
        assertTrue(isIn(0x112, 0x1));
        assertTrue(isIn(0x112, 0x11));
        assertTrue(isIn(0x112, 0x112));

        assertFalse(isIn(0x1, 0x112));
        assertFalse(isIn(0x12, 0x2));
    }

    @Test
    public void testStateMachineInitialState() {
        StateMachine stateMachine = new StateMachine();
        assertEquals(0, stateMachine.getState());

        stateMachine = new StateMachine(0x23);
        assertEquals(0x23, stateMachine.getState());
    }

    @Test
    public void testStateMachineTransitToChild() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine();
        stateMachine.addStateHandler(0x1, new LoggingHandler(0x1, log));
        stateMachine.addStateHandler(0x12, new LoggingHandler(0x12, log));
        stateMachine.addStateHandler(0x123, new LoggingHandler(0x123, log));
        stateMachine.addStateHandler(0x1233, new LoggingHandler(0x1233, log));

        // 0x0 -> 0x12
        stateMachine.transit(0x12);
        assertEquals("i1;i12;", log.toString());
        assertEquals(0x12, stateMachine.getState());

        // 0x12 -> 0x1233
        log.setLength(0);
        stateMachine.transit(0x1233);
        assertEquals(0x1233, stateMachine.getState());
        assertEquals("i123;i1233;", log.toString());
    }

    @Test
    public void testStateMachineTransitToParent() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x253);
        stateMachine.addStateHandler(0x2, new LoggingHandler(0x2, log));
        stateMachine.addStateHandler(0x25, new LoggingHandler(0x25, log));
        stateMachine.addStateHandler(0x253, new LoggingHandler(0x253, log));

        // 0x253 -> 0x2
        stateMachine.transit(0x2);
        assertEquals(0x2, stateMachine.getState());
        assertEquals("o253;o25;", log.toString());
    }

    @Test
    public void testStateMachineTransitSelf() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x253);
        stateMachine.addStateHandler(0x2, new LoggingHandler(0x2, log));
        stateMachine.addStateHandler(0x25, new LoggingHandler(0x25, log));
        stateMachine.addStateHandler(0x253, new LoggingHandler(0x253, log));

        // 0x253 -> 0x253
        stateMachine.transit(0x253);
        assertEquals(0x253, stateMachine.getState());
        assertEquals("o253;i253;", log.toString());
    }

    @Test
    public void testStateMachineTransitGeneral() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x1351);
        stateMachine.addStateHandler(0x1, new LoggingHandler(0x1, log));
        stateMachine.addStateHandler(0x13, new LoggingHandler(0x13, log));
        stateMachine.addStateHandler(0x132, new LoggingHandler(0x132, log));
        stateMachine.addStateHandler(0x1322, new LoggingHandler(0x1322, log));
        stateMachine.addStateHandler(0x1322, new LoggingHandler(0x1322, log));
        stateMachine.addStateHandler(0x135, new LoggingHandler(0x135, log));
        stateMachine.addStateHandler(0x1351, new LoggingHandler(0x1351, log));

        // 0x1351 -> 0x1322
        // least common ancestor = 0x13
        stateMachine.transit(0x1322);
        assertEquals(0x1322, stateMachine.getState());
        assertEquals("o1351;o135;i132;i1322;", log.toString());
    }

    @Test
    public void testStateMachineTriggerStateAction() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x253);
        stateMachine.addStateHandler(0x2, new LoggingHandler(0x2, log));
        stateMachine.addStateHandler(0x25, new LoggingHandler(0x25, log));
        stateMachine.addStateHandler(0x253, new LoggingHandler(0x253, log));

        // state 0x253 handles the message itself
        stateMachine.handle(0, null);
        assertEquals("h253;", log.toString());
    }

    @Test
    public void testStateMachineTriggerStateActionDelegate() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x253);
        stateMachine.addStateHandler(0x2, new LoggingHandler(0x2, log));
        stateMachine.addStateHandler(0x25, new LoggingHandler(0x25, log));
        stateMachine.addStateHandler(0x253,
                new LoggingHandler(0x253, log, false /* handleSelf */));

        // state 0x253 delegate the message handling to its parent state
        stateMachine.handle(0, null);
        assertEquals("h25;", log.toString());
    }

    @Test
    public void testStateMachineTriggerStateActionDelegateRoot() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x2);
        stateMachine.addStateHandler(0x0, new LoggingHandler(0x0, log));
        stateMachine.addStateHandler(0x2,
                new LoggingHandler(0x2, log, false /* handleSelf */));

        // state 0x2 delegate the message handling to its parent state
        stateMachine.handle(0, null);
        assertEquals("h0;", log.toString());
    }

    @Test
    public void testStateMachineNestedTransition() {
        final StringBuffer log = new StringBuffer();

        StateMachine stateMachine = new StateMachine(0x25);
        stateMachine.addStateHandler(0x1, new LoggingHandler(0x1, log));

        // Force transit to state 0x3 in exit()
        stateMachine.addStateHandler(0x2,
                new LoggingHandlerTransferInExit(0x2, log, stateMachine, 0x3));
        stateMachine.addStateHandler(0x25, new LoggingHandler(0x25, log));
        stateMachine.addStateHandler(0x3, new LoggingHandler(0x3, log));

        stateMachine.transit(0x1);
        // Start transit to 0x1
        //  0x25 -> 0x2 [transit(0x3) requested] -> 0x1
        //  0x1 -> 0x3
        // Immediately set the status to 0x1, no enter/exit
        assertEquals("o25;o2;i1;o1;i3;", log.toString());
    }
}
