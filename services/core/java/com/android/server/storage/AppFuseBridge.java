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

package com.android.server.storage;

import android.annotation.CallSuper;
import android.annotation.WorkerThread;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import com.android.internal.os.AppFuseMount;
import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class AppFuseBridge implements Runnable {
    private static final String TAG = AppFuseBridge.class.getSimpleName();

    private final FileDescriptor mDeviceFd;
    private final FileDescriptor mProxyFd;
    private final CountDownLatch mMountLatch = new CountDownLatch(1);

    /**
     * @param deviceFd FD of /dev/fuse. Ownership of fd is taken by AppFuseBridge.
     * @param proxyFd FD of socket pair. Ownership of fd is taken by AppFuseBridge.
     */
    private AppFuseBridge(FileDescriptor deviceFd, FileDescriptor proxyFd) {
        mDeviceFd = deviceFd;
        mProxyFd = proxyFd;
    }

    public static AppFuseMount startMessageLoop(
            int uid,
            String name,
            FileDescriptor deviceFd,
            Handler handler,
            ParcelFileDescriptor.OnCloseListener listener)
                    throws IOException, ErrnoException, InterruptedException {
        final FileDescriptor localFd = new FileDescriptor();
        final FileDescriptor remoteFd = new FileDescriptor();
        // Needs to specify OsConstants.SOCK_SEQPACKET to keep message boundaries.
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, remoteFd, localFd);

        // Caller must invoke #start() after instantiate AppFuseBridge.
        // Otherwise FDs will be leaked.
        final AppFuseBridge bridge = new AppFuseBridge(deviceFd, localFd);
        final Thread thread = new Thread(bridge, TAG);
        thread.start();
        try {
            bridge.mMountLatch.await();
        } catch (InterruptedException error) {
            throw error;
        }
        return new AppFuseMount(
                new File("/mnt/appfuse/" + uid + "_" + name),
                ParcelFileDescriptor.fromFd(remoteFd, handler, listener));
    }

    @Override
    public void run() {
        // deviceFd and proxyFd must be closed in native_start_loop.
        final int deviceFd = mDeviceFd.getInt$();
        final int proxyFd = mProxyFd.getInt$();
        mDeviceFd.setInt$(-1);
        mProxyFd.setInt$(-1);
        native_start_loop(deviceFd, proxyFd);
    }

    // Used by com_android_server_storage_AppFuse.cpp.
    private void onMount() {
        mMountLatch.countDown();
    }

    private native boolean native_start_loop(int deviceFd, int proxyFd);
}
