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

import java.io.PrintWriter;
import java.util.ArrayList;

import android.app.IStopUserCallback;
import android.os.UserHandle;
import android.util.ArrayMap;

public final class UserState {
    // User is first coming up.
    public final static int STATE_BOOTING = 0;
    // User is in the normal running state.
    public final static int STATE_RUNNING = 1;
    // User is in the initial process of being stopped.
    public final static int STATE_STOPPING = 2;
    // User is in the final phase of stopping, sending Intent.ACTION_SHUTDOWN.
    public final static int STATE_SHUTDOWN = 3;

    public final UserHandle mHandle;
    public final ArrayList<IStopUserCallback> mStopCallbacks
            = new ArrayList<IStopUserCallback>();

    public int mState = STATE_BOOTING;
    public boolean switching;
    public boolean initializing;

    /**
     * The last time that a provider was reported to usage stats as being brought to important
     * foreground procstate.
     */
    public final ArrayMap<String,Long> mProviderLastReportedFg = new ArrayMap<>();

    public UserState(UserHandle handle, boolean initial) {
        mHandle = handle;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mState=");
        switch (mState) {
            case STATE_BOOTING: pw.print("BOOTING"); break;
            case STATE_RUNNING: pw.print("RUNNING"); break;
            case STATE_STOPPING: pw.print("STOPPING"); break;
            case STATE_SHUTDOWN: pw.print("SHUTDOWN"); break;
            default: pw.print(mState); break; 
        }
        if (switching) pw.print(" SWITCHING");
        if (initializing) pw.print(" INITIALIZING");
        pw.println();
    }
}
