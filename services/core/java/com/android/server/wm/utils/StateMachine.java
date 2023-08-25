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

import android.annotation.IntRange;
import android.annotation.Nullable;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.AnnotationValidations;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Simple hierarchical state machine.
 *
 * The state is represented by an integer value. The root state has a value {@code 0x0}, and top
 * level state has a value in range {@code 0x1} to {@code 0xF}. To indicate a state B is a sub state
 * of a state A, assign an integer state_value(B) = state_value(A) << 4 + (0x0 .. 0xF).
 */
public class StateMachine {
    private static final String TAG = "StateMachine";

    /**
     * Interface for implementing state specific actions.
     */
    public interface Handler {
        /**
         * Called when state machine changes its state to this state.
         */
        default void enter() {}

        /**
         * Called when state machine changes its state from this state to other state.
         */
        default void exit() {}

        /**
         * @param event type of this event.
         * @param param parameter passed to {@link StateMachine#handle(int, Object)}
         * @return {@code true} if the event was handled in this handler, so we don't need to
         *          check the parent state. Otherwise, handle() of the parent state is triggered.
         */
        default boolean handle(int event, @Nullable Object param) {
            return false;
        }
    }

    /**
     * The most recent state requested by transit() call.
     *
     * @note When transit() is called recursively, this might not be same value as mState until
     *       transit() finishes.
     */
    private int mLastRequestedState;

    /**
     * The current state of this state machine.
     */
    private int mState;

    private final IntArray mTmp = new IntArray();
    private final SparseArray<Handler> mStateHandlers = new SparseArray<>();

    /**
     * Actions which need to execute to finish requested transition.
     */
    private final Queue<Command> mCommands = new ArrayDeque<>();

    protected static class Command {
        static final int COMMIT = 1;
        static final int ENTER = 2;
        static final int EXIT = 3;

        final int mType;
        final int mState;

        private Command(int type, @IntRange(from = 0) int state) {
            mType = type;
            AnnotationValidations.validate(IntRange.class, null, state, "from", 0);
            mState = state;
        }

        static Command newCommit(int state) {
            return new Command(COMMIT, state);
        }

        static Command newEnter(int state) {
            return new Command(ENTER, state);
        }

        static Command newExit(int state) {
            return new Command(EXIT, state);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Command{ type: ");
            switch (mType) {
                case COMMIT:
                    sb.append("commit");
                    break;
                case ENTER:
                    sb.append("enter");
                    break;
                case EXIT:
                    sb.append("exit");
                    break;
                default:
                    sb.append("UNKNOWN(");
                    sb.append(mType);
                    sb.append(")");
                    break;
            }
            sb.append(" state: ");
            sb.append(Integer.toHexString(mState));
            sb.append(" }");
            return sb.toString();
        }
    }

    public StateMachine() {
        this(0);
    }

    public StateMachine(@IntRange(from = 0) int initialState) {
        mState = initialState;
        AnnotationValidations.validate(IntRange.class, null, initialState, "from", 0);
        mLastRequestedState = initialState;
    }

    /**
     * @see #mLastRequestedState
     */
    public int getState() {
        return mLastRequestedState;
    }

    protected int getCurrentState() {
        return mState;
    }

    protected Command[] getCommands() {
        final Command[] commands = new Command[mCommands.size()];
        mCommands.toArray(commands);
        return commands;
    }

    /**
     * Add a handler for a specific state.
     *
     * @param state State which the given handler processes.
     * @param handler A handler which runs entry, exit actions and processes events.
     * @return Previous state handler if it's already registered, or {@code null}.
     */
    @Nullable public Handler addStateHandler(int state, @Nullable Handler handler) {
        final Handler handlerOld = mStateHandlers.get(state);
        mStateHandlers.put(state, handler);
        return handlerOld;
    }

    /**
     * Process an event. Search handler for a given event and {@link Handler#handle(int, Object)}.
     * If the handler cannot handle the event, delegate it to a handler for a parent of the given
     * state.
     *
     * @param event Type of an event.
     */
    public void handle(int event, @Nullable Object param) {
        for (int state = mState;; state >>= 4) {
            final Handler h = mStateHandlers.get(state);
            if ((h != null && h.handle(event, param)) || state == 0) {
                return;
            }
        }
    }

    protected void enter(@IntRange(from = 0) int state) {
        AnnotationValidations.validate(IntRange.class, null, state, "from", 0);
        final Handler h = mStateHandlers.get(state);
        if (h != null) {
            h.enter();
        }
    }

    protected void exit(@IntRange(from = 0) int state) {
        AnnotationValidations.validate(IntRange.class, null, state, "from", 0);
        final Handler h = mStateHandlers.get(state);
        if (h != null) {
            h.exit();
        }
    }

    /**
     * @return {@code true} if a given sub state is a descendant of a given super state.
     */
    public static boolean isIn(int subState, int superState) {
        while (subState > superState) {
            subState >>= 4;
        }
        return subState == superState;
    }

    /**
     * Check if the last requested state is a sub state of a given state.
     *
     * @return {@code true} if the last requested state (via {@link #transit(int)}) is a sub state
     *         of a given state.
     */
    public boolean isIn(int state) {
        return isIn(mLastRequestedState, state);
    }

    /**
     * Change state to the requested state.
     *
     * @param newState The new state that the state machine should be changed.
     */
    public void transit(@IntRange(from = 0) int newState) {
        AnnotationValidations.validate(IntRange.class, null, newState, "from", 0);

        // entry and exit action might start another transition, so this transit() function can be
        // called recursively. In order to guarantee entry and exit actions in expected order,
        // we first compute the sequence and push them into a queue, then process them later.
        mCommands.add(Command.newCommit(newState));
        if (mLastRequestedState == newState) {
            mCommands.add(Command.newExit(newState));
            mCommands.add(Command.newEnter(newState));
        } else {
            // mLastRequestedState to least common ancestor
            for (int s = mLastRequestedState; !isIn(newState, s); s >>= 4) {
                mCommands.add(Command.newExit(s));
            }
            // least common ancestor to newState
            mTmp.clear();
            for (int s = newState; !isIn(mLastRequestedState, s); s >>= 4) {
                mTmp.add(s);
            }
            for (int i = mTmp.size() - 1; i >= 0; --i) {
                mCommands.add(Command.newEnter(mTmp.get(i)));
            }
        }
        mLastRequestedState = newState;
        while (!mCommands.isEmpty()) {
            final Command cmd = mCommands.remove();
            switch (cmd.mType) {
                case Command.EXIT:
                    exit(cmd.mState);
                    break;
                case Command.ENTER:
                    enter(cmd.mState);
                    break;
                case Command.COMMIT:
                    mState = cmd.mState;
                    break;
                default:
                    Slog.e(TAG, "Unknown command type: " + cmd.mType);
                    break;
            }
        }
    }
}
