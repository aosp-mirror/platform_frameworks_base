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

import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.FuseUnavailableMountException;
import com.android.internal.util.Preconditions;
import com.android.server.NativeDaemonConnectorException;
import libcore.io.IoUtils;
import java.util.concurrent.CountDownLatch;

/**
 * Runnable that delegates FUSE command from the kernel to application.
 * run() blocks until all opened files on the FUSE mount point are closed. So this should be run in
 * a separated thread.
 */
public class AppFuseBridge implements Runnable {
    public static final String TAG = "AppFuseBridge";

    /**
     * The path AppFuse is mounted to.
     * The first number is UID who is mounting the FUSE.
     * THe second number is mount ID.
     * The path must be sync with vold.
     */
    private static final String APPFUSE_MOUNT_NAME_TEMPLATE = "/mnt/appfuse/%d_%d";

    @GuardedBy("this")
    private final SparseArray<MountScope> mScopes = new SparseArray<>();

    @GuardedBy("this")
    private long mNativeLoop;

    public AppFuseBridge() {
        mNativeLoop = native_new();
    }

    public ParcelFileDescriptor addBridge(MountScope mountScope)
            throws FuseUnavailableMountException, NativeDaemonConnectorException {
        /*
        ** Dead Lock between Java lock (AppFuseBridge.java) and Native lock (FuseBridgeLoop.cc)
        **
        **  (Thread A) Got Java lock (addBrdige) -> Try to get Native lock (native_add_brdige)
        **  (Thread B)        Got Native lock (FuseBrdigeLoop.start) -> Try to get Java lock (onClosed)
        **
        ** Guarantee the lock order (native lock -> java lock) when adding Bridge.
        */
        native_lock();
        try {
            synchronized (this) {
                Preconditions.checkArgument(mScopes.indexOfKey(mountScope.mountId) < 0);
                if (mNativeLoop == 0) {
                    throw new FuseUnavailableMountException(mountScope.mountId);
                }
                final int fd = native_add_bridge(
                        mNativeLoop, mountScope.mountId, mountScope.open().detachFd());
                if (fd == -1) {
                    throw new FuseUnavailableMountException(mountScope.mountId);
                }
                final ParcelFileDescriptor result = ParcelFileDescriptor.adoptFd(fd);
                mScopes.put(mountScope.mountId, mountScope);
                mountScope = null;
                return result;
            }
        } finally {
            native_unlock();
            IoUtils.closeQuietly(mountScope);
        }
    }

    @Override
    public void run() {
        native_start_loop(mNativeLoop);
        synchronized (this) {
            native_delete(mNativeLoop);
            mNativeLoop = 0;
        }
    }

    public ParcelFileDescriptor openFile(int mountId, int fileId, int mode)
            throws FuseUnavailableMountException, InterruptedException {
        final MountScope scope;
        synchronized (this) {
            scope = mScopes.get(mountId);
            if (scope == null) {
                throw new FuseUnavailableMountException(mountId);
            }
        }
        final boolean result = scope.waitForMount();
        if (result == false) {
            throw new FuseUnavailableMountException(mountId);
        }
        try {
            int flags = FileUtils.translateModePfdToPosix(mode);
            return scope.openFile(mountId, fileId, flags);
        } catch (NativeDaemonConnectorException error) {
            throw new FuseUnavailableMountException(mountId);
        }
    }

    // Used by com_android_server_storage_AppFuse.cpp.
    synchronized private void onMount(int mountId) {
        final MountScope scope = mScopes.get(mountId);
        if (scope != null) {
            scope.setMountResultLocked(true);
        }
    }

    // Used by com_android_server_storage_AppFuse.cpp.
    synchronized private void onClosed(int mountId) {
        final MountScope scope = mScopes.get(mountId);
        if (scope != null) {
            scope.setMountResultLocked(false);
            IoUtils.closeQuietly(scope);
            mScopes.remove(mountId);
        }
    }

    public static abstract class MountScope implements AutoCloseable {
        public final int uid;
        public final int mountId;
        private final CountDownLatch mMounted = new CountDownLatch(1);
        private boolean mMountResult = false;

        public MountScope(int uid, int mountId) {
            this.uid = uid;
            this.mountId = mountId;
        }

        @GuardedBy("AppFuseBridge.this")
        void setMountResultLocked(boolean result) {
            if (mMounted.getCount() == 0) {
                return;
            }
            mMountResult = result;
            mMounted.countDown();
        }

        boolean waitForMount() throws InterruptedException {
            mMounted.await();
            return mMountResult;
        }

        public abstract ParcelFileDescriptor open() throws NativeDaemonConnectorException;
        public abstract ParcelFileDescriptor openFile(int mountId, int fileId, int flags)
                throws NativeDaemonConnectorException;
    }

    private native long native_new();
    private native void native_delete(long loop);
    private native void native_start_loop(long loop);
    private native int native_add_bridge(long loop, int mountId, int deviceId);
    private native void native_lock();
    private native void native_unlock();
}
