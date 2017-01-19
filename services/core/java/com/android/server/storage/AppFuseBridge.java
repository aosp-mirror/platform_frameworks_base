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

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import com.android.internal.util.Preconditions;
import libcore.io.IoUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.BlockingQueue;

/**
 * Runnable that delegates FUSE command from the kernel to application.
 * run() blocks until all opened files on the FUSE mount point are closed. So this should be run in
 * a separated thread.
 */
public class AppFuseBridge implements Runnable, AutoCloseable {
    public static final String TAG = "AppFuseBridge";

    /**
     * The path AppFuse is mounted to.
     * The first number is UID who is mounting the FUSE.
     * THe second number is mount ID.
     * The path must be sync with vold.
     */
    private static final String APPFUSE_MOUNT_NAME_TEMPLATE = "/mnt/appfuse/%d_%d";

    private final IMountScope mMountScope;
    private final ParcelFileDescriptor mProxyFd;
    private final BlockingQueue<Boolean> mChannel;

    /**
     * @param mountScope Listener to unmount mount point.
     * @param proxyFd FD of socket pair. Ownership of FD is taken by AppFuseBridge.
     * @param channel Channel that the runnable send mount result to.
     */
    public AppFuseBridge(
            IMountScope mountScope, ParcelFileDescriptor proxyFd, BlockingQueue<Boolean> channel) {
        Preconditions.checkNotNull(mountScope);
        Preconditions.checkNotNull(proxyFd);
        Preconditions.checkNotNull(channel);
        mMountScope = mountScope;
        mProxyFd = proxyFd;
        mChannel = channel;
    }

    @Override
    public void run() {
        try {
            // deviceFd and proxyFd must be closed in native_start_loop.
            native_start_loop(
                    mMountScope.getDeviceFileDescriptor().detachFd(),
                    mProxyFd.detachFd());
        } finally {
            close();
        }
    }

    public static ParcelFileDescriptor openFile(int uid, int mountId, int fileId, int mode)
            throws FileNotFoundException {
        final File mountPoint = getMountPoint(uid, mountId);
        try {
            if (Os.stat(mountPoint.getPath()).st_ino != 1) {
                throw new FileNotFoundException("Could not find bridge mount point.");
            }
        } catch (ErrnoException e) {
            throw new FileNotFoundException(
                    "Failed to stat mount point: " + mountPoint.getParent());
        }
        return ParcelFileDescriptor.open(new File(mountPoint, String.valueOf(fileId)), mode);
    }

    private static File getMountPoint(int uid, int mountId) {
        return new File(String.format(APPFUSE_MOUNT_NAME_TEMPLATE,  uid, mountId));
    }

    @Override
    public void close() {
        IoUtils.closeQuietly(mMountScope);
        IoUtils.closeQuietly(mProxyFd);
        // Invoke countDown here in case where close is invoked before mount.
        mChannel.offer(false);
    }

    // Used by com_android_server_storage_AppFuse.cpp.
    private void onMount() {
        mChannel.offer(true);
    }

    public static interface IMountScope extends AutoCloseable {
        ParcelFileDescriptor getDeviceFileDescriptor();
    }

    private native boolean native_start_loop(int deviceFd, int proxyFd);
}
