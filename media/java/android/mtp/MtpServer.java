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

    public MtpServer(MtpDatabase database) {
        native_setup(database);
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

    public void addStorage(MtpStorage storage) {
        native_add_storage(storage);
    }

    public void removeStorage(MtpStorage storage) {
        native_remove_storage(storage.getStorageId());
    }

    private native final void native_setup(MtpDatabase database);
    private native final void native_start();
    private native final void native_stop();
    private native final void native_send_object_added(int handle);
    private native final void native_send_object_removed(int handle);
    private native final void native_set_ptp_mode(boolean usePtp);
    private native final void native_add_storage(MtpStorage storage);
    private native final void native_remove_storage(int storageId);
}
