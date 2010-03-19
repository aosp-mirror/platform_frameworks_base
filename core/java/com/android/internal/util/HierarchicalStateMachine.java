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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * {@hide}
 *
 * A hierarchical state machine is a state machine which processes messages
 * and can have states arranged hierarchically. A state is a <code>HierarchicalState</code>
 * object and must implement <code>processMessage</code> and optionally <code>enter/exit/getName</code>.
 * The enter/exit methods are equivalent to the construction and destruction
 * in Object Oriented programming and are used to perform initialization and
 * cleanup of the state respectively. The <code>getName</code> method returns the
 * name of the state the default implementation returns the class name it may be
 * desirable to have this return the name of the state instance name instead.
 * In particular if a particular state class has multiple instances.
 *
 * When a state machine is created <code>addState</code> is used to build the
 * hierarchy and <code>setInitialState</code> is used to identify which of these
 * is the initial state. After construction the programmer calls <code>start</code>
 * which initializes the state machine and calls <code>enter</code> for all of the initial
 * state's hierarchy, starting at its eldest parent. For example given the simple
 * state machine below after start is called mP1.enter will have been called and
 * then mS1.enter.
<code>
        mP1
       /   \
      mS2   mS1 ----> initial state
</code>
 * After the state machine is created and started, messages are sent to a state
 * machine using <code>sendMessage</code and the messages are created using
 * <code>obtainMessage</code>. When the state machine receives a message the
 * current state's <code>processMessage</code> is invoked. In the above example
 * mS1.processMessage will be invoked first. The state may use <code>transitionTo</code>
 * to change the current state to a new state
 *
 * Each state in the state machine may have a zero or one parent states and if
 * a child state is unable to handle a message it may have the message processed
 * by its parent by returning false. If a message is never processed <code>unhandledMessage</code>
 * will be invoked to give one last chance for the state machine to process
 * the message.
 *
 * When all processing is completed a state machine may choose to call
 * <code>transitionToHaltingState</code>. When the current <code>processingMessage</code>
 * returns the state machine will transfer to an internal <code>HaltingState</code>
 * and invoke <code>halting</code>. Any message subsequently received by the state
 * machine will cause <code>haltedProcessMessage</code> to be invoked.
 *
 * If it is desirable to completely stop the state machine call <code>quit</code>. This
 * will exit the current state and its parent and then exit from the controlling thread
 * and no further messages will be processed.
 *
 * In addition to <code>processMessage</code> each <code>HierarchicalState</code> has
 * an <code>enter</code> method and <code>exit</exit> method which may be overridden.
 *
 * Since the states are arranged in a hierarchy transitioning to a new state
 * causes current states to be exited and new states to be entered. To determine
 * the list of states to be entered/exited the common parent closest to
 * the current state is found. We then exit from the current state and its
 * parent's up to but not including the common parent state and then enter all
 * of the new states below the common parent down to the destination state.
 * If there is no common parent all states are exited and then the new states
 * are entered.
 *
 * Two other methods that states can use are <code>deferMessage</code> and
 * <code>sendMessageAtFrontOfQueue</code>. The <code>sendMessageAtFrontOfQueue</code> sends
 * a message but places it on the front of the queue rather than the back. The
 * <code>deferMessage</code> causes the message to be saved on a list until a
 * transition is made to a new state. At which time all of the deferred messages
 * will be put on the front of the state machine queue with the oldest message
 * at the front. These will then be processed by the new current state before
 * any other messages that are on the queue or might be added later. Both of
 * these are protected and may only be invoked from within a state machine.
 *
 * To illustrate some of these properties we'll use state machine with 8
 * state hierarchy:
<code>
          mP0
         /   \
        mP1   mS0
       /   \
      mS2   mS1
     /  \    \
    mS3  mS4  mS5  ---> initial state
</code>
 *
 * After starting mS5 the list of active states is mP0, mP1, mS1 and mS5.
 * So the order of calling processMessage when a message is received is mS5,
 * mS1, mP1, mP0 assuming each processMessage  indicates it can't handle this
 * message by returning false.
 *
 * Now assume mS5.processMessage receives a message it can handle, and during
 * the handling determines the machine should changes states. It would call
 * transitionTo(mS4) and return true. Immediately after returning from
 * processMessage the state machine runtime will find the common parent,
 * which is mP1. It will then call mS5.exit, mS1.exit, mS2.enter and then
 * mS4.enter. The new list of active states is mP0, mP1, mS2 and mS4. So
 * when the next message is received mS4.processMessage will be invoked.
 *
 * To assist in describing an HSM a simple grammar has been created which
 * is informally defined here and a formal EBNF description is at the end
 * of the class comment.
 *
 * An HSM starts with the name and includes a set of hierarchical states.
 * A state is preceeded by one or more plus signs (+), to indicate its
 * depth and a hash (#) if its the initial state. Child states follow their
 * parents and have one more plus sign then their parent. Inside a state
 * are a series of messages, the actions they perform and if the processing
 * is complete ends with a period (.). If processing isn't complete and
 * the parent should process the message it ends with a caret (^). The
 * actions include send a message ($MESSAGE), defer a message (%MESSAGE),
 * transition to a new state (>MESSAGE) and an if statement
 * (if ( expression ) { list of actions }.)
 *
 * The Hsm HelloWorld could documented as:
 *
 * HelloWorld {
 *   + # mState1.
 * }
 *
 * and interpreted as HSM HelloWorld:
 *
 * mState1 a root state (single +) and initial state (#) which
 * processes all messages completely, the period (.).
 *
 * The implementation is:
<code>
class HelloWorld extends HierarchicalStateMachine {
    Hsm1(String name) {
        super(name);
        addState(mState1);
        setInitialState(mState1);
    }

    public static HelloWorld makeHelloWorld() {
        HelloWorld hw = new HelloWorld("hw");
        hw.start();
        return hw;
    }

    class State1 extends HierarchicalState {
        @Override public boolean processMessage(Message message) {
            Log.d(TAG, "Hello World");
            return true;
        }
    }
    State1 mState1 = new State1();
}

void testHelloWorld() {
    HelloWorld hw = makeHelloWorld();
    hw.sendMessage(hw.obtainMessage());
}
</code>
 *
 * A more interesting state machine is one of four states
 * with two independent parent states.
<code>
        mP1      mP2
       /   \
      mS2   mS1
</code>
 *
 * documented as:
 *
 * Hsm1 {
 *   + mP1 {
 *       CMD_2 {
 *          $CMD_3
 *          %CMD_2
 *          >mS2
 *       }.
 *     }
 *   ++ # mS1 { CMD_1{ >mS1 }^ }
 *   ++   mS2 {
 *            CMD_2{$CMD_4}.
 *            CMD_3{%CMD_3 ; >mP2}.
 *     }
 *
 *   + mP2 e($CMD_5) {
 *            CMD_3, CMD_4.
 *            CMD_5{>HALT}.
 *     }
 * }
 *
 * and interpreted as HierarchicalStateMachine Hsm1:
 *
 * mP1 a root state.
 *      processes message CMD_2 which sends CMD_3, defers CMD_2, and transitions to mS2
 *
 * mS1 a child of mP1 is the initial state:
 *      processes message CMD_1 which transitions to itself and returns false to let mP1 handle it.
 *
 * mS2 a child of mP1:
 *      processes message CMD_2 which send CMD_4
 *      processes message CMD_3 which defers CMD_3 and transitions to mP2
 *
 * mP2 a root state.
 *      on enter it sends CMD_5
 *      processes message CMD_3
 *      processes message CMD_4
 *      processes message CMD_5 which transitions to halt state
 *
 * The implementation is below and also in HierarchicalStateMachineTest:
<code>
class Hsm1 extends HierarchicalStateMachine {
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

    class P1 extends HierarchicalState {
        @Override public void enter() {
            Log.d(TAG, "mP1.enter");
        }
        @Override public boolean processMessage(Message message) {
            boolean retVal;
            Log.d(TAG, "mP1.processMessage what=" + message.what);
            switch(message.what) {
            case CMD_2:
                // CMD_2 will arrive in mS2 before CMD_3
                sendMessage(obtainMessage(CMD_3));
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
        @Override public void exit() {
            Log.d(TAG, "mP1.exit");
        }
    }

    class S1 extends HierarchicalState {
        @Override public void enter() {
            Log.d(TAG, "mS1.enter");
        }
        @Override public boolean processMessage(Message message) {
            Log.d(TAG, "S1.processMessage what=" + message.what);
            if (message.what == CMD_1) {
                // Transition to ourself to show that enter/exit is called
                transitionTo(mS1);
                return true;
            } else {
                // Let parent process all other messages
                return false;
            }
        }
        @Override public void exit() {
            Log.d(TAG, "mS1.exit");
        }
    }

    class S2 extends HierarchicalState {
        @Override public void enter() {
            Log.d(TAG, "mS2.enter");
        }
        @Override public boolean processMessage(Message message) {
            boolean retVal;
            Log.d(TAG, "mS2.processMessage what=" + message.what);
            switch(message.what) {
            case(CMD_2):
                sendMessage(obtainMessage(CMD_4));
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
        @Override public void exit() {
            Log.d(TAG, "mS2.exit");
        }
    }

    class P2 extends HierarchicalState {
        @Override public void enter() {
            Log.d(TAG, "mP2.enter");
            sendMessage(obtainMessage(CMD_5));
        }
        @Override public boolean processMessage(Message message) {
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
            return true;
        }
        @Override public void exit() {
            Log.d(TAG, "mP2.exit");
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
</code>
 *
 * If this is executed by sending two messages CMD_1 and CMD_2
 * (Note the synchronize is only needed because we use hsm.wait())
 *
 * Hsm1 hsm = makeHsm1();
 * synchronize(hsm) {
 *      hsm.sendMessage(obtainMessage(hsm.CMD_1));
 *      hsm.sendMessage(obtainMessage(hsm.CMD_2));
 *      try {
 *           // wait for the messages to be handled
 *           hsm.wait();
 *      } catch (InterruptedException e) {
 *           Log.e(TAG, "exception while waiting " + e.getMessage());
 *      }
 * }
 *
 *
 * The output is:
 *
 * D/hsm1    ( 1999): makeHsm1 E
 * D/hsm1    ( 1999): ctor E
 * D/hsm1    ( 1999): ctor X
 * D/hsm1    ( 1999): mP1.enter
 * D/hsm1    ( 1999): mS1.enter
 * D/hsm1    ( 1999): makeHsm1 X
 * D/hsm1    ( 1999): mS1.processMessage what=1
 * D/hsm1    ( 1999): mS1.exit
 * D/hsm1    ( 1999): mS1.enter
 * D/hsm1    ( 1999): mS1.processMessage what=2
 * D/hsm1    ( 1999): mP1.processMessage what=2
 * D/hsm1    ( 1999): mS1.exit
 * D/hsm1    ( 1999): mS2.enter
 * D/hsm1    ( 1999): mS2.processMessage what=2
 * D/hsm1    ( 1999): mS2.processMessage what=3
 * D/hsm1    ( 1999): mS2.exit
 * D/hsm1    ( 1999): mP1.exit
 * D/hsm1    ( 1999): mP2.enter
 * D/hsm1    ( 1999): mP2.processMessage what=3
 * D/hsm1    ( 1999): mP2.processMessage what=4
 * D/hsm1    ( 1999): mP2.processMessage what=5
 * D/hsm1    ( 1999): mP2.exit
 * D/hsm1    ( 1999): halting
 *
 * Here is the HSM a BNF grammar, this is a first stab at creating an
 * HSM description language, suggestions corrections or alternatives
 * would be much appreciated.
 *
 * Legend:
 *   {}  ::= zero or more
 *   {}+ ::= one or more
 *   []  ::= zero or one
 *   ()  ::= define a group with "or" semantics.
 *
 * HSM EBNF:
 *   HSM = HSM_NAME "{" { STATE }+ "}" ;
 *   HSM_NAME = alpha_numeric_name ;
 *   STATE = INTRODUCE_STATE [ ENTER | [ ENTER EXIT ] "{" [ MESSAGES ] "}" [ EXIT ] ;
 *   INTRODUCE_STATE = { STATE_DEPTH }+ [ INITIAL_STATE_INDICATOR ] STATE_NAME ;
 *   STATE_DEPTH = "+" ;
 *   INITIAL_STATE_INDICATOR = "#"
 *   ENTER = "e(" SEND_ACTION | TRANSITION_ACTION | HALT_ACTION ")" ;
 *   MESSAGES = { MSG_LIST MESSAGE_ACTIONS } ;
 *   MSG_LIST = { MSG_NAME { "," MSG_NAME } };
 *   EXIT = "x(" SEND_ACTION | TRANSITION_ACTION | HALT_ACTION ")" ;
 *   PROCESS_COMPLETION = PROCESS_IN_PARENT_OR_COMPLETE | PROCESS_COMPLETE ;
 *   SEND_ACTION = "$" MSG_NAME ;
 *   DEFER_ACTION = "%" MSG_NAME ;
 *   TRANSITION_ACTION = ">" STATE_NAME ;
 *   HALT_ACTION = ">" HALT ;
 *   MESSAGE_ACTIONS = { "{" ACTION_LIST "}" } [ PROCESS_COMPLETION ] ;
 *   ACTION_LIST = ACTION { (";" | "\n") ACTION } ;
 *   ACTION = IF_ACTION | SEND_ACTION | DEFER_ACTION | TRANSITION_ACTION | HALT_ACTION ;
 *   IF_ACTION = "if(" boolean_expression ")" "{" ACTION_LIST "}"
 *   PROCESS_IN_PARENT_OR_COMPLETE = "^" ;
 *   PROCESS_COMPLETE = "." ;
 *   STATE_NAME = alpha_numeric_name ;
 *   MSG_NAME = alpha_numeric_name | ALL_OTHER_MESSAGES ;
 *   ALL_OTHER_MESSAGES = "*" ;
 *   EXP = boolean_expression ;
 *
 * Idioms:
 *   * { %* }. ::= All other messages will be deferred.
 */
public class HierarchicalStateMachine {

    private static final String TAG = "HierarchicalStateMachine";
    private String mName;

    public static final int HSM_QUIT_CMD = -1;

    private static class HsmHandler extends Handler {

        /** The debug flag */
        private boolean mDbg = false;

        /** The quit object */
        private static final Object mQuitObj = new Object();

        /** A list of messages that this state machine has processed */
        private ProcessedMessages mProcessedMessages = new ProcessedMessages();

        /** true if construction of the state machine has not been completed */
        private boolean mIsConstructionCompleted;

        /** Stack used to manage the current hierarchy of states */
        private StateInfo mStateStack[];

        /** Top of mStateStack */
        private int mStateStackTopIndex = -1;

        /** A temporary stack used to manage the state stack */
        private StateInfo mTempStateStack[];

        /** The top of the mTempStateStack */
        private int mTempStateStackCount;

        /** State used when state machine is halted */
        private HaltingState mHaltingState = new HaltingState();

        /** State used when state machine is quitting */
        private QuittingState mQuittingState = new QuittingState();

        /** Reference to the HierarchicalStateMachine */
        private HierarchicalStateMachine mHsm;

        /**
         * Information about a state.
         * Used to maintain the hierarchy.
         */
        private class StateInfo {
            /** The state */
            HierarchicalState state;

            /** The parent of this state, null if there is no parent */
            StateInfo parentStateInfo;

            /** True when the state has been entered and on the stack */
            boolean active;

            /**
             * Convert StateInfo to string
             */
            @Override
            public String toString() {
                return "state=" + state.getName() + ",active=" + active
                        + ",parent=" + ((parentStateInfo == null) ?
                                        "null" : parentStateInfo.state.getName());
            }
        }

        /** The map of all of the states in the state machine */
        private HashMap<HierarchicalState, StateInfo> mStateInfo =
            new HashMap<HierarchicalState, StateInfo>();

        /** The initial state that will process the first message */
        private HierarchicalState mInitialState;

        /** The destination state when transitionTo has been invoked */
        private HierarchicalState mDestState;

        /** The list of deferred messages */
        private ArrayList<Message> mDeferredMessages = new ArrayList<Message>();

        /**
         * State entered when transitionToHaltingState is called.
         */
        private class HaltingState extends HierarchicalState {
            @Override
            public boolean processMessage(Message msg) {
                mHsm.haltedProcessMessage(msg);
                return true;
            }
        }

        /**
         * State entered when a valid quit message is handled.
         */
        private class QuittingState extends HierarchicalState {
            @Override
            public boolean processMessage(Message msg) {
                // Ignore
                return false;
            }
        }

        /**
         * Handle messages sent to the state machine by calling
         * the current state's processMessage. It also handles
         * the enter/exit calls and placing any deferred messages
         * back onto the queue when transitioning to a new state.
         */
        @Override
        public final void handleMessage(Message msg) {
            if (mDbg) Log.d(TAG, "handleMessage: E msg.what=" + msg.what);

            /**
             * Check that construction was completed
             */
            if (!mIsConstructionCompleted) {
                Log.e(TAG, "The start method not called, ignore msg: " + msg);
                return;
            }

            /**
             * Process the message abiding by the hierarchical semantics
             * and perform any requested transitions.
             */
            processMsg(msg);
            performTransitions();

            if (mDbg) Log.d(TAG, "handleMessage: X");
        }

        /**
         * Do any transitions
         */
        private void performTransitions() {
            /**
             * If transitionTo has been called, exit and then enter
             * the appropriate states. We loop on this to allow
             * enter and exit methods to use transitionTo.
             */
            HierarchicalState destState = null;
            while (mDestState != null) {
                if (mDbg) Log.d(TAG, "handleMessage: new destination call exit");

                /**
                 * Save mDestState locally and set to null
                 * to know if enter/exit use transitionTo.
                 */
                destState = mDestState;
                mDestState = null;

                /**
                 * Determine the states to exit and enter and return the
                 * common ancestor state of the enter/exit states. Then
                 * invoke the exit methods then the enter methods.
                 */
                StateInfo commonStateInfo = setupTempStateStackWithStatesToEnter(destState);
                invokeExitMethods(commonStateInfo);
                int stateStackEnteringIndex = moveTempStateStackToStateStack();
                invokeEnterMethods(stateStackEnteringIndex);


                /**
                 * Since we have transitioned to a new state we need to have
                 * any deferred messages moved to the front of the message queue
                 * so they will be processed before any other messages in the
                 * message queue.
                 */
                moveDeferredMessageAtFrontOfQueue();
            }

            /**
             * After processing all transitions check and
             * see if the last transition was to quit or halt.
             */
            if (destState != null) {
                if (destState == mQuittingState) {
                    /**
                     * We are quitting so ignore all messages.
                     */
                    mHsm.quitting();
                    if (mHsm.mHsmThread != null) {
                        // If we made the thread then quit looper
                        getLooper().quit();
                    }
                } else if (destState == mHaltingState) {
                    /**
                     * Call halting() if we've transitioned to the halting
                     * state. All subsequent messages will be processed in
                     * in the halting state which invokes haltedProcessMessage(msg);
                     */
                    mHsm.halting();
                }
            }
        }

        /**
         * Complete the construction of the state machine.
         */
        private final void completeConstruction() {
            if (mDbg) Log.d(TAG, "completeConstruction: E");

            /**
             * Determine the maximum depth of the state hierarchy
             * so we can allocate the state stacks.
             */
            int maxDepth = 0;
            for (StateInfo si : mStateInfo.values()) {
                int depth = 0;
                for (StateInfo i = si; i != null; depth++) {
                    i = i.parentStateInfo;
                }
                if (maxDepth < depth) {
                    maxDepth = depth;
                }
            }
            if (mDbg) Log.d(TAG, "completeConstruction: maxDepth=" + maxDepth);

            mStateStack = new StateInfo[maxDepth];
            mTempStateStack = new StateInfo[maxDepth];
            setupInitialStateStack();

            /**
             * Construction is complete call all enter methods
             * starting at the first entry.
             */
            mIsConstructionCompleted = true;
            invokeEnterMethods(0);

            /**
             * Perform any transitions requested by the enter methods
             */
            performTransitions();

            if (mDbg) Log.d(TAG, "completeConstruction: X");
        }

        /**
         * Process the message. If the current state doesn't handle
         * it, call the states parent and so on. If it is never handled then
         * call the state machines unhandledMessage method.
         */
        private final void processMsg(Message msg) {
            StateInfo curStateInfo = mStateStack[mStateStackTopIndex];
            if (mDbg) {
                Log.d(TAG, "processMsg: " + curStateInfo.state.getName());
            }
            while (!curStateInfo.state.processMessage(msg)) {
                /**
                 * Not processed
                 */
                curStateInfo = curStateInfo.parentStateInfo;
                if (curStateInfo == null) {
                    /**
                     * No parents left so it's not handled
                     */
                    mHsm.unhandledMessage(msg);
                    if (isQuit(msg)) {
                        transitionTo(mQuittingState);
                    }
                    break;
                }
                if (mDbg) {
                    Log.d(TAG, "processMsg: " + curStateInfo.state.getName());
                }
            }

            /**
             * Record that we processed the message
             */
            if (curStateInfo != null) {
                HierarchicalState orgState = mStateStack[mStateStackTopIndex].state;
                mProcessedMessages.add(msg, curStateInfo.state, orgState);
            } else {
                mProcessedMessages.add(msg, null, null);
            }
        }

        /**
         * Call the exit method for each state from the top of stack
         * up to the common ancestor state.
         */
        private final void invokeExitMethods(StateInfo commonStateInfo) {
            while ((mStateStackTopIndex >= 0) &&
                    (mStateStack[mStateStackTopIndex] != commonStateInfo)) {
                HierarchicalState curState = mStateStack[mStateStackTopIndex].state;
                if (mDbg) Log.d(TAG, "invokeExitMethods: " + curState.getName());
                curState.exit();
                mStateStack[mStateStackTopIndex].active = false;
                mStateStackTopIndex -= 1;
            }
        }

        /**
         * Invoke the enter method starting at the entering index to top of state stack
         */
        private final void invokeEnterMethods(int stateStackEnteringIndex) {
            for (int i = stateStackEnteringIndex; i <= mStateStackTopIndex; i++) {
                if (mDbg) Log.d(TAG, "invokeEnterMethods: " + mStateStack[i].state.getName());
                mStateStack[i].state.enter();
                mStateStack[i].active = true;
            }
        }

        /**
         * Move the deferred message to the front of the message queue.
         */
        private final void moveDeferredMessageAtFrontOfQueue() {
            /**
             * The oldest messages on the deferred list must be at
             * the front of the queue so start at the back, which
             * as the most resent message and end with the oldest
             * messages at the front of the queue.
             */
            for (int i = mDeferredMessages.size() - 1; i >= 0; i-- ) {
                Message curMsg = mDeferredMessages.get(i);
                if (mDbg) Log.d(TAG, "moveDeferredMessageAtFrontOfQueue; what=" + curMsg.what);
                sendMessageAtFrontOfQueue(curMsg);
            }
            mDeferredMessages.clear();
        }

        /**
         * Move the contents of the temporary stack to the state stack
         * reversing the order of the items on the temporary stack as
         * they are moved.
         *
         * @return index into mStateState where entering needs to start
         */
        private final int moveTempStateStackToStateStack() {
            int startingIndex = mStateStackTopIndex + 1;
            int i = mTempStateStackCount - 1;
            int j = startingIndex;
            while (i >= 0) {
                if (mDbg) Log.d(TAG, "moveTempStackToStateStack: i=" + i + ",j=" + j);
                mStateStack[j] = mTempStateStack[i];
                j += 1;
                i -= 1;
            }

            mStateStackTopIndex = j - 1;
            if (mDbg) {
                Log.d(TAG, "moveTempStackToStateStack: X mStateStackTop="
                      + mStateStackTopIndex + ",startingIndex=" + startingIndex
                      + ",Top=" + mStateStack[mStateStackTopIndex].state.getName());
            }
            return startingIndex;
        }

        /**
         * Setup the mTempStateStack with the states we are going to enter.
         *
         * This is found by searching up the destState's ancestors for a
         * state that is already active i.e. StateInfo.active == true.
         * The destStae and all of its inactive parents will be on the
         * TempStateStack as the list of states to enter.
         *
         * @return StateInfo of the common ancestor for the destState and
         * current state or null if there is no common parent.
         */
        private final StateInfo setupTempStateStackWithStatesToEnter(HierarchicalState destState) {
            /**
             * Search up the parent list of the destination state for an active
             * state. Use a do while() loop as the destState must always be entered
             * even if it is active. This can happen if we are exiting/entering
             * the current state.
             */
            mTempStateStackCount = 0;
            StateInfo curStateInfo = mStateInfo.get(destState);
            do {
                mTempStateStack[mTempStateStackCount++] = curStateInfo;
                curStateInfo = curStateInfo.parentStateInfo;
            } while ((curStateInfo != null) && !curStateInfo.active);

            if (mDbg) {
                Log.d(TAG, "setupTempStateStackWithStatesToEnter: X mTempStateStackCount="
                      + mTempStateStackCount + ",curStateInfo: " + curStateInfo);
            }
            return curStateInfo;
        }

        /**
         * Initialize StateStack to mInitialState.
         */
        private final void setupInitialStateStack() {
            if (mDbg) {
                Log.d(TAG, "setupInitialStateStack: E mInitialState="
                    + mInitialState.getName());
            }

            StateInfo curStateInfo = mStateInfo.get(mInitialState);
            for (mTempStateStackCount = 0; curStateInfo != null; mTempStateStackCount++) {
                mTempStateStack[mTempStateStackCount] = curStateInfo;
                curStateInfo = curStateInfo.parentStateInfo;
            }

            // Empty the StateStack
            mStateStackTopIndex = -1;

            moveTempStateStackToStateStack();
        }

        /**
         * @return current state
         */
        private final HierarchicalState getCurrentState() {
            return mStateStack[mStateStackTopIndex].state;
        }

        /**
         * Add a new state to the state machine. Bottom up addition
         * of states is allowed but the same state may only exist
         * in one hierarchy.
         *
         * @param state the state to add
         * @param parent the parent of state
         * @return stateInfo for this state
         */
        private final StateInfo addState(HierarchicalState state, HierarchicalState parent) {
            if (mDbg) {
                Log.d(TAG, "addStateInternal: E state=" + state.getName()
                        + ",parent=" + ((parent == null) ? "" : parent.getName()));
            }
            StateInfo parentStateInfo = null;
            if (parent != null) {
                parentStateInfo = mStateInfo.get(parent);
                if (parentStateInfo == null) {
                    // Recursively add our parent as it's not been added yet.
                    parentStateInfo = addState(parent, null);
                }
            }
            StateInfo stateInfo = mStateInfo.get(state);
            if (stateInfo == null) {
                stateInfo = new StateInfo();
                mStateInfo.put(state, stateInfo);
            }

            // Validate that we aren't adding the same state in two different hierarchies.
            if ((stateInfo.parentStateInfo != null) &&
                    (stateInfo.parentStateInfo != parentStateInfo)) {
                    throw new RuntimeException("state already added");
            }
            stateInfo.state = state;
            stateInfo.parentStateInfo = parentStateInfo;
            stateInfo.active = false;
            if (mDbg) Log.d(TAG, "addStateInternal: X stateInfo: " + stateInfo);
            return stateInfo;
        }

        /**
         * Constructor
         *
         * @param looper for dispatching messages
         * @param hsm the hierarchical state machine
         */
        private HsmHandler(Looper looper, HierarchicalStateMachine hsm) {
            super(looper);
            mHsm = hsm;

            addState(mHaltingState, null);
            addState(mQuittingState, null);
        }

        /** @see HierarchicalStateMachine#setInitialState(HierarchicalState) */
        private final void setInitialState(HierarchicalState initialState) {
            if (mDbg) Log.d(TAG, "setInitialState: initialState" + initialState.getName());
            mInitialState = initialState;
        }

        /** @see HierarchicalStateMachine#transitionTo(HierarchicalState) */
        private final void transitionTo(HierarchicalState destState) {
            if (mDbg) Log.d(TAG, "StateMachine.transitionTo EX destState" + destState.getName());
            mDestState = destState;
        }

        /** @see HierarchicalStateMachine#deferMessage(Message) */
        private final void deferMessage(Message msg) {
            if (mDbg) Log.d(TAG, "deferMessage: msg=" + msg.what);

            /* Copy the "msg" to "newMsg" as "msg" will be recycled */
            Message newMsg = obtainMessage();
            newMsg.copyFrom(msg);

            mDeferredMessages.add(newMsg);
        }

        /** @see HierarchicalStateMachine#deferMessage(Message) */
        private final void quit() {
            if (mDbg) Log.d(TAG, "quit:");
            sendMessage(obtainMessage(HSM_QUIT_CMD, mQuitObj));
        }

        /** @see HierarchicalStateMachine#isQuit(Message) */
        private final boolean isQuit(Message msg) {
            return (msg.what == HSM_QUIT_CMD) && (msg.obj == mQuitObj);
        }

        /** @see HierarchicalStateMachine#isDbg() */
        private final boolean isDbg() {
            return mDbg;
        }

        /** @see HierarchicalStateMachine#setDbg(boolean) */
        private final void setDbg(boolean dbg) {
            mDbg = dbg;
        }

        /** @see HierarchicalStateMachine#setProcessedMessagesSize(int) */
        private final void setProcessedMessagesSize(int maxSize) {
            mProcessedMessages.setSize(maxSize);
        }

        /** @see HierarchicalStateMachine#getProcessedMessagesSize() */
        private final int getProcessedMessagesSize() {
            return mProcessedMessages.size();
        }

        /** @see HierarchicalStateMachine#getProcessedMessagesCount() */
        private final int getProcessedMessagesCount() {
            return mProcessedMessages.count();
        }

        /** @see HierarchicalStateMachine#getProcessedMessage(int) */
        private final ProcessedMessages.Info getProcessedMessage(int index) {
            return mProcessedMessages.get(index);
        }

    }

    private HsmHandler mHsmHandler;
    private HandlerThread mHsmThread;

    /**
     * Initialize.
     *
     * @param looper for this state machine
     * @param name of the state machine
     */
    private void initStateMachine(String name, Looper looper) {
        mName = name;
        mHsmHandler = new HsmHandler(looper, this);
    }

    /**
     * Constructor creates an HSM with its own thread.
     *
     * @param name of the state machine
     */
    protected HierarchicalStateMachine(String name) {
        mHsmThread = new HandlerThread(name);
        mHsmThread.start();
        Looper looper = mHsmThread.getLooper();

        initStateMachine(name, looper);
    }

    /**
     * Constructor creates an HSMStateMachine using the looper.
     *
     * @param name of the state machine
     */
    protected HierarchicalStateMachine(String name, Looper looper) {
        initStateMachine(name, looper);
    }

    /**
     * Add a new state to the state machine
     * @param state the state to add
     * @param parent the parent of state
     */
    protected final void addState(HierarchicalState state, HierarchicalState parent) {
        mHsmHandler.addState(state, parent);
    }
    /**
     * @return current state
     */
    protected final HierarchicalState getCurrentState() {
        return mHsmHandler.getCurrentState();
    }


    /**
     * Add a new state to the state machine, parent will be null
     * @param state to add
     */
    protected final void addState(HierarchicalState state) {
        mHsmHandler.addState(state, null);
    }

    /**
     * Set the initial state. This must be invoked before
     * and messages are sent to the state machine.
     *
     * @param initialState is the state which will receive the first message.
     */
    protected final void setInitialState(HierarchicalState initialState) {
        mHsmHandler.setInitialState(initialState);
    }

    /**
     * transition to destination state. Upon returning
     * from processMessage the current state's exit will
     * be executed and upon the next message arriving
     * destState.enter will be invoked.
     *
     * @param destState will be the state that receives the next message.
     */
    protected final void transitionTo(HierarchicalState destState) {
        mHsmHandler.transitionTo(destState);
    }

    /**
     * transition to halt state. Upon returning
     * from processMessage we will exit all current
     * states, execute the halting() method and then
     * all subsequent messages haltedProcessMesage
     * will be called.
     */
    protected final void transitionToHaltingState() {
        mHsmHandler.transitionTo(mHsmHandler.mHaltingState);
    }

    /**
     * Defer this message until next state transition.
     * Upon transitioning all deferred messages will be
     * placed on the queue and reprocessed in the original
     * order. (i.e. The next state the oldest messages will
     * be processed first)
     *
     * @param msg is deferred until the next transition.
     */
    protected final void deferMessage(Message msg) {
        mHsmHandler.deferMessage(msg);
    }


    /**
     * Called when message wasn't handled
     *
     * @param msg that couldn't be handled.
     */
    protected void unhandledMessage(Message msg) {
        Log.e(TAG, mName + " - unhandledMessage: msg.what=" + msg.what);
    }

    /**
     * Called for any message that is received after
     * transitionToHalting is called.
     */
    protected void haltedProcessMessage(Message msg) {
    }

    /**
     * Called after the message that called transitionToHalting
     * is called and should be overridden by StateMachine's that
     * call transitionToHalting.
     */
    protected void halting() {
    }

    /**
     * Called after the quitting message was NOT handled and
     * just before the quit actually occurs.
     */
    protected void quitting() {
    }

    /**
     * @return the name
     */
    public final String getName() {
        return mName;
    }

    /**
     * Set size of messages to maintain and clears all current messages.
     *
     * @param maxSize number of messages to maintain at anyone time.
     */
    public final void setProcessedMessagesSize(int maxSize) {
        mHsmHandler.setProcessedMessagesSize(maxSize);
    }

    /**
     * @return number of messages processed
     */
    public final int getProcessedMessagesSize() {
        return mHsmHandler.getProcessedMessagesSize();
    }

    /**
     * @return the total number of messages processed
     */
    public final int getProcessedMessagesCount() {
        return mHsmHandler.getProcessedMessagesCount();
    }

    /**
     * @return a processed message
     */
    public final ProcessedMessages.Info getProcessedMessage(int index) {
        return mHsmHandler.getProcessedMessage(index);
    }

    /**
     * @return Handler
     */
    public final Handler getHandler() {
        return mHsmHandler;
    }

    /**
     * Get a message and set Message.target = this.
     *
     * @return message
     */
    public final Message obtainMessage()
    {
        return Message.obtain(mHsmHandler);
    }

    /**
     * Get a message and set Message.target = this and what
     *
     * @param what is the assigned to Message.what.
     * @return message
     */
    public final Message obtainMessage(int what) {
        return Message.obtain(mHsmHandler, what);
    }

    /**
     * Get a message and set Message.target = this,
     * what and obj.
     *
     * @param what is the assigned to Message.what.
     * @param obj is assigned to Message.obj.
     * @return message
     */
    public final Message obtainMessage(int what, Object obj)
    {
        return Message.obtain(mHsmHandler, what, obj);
    }

    /**
     * Enqueue a message to this state machine.
     */
    public final void sendMessage(int what) {
        mHsmHandler.sendMessage(obtainMessage(what));
    }

    /**
     * Enqueue a message to this state machine.
     */
    public final void sendMessage(int what, Object obj) {
        mHsmHandler.sendMessage(obtainMessage(what,obj));
    }

    /**
     * Enqueue a message to this state machine.
     */
    public final void sendMessage(Message msg) {
        mHsmHandler.sendMessage(msg);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     */
    public final void sendMessageDelayed(int what, long delayMillis) {
        mHsmHandler.sendMessageDelayed(obtainMessage(what), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     */
    public final void sendMessageDelayed(int what, Object obj, long delayMillis) {
        mHsmHandler.sendMessageDelayed(obtainMessage(what, obj), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     */
    public final void sendMessageDelayed(Message msg, long delayMillis) {
        mHsmHandler.sendMessageDelayed(msg, delayMillis);
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of HierarchicalStateMachine.
     */
    protected final void sendMessageAtFrontOfQueue(int what, Object obj) {
        mHsmHandler.sendMessageAtFrontOfQueue(obtainMessage(what, obj));
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of HierarchicalStateMachine.
     */
    protected final void sendMessageAtFrontOfQueue(int what) {
        mHsmHandler.sendMessageAtFrontOfQueue(obtainMessage(what));
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of HierarchicalStateMachine.
     */
    protected final void sendMessageAtFrontOfQueue(Message msg) {
        mHsmHandler.sendMessageAtFrontOfQueue(msg);
    }

    /**
     * Conditionally quit the looper and stop execution.
     *
     * This sends the HSM_QUIT_MSG to the state machine and
     * if not handled by any state's processMessage then the
     * state machine will be stopped and no further messages
     * will be processed.
     */
    public final void quit() {
        mHsmHandler.quit();
    }

    /**
     * @return ture if msg is quit
     */
    protected final boolean isQuit(Message msg) {
        return mHsmHandler.isQuit(msg);
    }

    /**
     * @return if debugging is enabled
     */
    public boolean isDbg() {
        return mHsmHandler.isDbg();
    }

    /**
     * Set debug enable/disabled.
     *
     * @param dbg is true to enable debugging.
     */
    public void setDbg(boolean dbg) {
        mHsmHandler.setDbg(dbg);
    }

    /**
     * Start the state machine.
     */
    public void start() {
        /** Send the complete construction message */
        mHsmHandler.completeConstruction();
    }
}
