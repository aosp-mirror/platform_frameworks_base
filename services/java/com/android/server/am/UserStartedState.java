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

public class UserStartedState {
    public final static int STATE_BOOTING = 0;
    public final static int STATE_RUNNING = 1;
    public final static int STATE_STOPPING = 2;

    public final UserHandle mHandle;
    public final ArrayList<IStopUserCallback> mStopCallbacks
            = new ArrayList<IStopUserCallback>();

    public int mState = STATE_BOOTING;

    public UserStartedState(UserHandle handle, boolean initial) {
        mHandle = handle;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mState="); pw.println(mState);
    }
}
