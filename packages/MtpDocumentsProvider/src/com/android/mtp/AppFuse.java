/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;

import android.os.Process;

/**
 * TODO: Remove VisibleForTesting class.
 */
@VisibleForTesting
public class AppFuse {
    static {
        System.loadLibrary("appfuse_jni");
    }

    private final String mName;
    private final Thread mMessageThread;
    private ParcelFileDescriptor mDeviceFd;

    @VisibleForTesting
    AppFuse(String name) {
        mName = name;
        mMessageThread = new Thread(new Runnable() {
            @Override
            public void run() {
                native_start_app_fuse_loop(mDeviceFd.getFd());
            }
        });
    }

    @VisibleForTesting
    void mount(StorageManager storageManager) {
        mDeviceFd = storageManager.mountAppFuse(mName);
        mMessageThread.start();
    }

    @VisibleForTesting
    void close() {
        try {
            // Remote side of ParcelFileDescriptor is tracking the close of mDeviceFd, and unmount
            // the corresponding fuse file system. The mMessageThread will receive FUSE_FORGET, and
            // then terminate itself.
            mDeviceFd.close();
            mMessageThread.join();
        } catch (IOException exp) {
            Log.e(MtpDocumentsProvider.TAG, "Failed to close device FD.", exp);
        } catch (InterruptedException exp) {
            Log.e(MtpDocumentsProvider.TAG, "Failed to terminate message thread.", exp);
        }
    }

    @VisibleForTesting
    File getMountPoint() {
        return new File("/mnt/appfuse/" + Process.myUid() + "_" + mName);
    }

    private native boolean native_start_app_fuse_loop(int fd);
}
