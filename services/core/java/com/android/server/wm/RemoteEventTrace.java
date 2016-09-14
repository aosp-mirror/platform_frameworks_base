/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.DataOutputStream;

import android.util.Slog;
import android.os.Debug;

// Counterpart to remote surface trace for events which are not tied to a particular surface.
class RemoteEventTrace {
    private static final String TAG = "RemoteEventTrace";
    private final WindowManagerService mService;
    private final DataOutputStream mOut;

    RemoteEventTrace(WindowManagerService service, FileDescriptor fd) {
        mService = service;
        mOut = new DataOutputStream(new FileOutputStream(fd, false));
    }

    void openSurfaceTransaction() {
        try {
            mOut.writeUTF("OpenTransaction");
        } catch (Exception e) {
            logException(e);
            mService.disableSurfaceTrace();
        }
    }

    void closeSurfaceTransaction() {
        try {
            mOut.writeUTF("CloseTransaction");
        } catch (Exception e) {
            logException(e);
            mService.disableSurfaceTrace();
        }
    }

    static void logException(Exception e) {
        Slog.i(TAG, "Exception writing to SurfaceTrace (client vanished?): " + e.toString());
    }
}
