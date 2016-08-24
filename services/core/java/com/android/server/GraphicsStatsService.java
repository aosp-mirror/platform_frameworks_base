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

package com.android.server;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.MemoryFile;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.IGraphicsStats;
import android.view.ThreadedRenderer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * This service's job is to collect aggregate rendering profile data. It
 * does this by allowing rendering processes to request an ashmem buffer
 * to place their stats into. This buffer will be pre-initialized with historical
 * data for that process if it exists (if the userId & packageName match a buffer
 * in the historical log)
 *
 * This service does not itself attempt to understand the data in the buffer,
 * its primary job is merely to manage distributing these buffers. However,
 * it is assumed that this buffer is for ThreadedRenderer and delegates
 * directly to ThreadedRenderer for dumping buffers.
 *
 * MEMORY USAGE:
 *
 * This class consumes UP TO:
 * 1) [active rendering processes] * (ASHMEM_SIZE * 2)
 * 2) ASHMEM_SIZE (for scratch space used during dumping)
 * 3) ASHMEM_SIZE * HISTORY_SIZE
 *
 * This is currently under 20KiB total memory in the worst case of
 * 20 processes in history + 10 unique active processes.
 *
 *  @hide */
public class GraphicsStatsService extends IGraphicsStats.Stub {
    public static final String GRAPHICS_STATS_SERVICE = "graphicsstats";

    private static final String TAG = "GraphicsStatsService";
    private static final int ASHMEM_SIZE = 464;
    private static final int HISTORY_SIZE = 20;

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final Object mLock = new Object();
    private ArrayList<ActiveBuffer> mActive = new ArrayList<>();
    private HistoricalData[] mHistoricalLog = new HistoricalData[HISTORY_SIZE];
    private int mNextHistoricalSlot = 0;
    private byte[] mTempBuffer = new byte[ASHMEM_SIZE];

    public GraphicsStatsService(Context context) {
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
    }

    @Override
    public ParcelFileDescriptor requestBufferForProcess(String packageName, IBinder token)
            throws RemoteException {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        ParcelFileDescriptor pfd = null;
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            mAppOps.checkPackage(uid, packageName);
            synchronized (mLock) {
                pfd = requestBufferForProcessLocked(token, uid, pid, packageName);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return pfd;
    }

    private ParcelFileDescriptor getPfd(MemoryFile file) {
        try {
            return new ParcelFileDescriptor(file.getFileDescriptor());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to get PFD from memory file", ex);
        }
    }

    private ParcelFileDescriptor requestBufferForProcessLocked(IBinder token,
            int uid, int pid, String packageName) throws RemoteException {
        ActiveBuffer buffer = fetchActiveBuffersLocked(token, uid, pid, packageName);
        return getPfd(buffer.mProcessBuffer);
    }

    private void processDied(ActiveBuffer buffer) {
        synchronized (mLock) {
            mActive.remove(buffer);
            Log.d("GraphicsStats", "Buffer count: " + mActive.size());
        }
        HistoricalData data = buffer.mPreviousData;
        buffer.mPreviousData = null;
        if (data == null) {
            data = mHistoricalLog[mNextHistoricalSlot];
            if (data == null) {
                data = new HistoricalData();
            }
        }
        data.update(buffer.mPackageName, buffer.mUid, buffer.mProcessBuffer);
        buffer.closeAllBuffers();

        mHistoricalLog[mNextHistoricalSlot] = data;
        mNextHistoricalSlot = (mNextHistoricalSlot + 1) % mHistoricalLog.length;
    }

    private ActiveBuffer fetchActiveBuffersLocked(IBinder token, int uid, int pid,
            String packageName) throws RemoteException {
        int size = mActive.size();
        for (int i = 0; i < size; i++) {
            ActiveBuffer buffers = mActive.get(i);
            if (buffers.mPid == pid
                    && buffers.mUid == uid) {
                return buffers;
            }
        }
        // Didn't find one, need to create it
        try {
            ActiveBuffer buffers = new ActiveBuffer(token, uid, pid, packageName);
            mActive.add(buffers);
            return buffers;
        } catch (IOException ex) {
            throw new RemoteException("Failed to allocate space");
        }
    }

    private HistoricalData removeHistoricalDataLocked(int uid, String packageName) {
        for (int i = 0; i < mHistoricalLog.length; i++) {
            final HistoricalData data = mHistoricalLog[i];
            if (data != null && data.mUid == uid
                    && data.mPackageName.equals(packageName)) {
                if (i == mNextHistoricalSlot) {
                    mHistoricalLog[i] = null;
                } else {
                    mHistoricalLog[i] = mHistoricalLog[mNextHistoricalSlot];
                    mHistoricalLog[mNextHistoricalSlot] = null;
                }
                return data;
            }
        }
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        synchronized (mLock) {
            for (int i = 0; i < mActive.size(); i++) {
                final ActiveBuffer buffer = mActive.get(i);
                fout.print("Package: ");
                fout.print(buffer.mPackageName);
                fout.flush();
                try {
                    buffer.mProcessBuffer.readBytes(mTempBuffer, 0, 0, ASHMEM_SIZE);
                    ThreadedRenderer.dumpProfileData(mTempBuffer, fd);
                } catch (IOException e) {
                    fout.println("Failed to dump");
                }
                fout.println();
            }
            for (HistoricalData buffer : mHistoricalLog) {
                if (buffer == null) continue;
                fout.print("Package: ");
                fout.print(buffer.mPackageName);
                fout.flush();
                ThreadedRenderer.dumpProfileData(buffer.mBuffer, fd);
                fout.println();
            }
        }
    }

    private final class ActiveBuffer implements DeathRecipient {
        final int mUid;
        final int mPid;
        final String mPackageName;
        final IBinder mToken;
        MemoryFile mProcessBuffer;
        HistoricalData mPreviousData;

        ActiveBuffer(IBinder token, int uid, int pid, String packageName)
                throws RemoteException, IOException {
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mToken = token;
            mToken.linkToDeath(this, 0);
            mProcessBuffer = new MemoryFile("GFXStats-" + uid, ASHMEM_SIZE);
            mPreviousData = removeHistoricalDataLocked(mUid, mPackageName);
            if (mPreviousData != null) {
                mProcessBuffer.writeBytes(mPreviousData.mBuffer, 0, 0, ASHMEM_SIZE);
            }
        }

        @Override
        public void binderDied() {
            mToken.unlinkToDeath(this, 0);
            processDied(this);
        }

        void closeAllBuffers() {
            if (mProcessBuffer != null) {
                mProcessBuffer.close();
                mProcessBuffer = null;
            }
        }
    }

    private final static class HistoricalData {
        final byte[] mBuffer = new byte[ASHMEM_SIZE];
        int mUid;
        String mPackageName;

        void update(String packageName, int uid, MemoryFile file) {
            mUid = uid;
            mPackageName = packageName;
            try {
                file.readBytes(mBuffer, 0, 0, ASHMEM_SIZE);
            } catch (IOException e) {}
        }
    }
}
