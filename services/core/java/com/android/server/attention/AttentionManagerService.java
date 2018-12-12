/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.attention;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.attention.AttentionManagerInternal;
import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.attention.AttentionService;
import android.service.attention.AttentionService.AttentionFailureCodes;
import android.service.attention.IAttentionCallback;
import android.service.attention.IAttentionService;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.PrintWriter;

/**
 * An attention service implementation that runs in System Server process.
 * This service publishes a LocalService and reroutes calls to a {@link AttentionService} that it
 * manages.
 */
public class AttentionManagerService extends SystemService {
    private static final String LOG_TAG = "AttentionManagerService";

    /** Service will unbind if connection is not used for that amount of time. */
    private static final long CONNECTION_TTL_MILLIS = 60_000;

    /** If the check attention called within that period - cached value will be returned. */
    private static final long STALE_AFTER_MILLIS = 5_000;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final ActivityManager mActivityManager;
    private final Object mLock;
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    private final AttentionHandler mAttentionHandler;

    private ComponentName mComponentName;

    public AttentionManagerService(Context context) {
        super(context);
        mContext = Preconditions.checkNotNull(context);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mLock = new Object();
        mAttentionHandler = new AttentionHandler();
    }

    @Override
    public void onStart() {
        publishLocalService(AttentionManagerInternal.class, new LocalService());
    }

    @Override
    public void onStopUser(int userId) {
        cancelAndUnbindLocked(peekUserStateLocked(userId),
                AttentionService.ATTENTION_FAILURE_UNKNOWN);
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            mComponentName = resolveAttentionService(mContext);
            if (mComponentName != null) {
                // If the service is supported we want to keep receiving the screen off events.
                mContext.registerReceiver(new ScreenStateReceiver(),
                        new IntentFilter(Intent.ACTION_SCREEN_OFF));
            }
        }
    }

    /**
     * Returns {@code true} if attention service is supported on this device.
     */
    public boolean isAttentionServiceSupported() {
        return mComponentName != null;
    }

    /**
     * Checks whether user attention is at the screen and calls in the provided callback.
     *
     * @return {@code true} if the framework was able to send the provided callback to the service
     */
    public boolean checkAttention(int requestCode, long timeout,
            AttentionCallbackInternal callback) {
        Preconditions.checkNotNull(callback);

        if (!isAttentionServiceSupported()) {
            Slog.w(LOG_TAG, "Trying to call checkAttention() on an unsupported device.");
            return false;
        }

        // don't allow attention check in screen off state
        if (!mPowerManager.isInteractive()) {
            return false;
        }

        synchronized (mLock) {
            unbindAfterTimeoutLocked();

            final UserState userState = getOrCreateCurrentUserStateLocked();
            // lazily start the service, which should be very lightweight to start
            if (!userState.bindLocked()) {
                return false;
            }

            if (userState.mService == null) {
                // make sure every callback is called back
                if (userState.mPendingAttentionCheck != null) {
                    userState.mPendingAttentionCheck.cancel(
                            AttentionService.ATTENTION_FAILURE_UNKNOWN);
                }
                userState.mPendingAttentionCheck = new PendingAttentionCheck(requestCode,
                        callback, () -> checkAttention(requestCode, timeout, callback));
            } else {
                try {
                    // throttle frequent requests
                    final AttentionCheckCache attentionCheckCache = userState.mAttentionCheckCache;
                    if (attentionCheckCache != null && SystemClock.uptimeMillis()
                            < attentionCheckCache.mLastComputed + STALE_AFTER_MILLIS) {
                        callback.onSuccess(requestCode, attentionCheckCache.mResult,
                                attentionCheckCache.mTimestamp);
                        return true;
                    }

                    cancelAfterTimeoutLocked(timeout);

                    userState.mCurrentAttentionCheckRequestCode = requestCode;
                    userState.mService.checkAttention(requestCode, new IAttentionCallback.Stub() {
                        @Override
                        public void onSuccess(int requestCode, int result, long timestamp) {
                            callback.onSuccess(requestCode, result, timestamp);
                            userState.mAttentionCheckCache = new AttentionCheckCache(
                                    SystemClock.uptimeMillis(), result,
                                    timestamp);
                        }

                        @Override
                        public void onFailure(int requestCode, int error) {
                            callback.onFailure(requestCode, error);
                        }

                        @Override
                        public IBinder asBinder() {
                            return null;
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Cannot call into the AttentionService");
                    return false;
                }
            }
            return true;
        }
    }

    /** Cancels the specified attention check. */
    public void cancelAttentionCheck(int requestCode) {
        final UserState userState = getOrCreateCurrentUserStateLocked();
        try {
            userState.mService.cancelAttentionCheck(requestCode);
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Cannot call into the AttentionService");
        }
    }

    @GuardedBy("mLock")
    private void unbindAfterTimeoutLocked() {
        mAttentionHandler.sendEmptyMessageDelayed(AttentionHandler.CONNECTION_EXPIRED,
                CONNECTION_TTL_MILLIS);
    }

    @GuardedBy("mLock")
    private void cancelAfterTimeoutLocked(long timeout) {
        mAttentionHandler.sendEmptyMessageDelayed(AttentionHandler.ATTENTION_CHECK_TIMEOUT,
                timeout);
    }


    @GuardedBy("mLock")
    private UserState getOrCreateCurrentUserStateLocked() {
        return getOrCreateUserStateLocked(mActivityManager.getCurrentUser());
    }

    @GuardedBy("mLock")
    private UserState getOrCreateUserStateLocked(int userId) {
        UserState result = mUserStates.get(userId);
        if (result == null) {
            result = new UserState(userId, mContext, mLock);
            mUserStates.put(userId, result);
        }
        return result;
    }

    @GuardedBy("mLock")
    UserState peekCurrentUserStateLocked() {
        return peekUserStateLocked(mActivityManager.getCurrentUser());
    }

    @GuardedBy("mLock")
    UserState peekUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    /**
     * Provides attention service component name at runtime, making sure it's provided by the
     * system.
     */
    private static ComponentName resolveAttentionService(Context context) {
        // TODO(b/111939367): add a flag to turn on/off.
        final String componentNameString = context.getString(
                R.string.config_defaultAttentionService);

        if (TextUtils.isEmpty(componentNameString)) {
            return null;
        }

        final ComponentName componentName = ComponentName.unflattenFromString(componentNameString);
        if (componentName == null) {
            return null;
        }

        final Intent intent = new Intent(AttentionService.SERVICE_INTERFACE).setPackage(
                componentName.getPackageName());

        // Make sure that only system apps can declare the AttentionService.
        final ResolveInfo resolveInfo = context.getPackageManager().resolveService(intent,
                PackageManager.MATCH_SYSTEM_ONLY);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.wtf(LOG_TAG, String.format("Service %s not found in package %s",
                    AttentionService.SERVICE_INTERFACE, componentName
            ));
            return null;
        }

        final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        final String permission = serviceInfo.permission;
        if (Manifest.permission.BIND_ATTENTION_SERVICE.equals(permission)) {
            return serviceInfo.getComponentName();
        }
        Slog.e(LOG_TAG, String.format(
                "Service %s should require %s permission. Found %s permission",
                serviceInfo.getComponentName(),
                Manifest.permission.BIND_ATTENTION_SERVICE,
                serviceInfo.permission));
        return null;
    }

    private void dumpInternal(PrintWriter pw) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("Attention Manager Service (dumpsys attention)\n");

        ipw.printPair("context", mContext);
        pw.println();
        synchronized (mLock) {
            int size = mUserStates.size();
            ipw.print("Number user states: ");
            pw.println(size);
            if (size > 0) {
                ipw.increaseIndent();
                for (int i = 0; i < size; i++) {
                    UserState userState = mUserStates.valueAt(i);
                    ipw.print(i);
                    ipw.print(":");
                    userState.dump(ipw);
                    ipw.println();
                }
                ipw.decreaseIndent();
            }
        }
    }

    private final class LocalService extends AttentionManagerInternal {
        @Override
        public boolean isAttentionServiceSupported() {
            return AttentionManagerService.this.isAttentionServiceSupported();
        }

        @Override
        public boolean checkAttention(int requestCode, long timeout,
                AttentionCallbackInternal callback) {
            return AttentionManagerService.this.checkAttention(requestCode, timeout, callback);
        }

        @Override
        public void cancelAttentionCheck(int requestCode) {
            AttentionManagerService.this.cancelAttentionCheck(requestCode);
        }
    }

    private static final class AttentionCheckCache {
        private final long mLastComputed;
        private final int mResult;
        private final long mTimestamp;

        AttentionCheckCache(long lastComputed, @AttentionService.AttentionSuccessCodes int result,
                long timestamp) {
            mLastComputed = lastComputed;
            mResult = result;
            mTimestamp = timestamp;
        }
    }

    private static final class PendingAttentionCheck {
        private final int mRequestCode;
        private final AttentionCallbackInternal mCallback;
        private final Runnable mRunnable;

        PendingAttentionCheck(int requestCode, AttentionCallbackInternal callback,
                Runnable runnable) {
            mRequestCode = requestCode;
            mCallback = callback;
            mRunnable = runnable;
        }

        void cancel(@AttentionFailureCodes int failureCode) {
            mCallback.onFailure(mRequestCode, failureCode);
        }

        void run() {
            mRunnable.run();
        }
    }

    private static final class UserState {
        final AttentionServiceConnection mConnection = new AttentionServiceConnection();

        @GuardedBy("mLock")
        IAttentionService mService;
        @GuardedBy("mLock")
        boolean mBinding;
        @GuardedBy("mLock")
        int mCurrentAttentionCheckRequestCode;
        @GuardedBy("mLock")
        PendingAttentionCheck mPendingAttentionCheck;

        @GuardedBy("mLock")
        AttentionCheckCache mAttentionCheckCache;

        @UserIdInt
        final int mUserId;
        final Context mContext;
        final Object mLock;

        private UserState(int userId, Context context, Object lock) {
            mUserId = userId;
            mContext = Preconditions.checkNotNull(context);
            mLock = Preconditions.checkNotNull(lock);
        }


        @GuardedBy("mLock")
        private void handlePendingCallbackLocked() {
            if (mService != null && mPendingAttentionCheck != null) {
                mPendingAttentionCheck.run();
                mPendingAttentionCheck = null;
            }
        }

        /** Binds to the system's AttentionService which provides an actual implementation. */
        @GuardedBy("mLock")
        private boolean bindLocked() {
            // No need to bind if service is binding or has already been bound.
            if (mBinding || mService != null) {
                return true;
            }

            final boolean willBind;
            final long identity = Binder.clearCallingIdentity();

            try {
                final ComponentName componentName =
                        resolveAttentionService(mContext);
                if (componentName == null) {
                    // Might happen if the storage is encrypted and the user is not unlocked
                    return false;
                }
                final Intent mServiceIntent = new Intent(
                        AttentionService.SERVICE_INTERFACE).setComponent(
                        componentName);
                willBind = mContext.bindServiceAsUser(mServiceIntent, mConnection,
                        Context.BIND_AUTO_CREATE, UserHandle.CURRENT);
                mBinding = willBind;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return willBind;
        }

        private void dump(IndentingPrintWriter pw) {
            pw.printPair("context", mContext);
            pw.printPair("userId", mUserId);
            synchronized (mLock) {
                pw.printPair("binding", mBinding);
                pw.printPair("isAttentionCheckPending", mPendingAttentionCheck != null);
            }
        }

        private final class AttentionServiceConnection implements ServiceConnection {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                init(IAttentionService.Stub.asInterface(service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                cleanupService();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                cleanupService();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                cleanupService();
            }

            void cleanupService() {
                init(null);
            }

            private void init(@Nullable IAttentionService service) {
                synchronized (mLock) {
                    mService = service;
                    mBinding = false;
                    handlePendingCallbackLocked();
                }
            }
        }
    }

    private class AttentionHandler extends Handler {
        private static final int CONNECTION_EXPIRED = 1;
        private static final int ATTENTION_CHECK_TIMEOUT = 2;

        AttentionHandler() {
            super(Looper.myLooper());
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Do not occupy resources when not in use - unbind proactively.
                case CONNECTION_EXPIRED: {
                    for (int i = 0; i < mUserStates.size(); i++) {
                        cancelAndUnbindLocked(mUserStates.valueAt(i),
                                AttentionService.ATTENTION_FAILURE_UNKNOWN);
                    }

                }
                break;

                // Callee is no longer interested in the attention check result - cancel.
                case ATTENTION_CHECK_TIMEOUT: {
                    cancelAndUnbindLocked(peekCurrentUserStateLocked(),
                            AttentionService.ATTENTION_FAILURE_TIMED_OUT);
                }
                break;

                default:
                    break;
            }
        }
    }

    @GuardedBy("mLock")
    private void cancelAndUnbindLocked(UserState userState,
            @AttentionFailureCodes int failureCode) {
        synchronized (mLock) {
            if (userState != null && userState.mService != null) {
                try {
                    userState.mService.cancelAttentionCheck(
                            userState.mCurrentAttentionCheckRequestCode);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Unable to cancel attention check");
                }

                if (userState.mPendingAttentionCheck != null) {
                    userState.mPendingAttentionCheck.cancel(failureCode);
                }
                mContext.unbindService(userState.mConnection);
                userState.mConnection.cleanupService();
                mUserStates.remove(userState.mUserId);
            }
        }
    }

    /**
     * Unbinds and stops the service when the screen off intent is received.
     * Attention service only makes sense when screen is ON; disconnect and stop service otherwise.
     */
    private final class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelAndUnbindLocked(peekCurrentUserStateLocked(),
                        AttentionService.ATTENTION_FAILURE_UNKNOWN);
            }
        }
    }
}
