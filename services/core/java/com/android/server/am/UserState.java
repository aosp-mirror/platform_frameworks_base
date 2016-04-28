/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.IStopUserCallback;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.util.ProgressReporter;

import java.io.PrintWriter;
import java.util.ArrayList;

public final class UserState {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "UserState" : TAG_AM;

    // User is first coming up.
    public final static int STATE_BOOTING = 0;
    // User is in the locked state.
    public final static int STATE_RUNNING_LOCKED = 1;
    // User is in the unlocking state.
    public final static int STATE_RUNNING_UNLOCKING = 2;
    // User is in the running state.
    public final static int STATE_RUNNING_UNLOCKED = 3;
    // User is in the initial process of being stopped.
    public final static int STATE_STOPPING = 4;
    // User is in the final phase of stopping, sending Intent.ACTION_SHUTDOWN.
    public final static int STATE_SHUTDOWN = 5;

    public final UserHandle mHandle;
    public final ArrayList<IStopUserCallback> mStopCallbacks
            = new ArrayList<IStopUserCallback>();
    public final ProgressReporter mUnlockProgress;

    public int state = STATE_BOOTING;
    public int lastState = STATE_BOOTING;
    public boolean switching;

    /**
     * The last time that a provider was reported to usage stats as being brought to important
     * foreground procstate.
     */
    public final ArrayMap<String,Long> mProviderLastReportedFg = new ArrayMap<>();

    public UserState(UserHandle handle) {
        mHandle = handle;
        mUnlockProgress = new ProgressReporter(handle.getIdentifier());
    }

    public boolean setState(int oldState, int newState) {
        if (state == oldState) {
            setState(newState);
            return true;
        } else {
            Slog.w(TAG, "Expected user " + mHandle.getIdentifier() + " in state "
                    + stateToString(oldState) + " but was in state " + stateToString(state));
            return false;
        }
    }

    public void setState(int newState) {
        if (DEBUG_MU) {
            Slog.i(TAG, "User " + mHandle.getIdentifier() + " state changed from "
                    + stateToString(state) + " to " + stateToString(newState));
        }
        lastState = state;
        state = newState;
    }

    private static String stateToString(int state) {
        switch (state) {
            case STATE_BOOTING: return "BOOTING";
            case STATE_RUNNING_LOCKED: return "RUNNING_LOCKED";
            case STATE_RUNNING_UNLOCKING: return "RUNNING_UNLOCKING";
            case STATE_RUNNING_UNLOCKED: return "RUNNING_UNLOCKED";
            case STATE_STOPPING: return "STOPPING";
            case STATE_SHUTDOWN: return "SHUTDOWN";
            default: return Integer.toString(state);
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("state="); pw.print(stateToString(state));
        if (switching) pw.print(" SWITCHING");
        pw.println();
    }
}
