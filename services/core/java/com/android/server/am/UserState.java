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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.UserIdInt;
import android.app.IStopUserCallback;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

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
    public final ArrayList<IStopUserCallback> mStopCallbacks = new ArrayList<>();
    public final ProgressReporter mUnlockProgress;
    public final ArrayList<KeyEvictedCallback> mKeyEvictedCallbacks = new ArrayList<>();

    public int state = STATE_BOOTING;
    public int lastState = STATE_BOOTING;
    public boolean switching;
    public boolean tokenProvided;

    /** Callback for key eviction. */
    public interface KeyEvictedCallback {
        /** Invoked when the key is evicted. */
        void keyEvicted(@UserIdInt int userId);
    }

    /**
     * The last time that a provider was reported to usage stats as being brought to important
     * foreground procstate.
     * <p><strong>Important: </strong>Only access this field when holding ActivityManagerService
     * lock.
     */
    final ArrayMap<String,Long> mProviderLastReportedFg = new ArrayMap<>();

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
        if (newState == state) {
            return;
        }
        final int userId = mHandle.getIdentifier();
        if (state != STATE_BOOTING) {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    stateToString(state) + " " + userId, userId);
        }
        if (newState != STATE_SHUTDOWN) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    stateToString(newState) + " " + userId, userId);
        }
        Slog.i(TAG, "User " + userId + " state changed from "
                + stateToString(state) + " to " + stateToString(newState));
        EventLogTags.writeAmUserStateChanged(userId, newState);
        lastState = state;
        state = newState;
    }

    public static String stateToString(int state) {
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

    public static int stateToProtoEnum(int state) {
        switch (state) {
            case STATE_BOOTING: return UserStateProto.STATE_BOOTING;
            case STATE_RUNNING_LOCKED: return UserStateProto.STATE_RUNNING_LOCKED;
            case STATE_RUNNING_UNLOCKING: return UserStateProto.STATE_RUNNING_UNLOCKING;
            case STATE_RUNNING_UNLOCKED: return UserStateProto.STATE_RUNNING_UNLOCKED;
            case STATE_STOPPING: return UserStateProto.STATE_STOPPING;
            case STATE_SHUTDOWN: return UserStateProto.STATE_SHUTDOWN;
            default: return state;
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("state="); pw.print(stateToString(state));
        if (switching) pw.print(" SWITCHING");
        pw.println();
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(UserStateProto.STATE, stateToProtoEnum(state));
        proto.write(UserStateProto.SWITCHING, switching);
        proto.end(token);
    }
}
