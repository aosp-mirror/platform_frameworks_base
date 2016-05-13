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
import android.text.TextUtils;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

/**
 * {@hide}
 *
 * <p>The state machine defined here is a hierarchical state machine which processes messages
 * and can have states arranged hierarchically.</p>
 *
 * <p>A state is a <code>State</code> object and must implement
 * <code>processMessage</code> and optionally <code>enter/exit/getName</code>.
 * The enter/exit methods are equivalent to the construction and destruction
 * in Object Oriented programming and are used to perform initialization and
 * cleanup of the state respectively. The <code>getName</code> method returns the
 * name of the state; the default implementation returns the class name. It may be
 * desirable to have <code>getName</code> return the the state instance name instead,
 * in particular if a particular state class has multiple instances.</p>
 *
 * <p>When a state machine is created, <code>addState</code> is used to build the
 * hierarchy and <code>setInitialState</code> is used to identify which of these
 * is the initial state. After construction the programmer calls <code>start</code>
 * which initializes and starts the state machine. The first action the StateMachine
 * is to the invoke <code>enter</code> for all of the initial state's hierarchy,
 * starting at its eldest parent. The calls to enter will be done in the context
 * of the StateMachine's Handler, not in the context of the call to start, and they
 * will be invoked before any messages are processed. For example, given the simple
 * state machine below, mP1.enter will be invoked and then mS1.enter. Finally,
 * messages sent to the state machine will be processed by the current state;
 * in our simple state machine below that would initially be mS1.processMessage.</p>
<pre>
        mP1
       /   \
      mS2   mS1 ----&gt; initial state
</pre>
 * <p>After the state machine is created and started, messages are sent to a state
 * machine using <code>sendMessage</code> and the messages are created using
 * <code>obtainMessage</code>. When the state machine receives a message the
 * current state's <code>processMessage</code> is invoked. In the above example
 * mS1.processMessage will be invoked first. The state may use <code>transitionTo</code>
 * to change the current state to a new state.</p>
 *
 * <p>Each state in the state machine may have a zero or one parent states. If
 * a child state is unable to handle a message it may have the message processed
 * by its parent by returning false or NOT_HANDLED. If a message is not handled by
 * a child state or any of its ancestors, <code>unhandledMessage</code> will be invoked
 * to give one last chance for the state machine to process the message.</p>
 *
 * <p>When all processing is completed a state machine may choose to call
 * <code>transitionToHaltingState</code>. When the current <code>processingMessage</code>
 * returns the state machine will transfer to an internal <code>HaltingState</code>
 * and invoke <code>halting</code>. Any message subsequently received by the state
 * machine will cause <code>haltedProcessMessage</code> to be invoked.</p>
 *
 * <p>If it is desirable to completely stop the state machine call <code>quit</code> or
 * <code>quitNow</code>. These will call <code>exit</code> of the current state and its parents,
 * call <code>onQuitting</code> and then exit Thread/Loopers.</p>
 *
 * <p>In addition to <code>processMessage</code> each <code>State</code> has
 * an <code>enter</code> method and <code>exit</code> method which may be overridden.</p>
 *
 * <p>Since the states are arranged in a hierarchy transitioning to a new state
 * causes current states to be exited and new states to be entered. To determine
 * the list of states to be entered/exited the common parent closest to
 * the current state is found. We then exit from the current state and its
 * parent's up to but not including the common parent state and then enter all
 * of the new states below the common parent down to the destination state.
 * If there is no common parent all states are exited and then the new states
 * are entered.</p>
 *
 * <p>Two other methods that states can use are <code>deferMessage</code> and
 * <code>sendMessageAtFrontOfQueue</code>. The <code>sendMessageAtFrontOfQueue</code> sends
 * a message but places it on the front of the queue rather than the back. The
 * <code>deferMessage</code> causes the message to be saved on a list until a
 * transition is made to a new state. At which time all of the deferred messages
 * will be put on the front of the state machine queue with the oldest message
 * at the front. These will then be processed by the new current state before
 * any other messages that are on the queue or might be added later. Both of
 * these are protected and may only be invoked from within a state machine.</p>
 *
 * <p>To illustrate some of these properties we'll use state machine with an 8
 * state hierarchy:</p>
<pre>
          mP0
         /   \
        mP1   mS0
       /   \
      mS2   mS1
     /  \    \
    mS3  mS4  mS5  ---&gt; initial state
</pre>
 * <p>After starting mS5 the list of active states is mP0, mP1, mS1 and mS5.
 * So the order of calling processMessage when a message is received is mS5,
 * mS1, mP1, mP0 assuming each processMessage indicates it can't handle this
 * message by returning false or NOT_HANDLED.</p>
 *
 * <p>Now assume mS5.processMessage receives a message it can handle, and during
 * the handling determines the machine should change states. It could call
 * transitionTo(mS4) and return true or HANDLED. Immediately after returning from
 * processMessage the state machine runtime will find the common parent,
 * which is mP1. It will then call mS5.exit, mS1.exit, mS2.enter and then
 * mS4.enter. The new list of active states is mP0, mP1, mS2 and mS4. So
 * when the next message is received mS4.processMessage will be invoked.</p>
 *
 * <p>Now for some concrete examples, here is the canonical HelloWorld as a state machine.
 * It responds with "Hello World" being printed to the log for every message.</p>
<pre>
class HelloWorld extends StateMachine {
    HelloWorld(String name) {
        super(name);
        addState(mState1);
        setInitialState(mState1);
    }

    public static HelloWorld makeHelloWorld() {
        HelloWorld hw = new HelloWorld("hw");
        hw.start();
        return hw;
    }

    class State1 extends State {
        &#64;Override public boolean processMessage(Message message) {
            log("Hello World");
            return HANDLED;
        }
    }
    State1 mState1 = new State1();
}

void testHelloWorld() {
    HelloWorld hw = makeHelloWorld();
    hw.sendMessage(hw.obtainMessage());
}
</pre>
 * <p>A more interesting state machine is one with four states
 * with two independent parent states.</p>
<pre>
        mP1      mP2
       /   \
      mS2   mS1
</pre>
 * <p>Here is a description of this state machine using pseudo code.</p>
 <pre>
state mP1 {
     enter { log("mP1.enter"); }
     exit { log("mP1.exit");  }
     on msg {
         CMD_2 {
             send(CMD_3);
             defer(msg);
             transitionTo(mS2);
             return HANDLED;
         }
         return NOT_HANDLED;
     }
}

INITIAL
state mS1 parent mP1 {
     enter { log("mS1.enter"); }
     exit  { log("mS1.exit");  }
     on msg {
         CMD_1 {
             transitionTo(mS1);
             return HANDLED;
         }
         return NOT_HANDLED;
     }
}

state mS2 parent mP1 {
     enter { log("mS2.enter"); }
     exit  { log("mS2.exit");  }
     on msg {
         CMD_2 {
             send(CMD_4);
             return HANDLED;
         }
         CMD_3 {
             defer(msg);
             transitionTo(mP2);
             return HANDLED;
         }
         return NOT_HANDLED;
     }
}

state mP2 {
     enter {
         log("mP2.enter");
         send(CMD_5);
     }
     exit { log("mP2.exit"); }
     on msg {
         CMD_3, CMD_4 { return HANDLED; }
         CMD_5 {
             transitionTo(HaltingState);
             return HANDLED;
         }
         return NOT_HANDLED;
     }
}
</pre>
 * <p>The implementation is below and also in StateMachineTest:</p>
<pre>
class Hsm1 extends StateMachine {
    public static final int CMD_1 = 1;
    public static final int CMD_2 = 2;
    public static final int CMD_3 = 3;
    public static final int CMD_4 = 4;
    public static final int CMD_5 = 5;

    public static Hsm1 makeHsm1() {
        log("makeHsm1 E");
        Hsm1 sm = new Hsm1("hsm1");
        sm.start();
        log("makeHsm1 X");
        return sm;
    }

    Hsm1(String name) {
        super(name);
        log("ctor E");

        // Add states, use indentation to show hierarchy
        addState(mP1);
            addState(mS1, mP1);
            addState(mS2, mP1);
        addState(mP2);

        // Set the initial state
        setInitialState(mS1);
        log("ctor X");
    }

    class P1 extends State {
        &#64;Override public void enter() {
            log("mP1.enter");
        }
        &#64;Override public boolean processMessage(Message message) {
            boolean retVal;
            log("mP1.processMessage what=" + message.what);
            switch(message.what) {
            case CMD_2:
                // CMD_2 will arrive in mS2 before CMD_3
                sendMessage(obtainMessage(CMD_3));
                deferMessage(message);
                transitionTo(mS2);
                retVal = HANDLED;
                break;
            default:
                // Any message we don't understand in this state invokes unhandledMessage
                retVal = NOT_HANDLED;
                break;
            }
            return retVal;
        }
        &#64;Override public void exit() {
            log("mP1.exit");
        }
    }

    class S1 extends State {
        &#64;Override public void enter() {
            log("mS1.enter");
        }
        &#64;Override public boolean processMessage(Message message) {
            log("S1.processMessage what=" + message.what);
            if (message.what == CMD_1) {
                // Transition to ourself to show that enter/exit is called
                transitionTo(mS1);
                return HANDLED;
            } else {
                // Let parent process all other messages
                return NOT_HANDLED;
            }
        }
        &#64;Override public void exit() {
            log("mS1.exit");
        }
    }

    class S2 extends State {
        &#64;Override public void enter() {
            log("mS2.enter");
        }
        &#64;Override public boolean processMessage(Message message) {
            boolean retVal;
            log("mS2.processMessage what=" + message.what);
            switch(message.what) {
            case(CMD_2):
                sendMessage(obtainMessage(CMD_4));
                retVal = HANDLED;
                break;
            case(CMD_3):
                deferMessage(message);
                transitionTo(mP2);
                retVal = HANDLED;
                break;
            default:
                retVal = NOT_HANDLED;
                break;
            }
            return retVal;
        }
        &#64;Override public void exit() {
            log("mS2.exit");
        }
    }

    class P2 extends State {
        &#64;Override public void enter() {
            log("mP2.enter");
            sendMessage(obtainMessage(CMD_5));
        }
        &#64;Override public boolean processMessage(Message message) {
            log("P2.processMessage what=" + message.what);
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
        &#64;Override public void exit() {
            log("mP2.exit");
        }
    }

    &#64;Override
    void onHalting() {
        log("halting");
        synchronized (this) {
            this.notifyAll();
        }
    }

    P1 mP1 = new P1();
    S1 mS1 = new S1();
    S2 mS2 = new S2();
    P2 mP2 = new P2();
}
</pre>
 * <p>If this is executed by sending two messages CMD_1 and CMD_2
 * (Note the synchronize is only needed because we use hsm.wait())</p>
<pre>
Hsm1 hsm = makeHsm1();
synchronize(hsm) {
     hsm.sendMessage(obtainMessage(hsm.CMD_1));
     hsm.sendMessage(obtainMessage(hsm.CMD_2));
     try {
          // wait for the messages to be handled
          hsm.wait();
     } catch (InterruptedException e) {
          loge("exception while waiting " + e.getMessage());
     }
}
</pre>
 * <p>The output is:</p>
<pre>
D/hsm1    ( 1999): makeHsm1 E
D/hsm1    ( 1999): ctor E
D/hsm1    ( 1999): ctor X
D/hsm1    ( 1999): mP1.enter
D/hsm1    ( 1999): mS1.enter
D/hsm1    ( 1999): makeHsm1 X
D/hsm1    ( 1999): mS1.processMessage what=1
D/hsm1    ( 1999): mS1.exit
D/hsm1    ( 1999): mS1.enter
D/hsm1    ( 1999): mS1.processMessage what=2
D/hsm1    ( 1999): mP1.processMessage what=2
D/hsm1    ( 1999): mS1.exit
D/hsm1    ( 1999): mS2.enter
D/hsm1    ( 1999): mS2.processMessage what=2
D/hsm1    ( 1999): mS2.processMessage what=3
D/hsm1    ( 1999): mS2.exit
D/hsm1    ( 1999): mP1.exit
D/hsm1    ( 1999): mP2.enter
D/hsm1    ( 1999): mP2.processMessage what=3
D/hsm1    ( 1999): mP2.processMessage what=4
D/hsm1    ( 1999): mP2.processMessage what=5
D/hsm1    ( 1999): mP2.exit
D/hsm1    ( 1999): halting
</pre>
 */
public class StateMachine {
    // Name of the state machine and used as logging tag
    private String mName;

    /** Message.what value when quitting */
    private static final int SM_QUIT_CMD = -1;

    /** Message.what value when initializing */
    private static final int SM_INIT_CMD = -2;

    /**
     * Convenience constant that maybe returned by processMessage
     * to indicate the the message was processed and is not to be
     * processed by parent states
     */
    public static final boolean HANDLED = true;

    /**
     * Convenience constant that maybe returned by processMessage
     * to indicate the the message was NOT processed and is to be
     * processed by parent states
     */
    public static final boolean NOT_HANDLED = false;

    /**
     * StateMachine logging record.
     * {@hide}
     */
    public static class LogRec {
        private StateMachine mSm;
        private long mTime;
        private int mWhat;
        private String mInfo;
        private IState mState;
        private IState mOrgState;
        private IState mDstState;

        /**
         * Constructor
         *
         * @param msg
         * @param state the state which handled the message
         * @param orgState is the first state the received the message but
         * did not processes the message.
         * @param transToState is the state that was transitioned to after the message was
         * processed.
         */
        LogRec(StateMachine sm, Message msg, String info, IState state, IState orgState,
                IState transToState) {
            update(sm, msg, info, state, orgState, transToState);
        }

        /**
         * Update the information in the record.
         * @param state that handled the message
         * @param orgState is the first state the received the message
         * @param dstState is the state that was the transition target when logging
         */
        public void update(StateMachine sm, Message msg, String info, IState state, IState orgState,
                IState dstState) {
            mSm = sm;
            mTime = System.currentTimeMillis();
            mWhat = (msg != null) ? msg.what : 0;
            mInfo = info;
            mState = state;
            mOrgState = orgState;
            mDstState = dstState;
        }

        /**
         * @return time stamp
         */
        public long getTime() {
            return mTime;
        }

        /**
         * @return msg.what
         */
        public long getWhat() {
            return mWhat;
        }

        /**
         * @return the command that was executing
         */
        public String getInfo() {
            return mInfo;
        }

        /**
         * @return the state that handled this message
         */
        public IState getState() {
            return mState;
        }

        /**
         * @return the state destination state if a transition is occurring or null if none.
         */
        public IState getDestState() {
            return mDstState;
        }

        /**
         * @return the original state that received the message.
         */
        public IState getOriginalState() {
            return mOrgState;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("time=");
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(mTime);
            sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            sb.append(" processed=");
            sb.append(mState == null ? "<null>" : mState.getName());
            sb.append(" org=");
            sb.append(mOrgState == null ? "<null>" : mOrgState.getName());
            sb.append(" dest=");
            sb.append(mDstState == null ? "<null>" : mDstState.getName());
            sb.append(" what=");
            String what = mSm != null ? mSm.getWhatToString(mWhat) : "";
            if (TextUtils.isEmpty(what)) {
                sb.append(mWhat);
                sb.append("(0x");
                sb.append(Integer.toHexString(mWhat));
                sb.append(")");
            } else {
                sb.append(what);
            }
            if (!TextUtils.isEmpty(mInfo)) {
                sb.append(" ");
                sb.append(mInfo);
            }
            return sb.toString();
        }
    }

    /**
     * A list of log records including messages recently processed by the state machine.
     *
     * The class maintains a list of log records including messages
     * recently processed. The list is finite and may be set in the
     * constructor or by calling setSize. The public interface also
     * includes size which returns the number of recent records,
     * count which is the number of records processed since the
     * the last setSize, get which returns a record and
     * add which adds a record.
     */
    private static class LogRecords {

        private static final int DEFAULT_SIZE = 20;

        private Vector<LogRec> mLogRecVector = new Vector<LogRec>();
        private int mMaxSize = DEFAULT_SIZE;
        private int mOldestIndex = 0;
        private int mCount = 0;
        private boolean mLogOnlyTransitions = false;

        /**
         * private constructor use add
         */
        private LogRecords() {
        }

        /**
         * Set size of messages to maintain and clears all current records.
         *
         * @param maxSize number of records to maintain at anyone time.
        */
        synchronized void setSize(int maxSize) {
            // TODO: once b/28217358 is fixed, add unit tests  to verify that these variables are
            // cleared after calling this method, and that subsequent calls to get() function as
            // expected.
            mMaxSize = maxSize;
            mOldestIndex = 0;
            mCount = 0;
            mLogRecVector.clear();
        }

        synchronized void setLogOnlyTransitions(boolean enable) {
            mLogOnlyTransitions = enable;
        }

        synchronized boolean logOnlyTransitions() {
            return mLogOnlyTransitions;
        }

        /**
         * @return the number of recent records.
         */
        synchronized int size() {
            return mLogRecVector.size();
        }

        /**
         * @return the total number of records processed since size was set.
         */
        synchronized int count() {
            return mCount;
        }

        /**
         * Clear the list of records.
         */
        synchronized void cleanup() {
            mLogRecVector.clear();
        }

        /**
         * @return the information on a particular record. 0 is the oldest
         * record and size()-1 is the newest record. If the index is to
         * large null is returned.
         */
        synchronized LogRec get(int index) {
            int nextIndex = mOldestIndex + index;
            if (nextIndex >= mMaxSize) {
                nextIndex -= mMaxSize;
            }
            if (nextIndex >= size()) {
                return null;
            } else {
                return mLogRecVector.get(nextIndex);
            }
        }

        /**
         * Add a processed message.
         *
         * @param msg
         * @param messageInfo to be stored
         * @param state that handled the message
         * @param orgState is the first state the received the message but
         * did not processes the message.
         * @param transToState is the state that was transitioned to after the message was
         * processed.
         *
         */
        synchronized void add(StateMachine sm, Message msg, String messageInfo, IState state,
                IState orgState, IState transToState) {
            mCount += 1;
            if (mLogRecVector.size() < mMaxSize) {
                mLogRecVector.add(new LogRec(sm, msg, messageInfo, state, orgState, transToState));
            } else {
                LogRec pmi = mLogRecVector.get(mOldestIndex);
                mOldestIndex += 1;
                if (mOldestIndex >= mMaxSize) {
                    mOldestIndex = 0;
                }
                pmi.update(sm, msg, messageInfo, state, orgState, transToState);
            }
        }
    }

    private static class SmHandler extends Handler {

        /** true if StateMachine has quit */
        private boolean mHasQuit = false;

        /** The debug flag */
        private boolean mDbg = false;

        /** The SmHandler object, identifies that message is internal */
        private static final Object mSmHandlerObj = new Object();

        /** The current message */
        private Message mMsg;

        /** A list of log records including messages this state machine has processed */
        private LogRecords mLogRecords = new LogRecords();

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

        /** Reference to the StateMachine */
        private StateMachine mSm;

        /**
         * Information about a state.
         * Used to maintain the hierarchy.
         */
        private class StateInfo {
            /** The state */
            State state;

            /** The parent of this state, null if there is no parent */
            StateInfo parentStateInfo;

            /** True when the state has been entered and on the stack */
            boolean active;

            /**
             * Convert StateInfo to string
             */
            @Override
            public String toString() {
                return "state=" + state.getName() + ",active=" + active + ",parent="
                        + ((parentStateInfo == null) ? "null" : parentStateInfo.state.getName());
            }
        }

        /** The map of all of the states in the state machine */
        private HashMap<State, StateInfo> mStateInfo = new HashMap<State, StateInfo>();

        /** The initial state that will process the first message */
        private State mInitialState;

        /** The destination state when transitionTo has been invoked */
        private State mDestState;

        /** The list of deferred messages */
        private ArrayList<Message> mDeferredMessages = new ArrayList<Message>();

        /**
         * State entered when transitionToHaltingState is called.
         */
        private class HaltingState extends State {
            @Override
            public boolean processMessage(Message msg) {
                mSm.haltedProcessMessage(msg);
                return true;
            }
        }

        /**
         * State entered when a valid quit message is handled.
         */
        private class QuittingState extends State {
            @Override
            public boolean processMessage(Message msg) {
                return NOT_HANDLED;
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
            if (!mHasQuit) {
                if (mSm != null && msg.what != SM_INIT_CMD && msg.what != SM_QUIT_CMD) {
                    mSm.onPreHandleMessage(msg);
                }

                if (mDbg) mSm.log("handleMessage: E msg.what=" + msg.what);

                /** Save the current message */
                mMsg = msg;

                /** State that processed the message */
                State msgProcessedState = null;
                if (mIsConstructionCompleted) {
                    /** Normal path */
                    msgProcessedState = processMsg(msg);
                } else if (!mIsConstructionCompleted && (mMsg.what == SM_INIT_CMD)
                        && (mMsg.obj == mSmHandlerObj)) {
                    /** Initial one time path. */
                    mIsConstructionCompleted = true;
                    invokeEnterMethods(0);
                } else {
                    throw new RuntimeException("StateMachine.handleMessage: "
                            + "The start method not called, received msg: " + msg);
                }
                performTransitions(msgProcessedState, msg);

                // We need to check if mSm == null here as we could be quitting.
                if (mDbg && mSm != null) mSm.log("handleMessage: X");

                if (mSm != null && msg.what != SM_INIT_CMD && msg.what != SM_QUIT_CMD) {
                    mSm.onPostHandleMessage(msg);
                }
            }
        }

        /**
         * Do any transitions
         * @param msgProcessedState is the state that processed the message
         */
        private void performTransitions(State msgProcessedState, Message msg) {
            /**
             * If transitionTo has been called, exit and then enter
             * the appropriate states. We loop on this to allow
             * enter and exit methods to use transitionTo.
             */
            State orgState = mStateStack[mStateStackTopIndex].state;

            /**
             * Record whether message needs to be logged before we transition and
             * and we won't log special messages SM_INIT_CMD or SM_QUIT_CMD which
             * always set msg.obj to the handler.
             */
            boolean recordLogMsg = mSm.recordLogRec(mMsg) && (msg.obj != mSmHandlerObj);

            if (mLogRecords.logOnlyTransitions()) {
                /** Record only if there is a transition */
                if (mDestState != null) {
                    mLogRecords.add(mSm, mMsg, mSm.getLogRecString(mMsg), msgProcessedState,
                            orgState, mDestState);
                }
            } else if (recordLogMsg) {
                /** Record message */
                mLogRecords.add(mSm, mMsg, mSm.getLogRecString(mMsg), msgProcessedState, orgState,
                        mDestState);
            }

            State destState = mDestState;
            if (destState != null) {
                /**
                 * Process the transitions including transitions in the enter/exit methods
                 */
                while (true) {
                    if (mDbg) mSm.log("handleMessage: new destination call exit/enter");

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

                    if (destState != mDestState) {
                        // A new mDestState so continue looping
                        destState = mDestState;
                    } else {
                        // No change in mDestState so we're done
                        break;
                    }
                }
                mDestState = null;
            }

            /**
             * After processing all transitions check and
             * see if the last transition was to quit or halt.
             */
            if (destState != null) {
                if (destState == mQuittingState) {
                    /**
                     * Call onQuitting to let subclasses cleanup.
                     */
                    mSm.onQuitting();
                    cleanupAfterQuitting();
                } else if (destState == mHaltingState) {
                    /**
                     * Call onHalting() if we've transitioned to the halting
                     * state. All subsequent messages will be processed in
                     * in the halting state which invokes haltedProcessMessage(msg);
                     */
                    mSm.onHalting();
                }
            }
        }

        /**
         * Cleanup all the static variables and the looper after the SM has been quit.
         */
        private final void cleanupAfterQuitting() {
            if (mSm.mSmThread != null) {
                // If we made the thread then quit looper which stops the thread.
                getLooper().quit();
                mSm.mSmThread = null;
            }

            mSm.mSmHandler = null;
            mSm = null;
            mMsg = null;
            mLogRecords.cleanup();
            mStateStack = null;
            mTempStateStack = null;
            mStateInfo.clear();
            mInitialState = null;
            mDestState = null;
            mDeferredMessages.clear();
            mHasQuit = true;
        }

        /**
         * Complete the construction of the state machine.
         */
        private final void completeConstruction() {
            if (mDbg) mSm.log("completeConstruction: E");

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
            if (mDbg) mSm.log("completeConstruction: maxDepth=" + maxDepth);

            mStateStack = new StateInfo[maxDepth];
            mTempStateStack = new StateInfo[maxDepth];
            setupInitialStateStack();

            /** Sending SM_INIT_CMD message to invoke enter methods asynchronously */
            sendMessageAtFrontOfQueue(obtainMessage(SM_INIT_CMD, mSmHandlerObj));

            if (mDbg) mSm.log("completeConstruction: X");
        }

        /**
         * Process the message. If the current state doesn't handle
         * it, call the states parent and so on. If it is never handled then
         * call the state machines unhandledMessage method.
         * @return the state that processed the message
         */
        private final State processMsg(Message msg) {
            StateInfo curStateInfo = mStateStack[mStateStackTopIndex];
            if (mDbg) {
                mSm.log("processMsg: " + curStateInfo.state.getName());
            }

            if (isQuit(msg)) {
                transitionTo(mQuittingState);
            } else {
                while (!curStateInfo.state.processMessage(msg)) {
                    /**
                     * Not processed
                     */
                    curStateInfo = curStateInfo.parentStateInfo;
                    if (curStateInfo == null) {
                        /**
                         * No parents left so it's not handled
                         */
                        mSm.unhandledMessage(msg);
                        break;
                    }
                    if (mDbg) {
                        mSm.log("processMsg: " + curStateInfo.state.getName());
                    }
                }
            }
            return (curStateInfo != null) ? curStateInfo.state : null;
        }

        /**
         * Call the exit method for each state from the top of stack
         * up to the common ancestor state.
         */
        private final void invokeExitMethods(StateInfo commonStateInfo) {
            while ((mStateStackTopIndex >= 0)
                    && (mStateStack[mStateStackTopIndex] != commonStateInfo)) {
                State curState = mStateStack[mStateStackTopIndex].state;
                if (mDbg) mSm.log("invokeExitMethods: " + curState.getName());
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
                if (mDbg) mSm.log("invokeEnterMethods: " + mStateStack[i].state.getName());
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
            for (int i = mDeferredMessages.size() - 1; i >= 0; i--) {
                Message curMsg = mDeferredMessages.get(i);
                if (mDbg) mSm.log("moveDeferredMessageAtFrontOfQueue; what=" + curMsg.what);
                sendMessageAtFrontOfQueue(curMsg);
            }
            mDeferredMessages.clear();
        }

        /**
         * Move the contents of the temporary stack to the state stack
         * reversing the order of the items on the temporary stack as
         * they are moved.
         *
         * @return index into mStateStack where entering needs to start
         */
        private final int moveTempStateStackToStateStack() {
            int startingIndex = mStateStackTopIndex + 1;
            int i = mTempStateStackCount - 1;
            int j = startingIndex;
            while (i >= 0) {
                if (mDbg) mSm.log("moveTempStackToStateStack: i=" + i + ",j=" + j);
                mStateStack[j] = mTempStateStack[i];
                j += 1;
                i -= 1;
            }

            mStateStackTopIndex = j - 1;
            if (mDbg) {
                mSm.log("moveTempStackToStateStack: X mStateStackTop=" + mStateStackTopIndex
                        + ",startingIndex=" + startingIndex + ",Top="
                        + mStateStack[mStateStackTopIndex].state.getName());
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
        private final StateInfo setupTempStateStackWithStatesToEnter(State destState) {
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
                mSm.log("setupTempStateStackWithStatesToEnter: X mTempStateStackCount="
                        + mTempStateStackCount + ",curStateInfo: " + curStateInfo);
            }
            return curStateInfo;
        }

        /**
         * Initialize StateStack to mInitialState.
         */
        private final void setupInitialStateStack() {
            if (mDbg) {
                mSm.log("setupInitialStateStack: E mInitialState=" + mInitialState.getName());
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
         * @return current message
         */
        private final Message getCurrentMessage() {
            return mMsg;
        }

        /**
         * @return current state
         */
        private final IState getCurrentState() {
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
        private final StateInfo addState(State state, State parent) {
            if (mDbg) {
                mSm.log("addStateInternal: E state=" + state.getName() + ",parent="
                        + ((parent == null) ? "" : parent.getName()));
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
            if ((stateInfo.parentStateInfo != null)
                    && (stateInfo.parentStateInfo != parentStateInfo)) {
                throw new RuntimeException("state already added");
            }
            stateInfo.state = state;
            stateInfo.parentStateInfo = parentStateInfo;
            stateInfo.active = false;
            if (mDbg) mSm.log("addStateInternal: X stateInfo: " + stateInfo);
            return stateInfo;
        }

        /**
         * Constructor
         *
         * @param looper for dispatching messages
         * @param sm the hierarchical state machine
         */
        private SmHandler(Looper looper, StateMachine sm) {
            super(looper);
            mSm = sm;

            addState(mHaltingState, null);
            addState(mQuittingState, null);
        }

        /** @see StateMachine#setInitialState(State) */
        private final void setInitialState(State initialState) {
            if (mDbg) mSm.log("setInitialState: initialState=" + initialState.getName());
            mInitialState = initialState;
        }

        /** @see StateMachine#transitionTo(IState) */
        private final void transitionTo(IState destState) {
            mDestState = (State) destState;
            if (mDbg) mSm.log("transitionTo: destState=" + mDestState.getName());
        }

        /** @see StateMachine#deferMessage(Message) */
        private final void deferMessage(Message msg) {
            if (mDbg) mSm.log("deferMessage: msg=" + msg.what);

            /* Copy the "msg" to "newMsg" as "msg" will be recycled */
            Message newMsg = obtainMessage();
            newMsg.copyFrom(msg);

            mDeferredMessages.add(newMsg);
        }

        /** @see StateMachine#quit() */
        private final void quit() {
            if (mDbg) mSm.log("quit:");
            sendMessage(obtainMessage(SM_QUIT_CMD, mSmHandlerObj));
        }

        /** @see StateMachine#quitNow() */
        private final void quitNow() {
            if (mDbg) mSm.log("quitNow:");
            sendMessageAtFrontOfQueue(obtainMessage(SM_QUIT_CMD, mSmHandlerObj));
        }

        /** Validate that the message was sent by quit or quitNow. */
        private final boolean isQuit(Message msg) {
            return (msg.what == SM_QUIT_CMD) && (msg.obj == mSmHandlerObj);
        }

        /** @see StateMachine#isDbg() */
        private final boolean isDbg() {
            return mDbg;
        }

        /** @see StateMachine#setDbg(boolean) */
        private final void setDbg(boolean dbg) {
            mDbg = dbg;
        }

    }

    private SmHandler mSmHandler;
    private HandlerThread mSmThread;

    /**
     * Initialize.
     *
     * @param looper for this state machine
     * @param name of the state machine
     */
    private void initStateMachine(String name, Looper looper) {
        mName = name;
        mSmHandler = new SmHandler(looper, this);
    }

    /**
     * Constructor creates a StateMachine with its own thread.
     *
     * @param name of the state machine
     */
    protected StateMachine(String name) {
        mSmThread = new HandlerThread(name);
        mSmThread.start();
        Looper looper = mSmThread.getLooper();

        initStateMachine(name, looper);
    }

    /**
     * Constructor creates a StateMachine using the looper.
     *
     * @param name of the state machine
     */
    protected StateMachine(String name, Looper looper) {
        initStateMachine(name, looper);
    }

    /**
     * Constructor creates a StateMachine using the handler.
     *
     * @param name of the state machine
     */
    protected StateMachine(String name, Handler handler) {
        initStateMachine(name, handler.getLooper());
    }

    /**
     * Notifies subclass that the StateMachine handler is about to process the Message msg
     * @param msg The message that is being handled
     */
    protected void onPreHandleMessage(Message msg) {
    }

    /**
     * Notifies subclass that the StateMachine handler has finished processing the Message msg and
     * has possibly transitioned to a new state.
     * @param msg The message that is being handled
     */
    protected void onPostHandleMessage(Message msg) {
    }

    /**
     * Add a new state to the state machine
     * @param state the state to add
     * @param parent the parent of state
     */
    protected final void addState(State state, State parent) {
        mSmHandler.addState(state, parent);
    }

    /**
     * Add a new state to the state machine, parent will be null
     * @param state to add
     */
    protected final void addState(State state) {
        mSmHandler.addState(state, null);
    }

    /**
     * Set the initial state. This must be invoked before
     * and messages are sent to the state machine.
     *
     * @param initialState is the state which will receive the first message.
     */
    protected final void setInitialState(State initialState) {
        mSmHandler.setInitialState(initialState);
    }

    /**
     * @return current message
     */
    protected final Message getCurrentMessage() {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return null;
        return smh.getCurrentMessage();
    }

    /**
     * @return current state
     */
    protected final IState getCurrentState() {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return null;
        return smh.getCurrentState();
    }

    /**
     * transition to destination state. Upon returning
     * from processMessage the current state's exit will
     * be executed and upon the next message arriving
     * destState.enter will be invoked.
     *
     * this function can also be called inside the enter function of the
     * previous transition target, but the behavior is undefined when it is
     * called mid-way through a previous transition (for example, calling this
     * in the enter() routine of a intermediate node when the current transition
     * target is one of the nodes descendants).
     *
     * @param destState will be the state that receives the next message.
     */
    protected final void transitionTo(IState destState) {
        mSmHandler.transitionTo(destState);
    }

    /**
     * transition to halt state. Upon returning
     * from processMessage we will exit all current
     * states, execute the onHalting() method and then
     * for all subsequent messages haltedProcessMessage
     * will be called.
     */
    protected final void transitionToHaltingState() {
        mSmHandler.transitionTo(mSmHandler.mHaltingState);
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
        mSmHandler.deferMessage(msg);
    }

    /**
     * Called when message wasn't handled
     *
     * @param msg that couldn't be handled.
     */
    protected void unhandledMessage(Message msg) {
        if (mSmHandler.mDbg) loge(" - unhandledMessage: msg.what=" + msg.what);
    }

    /**
     * Called for any message that is received after
     * transitionToHalting is called.
     */
    protected void haltedProcessMessage(Message msg) {
    }

    /**
     * This will be called once after handling a message that called
     * transitionToHalting. All subsequent messages will invoke
     * {@link StateMachine#haltedProcessMessage(Message)}
     */
    protected void onHalting() {
    }

    /**
     * This will be called once after a quit message that was NOT handled by
     * the derived StateMachine. The StateMachine will stop and any subsequent messages will be
     * ignored. In addition, if this StateMachine created the thread, the thread will
     * be stopped after this method returns.
     */
    protected void onQuitting() {
    }

    /**
     * @return the name
     */
    public final String getName() {
        return mName;
    }

    /**
     * Set number of log records to maintain and clears all current records.
     *
     * @param maxSize number of messages to maintain at anyone time.
     */
    public final void setLogRecSize(int maxSize) {
        mSmHandler.mLogRecords.setSize(maxSize);
    }

    /**
     * Set to log only messages that cause a state transition
     *
     * @param enable {@code true} to enable, {@code false} to disable
     */
    public final void setLogOnlyTransitions(boolean enable) {
        mSmHandler.mLogRecords.setLogOnlyTransitions(enable);
    }

    /**
     * @return number of log records
     */
    public final int getLogRecSize() {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return 0;
        return smh.mLogRecords.size();
    }

    /**
     * @return the total number of records processed
     */
    public final int getLogRecCount() {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return 0;
        return smh.mLogRecords.count();
    }

    /**
     * @return a log record, or null if index is out of range
     */
    public final LogRec getLogRec(int index) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return null;
        return smh.mLogRecords.get(index);
    }

    /**
     * @return a copy of LogRecs as a collection
     */
    public final Collection<LogRec> copyLogRecs() {
        Vector<LogRec> vlr = new Vector<LogRec>();
        SmHandler smh = mSmHandler;
        if (smh != null) {
            for (LogRec lr : smh.mLogRecords.mLogRecVector) {
                vlr.add(lr);
            }
        }
        return vlr;
    }

    /**
     * Add the string to LogRecords.
     *
     * @param string
     */
    protected void addLogRec(String string) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;
        smh.mLogRecords.add(this, smh.getCurrentMessage(), string, smh.getCurrentState(),
                smh.mStateStack[smh.mStateStackTopIndex].state, smh.mDestState);
    }

    /**
     * @return true if msg should be saved in the log, default is true.
     */
    protected boolean recordLogRec(Message msg) {
        return true;
    }

    /**
     * Return a string to be logged by LogRec, default
     * is an empty string. Override if additional information is desired.
     *
     * @param msg that was processed
     * @return information to be logged as a String
     */
    protected String getLogRecString(Message msg) {
        return "";
    }

    /**
     * @return the string for msg.what
     */
    protected String getWhatToString(int what) {
        return null;
    }

    /**
     * @return Handler, maybe null if state machine has quit.
     */
    public final Handler getHandler() {
        return mSmHandler;
    }

    /**
     * Get a message and set Message.target state machine handler.
     *
     * Note: The handler can be null if the state machine has quit,
     * which means target will be null and may cause a AndroidRuntimeException
     * in MessageQueue#enqueMessage if sent directly or if sent using
     * StateMachine#sendMessage the message will just be ignored.
     *
     * @return  A Message object from the global pool
     */
    public final Message obtainMessage() {
        return Message.obtain(mSmHandler);
    }

    /**
     * Get a message and set Message.target state machine handler, what.
     *
     * Note: The handler can be null if the state machine has quit,
     * which means target will be null and may cause a AndroidRuntimeException
     * in MessageQueue#enqueMessage if sent directly or if sent using
     * StateMachine#sendMessage the message will just be ignored.
     *
     * @param what is the assigned to Message.what.
     * @return  A Message object from the global pool
     */
    public final Message obtainMessage(int what) {
        return Message.obtain(mSmHandler, what);
    }

    /**
     * Get a message and set Message.target state machine handler,
     * what and obj.
     *
     * Note: The handler can be null if the state machine has quit,
     * which means target will be null and may cause a AndroidRuntimeException
     * in MessageQueue#enqueMessage if sent directly or if sent using
     * StateMachine#sendMessage the message will just be ignored.
     *
     * @param what is the assigned to Message.what.
     * @param obj is assigned to Message.obj.
     * @return  A Message object from the global pool
     */
    public final Message obtainMessage(int what, Object obj) {
        return Message.obtain(mSmHandler, what, obj);
    }

    /**
     * Get a message and set Message.target state machine handler,
     * what, arg1 and arg2
     *
     * Note: The handler can be null if the state machine has quit,
     * which means target will be null and may cause a AndroidRuntimeException
     * in MessageQueue#enqueMessage if sent directly or if sent using
     * StateMachine#sendMessage the message will just be ignored.
     *
     * @param what  is assigned to Message.what
     * @param arg1  is assigned to Message.arg1
     * @return  A Message object from the global pool
     */
    public final Message obtainMessage(int what, int arg1) {
        // use this obtain so we don't match the obtain(h, what, Object) method
        return Message.obtain(mSmHandler, what, arg1, 0);
    }

    /**
     * Get a message and set Message.target state machine handler,
     * what, arg1 and arg2
     *
     * Note: The handler can be null if the state machine has quit,
     * which means target will be null and may cause a AndroidRuntimeException
     * in MessageQueue#enqueMessage if sent directly or if sent using
     * StateMachine#sendMessage the message will just be ignored.
     *
     * @param what  is assigned to Message.what
     * @param arg1  is assigned to Message.arg1
     * @param arg2  is assigned to Message.arg2
     * @return  A Message object from the global pool
     */
    public final Message obtainMessage(int what, int arg1, int arg2) {
        return Message.obtain(mSmHandler, what, arg1, arg2);
    }

    /**
     * Get a message and set Message.target state machine handler,
     * what, arg1, arg2 and obj
     *
     * Note: The handler can be null if the state machine has quit,
     * which means target will be null and may cause a AndroidRuntimeException
     * in MessageQueue#enqueMessage if sent directly or if sent using
     * StateMachine#sendMessage the message will just be ignored.
     *
     * @param what  is assigned to Message.what
     * @param arg1  is assigned to Message.arg1
     * @param arg2  is assigned to Message.arg2
     * @param obj is assigned to Message.obj
     * @return  A Message object from the global pool
     */
    public final Message obtainMessage(int what, int arg1, int arg2, Object obj) {
        return Message.obtain(mSmHandler, what, arg1, arg2, obj);
    }

    /**
     * Enqueue a message to this state machine.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessage(int what) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessage(obtainMessage(what));
    }

    /**
     * Enqueue a message to this state machine.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessage(int what, Object obj) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessage(obtainMessage(what, obj));
    }

    /**
     * Enqueue a message to this state machine.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessage(int what, int arg1) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessage(obtainMessage(what, arg1));
    }

    /**
     * Enqueue a message to this state machine.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessage(int what, int arg1, int arg2) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessage(obtainMessage(what, arg1, arg2));
    }

    /**
     * Enqueue a message to this state machine.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessage(int what, int arg1, int arg2, Object obj) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessage(obtainMessage(what, arg1, arg2, obj));
    }

    /**
     * Enqueue a message to this state machine.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessage(Message msg) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessage(msg);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessageDelayed(int what, long delayMillis) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageDelayed(obtainMessage(what), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessageDelayed(int what, Object obj, long delayMillis) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageDelayed(obtainMessage(what, obj), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessageDelayed(int what, int arg1, long delayMillis) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageDelayed(obtainMessage(what, arg1), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessageDelayed(int what, int arg1, int arg2, long delayMillis) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageDelayed(obtainMessage(what, arg1, arg2), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessageDelayed(int what, int arg1, int arg2, Object obj,
            long delayMillis) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageDelayed(obtainMessage(what, arg1, arg2, obj), delayMillis);
    }

    /**
     * Enqueue a message to this state machine after a delay.
     *
     * Message is ignored if state machine has quit.
     */
    public void sendMessageDelayed(Message msg, long delayMillis) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageDelayed(msg, delayMillis);
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of StateMachine.
     *
     * Message is ignored if state machine has quit.
     */
    protected final void sendMessageAtFrontOfQueue(int what) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageAtFrontOfQueue(obtainMessage(what));
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of StateMachine.
     *
     * Message is ignored if state machine has quit.
     */
    protected final void sendMessageAtFrontOfQueue(int what, Object obj) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageAtFrontOfQueue(obtainMessage(what, obj));
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of StateMachine.
     *
     * Message is ignored if state machine has quit.
     */
    protected final void sendMessageAtFrontOfQueue(int what, int arg1) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageAtFrontOfQueue(obtainMessage(what, arg1));
    }


    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of StateMachine.
     *
     * Message is ignored if state machine has quit.
     */
    protected final void sendMessageAtFrontOfQueue(int what, int arg1, int arg2) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageAtFrontOfQueue(obtainMessage(what, arg1, arg2));
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of StateMachine.
     *
     * Message is ignored if state machine has quit.
     */
    protected final void sendMessageAtFrontOfQueue(int what, int arg1, int arg2, Object obj) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageAtFrontOfQueue(obtainMessage(what, arg1, arg2, obj));
    }

    /**
     * Enqueue a message to the front of the queue for this state machine.
     * Protected, may only be called by instances of StateMachine.
     *
     * Message is ignored if state machine has quit.
     */
    protected final void sendMessageAtFrontOfQueue(Message msg) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.sendMessageAtFrontOfQueue(msg);
    }

    /**
     * Removes a message from the message queue.
     * Protected, may only be called by instances of StateMachine.
     */
    protected final void removeMessages(int what) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.removeMessages(what);
    }

    /**
     * Removes a message from the deferred messages queue.
     */
    protected final void removeDeferredMessages(int what) {
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        Iterator<Message> iterator = smh.mDeferredMessages.iterator();
        while (iterator.hasNext()) {
            Message msg = iterator.next();
            if (msg.what == what) iterator.remove();
        }
    }

    /**
     * Check if there are any pending messages with code 'what' in deferred messages queue.
     */
    protected final boolean hasDeferredMessages(int what) {
        SmHandler smh = mSmHandler;
        if (smh == null) return false;

        Iterator<Message> iterator = smh.mDeferredMessages.iterator();
        while (iterator.hasNext()) {
            Message msg = iterator.next();
            if (msg.what == what) return true;
        }

        return false;
    }

    /**
     * Check if there are any pending posts of messages with code 'what' in
     * the message queue. This does NOT check messages in deferred message queue.
     */
    protected final boolean hasMessages(int what) {
        SmHandler smh = mSmHandler;
        if (smh == null) return false;

        return smh.hasMessages(what);
    }

    /**
     * Validate that the message was sent by
     * {@link StateMachine#quit} or {@link StateMachine#quitNow}.
     * */
    protected final boolean isQuit(Message msg) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return msg.what == SM_QUIT_CMD;

        return smh.isQuit(msg);
    }

    /**
     * Quit the state machine after all currently queued up messages are processed.
     */
    protected final void quit() {
        // mSmHandler can be null if the state machine is already stopped.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.quit();
    }

    /**
     * Quit the state machine immediately all currently queued messages will be discarded.
     */
    protected final void quitNow() {
        // mSmHandler can be null if the state machine is already stopped.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.quitNow();
    }

    /**
     * @return if debugging is enabled
     */
    public boolean isDbg() {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return false;

        return smh.isDbg();
    }

    /**
     * Set debug enable/disabled.
     *
     * @param dbg is true to enable debugging.
     */
    public void setDbg(boolean dbg) {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        smh.setDbg(dbg);
    }

    /**
     * Start the state machine.
     */
    public void start() {
        // mSmHandler can be null if the state machine has quit.
        SmHandler smh = mSmHandler;
        if (smh == null) return;

        /** Send the complete construction message */
        smh.completeConstruction();
    }

    /**
     * Dump the current state.
     *
     * @param fd
     * @param pw
     * @param args
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // Cannot just invoke pw.println(this.toString()) because if the
        // resulting string is to long it won't be displayed.
        pw.println(getName() + ":");
        pw.println(" total records=" + getLogRecCount());
        for (int i = 0; i < getLogRecSize(); i++) {
            pw.println(" rec[" + i + "]: " + getLogRec(i).toString());
            pw.flush();
        }
        pw.println("curState=" + getCurrentState().getName());
    }

    @Override
    public String toString() {
        StringWriter sr = new StringWriter();
        PrintWriter pr = new PrintWriter(sr);
        dump(null, pr, null);
        pr.flush();
        pr.close();
        return sr.toString();
    }

    /**
     * Log with debug and add to the LogRecords.
     *
     * @param s is string log
     */
    protected void logAndAddLogRec(String s) {
        addLogRec(s);
        log(s);
    }

    /**
     * Log with debug
     *
     * @param s is string log
     */
    protected void log(String s) {
        Log.d(mName, s);
    }

    /**
     * Log with debug attribute
     *
     * @param s is string log
     */
    protected void logd(String s) {
        Log.d(mName, s);
    }

    /**
     * Log with verbose attribute
     *
     * @param s is string log
     */
    protected void logv(String s) {
        Log.v(mName, s);
    }

    /**
     * Log with info attribute
     *
     * @param s is string log
     */
    protected void logi(String s) {
        Log.i(mName, s);
    }

    /**
     * Log with warning attribute
     *
     * @param s is string log
     */
    protected void logw(String s) {
        Log.w(mName, s);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    protected void loge(String s) {
        Log.e(mName, s);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     * @param e is a Throwable which logs additional information.
     */
    protected void loge(String s, Throwable e) {
        Log.e(mName, s, e);
    }
}
