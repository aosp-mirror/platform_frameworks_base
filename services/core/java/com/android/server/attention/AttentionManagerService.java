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

import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;
import static android.service.attention.AttentionService.ATTENTION_FAILURE_CANCELLED;
import static android.service.attention.AttentionService.ATTENTION_FAILURE_UNKNOWN;

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
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.attention.AttentionService;
import android.service.attention.AttentionService.AttentionFailureCodes;
import android.service.attention.AttentionService.AttentionSuccessCodes;
import android.service.attention.IAttentionCallback;
import android.service.attention.IAttentionService;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * An attention service implementation that runs in System Server process.
 * This service publishes a LocalService and reroutes calls to a {@link AttentionService} that it
 * manages.
 */
public class AttentionManagerService extends SystemService {
    private static final String LOG_TAG = "AttentionManagerService";
    private static final boolean DEBUG = false;

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;

    /** Service will unbind if connection is not used for that amount of time. */
    private static final long CONNECTION_TTL_MILLIS = 60_000;

    /** If the check attention called within that period - cached value will be returned. */
    private static final long STALE_AFTER_MILLIS = 5_000;

    /** DeviceConfig flag name, if {@code true}, enables AttentionManagerService features. */
    private static final String SERVICE_ENABLED = "service_enabled";
    private static String sTestAttentionServicePackage;
    private final Context mContext;
    private final PowerManager mPowerManager;
    private final Object mLock;
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    private AttentionHandler mAttentionHandler;

    @VisibleForTesting
    ComponentName mComponentName;

    public AttentionManagerService(Context context) {
        this(context, (PowerManager) context.getSystemService(Context.POWER_SERVICE),
                new Object(), null);
        mAttentionHandler = new AttentionHandler();
    }

    @VisibleForTesting
    AttentionManagerService(Context context, PowerManager powerManager, Object lock,
            AttentionHandler handler) {
        super(context);
        mContext = Preconditions.checkNotNull(context);
        mPowerManager = powerManager;
        mLock = lock;
        mAttentionHandler = handler;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mContext.registerReceiver(new ScreenStateReceiver(),
                    new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.ATTENTION_SERVICE, new BinderService());
        publishLocalService(AttentionManagerInternal.class, new LocalService());
    }

    @Override
    public void onSwitchUser(int userId) {
        cancelAndUnbindLocked(peekUserStateLocked(userId));
    }

    /** Returns {@code true} if attention service is configured on this device. */
    public static boolean isServiceConfigured(Context context) {
        return !TextUtils.isEmpty(getServiceConfigPackage(context));
    }

    /** Resolves and sets up the attention service if it had not been done yet. */
    private boolean isServiceAvailable() {
        if (mComponentName == null) {
            mComponentName = resolveAttentionService(mContext);
        }
        return mComponentName != null;
    }

    /**
     * Returns {@code true} if attention service is supported on this device.
     */
    private boolean isAttentionServiceSupported() {
        return isServiceEnabled() && isServiceAvailable();
    }

    @VisibleForTesting
    protected boolean isServiceEnabled() {
        return DeviceConfig.getBoolean(NAMESPACE_ATTENTION_MANAGER_SERVICE, SERVICE_ENABLED,
                DEFAULT_SERVICE_ENABLED);
    }

    /**
     * Checks whether user attention is at the screen and calls in the provided callback.
     *
     * Calling this multiple times quickly in a row will result in either a) returning a cached
     * value, if present, or b) returning {@code false} because only one active request at a time is
     * allowed.
     *
     * @return {@code true} if the framework was able to dispatch the request
     */
    @VisibleForTesting
    boolean checkAttention(long timeout, AttentionCallbackInternal callbackInternal) {
        Preconditions.checkNotNull(callbackInternal);

        if (!isAttentionServiceSupported()) {
            Slog.w(LOG_TAG, "Trying to call checkAttention() on an unsupported device.");
            return false;
        }

        // don't allow attention check in screen off state
        if (!mPowerManager.isInteractive()) {
            return false;
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            // schedule shutting down the connection if no one resets this timer
            freeIfInactiveLocked();

            final UserState userState = getOrCreateCurrentUserStateLocked();
            // lazily start the service, which should be very lightweight to start
            userState.bindLocked();

            // throttle frequent requests
            final AttentionCheckCache cache = userState.mAttentionCheckCache;
            if (cache != null && now < cache.mLastComputed + STALE_AFTER_MILLIS) {
                callbackInternal.onSuccess(cache.mResult, cache.mTimestamp);
                return true;
            }

            // prevent spamming with multiple requests, only one at a time is allowed
            if (userState.mCurrentAttentionCheck != null) {
                if (!userState.mCurrentAttentionCheck.mIsDispatched
                        || !userState.mCurrentAttentionCheck.mIsFulfilled) {
                    return false;
                }
            }

            userState.mCurrentAttentionCheck = createAttentionCheck(callbackInternal, userState);

            if (userState.mService != null) {
                try {
                    // schedule request cancellation if not returned by that point yet
                    cancelAfterTimeoutLocked(timeout);
                    userState.mService.checkAttention(
                            userState.mCurrentAttentionCheck.mIAttentionCallback);
                    userState.mCurrentAttentionCheck.mIsDispatched = true;
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Cannot call into the AttentionService");
                    return false;
                }
            }
            return true;
        }
    }

    private AttentionCheck createAttentionCheck(AttentionCallbackInternal callbackInternal,
            UserState userState) {
        final IAttentionCallback iAttentionCallback = new IAttentionCallback.Stub() {
            @Override
            public void onSuccess(@AttentionSuccessCodes int result, long timestamp) {
                // the callback might have been cancelled already
                if (!userState.mCurrentAttentionCheck.mIsFulfilled) {
                    callbackInternal.onSuccess(result, timestamp);
                    userState.mCurrentAttentionCheck.mIsFulfilled = true;
                }

                synchronized (mLock) {
                    userState.mAttentionCheckCache = new AttentionCheckCache(
                            SystemClock.uptimeMillis(), result,
                            timestamp);
                }
                StatsLog.write(
                        StatsLog.ATTENTION_MANAGER_SERVICE_RESULT_REPORTED,
                        result);
            }

            @Override
            public void onFailure(@AttentionFailureCodes int error) {
                // the callback might have been cancelled already
                if (!userState.mCurrentAttentionCheck.mIsFulfilled) {
                    callbackInternal.onFailure(error);
                    userState.mCurrentAttentionCheck.mIsFulfilled = true;
                }

                StatsLog.write(
                        StatsLog.ATTENTION_MANAGER_SERVICE_RESULT_REPORTED,
                        error);
            }
        };

        return new AttentionCheck(callbackInternal, iAttentionCallback);
    }

    /** Cancels the specified attention check. */
    @VisibleForTesting
    void cancelAttentionCheck(AttentionCallbackInternal callbackInternal) {
        synchronized (mLock) {
            final UserState userState = peekCurrentUserStateLocked();
            if (userState == null) {
                return;
            }
            if (!userState.mCurrentAttentionCheck.mCallbackInternal.equals(callbackInternal)) {
                Slog.w(LOG_TAG, "Cannot cancel a non-current request");
                return;
            }
            cancel(userState);
        }
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    protected void freeIfInactiveLocked() {
        // If we are called here, it means someone used the API again - reset the timer then.
        mAttentionHandler.removeMessages(AttentionHandler.CHECK_CONNECTION_EXPIRATION);

        // Schedule resources cleanup if no one calls the API again.
        mAttentionHandler.sendEmptyMessageDelayed(AttentionHandler.CHECK_CONNECTION_EXPIRATION,
                CONNECTION_TTL_MILLIS);
    }

    @GuardedBy("mLock")
    private void cancelAfterTimeoutLocked(long timeout) {
        mAttentionHandler.sendEmptyMessageDelayed(AttentionHandler.ATTENTION_CHECK_TIMEOUT,
                timeout);
    }


    @GuardedBy("mLock")
    @VisibleForTesting
    protected UserState getOrCreateCurrentUserStateLocked() {
        return getOrCreateUserStateLocked(ActivityManager.getCurrentUser());
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    protected UserState getOrCreateUserStateLocked(int userId) {
        UserState result = mUserStates.get(userId);
        if (result == null) {
            result = new UserState(userId, mContext, mLock, mAttentionHandler, mComponentName);
            mUserStates.put(userId, result);
        }
        return result;
    }

    @GuardedBy("mLock")
    @Nullable
    @VisibleForTesting
    protected UserState peekCurrentUserStateLocked() {
        return peekUserStateLocked(ActivityManager.getCurrentUser());
    }

    @GuardedBy("mLock")
    @Nullable
    private UserState peekUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    private static String getServiceConfigPackage(Context context) {
        return context.getPackageManager().getAttentionServicePackageName();
    }

    /**
     * Provides attention service component name at runtime, making sure it's provided by the
     * system.
     */
    private static ComponentName resolveAttentionService(Context context) {
        final String serviceConfigPackage = getServiceConfigPackage(context);

        String resolvedPackage;
        int flags = PackageManager.MATCH_SYSTEM_ONLY;
        if (!TextUtils.isEmpty(sTestAttentionServicePackage)) {
            resolvedPackage = sTestAttentionServicePackage;
            flags = PackageManager.GET_META_DATA;
        } else if (!TextUtils.isEmpty(serviceConfigPackage)) {
            resolvedPackage = serviceConfigPackage;
        } else {
            return null;
        }

        final Intent intent = new Intent(AttentionService.SERVICE_INTERFACE).setPackage(
                resolvedPackage);

        final ResolveInfo resolveInfo = context.getPackageManager().resolveService(intent, flags);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.wtf(LOG_TAG, String.format("Service %s not found in package %s",
                    AttentionService.SERVICE_INTERFACE, serviceConfigPackage
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

    private void dumpInternal(IndentingPrintWriter ipw) {
        ipw.println("Attention Manager Service (dumpsys attention) state:\n");
        ipw.println("isServiceEnabled=" + isServiceEnabled());
        ipw.println("AttentionServicePackageName=" + getServiceConfigPackage(mContext));
        ipw.println("Resolved component:");
        if (mComponentName != null) {
            ipw.increaseIndent();
            ipw.println("Component=" + mComponentName.getPackageName());
            ipw.println("Class=" + mComponentName.getClassName());
            ipw.decreaseIndent();
        }

        synchronized (mLock) {
            int size = mUserStates.size();
            ipw.print("Number user states: ");
            ipw.println(size);
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
        public boolean checkAttention(long timeout, AttentionCallbackInternal callbackInternal) {
            return AttentionManagerService.this.checkAttention(timeout, callbackInternal);
        }

        @Override
        public void cancelAttentionCheck(AttentionCallbackInternal callbackInternal) {
            AttentionManagerService.this.cancelAttentionCheck(callbackInternal);
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

    @VisibleForTesting
    static final class AttentionCheck {
        private final AttentionCallbackInternal mCallbackInternal;
        private final IAttentionCallback mIAttentionCallback;
        private boolean mIsDispatched;
        private boolean mIsFulfilled;

        AttentionCheck(AttentionCallbackInternal callbackInternal,
                IAttentionCallback iAttentionCallback) {
            mCallbackInternal = callbackInternal;
            mIAttentionCallback = iAttentionCallback;
        }

        void cancelInternal() {
            mIsFulfilled = true;
            mCallbackInternal.onFailure(ATTENTION_FAILURE_CANCELLED);
        }
    }

    @VisibleForTesting
    protected static class UserState {
        private final ComponentName mComponentName;
        private final AttentionServiceConnection mConnection = new AttentionServiceConnection();

        @GuardedBy("mLock")
        IAttentionService mService;
        @GuardedBy("mLock")
        AttentionCheck mCurrentAttentionCheck;
        @GuardedBy("mLock")
        AttentionCheckCache mAttentionCheckCache;
        @GuardedBy("mLock")
        private boolean mBinding;

        @UserIdInt
        private final int mUserId;
        private final Context mContext;
        private final Object mLock;
        private final Handler mAttentionHandler;

        UserState(int userId, Context context, Object lock, Handler handler,
                ComponentName componentName) {
            mUserId = userId;
            mContext = Preconditions.checkNotNull(context);
            mLock = Preconditions.checkNotNull(lock);
            mComponentName = Preconditions.checkNotNull(componentName);
            mAttentionHandler = handler;
        }

        @GuardedBy("mLock")
        private void handlePendingCallbackLocked() {
            if (!mCurrentAttentionCheck.mIsDispatched) {
                if (mService != null) {
                    try {
                        mService.checkAttention(mCurrentAttentionCheck.mIAttentionCallback);
                        mCurrentAttentionCheck.mIsDispatched = true;
                    } catch (RemoteException e) {
                        Slog.e(LOG_TAG, "Cannot call into the AttentionService");
                    }
                } else {
                    mCurrentAttentionCheck.mCallbackInternal.onFailure(ATTENTION_FAILURE_UNKNOWN);
                }
            }
        }

        /** Binds to the system's AttentionService which provides an actual implementation. */
        @GuardedBy("mLock")
        private void bindLocked() {
            // No need to bind if service is binding or has already been bound.
            if (mBinding || mService != null) {
                return;
            }

            mBinding = true;
            // mContext.bindServiceAsUser() calls into ActivityManagerService which it may already
            // hold the lock and had called into PowerManagerService, which holds a lock.
            // That would create a deadlock. To solve that, putting it on a handler.
            mAttentionHandler.post(() -> {
                final Intent serviceIntent = new Intent(
                        AttentionService.SERVICE_INTERFACE).setComponent(
                        mComponentName);
                // Note: no reason to clear the calling identity, we won't have one in a handler.
                mContext.bindServiceAsUser(serviceIntent, mConnection,
                        Context.BIND_AUTO_CREATE, UserHandle.CURRENT);

            });
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println("userId=" + mUserId);
            synchronized (mLock) {
                pw.println("binding=" + mBinding);
                pw.println("current attention check:");
                if (mCurrentAttentionCheck != null) {
                    pw.increaseIndent();
                    pw.println("is dispatched=" + mCurrentAttentionCheck.mIsDispatched);
                    pw.println("is fulfilled:=" + mCurrentAttentionCheck.mIsFulfilled);
                    pw.decreaseIndent();
                }
                pw.println("attention check cache:");
                if (mAttentionCheckCache != null) {
                    pw.increaseIndent();
                    pw.println("last computed=" + mAttentionCheckCache.mLastComputed);
                    pw.println("timestamp=" + mAttentionCheckCache.mTimestamp);
                    pw.println("result=" + mAttentionCheckCache.mResult);
                    pw.decreaseIndent();
                }
            }
        }

        private class AttentionServiceConnection implements ServiceConnection {
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

    @VisibleForTesting
    protected class AttentionHandler extends Handler {
        private static final int CHECK_CONNECTION_EXPIRATION = 1;
        private static final int ATTENTION_CHECK_TIMEOUT = 2;

        AttentionHandler() {
            super(Looper.myLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Do not occupy resources when not in use - unbind proactively.
                case CHECK_CONNECTION_EXPIRATION: {
                    for (int i = 0; i < mUserStates.size(); i++) {
                        cancelAndUnbindLocked(mUserStates.valueAt(i));
                    }
                }
                break;

                // Callee is no longer interested in the attention check result - cancel.
                case ATTENTION_CHECK_TIMEOUT: {
                    synchronized (mLock) {
                        cancel(peekCurrentUserStateLocked());
                    }
                }
                break;

                default:
                    break;
            }
        }
    }

    @VisibleForTesting
    void cancel(UserState userState) {
        if (userState == null || userState.mCurrentAttentionCheck == null) {
            return;
        }

        if (userState.mCurrentAttentionCheck.mIsFulfilled) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "Trying to cancel the check that has been already fulfilled.");
            }
            return;
        }

        if (userState.mService == null) {
            userState.mCurrentAttentionCheck.cancelInternal();
            return;
        }

        try {
            userState.mService.cancelAttentionCheck(
                    userState.mCurrentAttentionCheck.mIAttentionCallback);
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Unable to cancel attention check");
            userState.mCurrentAttentionCheck.cancelInternal();
        }
    }

    @GuardedBy("mLock")
    private void cancelAndUnbindLocked(UserState userState) {
        synchronized (mLock) {
            if (userState == null) {
                return;
            }

            cancel(userState);

            if (userState.mService == null) {
                return;
            }

            mAttentionHandler.post(() -> mContext.unbindService(userState.mConnection));
            // Note: this will set mBinding to false even though it could still be trying to bind
            // (i.e. the runnable was posted in bindLocked but then cancelAndUnbindLocked was
            // called before it's run yet). This is a safe state at the moment,
            // since it will eventually, but feels like a source for confusion down the road and
            // may cause some expensive and unnecessary work to be done.
            userState.mConnection.cleanupService();
            mUserStates.remove(userState.mUserId);
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
                cancelAndUnbindLocked(peekCurrentUserStateLocked());
            }
        }
    }

    private final class AttentionManagerServiceShellCommand extends ShellCommand {
        class TestableAttentionCallbackInternal extends AttentionCallbackInternal {
            private int mLastCallbackCode = -1;

            @Override
            public void onSuccess(int result, long timestamp) {
                mLastCallbackCode = result;
            }

            @Override
            public void onFailure(int error) {
                mLastCallbackCode = error;
            }

            public void reset() {
                mLastCallbackCode = -1;
            }

            public int getLastCallbackCode() {
                return mLastCallbackCode;
            }
        }

        final TestableAttentionCallbackInternal mTestableAttentionCallback =
                new TestableAttentionCallbackInternal();

        @Override
        public int onCommand(@Nullable final String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter err = getErrPrintWriter();
            try {
                switch (cmd) {
                    case "getAttentionServiceComponent":
                        return cmdResolveAttentionServiceComponent();
                    case "call":
                        switch (getNextArgRequired()) {
                            case "checkAttention":
                                return cmdCallCheckAttention();
                            case "cancelCheckAttention":
                                return cmdCallCancelAttention();
                            default:
                                throw new IllegalArgumentException("Invalid argument");
                        }
                    case "setTestableAttentionService":
                        return cmdSetTestableAttentionService(getNextArgRequired());
                    case "clearTestableAttentionService":
                        return cmdClearTestableAttentionService();
                    case "getLastTestCallbackCode":
                        return cmdGetLastTestCallbackCode();
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (IllegalArgumentException e) {
                err.println("Error: " + e.getMessage());
            }
            return -1;
        }

        private int cmdSetTestableAttentionService(String testingServicePackage) {
            final PrintWriter out = getOutPrintWriter();
            if (TextUtils.isEmpty(testingServicePackage)) {
                out.println("false");
            } else {
                sTestAttentionServicePackage = testingServicePackage;
                resetStates();
                out.println(mComponentName != null ? "true" : "false");
            }
            return 0;
        }

        private int cmdClearTestableAttentionService() {
            sTestAttentionServicePackage = "";
            mTestableAttentionCallback.reset();
            resetStates();
            return 0;
        }

        private int cmdCallCheckAttention() {
            final PrintWriter out = getOutPrintWriter();
            boolean calledSuccessfully = checkAttention(2000, mTestableAttentionCallback);
            out.println(calledSuccessfully ? "true" : "false");
            return 0;
        }

        private int cmdCallCancelAttention() {
            final PrintWriter out = getOutPrintWriter();
            cancelAttentionCheck(mTestableAttentionCallback);
            out.println("true");
            return 0;
        }

        private int cmdResolveAttentionServiceComponent() {
            final PrintWriter out = getOutPrintWriter();
            ComponentName resolvedComponent = resolveAttentionService(mContext);
            out.println(resolvedComponent != null ? resolvedComponent.flattenToShortString() : "");
            return 0;
        }

        private int cmdGetLastTestCallbackCode() {
            final PrintWriter out = getOutPrintWriter();
            out.println(mTestableAttentionCallback.getLastCallbackCode());
            return 0;
        }

        private void resetStates() {
            mComponentName = resolveAttentionService(mContext);
            mUserStates.clear();
        }

        @Override
        public void onHelp() {
            final PrintWriter out = getOutPrintWriter();
            out.println("Attention commands: ");
            out.println("  setTestableAttentionService <service_package>: Bind to a custom"
                    + " implementation of attention service");
            out.println("  ---<service_package>:");
            out.println(
                    "       := Package containing the Attention Service implementation to bind to");
            out.println("  ---returns:");
            out.println("       := true, if was bound successfully");
            out.println("       := false, if was not bound successfully");
            out.println("  clearTestableAttentionService: Undo custom bindings. Revert to previous"
                    + " behavior");
            out.println("  getAttentionServiceComponent: Get the current service component string");
            out.println("  ---returns:");
            out.println("       := If valid, the component string (in shorten form) for the"
                    + " currently bound service.");
            out.println("       := else, empty string");
            out.println("  call checkAttention: Calls check attention");
            out.println("  ---returns:");
            out.println(
                    "       := true, if the call was successfully dispatched to the service "
                            + "implementation."
                            + " (to see the result, call getLastTestCallbackCode)");
            out.println("       := false, otherwise");
            out.println("  call cancelCheckAttention: Cancels check attention");
            out.println("  getLastTestCallbackCode");
            out.println("  ---returns:");
            out.println(
                    "       := An integer, representing the last callback code received from the "
                            + "bounded implementation. If none, it will return -1");
        }
    }

    private final class BinderService extends Binder {
        AttentionManagerServiceShellCommand mAttentionManagerServiceShellCommand =
                new AttentionManagerServiceShellCommand();

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err,
                String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            mAttentionManagerServiceShellCommand.exec(this, in, out, err, args, callback,
                    resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) {
                return;
            }

            dumpInternal(new IndentingPrintWriter(pw, "  "));
        }
    }
}
