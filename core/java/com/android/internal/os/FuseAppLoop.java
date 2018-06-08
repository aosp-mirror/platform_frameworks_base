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
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ThreadFactory;

public class FuseAppLoop implements Handler.Callback {
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
    private static final int FUSE_OK = 0;
    private static final int ARGS_POOL_SIZE = 50;

    private final Object mLock = new Object();
    private final int mMountPointId;
    private final Thread mThread;

    @GuardedBy("mLock")
    private final SparseArray<CallbackEntry> mCallbackMap = new SparseArray<>();

    @GuardedBy("mLock")
    private final BytesMap mBytesMap = new BytesMap();

    @GuardedBy("mLock")
    private final LinkedList<Args> mArgsPool = new LinkedList<>();

    /**
     * Sequential number can be used as file name and inode in AppFuse.
     * 0 is regarded as an error, 1 is mount point. So we start the number from 2.
     */
    @GuardedBy("mLock")
    private int mNextInode = MIN_INODE;

    @GuardedBy("mLock")
    private long mInstance;

    public FuseAppLoop(
            int mountPointId, @NonNull ParcelFileDescriptor fd, @Nullable ThreadFactory factory) {
        mMountPointId = mountPointId;
        if (factory == null) {
            factory = sDefaultThreadFactory;
        }
        mInstance = native_new(fd.detachFd());
        mThread = factory.newThread(() -> {
            native_start(mInstance);
            synchronized (mLock) {
                native_delete(mInstance);
                mInstance = 0;
                mBytesMap.clear();
            }
        });
        mThread.start();
    }

    public int registerCallback(@NonNull ProxyFileDescriptorCallback callback,
            @NonNull Handler handler) throws FuseUnavailableMountException {
        synchronized (mLock) {
            Preconditions.checkNotNull(callback);
            Preconditions.checkNotNull(handler);
            Preconditions.checkState(
                    mCallbackMap.size() < Integer.MAX_VALUE - MIN_INODE, "Too many opened files.");
            Preconditions.checkArgument(
                    Thread.currentThread().getId() != handler.getLooper().getThread().getId(),
                    "Handler must be different from the current thread");
            if (mInstance == 0) {
                throw new FuseUnavailableMountException(mMountPointId);
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
            mCallbackMap.put(id, new CallbackEntry(
                    callback, new Handler(handler.getLooper(), this)));
            return id;
        }
    }

    public void unregisterCallback(int id) {
        synchronized (mLock) {
            mCallbackMap.remove(id);
        }
    }

    public int getMountPointId() {
        return mMountPointId;
    }

    // Defined in fuse.h
    private static final int FUSE_LOOKUP = 1;
    private static final int FUSE_GETATTR = 3;
    private static final int FUSE_OPEN = 14;
    private static final int FUSE_READ = 15;
    private static final int FUSE_WRITE = 16;
    private static final int FUSE_RELEASE = 18;
    private static final int FUSE_FSYNC = 20;

    // Defined in FuseBuffer.h
    private static final int FUSE_MAX_WRITE = 128 * 1024;

    @Override
    public boolean handleMessage(Message msg) {
        final Args args = (Args) msg.obj;
        final CallbackEntry entry = args.entry;
        final long inode = args.inode;
        final long unique = args.unique;
        final int size = args.size;
        final long offset = args.offset;
        final byte[] data = args.data;

        try {
            switch (msg.what) {
                case FUSE_LOOKUP: {
                    final long fileSize = entry.callback.onGetSize();
                    synchronized (mLock) {
                        if (mInstance != 0) {
                            native_replyLookup(mInstance, unique, inode, fileSize);
                        }
                        recycleLocked(args);
                    }
                    break;
                }
                case FUSE_GETATTR: {
                    final long fileSize = entry.callback.onGetSize();
                    synchronized (mLock) {
                        if (mInstance != 0) {
                            native_replyGetAttr(mInstance, unique, inode, fileSize);
                        }
                        recycleLocked(args);
                    }
                    break;
                }
                case FUSE_READ:
                    final int readSize = entry.callback.onRead(
                            offset, size, data);
                    synchronized (mLock) {
                        if (mInstance != 0) {
                            native_replyRead(mInstance, unique, readSize, data);
                        }
                        recycleLocked(args);
                    }
                    break;
                case FUSE_WRITE:
                    final int writeSize = entry.callback.onWrite(offset, size, data);
                    synchronized (mLock) {
                        if (mInstance != 0) {
                            native_replyWrite(mInstance, unique, writeSize);
                        }
                        recycleLocked(args);
                    }
                    break;
                case FUSE_FSYNC:
                    entry.callback.onFsync();
                    synchronized (mLock) {
                        if (mInstance != 0) {
                            native_replySimple(mInstance, unique, FUSE_OK);
                        }
                        recycleLocked(args);
                    }
                    break;
                case FUSE_RELEASE:
                    entry.callback.onRelease();
                    synchronized (mLock) {
                        if (mInstance != 0) {
                            native_replySimple(mInstance, unique, FUSE_OK);
                        }
                        mBytesMap.stopUsing(entry.getThreadId());
                        recycleLocked(args);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown FUSE command: " + msg.what);
            }
        } catch (Exception error) {
            synchronized (mLock) {
                Log.e(TAG, "", error);
                replySimpleLocked(unique, getError(error));
                recycleLocked(args);
            }
        }

        return true;
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private void onCommand(int command, long unique, long inode, long offset, int size,
            byte[] data) {
        synchronized (mLock) {
            try {
                final Args args;
                if (mArgsPool.size() == 0) {
                    args = new Args();
                } else {
                    args = mArgsPool.pop();
                }
                args.unique = unique;
                args.inode = inode;
                args.offset = offset;
                args.size = size;
                args.data = data;
                args.entry = getCallbackEntryOrThrowLocked(inode);
                if (!args.entry.handler.sendMessage(
                        Message.obtain(args.entry.handler, command, 0, 0, args))) {
                    throw new ErrnoException("onCommand", OsConstants.EBADF);
                }
            } catch (Exception error) {
                replySimpleLocked(unique, getError(error));
            }
        }
    }

    // Called by JNI.
    @SuppressWarnings("unused")
    private byte[] onOpen(long unique, long inode) {
        synchronized (mLock) {
            try {
                final CallbackEntry entry = getCallbackEntryOrThrowLocked(inode);
                if (entry.opened) {
                    throw new ErrnoException("onOpen", OsConstants.EMFILE);
                }
                if (mInstance != 0) {
                    native_replyOpen(mInstance, unique, /* fh */ inode);
                    entry.opened = true;
                    return mBytesMap.startUsing(entry.getThreadId());
                }
            } catch (ErrnoException error) {
                replySimpleLocked(unique, getError(error));
            }
            return null;
        }
    }

    private static int getError(@NonNull Exception error) {
        if (error instanceof ErrnoException) {
            final int errno = ((ErrnoException) error).errno;
            if (errno != OsConstants.ENOSYS) {
                return -errno;
            }
        }
        return -OsConstants.EBADF;
    }

    @GuardedBy("mLock")
    private CallbackEntry getCallbackEntryOrThrowLocked(long inode) throws ErrnoException {
        final CallbackEntry entry = mCallbackMap.get(checkInode(inode));
        if (entry == null) {
            throw new ErrnoException("getCallbackEntryOrThrowLocked", OsConstants.ENOENT);
        }
        return entry;
    }

    @GuardedBy("mLock")
    private void recycleLocked(Args args) {
        if (mArgsPool.size() < ARGS_POOL_SIZE) {
            mArgsPool.add(args);
        }
    }

    @GuardedBy("mLock")
    private void replySimpleLocked(long unique, int result) {
        if (mInstance != 0) {
            native_replySimple(mInstance, unique, result);
        }
    }

    native long native_new(int fd);
    native void native_delete(long ptr);
    native void native_start(long ptr);

    native void native_replySimple(long ptr, long unique, int result);
    native void native_replyOpen(long ptr, long unique, long fh);
    native void native_replyLookup(long ptr, long unique, long inode, long size);
    native void native_replyGetAttr(long ptr, long unique, long inode, long size);
    native void native_replyWrite(long ptr, long unique, int size);
    native void native_replyRead(long ptr, long unique, int size, byte[] bytes);

    private static int checkInode(long inode) {
        Preconditions.checkArgumentInRange(inode, MIN_INODE, Integer.MAX_VALUE, "checkInode");
        return (int) inode;
    }

    public static class UnmountedException extends Exception {}

    private static class CallbackEntry {
        final ProxyFileDescriptorCallback callback;
        final Handler handler;
        boolean opened;

        CallbackEntry(ProxyFileDescriptorCallback callback, Handler handler) {
            this.callback = Preconditions.checkNotNull(callback);
            this.handler = Preconditions.checkNotNull(handler);
        }

        long getThreadId() {
            return handler.getLooper().getThread().getId();
        }
    }

    /**
     * Entry for bytes map.
     */
    private static class BytesMapEntry {
        int counter = 0;
        byte[] bytes = new byte[FUSE_MAX_WRITE];
    }

    /**
     * Map between Thread ID and byte buffer.
     */
    private static class BytesMap {
        final Map<Long, BytesMapEntry> mEntries = new HashMap<>();

        byte[] startUsing(long threadId) {
            BytesMapEntry entry = mEntries.get(threadId);
            if (entry == null) {
                entry = new BytesMapEntry();
                mEntries.put(threadId, entry);
            }
            entry.counter++;
            return entry.bytes;
        }

        void stopUsing(long threadId) {
            final BytesMapEntry entry = mEntries.get(threadId);
            Preconditions.checkNotNull(entry);
            entry.counter--;
            if (entry.counter <= 0) {
                mEntries.remove(threadId);
            }
        }

        void clear() {
            mEntries.clear();
        }
    }

    private static class Args {
        long unique;
        long inode;
        long offset;
        int size;
        byte[] data;
        CallbackEntry entry;
    }
}
