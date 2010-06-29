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

import android.util.Log;

/**
 * Java wrapper for MTP/PTP support as USB responder.
 * {@hide}
 */
public class MtpServer {

    private static final String TAG = "MtpServer";

    static {
        System.loadLibrary("media_jni");
    }

    public MtpServer(String storagePath, String databasePath) {
        native_setup(storagePath, databasePath);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    public void start() {
        native_start();
    }

    public void stop() {
        native_stop();
    }

    // used by the JNI code
    private int mNativeContext;

    private native final void native_setup(String storagePath, String databasePath);
    private native final void native_finalize();
    private native final void native_start();
    private native final void native_stop();
}
