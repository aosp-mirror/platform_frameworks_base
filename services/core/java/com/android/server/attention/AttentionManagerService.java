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

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;
import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;
import static android.service.attention.AttentionService.ATTENTION_FAILURE_CANCELLED;
import static android.service.attention.AttentionService.ATTENTION_FAILURE_UNKNOWN;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
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
import android.hardware.SensorPrivacyManager;
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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * An attention service implementation that runs in System Server process.
 * This service publishes a LocalService and reroutes calls to a {@link AttentionService} that it
 * manages.
 */
public class AttentionManagerService extends SystemService {
    private static final String LOG_TAG = "AttentionManagerService";
    private static final boolean DEBUG = false;

    /** Service will unbind if connection is not used for that amount of time. */
    private static final long CONNECTION_TTL_MILLIS = 60_000;

    /** DeviceConfig flag name, if {@code true}, enables AttentionManagerService features. */
    @VisibleForTesting
    static final String KEY_SERVICE_ENABLED = "service_enabled";

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;

    @VisibleForTesting
    boolean mIsServiceEnabled;

    /**
     * DeviceConfig flag name, describes how much time we consider a result fresh; if the check
     * attention called within that period - cached value will be returned.
     */
    @VisibleForTesting
    static final String KEY_STALE_AFTER_MILLIS = "stale_after_millis";

    /** Default value in absence of {@link DeviceConfig} override. */
    @VisibleForTesting
    static final long DEFAULT_STALE_AFTER_MILLIS = 1_000;

    @VisibleForTesting
    long mStaleAfterMillis;

    /** The size of the buffer that stores recent attention check results. */
    @VisibleForTesting
    protected static final int ATTENTION_CACHE_BUFFER_SIZE = 5;

    private final AttentionServiceConnection mConnection = new AttentionServiceConnection();
    private static String sTestAttentionServicePackage;
    private final Context mContext;
    private final PowerManager mPowerManager;
    private final SensorPrivacyManager mPrivacyManager;
    private final Object mLock;
    @GuardedBy("mLock")
    @VisibleForTesting
    protected IAttentionService mService;
    @GuardedBy("mLock")
    private AttentionCheckCacheBuffer mAttentionCheckCacheBuffer;
    @GuardedBy("mLock")
    private boolean mBinding;
    private AttentionHandler mAttentionHandler;

    @VisibleForTesting
    ComponentName mComponentName;

    @VisibleForTesting
    @GuardedBy("mLock")
    AttentionCheck mCurrentAttentionCheck;

    public AttentionManagerService(Context context) {
        this(context, (PowerManager) context.getSystemService(Context.POWER_SERVICE),
                new Object(), null);
        mAttentionHandler = new AttentionHandler();
    }

    @VisibleForTesting
    AttentionManagerService(Context context, PowerManager powerManager, Object lock,
            AttentionHandler handler) {
        super(context);
        mContext = Objects.requireNonNull(context);
        mPowerManager = powerManager;
        mLock = lock;
        mAttentionHandler = handler;
        mPrivacyManager = SensorPrivacyManager.getInstance(context);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mContext.registerReceiver(new ScreenStateReceiver(),
                    new IntentFilter(Intent.ACTION_SCREEN_OFF));

            readValuesFromDeviceConfig();
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                    ActivityThread.currentApplication().getMainExecutor(),
                    (properties) -> onDeviceConfigChange(properties.getKeyset()));
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.ATTENTION_SERVICE, new BinderService());
        publishLocalService(AttentionManagerInternal.class, new LocalService());
    }

    /** Returns {@code true} if attention service is configured on this device. */
    public static boolean isServiceConfigured(Context context) {
        return !TextUtils.isEmpty(getServiceConfigPackage(context));
    }

    /** Resolves and sets up the attention service if it had not been done yet. */
    @VisibleForTesting
    protected boolean isServiceAvailable() {
        if (mComponentName == null) {
            mComponentName = resolveAttentionService(mContext);
        }
        return mComponentName != null;
    }

    private boolean getIsServiceEnabled() {
        return DeviceConfig.getBoolean(NAMESPACE_ATTENTION_MANAGER_SERVICE, KEY_SERVICE_ENABLED,
                DEFAULT_SERVICE_ENABLED);
    }

    /**
     * How much time we consider a result fresh; if the check attention called within that period -
     * cached value will be returned.
     */
    @VisibleForTesting
    protected long getStaleAfterMillis() {
        final long millis = DeviceConfig.getLong(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_STALE_AFTER_MILLIS,
                DEFAULT_STALE_AFTER_MILLIS);

        if (millis < 0 || millis > 10_000) {
            Slog.w(LOG_TAG, "Bad flag value supplied for: " + KEY_STALE_AFTER_MILLIS);
            return DEFAULT_STALE_AFTER_MILLIS;
        }

        return millis;
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        for (String key : keys) {
            switch (key) {
                case KEY_SERVICE_ENABLED:
                case KEY_STALE_AFTER_MILLIS:
                    readValuesFromDeviceConfig();
                    return;
                default:
                    Slog.i(LOG_TAG, "Ignoring change on " + key);
            }
        }
    }

    private void readValuesFromDeviceConfig() {
        mIsServiceEnabled = getIsServiceEnabled();
        mStaleAfterMillis = getStaleAfterMillis();

        Slog.i(LOG_TAG, "readValuesFromDeviceConfig():"
                + "\nmIsServiceEnabled=" + mIsServiceEnabled
                + "\nmStaleAfterMillis=" + mStaleAfterMillis);
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
        Objects.requireNonNull(callbackInternal);

        if (!mIsServiceEnabled) {
            Slog.w(LOG_TAG, "Trying to call checkAttention() on an unsupported device.");
            return false;
        }

        if (!isServiceAvailable()) {
            Slog.w(LOG_TAG, "Service is not available at this moment.");
            return false;
        }

        if (mPrivacyManager.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA)) {
            Slog.w(LOG_TAG, "Camera is locked by a toggle.");
            return false;
        }

        // don't allow attention check in screen off state or power save mode
        if (!mPowerManager.isInteractive() || mPowerManager.isPowerSaveMode()) {
            return false;
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            // schedule shutting down the connection if no one resets this timer
            freeIfInactiveLocked();

            // lazily start the service, which should be very lightweight to start
            bindLocked();

            // throttle frequent requests
            final AttentionCheckCache cache = mAttentionCheckCacheBuffer == null ? null
                    : mAttentionCheckCacheBuffer.getLast();
            if (cache != null && now < cache.mLastComputed + mStaleAfterMillis) {
                callbackInternal.onSuccess(cache.mResult, cache.mTimestamp);
                return true;
            }

            // prevent spamming with multiple requests, only one at a time is allowed
            if (mCurrentAttentionCheck != null) {
                if (!mCurrentAttentionCheck.mIsDispatched
                        || !mCurrentAttentionCheck.mIsFulfilled) {
                    return false;
                }
            }

            mCurrentAttentionCheck = new AttentionCheck(callbackInternal, this);

            if (mService != null) {
                try {
                    // schedule request cancellation if not returned by that point yet
                    cancelAfterTimeoutLocked(timeout);
                    mService.checkAttention(mCurrentAttentionCheck.mIAttentionCallback);
                    mCurrentAttentionCheck.mIsDispatched = true;
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Cannot call into the AttentionService");
                    return false;
                }
            }
            return true;
        }
    }

    /** Cancels the specified attention check. */
    @VisibleForTesting
    void cancelAttentionCheck(AttentionCallbackInternal callbackInternal) {
        synchronized (mLock) {
            if (!mCurrentAttentionCheck.mCallbackInternal.equals(callbackInternal)) {
                Slog.w(LOG_TAG, "Cannot cancel a non-current request");
                return;
            }
            cancel();
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
        ipw.println("isServiceEnabled=" + mIsServiceEnabled);
        ipw.println("mStaleAfterMillis=" + mStaleAfterMillis);
        ipw.println("AttentionServicePackageName=" + getServiceConfigPackage(mContext));
        ipw.println("Resolved component:");
        if (mComponentName != null) {
            ipw.increaseIndent();
            ipw.println("Component=" + mComponentName.getPackageName());
            ipw.println("Class=" + mComponentName.getClassName());
            ipw.decreaseIndent();
        }
        ipw.println("binding=" + mBinding);
        ipw.println("current attention check:");
        synchronized (mLock) {
            if (mCurrentAttentionCheck != null) {
                mCurrentAttentionCheck.dump(ipw);
            }
            if (mAttentionCheckCacheBuffer != null) {
                mAttentionCheckCacheBuffer.dump(ipw);
            }
        }
    }

    private final class LocalService extends AttentionManagerInternal {
        @Override
        public boolean isAttentionServiceSupported() {
            return AttentionManagerService.this.mIsServiceEnabled;
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

    @VisibleForTesting
    protected static final class AttentionCheckCacheBuffer {
        private final AttentionCheckCache[] mQueue;
        private int mStartIndex;
        private int mSize;

        AttentionCheckCacheBuffer() {
            mQueue = new AttentionCheckCache[ATTENTION_CACHE_BUFFER_SIZE];
            mStartIndex = 0;
            mSize = 0;
        }

        public AttentionCheckCache getLast() {
            int lastIdx = (mStartIndex + mSize - 1) % ATTENTION_CACHE_BUFFER_SIZE;
            return mSize == 0 ? null : mQueue[lastIdx];
        }

        public void add(@NonNull AttentionCheckCache cache) {
            int nextIndex = (mStartIndex + mSize) % ATTENTION_CACHE_BUFFER_SIZE;
            mQueue[nextIndex] = cache;
            if (mSize == ATTENTION_CACHE_BUFFER_SIZE) {
                mStartIndex++;
            } else {
                mSize++;
            }
        }

        public AttentionCheckCache get(int offset) {
            return offset >= mSize ? null
                    : mQueue[(mStartIndex + offset) % ATTENTION_CACHE_BUFFER_SIZE];
        }

        private void dump(IndentingPrintWriter ipw) {
            ipw.println("attention check cache:");
            AttentionCheckCache cache;
            for (int i = 0; i < mSize; i++) {
                cache = get(i);
                if (cache != null) {
                    ipw.increaseIndent();
                    ipw.println("timestamp=" + cache.mTimestamp);
                    ipw.println("result=" + cache.mResult);
                    ipw.decreaseIndent();
                }
            }
        }
    }

    @VisibleForTesting
    protected static final class AttentionCheckCache {
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
                AttentionManagerService service) {
            mCallbackInternal = callbackInternal;
            mIAttentionCallback = new IAttentionCallback.Stub() {
                @Override
                public void onSuccess(@AttentionSuccessCodes int result, long timestamp) {
                    if (mIsFulfilled) {
                        return;
                    }
                    mIsFulfilled = true;
                    callbackInternal.onSuccess(result, timestamp);
                    logStats(result);
                    service.appendResultToAttentionCacheBuffer(
                            new AttentionCheckCache(SystemClock.uptimeMillis(), result,
                                    timestamp));
                }

                @Override
                public void onFailure(@AttentionFailureCodes int error) {
                    if (mIsFulfilled) {
                        return;
                    }
                    mIsFulfilled = true;
                    callbackInternal.onFailure(error);
                    logStats(error);
                }

                private void logStats(int result) {
                    FrameworkStatsLog.write(
                            FrameworkStatsLog.ATTENTION_MANAGER_SERVICE_RESULT_REPORTED,
                            result);
                }
            };
        }

        void cancelInternal() {
            mIsFulfilled = true;
            mCallbackInternal.onFailure(ATTENTION_FAILURE_CANCELLED);
        }

        void dump(IndentingPrintWriter ipw) {
            ipw.increaseIndent();
            ipw.println("is dispatched=" + mIsDispatched);
            ipw.println("is fulfilled:=" + mIsFulfilled);
            ipw.decreaseIndent();
        }
    }

    private void appendResultToAttentionCacheBuffer(AttentionCheckCache cache) {
        synchronized (mLock) {
            if (mAttentionCheckCacheBuffer == null) {
                mAttentionCheckCacheBuffer = new AttentionCheckCacheBuffer();
            }
            mAttentionCheckCacheBuffer.add(cache);
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

    @GuardedBy("mLock")
    private void handlePendingCallbackLocked() {
        if (mCurrentAttentionCheck != null && !mCurrentAttentionCheck.mIsDispatched) {
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
                    cancelAndUnbindLocked();
                }
                break;

                // Callee is no longer interested in the attention check result - cancel.
                case ATTENTION_CHECK_TIMEOUT: {
                    synchronized (mLock) {
                        cancel();
                    }
                }
                break;

                default:
                    break;
            }
        }
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void cancel() {
        if (mCurrentAttentionCheck.mIsFulfilled) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "Trying to cancel the check that has been already fulfilled.");
            }
            return;
        }

        if (mService == null) {
            mCurrentAttentionCheck.cancelInternal();
            return;
        }

        try {
            mService.cancelAttentionCheck(mCurrentAttentionCheck.mIAttentionCallback);
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Unable to cancel attention check");
            mCurrentAttentionCheck.cancelInternal();
        }
    }

    @GuardedBy("mLock")
    private void cancelAndUnbindLocked() {
        synchronized (mLock) {
            if (mCurrentAttentionCheck == null) {
                return;
            }
            cancel();
            if (mService == null) {
                return;
            }
            mAttentionHandler.post(() -> mContext.unbindService(mConnection));
            // Note: this will set mBinding to false even though it could still be trying to bind
            // (i.e. the runnable was posted in bindLocked but then cancelAndUnbindLocked was
            // called before it's run yet). This is a safe state at the moment,
            // since it will eventually, but feels like a source for confusion down the road and
            // may cause some expensive and unnecessary work to be done.
            mConnection.cleanupService();
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
                    BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES,
                    UserHandle.CURRENT);

        });
    }

    /**
     * Unbinds and stops the service when the screen off intent is received.
     * Attention service only makes sense when screen is ON; disconnect and stop service otherwise.
     */
    private final class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelAndUnbindLocked();
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
