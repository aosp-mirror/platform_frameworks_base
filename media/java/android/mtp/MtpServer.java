/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.mtp;

import android.util.Log;

/**
 * Java wrapper for MTP/PTP support as USB responder.
 * {@hide}
 */
public class MtpServer {

    private final Object mLock = new Object();
    private boolean mStarted;

    private static final String TAG = "MtpServer";

    static {
        System.loadLibrary("media_jni");
    }

    public MtpServer(MtpDatabase database, String storagePath, long reserveSpace) {
        native_setup(database, storagePath, reserveSpace);
    }

    public void start() {
        synchronized (mLock) {
            native_start();
            mStarted = true;
        }
    }

    public void stop() {
        synchronized (mLock) {
            if (mStarted) {
                native_stop();
                mStarted = false;
            }
        }
    }

    public void sendObjectAdded(int handle) {
        native_send_object_added(handle);
    }

    public void sendObjectRemoved(int handle) {
        native_send_object_removed(handle);
    }

    public void setPtpMode(boolean usePtp) {
        native_set_ptp_mode(usePtp);
    }

    // Used to disable MTP by removing all storage units.
    // This is done to disable access to file transfer when the device is locked.
    public void setLocked(boolean locked) {
        native_set_locked(locked);
    }

    private native final void native_setup(MtpDatabase database, String storagePath,
            long reserveSpace);
    private native final void native_start();
    private native final void native_stop();
    private native final void native_send_object_added(int handle);
    private native final void native_send_object_removed(int handle);
    private native final void native_set_ptp_mode(boolean usePtp);
    private native final void native_set_locked(boolean locked);
}
