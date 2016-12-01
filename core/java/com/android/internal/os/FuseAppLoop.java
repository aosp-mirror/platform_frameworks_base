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
import android.os.IProxyFileDescriptorCallback;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class FuseAppLoop {
    private static final String TAG = "FuseAppLoop";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final int ROOT_INODE = 1;
    private static final int MIN_INODE = 2;

    private final Object mLock = new Object();
    private final File mParent;

    @GuardedBy("mLock")
    private final SparseArray<CallbackEntry> mCallbackMap = new SparseArray<>();

    @GuardedBy("mLock")
    private boolean mActive = true;

    /**
     * Sequential number can be used as file name and inode in AppFuse.
     * 0 is regarded as an error, 1 is mount point. So we start the number from 2.
     */
    @GuardedBy("mLock")
    private int mNextInode = MIN_INODE;

    private FuseAppLoop(@NonNull File parent) {
        mParent = parent;
    }

    public static @NonNull FuseAppLoop open(
            @NonNull File parent, @NonNull ParcelFileDescriptor fd) {
        Preconditions.checkNotNull(parent);
        Preconditions.checkNotNull(fd);
        final FuseAppLoop bridge = new FuseAppLoop(parent);
        final int rawFd = fd.detachFd();
        new Thread(new Runnable() {
            @Override
            public void run() {
                bridge.native_start_loop(rawFd);
            }
        }, TAG).start();
        return bridge;
    }

    public @NonNull ParcelFileDescriptor openFile(int mode, IProxyFileDescriptorCallback callback)
            throws UnmountedException, IOException {
        int id;
        synchronized (mLock) {
            if (!mActive) {
                throw new UnmountedException();
            }
            if (mCallbackMap.size() >= Integer.MAX_VALUE - MIN_INODE) {
                throw new IOException("Too many opened files.");
            }
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

            // Register callback after we succeed to create pfd.
            mCallbackMap.put(id, new CallbackEntry(callback));
        }
        try {
            return ParcelFileDescriptor.open(new File(mParent, String.valueOf(id)), mode);
        } catch (FileNotFoundException error) {
            synchronized (mLock) {
                mCallbackMap.remove(id);
            }
            throw error;
        }
    }

    public @Nullable File getMountPoint() {
        synchronized (mLock) {
            return mActive ? mParent : null;
        }
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
                return -exp.errno;
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
                return -exp.errno;
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
                return -exp.errno;
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onRelease(long inode) {
        synchronized(mLock) {
            mCallbackMap.remove(checkInode(inode));
            if (mCallbackMap.size() == 0) {
                mActive = false;
                return -1;
            }
            return 0;
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private int onRead(long inode, long offset, int size, byte[] bytes) {
        synchronized(mLock) {
            try {
                return getCallbackEntryOrThrowLocked(inode).callback.onRead(offset, size, bytes);
            } catch (ErrnoException exp) {
                return -exp.errno;
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
                return -exp.errno;
            }
        }
    }

    native boolean native_start_loop(int fd);

    private static int checkInode(long inode) {
        Preconditions.checkArgumentInRange(inode, MIN_INODE, Integer.MAX_VALUE, "checkInode");
        return (int) inode;
    }

    public static class UnmountedException extends Exception {}

    private static class CallbackEntry {
        final IProxyFileDescriptorCallback callback;
        boolean opened;
        CallbackEntry(IProxyFileDescriptorCallback callback) {
            Preconditions.checkNotNull(callback);
            this.callback = callback;
        }
    }
}
