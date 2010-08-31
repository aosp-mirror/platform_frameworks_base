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

package android.media;

import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * {@hide}
 */
public class MtpClient {

    private static final String TAG = "MtpClient";

    private final Listener mListener;

    static {
        System.loadLibrary("media_jni");
    }

    public MtpClient(Listener listener) {
        native_setup();
        if (listener == null) {
            throw new NullPointerException("MtpClient: listener is null");
        }
        mListener = listener;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            native_finalize();
        } finally {
            super.finalize();
        }
    }

    public boolean start() {
        return native_start();
    }

    public void stop() {
        native_stop();
    }

    public boolean deleteObject(int deviceID, int objectID) {
        return native_delete_object(deviceID, objectID);
    }

    public int getParent(int deviceID, int objectID) {
        return native_get_parent(deviceID, objectID);
    }

    public int getStorageID(int deviceID, int objectID) {
        return native_get_storage_id(deviceID, objectID);
    }

    // create a file descriptor for reading the contents of an object over MTP
    public ParcelFileDescriptor openFile(int deviceID, int objectID) {
        return native_open_file(deviceID, objectID);
    }

    public interface Listener {
        // called when a new MTP device has been discovered
        void deviceAdded(int id);

        // called when an MTP device has been removed
        void deviceRemoved(int id);
    }

    // called from native code
    private void deviceAdded(int id) {
        Log.d(TAG, "deviceAdded " + id);
        mListener.deviceAdded(id);
    }

    // called from native code
    private void deviceRemoved(int id) {
        Log.d(TAG, "deviceRemoved " + id);
        mListener.deviceRemoved(id);
    }

    // used by the JNI code
    private int mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
    private native boolean native_start();
    private native void native_stop();
    private native boolean native_delete_object(int deviceID, int objectID);
    private native int native_get_parent(int deviceID, int objectID);
    private native int native_get_storage_id(int deviceID, int objectID);
    private native ParcelFileDescriptor native_open_file(int deviceID, int objectID);
}
