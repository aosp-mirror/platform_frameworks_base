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

package com.android.internal.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ProxyFileDescriptorCallback;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.util.concurrent.ThreadFactory;

public class FuseAppLoop {
    private static final String TAG = "FuseAppLoop";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final int ROOT_INODE = 1;
    private static final int MIN_INODE = 2;
    private static final ThreadFactory sDefaultThreadFactory = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, TAG);
        }
    };

    private final Object mLock = new Object();
    private final int mMountPointId;
    private final Thread mThread;

    @GuardedBy("mLock")
    private final SparseArray<CallbackEntry> mCallbackMap = new SparseArray<>();

    /**
     * Sequential number can be used as file name and inode in AppFuse.
     * 0 is regarded as an error, 1 is mount point. So we start the number from 2.
     */
    @GuardedBy("mLock")
    private int mNextInode = MIN_INODE;

    private FuseAppLoop(
            int mountPointId, @NonNull ParcelFileDescriptor fd, @Nullable ThreadFactory factory) {
        mMountPointId = mountPointId;
        final int rawFd = fd.detachFd();
        if (factory == null) {
            factory = sDefaultThreadFactory;
        }
        mThread = factory.newThread(new Runnable() {
            @Override
            public void run() {
                // rawFd is closed by native_start_loop. Java code does not need to close it.
                native_start_loop(rawFd);
            }
        });
    }

    public static @NonNull FuseAppLoop open(int mountPointId, @NonNull ParcelFileDescriptor fd,
            @Nullable ThreadFactory factory) {
        Preconditions.checkNotNull(fd);
        final FuseAppLoop loop = new FuseAppLoop(mountPointId, fd, factory);
        loop.mThread.start();
        return loop;
    }

    public int registerCallback(@NonNull ProxyFileDescriptorCallback callback)
            throws UnmountedException, IOException {
        if (mThread.getState() == Thread.State.TERMINATED) {
            throw new UnmountedException();
        }
        synchronized (mLock) {
            if (mCallbackMap.size() >= Integer.MAX_VALUE - MIN_INODE) {
                throw new IOException("Too many opened files.");
            }
            int id;
            while (true) {
                id = mNextInode;
                mNextInode++;
                if (mNextInode < 0) {
                    mNextInode = MIN_INODE;
                }
                if (mCallbackMap.get(id) == null) {
                    break;
                }
            }
            mCallbackMap.put(id, new CallbackEntry(callback));
            return id;
        }
    }

    public void unregisterCallback(int id) {
        mCallbackMap.remove(id);
    }

    public int getMountPointId() {
        return mMountPointId;
    }

    private CallbackEntry getCallbackEntryOrThrowLocked(long inode) throws ErrnoException {
        final CallbackEntry entry = mCallbackMap.get(checkInode(inode));
        if (entry != null) {
            return entry;
        } else {
            throw new ErrnoException("getCallbackEntry", OsConstants.ENOENT);
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private long onGetSize(long inode) {
        synchronized(mLock) {
            try {
                return getCallbackEntryOrThrowLocked(inode).callback.onGetSize();
            } catch (ErrnoException exp) {
                return getError(exp);
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onOpen(long inode) {
        synchronized(mLock) {
            try {
                final CallbackEntry entry = getCallbackEntryOrThrowLocked(inode);
                if (entry.opened) {
                    throw new ErrnoException("onOpen", OsConstants.EMFILE);
                }
                entry.opened = true;
                // Use inode as file handle. It's OK because AppFuse does not allow to open the same
                // file twice.
                return (int) inode;
            } catch (ErrnoException exp) {
                return getError(exp);
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onFsync(long inode) {
        synchronized(mLock) {
            try {
                getCallbackEntryOrThrowLocked(inode).callback.onFsync();
                return 0;
            } catch (ErrnoException exp) {
                return getError(exp);
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onRelease(long inode) {
        synchronized(mLock) {
            try {
                getCallbackEntryOrThrowLocked(inode).callback.onRelease();
                return 0;
            } catch (ErrnoException exp) {
                return getError(exp);
            } finally {
                mCallbackMap.remove(checkInode(inode));
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onRead(long inode, long offset, int size, byte[] bytes) {
        synchronized(mLock) {
            try {
                return getCallbackEntryOrThrowLocked(inode).callback.onRead(offset, size, bytes);
            } catch (ErrnoException exp) {
                return getError(exp);
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onWrite(long inode, long offset, int size, byte[] bytes) {
        synchronized(mLock) {
            try {
                return getCallbackEntryOrThrowLocked(inode).callback.onWrite(offset, size, bytes);
            } catch (ErrnoException exp) {
                return getError(exp);
            }
        }
    }

    private static int getError(@NonNull ErrnoException exp) {
        // Should not return ENOSYS because the kernel stops
        // dispatching the FUSE action once FUSE implementation returns ENOSYS for the action.
        return exp.errno != OsConstants.ENOSYS ? -exp.errno : -OsConstants.EIO;
    }

    native boolean native_start_loop(int fd);

    private static int checkInode(long inode) {
        Preconditions.checkArgumentInRange(inode, MIN_INODE, Integer.MAX_VALUE, "checkInode");
        return (int) inode;
    }

    public static class UnmountedException extends Exception {}

    private static class CallbackEntry {
        final ProxyFileDescriptorCallback callback;
        boolean opened;
        CallbackEntry(ProxyFileDescriptorCallback callback) {
            Preconditions.checkNotNull(callback);
            this.callback = callback;
        }
    }
}
