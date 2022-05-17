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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ILogd;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.logcat.ILogcatManagerService;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;


/**
 * Service responsible for managing the access to Logcat.
 */
public final class LogcatManagerService extends SystemService {
    private static final String TAG = "LogcatManagerService";
    private static final boolean DEBUG = false;

    /** How long to wait for the user to approve/decline before declining automatically */
    @VisibleForTesting
    static final int PENDING_CONFIRMATION_TIMEOUT_MILLIS = Build.IS_DEBUGGABLE ? 70000 : 400000;

    /**
     * How long an approved / declined status is valid for.
     *
     * After a client has been approved/declined log access, if they try to access logs again within
     * this timeout, the new request will be automatically approved/declined.
     * Only after this timeout expires will a new request generate another prompt to the user.
     **/
    @VisibleForTesting
    static final int STATUS_EXPIRATION_TIMEOUT_MILLIS = 60 * 1000;

    private static final int MSG_LOG_ACCESS_REQUESTED = 0;
    private static final int MSG_APPROVE_LOG_ACCESS = 1;
    private static final int MSG_DECLINE_LOG_ACCESS = 2;
    private static final int MSG_LOG_ACCESS_FINISHED = 3;
    private static final int MSG_PENDING_TIMEOUT = 4;
    private static final int MSG_LOG_ACCESS_STATUS_EXPIRED = 5;

    private static final int STATUS_NEW_REQUEST = 0;
    private static final int STATUS_PENDING = 1;
    private static final int STATUS_APPROVED = 2;
    private static final int STATUS_DECLINED = 3;

    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_NEW_REQUEST,
            STATUS_PENDING,
            STATUS_APPROVED,
            STATUS_DECLINED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LogAccessRequestStatus {
    }

    private final Context mContext;
    private final Injector mInjector;
    private final Supplier<Long> mClock;
    private final BinderService mBinderService;
    private final LogcatManagerServiceInternal mLocalService;
    private final Handler mHandler;
    private ActivityManagerInternal mActivityManagerInternal;
    private ILogd mLogdService;

    private static final class LogAccessClient {
        final int mUid;
        @NonNull
        final String mPackageName;

        LogAccessClient(int uid, @NonNull String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LogAccessClient)) return false;
            LogAccessClient that = (LogAccessClient) o;
            return mUid == that.mUid && Objects.equals(mPackageName, that.mPackageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUid, mPackageName);
        }

        @Override
        public String toString() {
            return "LogAccessClient{"
                    + "mUid=" + mUid
                    + ", mPackageName=" + mPackageName
                    + '}';
        }
    }

    private static final class LogAccessRequest {
        final int mUid;
        final int mGid;
        final int mPid;
        final int mFd;

        private LogAccessRequest(int uid, int gid, int pid, int fd) {
            mUid = uid;
            mGid = gid;
            mPid = pid;
            mFd = fd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LogAccessRequest)) return false;
            LogAccessRequest that = (LogAccessRequest) o;
            return mUid == that.mUid && mGid == that.mGid && mPid == that.mPid && mFd == that.mFd;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUid, mGid, mPid, mFd);
        }

        @Override
        public String toString() {
            return "LogAccessRequest{"
                    + "mUid=" + mUid
                    + ", mGid=" + mGid
                    + ", mPid=" + mPid
                    + ", mFd=" + mFd
                    + '}';
        }
    }

    private static final class LogAccessStatus {
        @LogAccessRequestStatus
        int mStatus = STATUS_NEW_REQUEST;
        final List<LogAccessRequest> mPendingRequests = new ArrayList<>();
    }

    private final Map<LogAccessClient, LogAccessStatus> mLogAccessStatus = new ArrayMap<>();
    private final Map<LogAccessClient, Integer> mActiveLogAccessCount = new ArrayMap<>();

    private final class BinderService extends ILogcatManagerService.Stub {
        @Override
        public void startThread(int uid, int gid, int pid, int fd) {
            final LogAccessRequest logAccessRequest = new LogAccessRequest(uid, gid, pid, fd);
            if (DEBUG) {
                Slog.d(TAG, "New log access request: " + logAccessRequest);
            }
            final Message msg = mHandler.obtainMessage(MSG_LOG_ACCESS_REQUESTED, logAccessRequest);
            mHandler.sendMessageAtTime(msg, mClock.get());
        }

        @Override
        public void finishThread(int uid, int gid, int pid, int fd) {
            final LogAccessRequest logAccessRequest = new LogAccessRequest(uid, gid, pid, fd);
            if (DEBUG) {
                Slog.d(TAG, "Log access finished: " + logAccessRequest);
            }
            final Message msg = mHandler.obtainMessage(MSG_LOG_ACCESS_FINISHED, logAccessRequest);
            mHandler.sendMessageAtTime(msg, mClock.get());
        }
    }

    final class LogcatManagerServiceInternal {
        public void approveAccessForClient(int uid, @NonNull String packageName) {
            final LogAccessClient client = new LogAccessClient(uid, packageName);
            if (DEBUG) {
                Slog.d(TAG, "Approving log access for client: " + client);
            }
            final Message msg = mHandler.obtainMessage(MSG_APPROVE_LOG_ACCESS, client);
            mHandler.sendMessageAtTime(msg, mClock.get());
        }

        public void declineAccessForClient(int uid, @NonNull String packageName) {
            final LogAccessClient client = new LogAccessClient(uid, packageName);
            if (DEBUG) {
                Slog.d(TAG, "Declining log access for client: " + client);
            }
            final Message msg = mHandler.obtainMessage(MSG_DECLINE_LOG_ACCESS, client);
            mHandler.sendMessageAtTime(msg, mClock.get());
        }
    }

    private ILogd getLogdService() {
        if (mLogdService == null) {
            mLogdService = mInjector.getLogdService();
        }
        return mLogdService;
    }

    private static class LogAccessRequestHandler extends Handler {
        private final LogcatManagerService mService;

        LogAccessRequestHandler(Looper looper, LogcatManagerService service) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOG_ACCESS_REQUESTED: {
                    LogAccessRequest request = (LogAccessRequest) msg.obj;
                    mService.onLogAccessRequested(request);
                    break;
                }
                case MSG_APPROVE_LOG_ACCESS: {
                    LogAccessClient client = (LogAccessClient) msg.obj;
                    mService.onAccessApprovedForClient(client);
                    break;
                }
                case MSG_DECLINE_LOG_ACCESS: {
                    LogAccessClient client = (LogAccessClient) msg.obj;
                    mService.onAccessDeclinedForClient(client);
                    break;
                }
                case MSG_LOG_ACCESS_FINISHED: {
                    LogAccessRequest request = (LogAccessRequest) msg.obj;
                    mService.onLogAccessFinished(request);
                    break;
                }
                case MSG_PENDING_TIMEOUT: {
                    LogAccessClient client = (LogAccessClient) msg.obj;
                    mService.onPendingTimeoutExpired(client);
                    break;
                }
                case MSG_LOG_ACCESS_STATUS_EXPIRED: {
                    LogAccessClient client = (LogAccessClient) msg.obj;
                    mService.onAccessStatusExpired(client);
                    break;
                }
            }
        }
    }

    static class Injector {
        protected Supplier<Long> createClock() {
            return SystemClock::uptimeMillis;
        }

        protected Looper getLooper() {
            return Looper.getMainLooper();
        }

        protected ILogd getLogdService() {
            return ILogd.Stub.asInterface(ServiceManager.getService("logd"));
        }
    }

    public LogcatManagerService(Context context) {
        this(context, new Injector());
    }

    public LogcatManagerService(Context context, Injector injector) {
        super(context);
        mContext = context;
        mInjector = injector;
        mClock = injector.createClock();
        mBinderService = new BinderService();
        mLocalService = new LogcatManagerServiceInternal();
        mHandler = new LogAccessRequestHandler(injector.getLooper(), this);
    }

    @Override
    public void onStart() {
        try {
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            publishBinderService("logcat", mBinderService);
            publishLocalService(LogcatManagerServiceInternal.class, mLocalService);
        } catch (Throwable t) {
            Slog.e(TAG, "Could not start the LogcatManagerService.", t);
        }
    }

    @VisibleForTesting
    LogcatManagerServiceInternal getLocalService() {
        return mLocalService;
    }

    @VisibleForTesting
    ILogcatManagerService getBinderService() {
        return mBinderService;
    }

    @Nullable
    private LogAccessClient getClientForRequest(LogAccessRequest request) {
        final String packageName = getPackageName(request);
        if (packageName == null) {
            return null;
        }

        return new LogAccessClient(request.mUid, packageName);
    }

    /**
     * Returns the package name.
     * If we cannot retrieve the package name, it returns null and we decline the full device log
     * access
     */
    private String getPackageName(LogAccessRequest request) {
        if (mActivityManagerInternal != null) {
            String packageName = mActivityManagerInternal.getPackageNameByPid(request.mPid);
            if (packageName != null) {
                return packageName;
            }
        }

        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            // Decline the logd access if PackageManager is null
            Slog.e(TAG, "PackageManager is null, declining the logd access");
            return null;
        }

        String[] packageNames = pm.getPackagesForUid(request.mUid);

        if (ArrayUtils.isEmpty(packageNames)) {
            // Decline the logd access if the app name is unknown
            Slog.e(TAG, "Unknown calling package name, declining the logd access");
            return null;
        }

        String firstPackageName = packageNames[0];

        if (firstPackageName == null || firstPackageName.isEmpty()) {
            // Decline the logd access if the package name from uid is unknown
            Slog.e(TAG, "Unknown calling package name, declining the logd access");
            return null;
        }

        return firstPackageName;
    }

    void onLogAccessRequested(LogAccessRequest request) {
        final LogAccessClient client = getClientForRequest(request);
        if (client == null) {
            declineRequest(request);
            return;
        }

        LogAccessStatus logAccessStatus = mLogAccessStatus.get(client);
        if (logAccessStatus == null) {
            logAccessStatus = new LogAccessStatus();
            mLogAccessStatus.put(client, logAccessStatus);
        }

        switch (logAccessStatus.mStatus) {
            case STATUS_NEW_REQUEST:
                logAccessStatus.mPendingRequests.add(request);
                processNewLogAccessRequest(client);
                break;
            case STATUS_PENDING:
                logAccessStatus.mPendingRequests.add(request);
                return;
            case STATUS_APPROVED:
                approveRequest(client, request);
                break;
            case STATUS_DECLINED:
                declineRequest(request);
                break;
        }
    }

    private boolean shouldShowConfirmationDialog(LogAccessClient client) {
        // If the process is foreground, show a dialog for user consent
        final int procState = mActivityManagerInternal.getUidProcessState(client.mUid);
        return procState == ActivityManager.PROCESS_STATE_TOP;
    }

    private void processNewLogAccessRequest(LogAccessClient client) {
        boolean isInstrumented = mActivityManagerInternal.getInstrumentationSourceUid(client.mUid)
                != android.os.Process.INVALID_UID;

        // The instrumented apks only run for testing, so we don't check user permission.
        if (isInstrumented) {
            onAccessApprovedForClient(client);
            return;
        }

        if (!shouldShowConfirmationDialog(client)) {
            onAccessDeclinedForClient(client);
            return;
        }

        final LogAccessStatus logAccessStatus = mLogAccessStatus.get(client);
        logAccessStatus.mStatus = STATUS_PENDING;

        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_PENDING_TIMEOUT, client),
                mClock.get() + PENDING_CONFIRMATION_TIMEOUT_MILLIS);
        final Intent mIntent = createIntent(client);
        mContext.startActivityAsUser(mIntent, UserHandle.SYSTEM);
    }

    void onAccessApprovedForClient(LogAccessClient client) {
        scheduleStatusExpiry(client);

        LogAccessStatus logAccessStatus = mLogAccessStatus.get(client);
        if (logAccessStatus != null) {
            for (LogAccessRequest request : logAccessStatus.mPendingRequests) {
                approveRequest(client, request);
            }
            logAccessStatus.mStatus = STATUS_APPROVED;
            logAccessStatus.mPendingRequests.clear();
        }
    }

    void onAccessDeclinedForClient(LogAccessClient client) {
        scheduleStatusExpiry(client);

        LogAccessStatus logAccessStatus = mLogAccessStatus.get(client);
        if (logAccessStatus != null) {
            for (LogAccessRequest request : logAccessStatus.mPendingRequests) {
                declineRequest(request);
            }
            logAccessStatus.mStatus = STATUS_DECLINED;
            logAccessStatus.mPendingRequests.clear();
        }
    }

    private void scheduleStatusExpiry(LogAccessClient client) {
        mHandler.removeMessages(MSG_PENDING_TIMEOUT, client);
        mHandler.removeMessages(MSG_LOG_ACCESS_STATUS_EXPIRED, client);
        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_LOG_ACCESS_STATUS_EXPIRED, client),
                mClock.get() + STATUS_EXPIRATION_TIMEOUT_MILLIS);
    }

    void onPendingTimeoutExpired(LogAccessClient client) {
        final LogAccessStatus logAccessStatus = mLogAccessStatus.get(client);
        if (logAccessStatus != null && logAccessStatus.mStatus == STATUS_PENDING) {
            onAccessDeclinedForClient(client);
        }
    }

    void onAccessStatusExpired(LogAccessClient client) {
        if (DEBUG) {
            Slog.d(TAG, "Log access status expired for " + client);
        }
        mLogAccessStatus.remove(client);
    }

    void onLogAccessFinished(LogAccessRequest request) {
        final LogAccessClient client = getClientForRequest(request);
        final int activeCount = mActiveLogAccessCount.getOrDefault(client, 1) - 1;

        if (activeCount == 0) {
            mActiveLogAccessCount.remove(client);
            if (DEBUG) {
                Slog.d(TAG, "Client is no longer accessing logs: " + client);
            }
            // TODO This will be used to notify the AppOpsManager that the logd data access
            // is finished.
        } else {
            mActiveLogAccessCount.put(client, activeCount);
        }
    }

    private void approveRequest(LogAccessClient client, LogAccessRequest request) {
        if (DEBUG) {
            Slog.d(TAG, "Approving log access: " + request);
        }
        try {
            getLogdService().approve(request.mUid, request.mGid, request.mPid, request.mFd);
            Integer activeCount = mActiveLogAccessCount.getOrDefault(client, 0);
            mActiveLogAccessCount.put(client, activeCount + 1);
        } catch (RemoteException e) {
            Slog.e(TAG, "Fails to call remote functions", e);
        }
    }

    private void declineRequest(LogAccessRequest request) {
        if (DEBUG) {
            Slog.d(TAG, "Declining log access: " + request);
        }
        try {
            getLogdService().decline(request.mUid, request.mGid, request.mPid, request.mFd);
        } catch (RemoteException e) {
            Slog.e(TAG, "Fails to call remote functions", e);
        }
    }

    /**
     * Create the Intent for LogAccessDialogActivity.
     */
    public Intent createIntent(LogAccessClient client) {
        final Intent intent = new Intent(mContext, LogAccessDialogActivity.class);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, client.mPackageName);
        intent.putExtra(Intent.EXTRA_UID, client.mUid);

        return intent;
    }
}
