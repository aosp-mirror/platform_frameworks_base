/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.policy;

import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that is responsible for queueing deferred key actions which can be triggered at a later
 * time.
 */
class DeferredKeyActionExecutor {
    private static final boolean DEBUG = PhoneWindowManager.DEBUG_INPUT;
    private static final String TAG = "DeferredKeyAction";

    private final SparseArray<TimedActionsBuffer> mBuffers = new SparseArray<>();

    /**
     * Queue a key action which can be triggered at a later time. Note that this method will also
     * delete any outdated actions belong to the same key code.
     *
     * <p>Warning: the queued actions will only be cleaned up lazily when a new gesture downTime is
     * recorded. If no new gesture downTime is recorded and the existing gesture is not executable,
     * the actions will be kept in the buffer indefinitely. This may cause memory leak if the action
     * itself holds references to temporary objects, or if too many actions are queued for the same
     * gesture. The risk scales as you track more key codes. Please use this method with caution and
     * ensure you only queue small amount of actions with limited size.
     *
     * <p>If you need to queue a large amount of actions with large size, there are several
     * potential solutions to relief the memory leak risks:
     *
     * <p>1. Add a timeout (e.g. ANR timeout) based clean-up mechanism.
     *
     * <p>2. Clean-up queued actions when we know they won't be needed. E.g., add a callback when
     * the gesture is handled by apps, and clean up queued actions associated with the handled
     * gesture.
     *
     * @param keyCode the key code which triggers the action.
     * @param downTime the down time of the key gesture. For multi-press actions, this is the down
     *     time of the last press. For long-press or very long-press actions, this is the initial
     *     down time.
     * @param action the action that will be triggered at a later time.
     */
    public void queueKeyAction(int keyCode, long downTime, Runnable action) {
        getActionsBufferWithLazyCleanUp(keyCode, downTime).addAction(action);
    }

    /**
     * Make actions associated with the given key gesture executable. Actions already queued for the
     * given gesture will be executed immediately. Any new actions belonging to this gesture will be
     * executed as soon as they get queued. Note that this method will also delete any outdated
     * actions belong to the same key code.
     *
     * @param keyCode the key code of the gesture.
     * @param downTime the down time of the gesture.
     */
    public void setActionsExecutable(int keyCode, long downTime) {
        getActionsBufferWithLazyCleanUp(keyCode, downTime).setExecutable();
    }

    private TimedActionsBuffer getActionsBufferWithLazyCleanUp(int keyCode, long downTime) {
        TimedActionsBuffer buffer = mBuffers.get(keyCode);
        if (buffer == null || buffer.getDownTime() != downTime) {
            if (DEBUG && buffer != null) {
                Log.d(
                        TAG,
                        "getActionsBufferWithLazyCleanUp: cleaning up gesture actions for key "
                                + KeyEvent.keyCodeToString(keyCode));
            }
            buffer = new TimedActionsBuffer(keyCode, downTime);
            mBuffers.put(keyCode, buffer);
        }
        return buffer;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "Deferred key action executor:");
        if (mBuffers.size() == 0) {
            pw.println(prefix + "  empty");
            return;
        }
        for (int i = 0; i < mBuffers.size(); i++) {
            mBuffers.valueAt(i).dump(prefix, pw);
        }
    }

    /** A buffer holding a gesture down time and its corresponding actions. */
    private static class TimedActionsBuffer {
        private final List<Runnable> mActions = new ArrayList<>();
        private final int mKeyCode;
        private final long mDownTime;
        private boolean mExecutable;

        TimedActionsBuffer(int keyCode, long downTime) {
            mKeyCode = keyCode;
            mDownTime = downTime;
        }

        long getDownTime() {
            return mDownTime;
        }

        void addAction(Runnable action) {
            if (mExecutable) {
                if (DEBUG) {
                    Log.i(
                            TAG,
                            "addAction: execute action for key "
                                    + KeyEvent.keyCodeToString(mKeyCode));
                }
                action.run();
                return;
            }
            mActions.add(action);
        }

        void setExecutable() {
            mExecutable = true;
            if (DEBUG && !mActions.isEmpty()) {
                Log.i(
                        TAG,
                        "setExecutable: execute actions for key "
                                + KeyEvent.keyCodeToString(mKeyCode));
            }
            for (Runnable action : mActions) {
                action.run();
            }
            mActions.clear();
        }

        void dump(String prefix, PrintWriter pw) {
            if (mExecutable) {
                pw.println(prefix + "  " + KeyEvent.keyCodeToString(mKeyCode) + ": executable");
            } else {
                pw.println(
                        prefix
                                + "  "
                                + KeyEvent.keyCodeToString(mKeyCode)
                                + ": "
                                + mActions.size()
                                + " actions queued");
            }
        }
    }
}
