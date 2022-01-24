/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.logcat;

import android.content.Context;
import android.os.ILogd;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.logcat.ILogcatManagerService;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service responsible for manage the access to Logcat.
 */
public final class LogcatManagerService extends SystemService {

    private static final String TAG = "LogcatManagerService";
    private final Context mContext;
    private final BinderService mBinderService;
    private final ExecutorService mThreadExecutor;
    private ILogd mLogdService;

    private final class BinderService extends ILogcatManagerService.Stub {
        @Override
        public void startThread(int uid, int gid, int pid, int fd) {
            mThreadExecutor.execute(new LogdMonitor(uid, gid, pid, fd, true));
        }

        @Override
        public void finishThread(int uid, int gid, int pid, int fd) {
            // TODO This thread will be used to notify the AppOpsManager that
            // the logd data access is finished.
            mThreadExecutor.execute(new LogdMonitor(uid, gid, pid, fd, false));
        }
    }

    private class LogdMonitor implements Runnable {

        private final int mUid;
        private final int mGid;
        private final int mPid;
        private final int mFd;
        private final boolean mStart;

        /**
         * For starting a thread, the start value is true.
         * For finishing a thread, the start value is false.
         */
        LogdMonitor(int uid, int gid, int pid, int fd, boolean start) {
            mUid = uid;
            mGid = gid;
            mPid = pid;
            mFd = fd;
            mStart = start;
        }

        /**
         * The current version grant the permission by default.
         * And track the logd access.
         * The next version will generate a prompt for users.
         * The users decide whether the logd access is allowed.
         */
        @Override
        public void run() {
            if (mLogdService == null) {
                LogcatManagerService.this.addLogdService();
            }

            if (mStart) {
                try {
                    mLogdService.approve(mUid, mGid, mPid, mFd);
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Fails to call remote functions ", ex);
                }
            }
        }
    }

    public LogcatManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
        mThreadExecutor = Executors.newCachedThreadPool();
    }

    @Override
    public void onStart() {
        try {
            publishBinderService("logcat", mBinderService);
        } catch (Throwable t) {
            Slog.e(TAG, "Could not start the LogcatManagerService.", t);
        }
    }

    private void addLogdService() {
        mLogdService = ILogd.Stub.asInterface(ServiceManager.getService("logd"));
    }

}
