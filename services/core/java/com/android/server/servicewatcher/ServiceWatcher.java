/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.servicewatcher;

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_NOT_FOREGROUND;
import static android.content.Context.BIND_NOT_VISIBLE;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.BoolRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.Immutable;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Maintains a binding to the best service that matches the given intent information. Bind and
 * unbind callbacks, as well as all binder operations, will all be run on a single thread.
 */
public class ServiceWatcher implements ServiceConnection {

    private static final String TAG = "ServiceWatcher";
    private static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    private static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";

    private static final long RETRY_DELAY_MS = 15 * 1000;

    private static final Predicate<ResolveInfo> DEFAULT_SERVICE_CHECK_PREDICATE = x -> true;

    /** Function to run on binder interface. */
    public interface BinderRunner {
        /** Called to run client code with the binder. */
        void run(IBinder binder) throws RemoteException;
        /**
         * Called if an error occurred and the function could not be run. This callback is only
         * intended for resource deallocation and cleanup in response to a single binder operation,
         * it should not be used to propagate errors further.
         */
        default void onError() {}
    }

    /** Function to run on binder interface when first bound. */
    public interface OnBindRunner {
        /** Called to run client code with the binder. */
        void run(IBinder binder, BoundService service) throws RemoteException;
    }

    /**
     * Information on the service ServiceWatcher has selected as the best option for binding.
     */
    @Immutable
    public static final class BoundService implements Comparable<BoundService> {

        public static final BoundService NONE = new BoundService(Integer.MIN_VALUE, null,
                false, null, -1);

        public final int version;
        @Nullable
        public final ComponentName component;
        public final boolean serviceIsMultiuser;
        public final int uid;
        @Nullable
        public final Bundle metadata;

        BoundService(ResolveInfo resolveInfo) {
            Preconditions.checkArgument(resolveInfo.serviceInfo.getComponentName() != null);

            metadata = resolveInfo.serviceInfo.metaData;
            if (metadata != null) {
                version = metadata.getInt(EXTRA_SERVICE_VERSION, Integer.MIN_VALUE);
                serviceIsMultiuser = metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false);
            } else {
                version = Integer.MIN_VALUE;
                serviceIsMultiuser = false;
            }

            component = resolveInfo.serviceInfo.getComponentName();
            uid = resolveInfo.serviceInfo.applicationInfo.uid;
        }

        private BoundService(int version, @Nullable ComponentName component,
                boolean serviceIsMultiuser, @Nullable Bundle metadata, int uid) {
            Preconditions.checkArgument(component != null || version == Integer.MIN_VALUE);
            this.version = version;
            this.component = component;
            this.serviceIsMultiuser = serviceIsMultiuser;
            this.metadata = metadata;
            this.uid = uid;
        }

        public @Nullable String getPackageName() {
            return component != null ? component.getPackageName() : null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BoundService)) {
                return false;
            }
            BoundService that = (BoundService) o;
            return version == that.version && uid == that.uid
                    && Objects.equals(component, that.component);
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, component, uid);
        }

        @Override
        public int compareTo(BoundService that) {
            // ServiceInfos with higher version numbers always win (having a version number >
            // MIN_VALUE implies having a non-null component). if version numbers are equal, a
            // non-null component wins over a null component. if the version numbers are equal and
            // both components exist then we prefer components that work for all users vs components
            // that only work for a single user at a time. otherwise everything's equal.
            int ret = Integer.compare(version, that.version);
            if (ret == 0) {
                if (component == null && that.component != null) {
                    ret = -1;
                } else if (component != null && that.component == null) {
                    ret = 1;
                } else {
                    if (UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM
                            && UserHandle.getUserId(that.uid) == UserHandle.USER_SYSTEM) {
                        ret = -1;
                    } else if (UserHandle.getUserId(uid) == UserHandle.USER_SYSTEM
                            && UserHandle.getUserId(that.uid) != UserHandle.USER_SYSTEM) {
                        ret = 1;
                    }
                }
            }
            return ret;
        }

        @Override
        public String toString() {
            if (component == null) {
                return "none";
            } else {
                return component.toShortString() + "@" + version + "[u"
                        + UserHandle.getUserId(uid) + "]";
            }
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Intent mIntent;
    private final Predicate<ResolveInfo> mServiceCheckPredicate;

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            return true;
        }

        @Override
        public void onSomePackagesChanged() {
            onBestServiceChanged(false);
        }
    };
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                return;
            }

            switch (action) {
                case Intent.ACTION_USER_SWITCHED:
                    onUserSwitched(userId);
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                    onUserUnlocked(userId);
                    break;
                default:
                    break;
            }

        }
    };

    // read/write from handler thread only
    private final Map<ComponentName, BoundService> mPendingBinds = new ArrayMap<>();

    @Nullable
    private final OnBindRunner mOnBind;

    @Nullable
    private final Runnable mOnUnbind;

    // read/write from handler thread only
    private boolean mRegistered;

    // read/write from handler thread only
    private int mCurrentUserId;

    // write from handler thread only, read anywhere
    private volatile BoundService mTargetService;
    private volatile IBinder mBinder;

    public ServiceWatcher(Context context, String action,
            @Nullable OnBindRunner onBind, @Nullable Runnable onUnbind,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        this(context, FgThread.getHandler(), action, onBind, onUnbind, enableOverlayResId,
                nonOverlayPackageResId, DEFAULT_SERVICE_CHECK_PREDICATE);
    }

    public ServiceWatcher(Context context, Handler handler, String action,
            @Nullable OnBindRunner onBind, @Nullable Runnable onUnbind,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        this(context, handler, action, onBind, onUnbind, enableOverlayResId, nonOverlayPackageResId,
                DEFAULT_SERVICE_CHECK_PREDICATE);
    }

    public ServiceWatcher(Context context, Handler handler, String action,
            @Nullable OnBindRunner onBind, @Nullable Runnable onUnbind,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId,
            @NonNull Predicate<ResolveInfo> serviceCheckPredicate) {
        mContext = context;
        mHandler = handler;
        mIntent = new Intent(Objects.requireNonNull(action));
        mServiceCheckPredicate = Objects.requireNonNull(serviceCheckPredicate);

        Resources resources = context.getResources();
        boolean enableOverlay = resources.getBoolean(enableOverlayResId);
        if (!enableOverlay) {
            mIntent.setPackage(resources.getString(nonOverlayPackageResId));
        }

        mOnBind = onBind;
        mOnUnbind = onUnbind;

        mCurrentUserId = UserHandle.USER_NULL;

        mTargetService = BoundService.NONE;
        mBinder = null;
    }

    /**
     * Returns true if there is at least one component that could satisfy the ServiceWatcher's
     * constraints.
     */
    public boolean checkServiceResolves() {
        List<ResolveInfo> resolveInfos = mContext.getPackageManager()
                .queryIntentServicesAsUser(mIntent,
                        MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE | MATCH_SYSTEM_ONLY,
                        UserHandle.USER_SYSTEM);
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (mServiceCheckPredicate.test(resolveInfo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts the process of determining the best matching service and maintaining a binding to it.
     */
    public void register() {
        mHandler.sendMessage(PooledLambda.obtainMessage(ServiceWatcher::registerInternal,
                ServiceWatcher.this));
    }

    private void registerInternal() {
        Preconditions.checkState(!mRegistered);

        mPackageMonitor.register(mContext, UserHandle.ALL, true, mHandler);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, intentFilter, null,
                mHandler);

        // TODO: This makes the behavior of the class unpredictable as the caller needs
        // to know the internal impl detail that calling register would pick the current user.
        mCurrentUserId = ActivityManager.getCurrentUser();

        mRegistered = true;

        mHandler.post(() -> onBestServiceChanged(false));
    }

    /**
     * Stops the process of determining the best matching service and releases any binding.
     */
    public void unregister() {
        mHandler.sendMessage(PooledLambda.obtainMessage(ServiceWatcher::unregisterInternal,
                ServiceWatcher.this));
    }

    private void unregisterInternal() {
        Preconditions.checkState(mRegistered);

        mRegistered = false;

        mPackageMonitor.unregister();
        mContext.unregisterReceiver(mBroadcastReceiver);

        mHandler.post(() -> onBestServiceChanged(false));
    }

    private void onBestServiceChanged(boolean forceRebind) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        BoundService bestServiceInfo = BoundService.NONE;

        if (mRegistered) {
            List<ResolveInfo> resolveInfos = mContext.getPackageManager().queryIntentServicesAsUser(
                    mIntent,
                    GET_META_DATA | MATCH_DIRECT_BOOT_AUTO | MATCH_SYSTEM_ONLY,
                    mCurrentUserId);
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (!mServiceCheckPredicate.test(resolveInfo)) {
                    continue;
                }
                BoundService serviceInfo = new BoundService(resolveInfo);
                if (serviceInfo.compareTo(bestServiceInfo) > 0) {
                    bestServiceInfo = serviceInfo;
                }
            }
        }

        if (forceRebind || !bestServiceInfo.equals(mTargetService)) {
            rebind(bestServiceInfo);
        }
    }

    private void rebind(BoundService newServiceInfo) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (!mTargetService.equals(BoundService.NONE)) {
            if (D) {
                Log.d(TAG, "[" + mIntent.getAction() + "] unbinding from " + mTargetService);
            }

            mContext.unbindService(this);
            onServiceDisconnected(mTargetService.component);
            mPendingBinds.remove(mTargetService.component);
            mTargetService = BoundService.NONE;
        }

        mTargetService = newServiceInfo;
        if (mTargetService.equals(BoundService.NONE)) {
            return;
        }

        Preconditions.checkState(mTargetService.component != null);

        Log.i(TAG, getLogPrefix() + " binding to " + mTargetService);

        Intent bindIntent = new Intent(mIntent).setComponent(mTargetService.component);
        if (!mContext.bindServiceAsUser(bindIntent, this,
                BIND_AUTO_CREATE | BIND_NOT_FOREGROUND | BIND_NOT_VISIBLE,
                mHandler, UserHandle.of(UserHandle.getUserId(mTargetService.uid)))) {
            mTargetService = BoundService.NONE;
            Log.e(TAG, getLogPrefix() + " unexpected bind failure - retrying later");
            mHandler.postDelayed(() -> onBestServiceChanged(false), RETRY_DELAY_MS);
        } else {
            mPendingBinds.put(mTargetService.component, mTargetService);
        }
    }

    @Override
    public final void onServiceConnected(ComponentName component, IBinder binder) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());
        Preconditions.checkState(mBinder == null);

        if (D) {
            Log.d(TAG, getLogPrefix() + " connected to " + component.toShortString());
        }

        final BoundService boundService = mPendingBinds.remove(component);
        if (boundService == null) {
            return;
        }

        mBinder = binder;
        if (mOnBind != null) {
            try {
                mOnBind.run(binder, boundService);
            } catch (RuntimeException | RemoteException e) {
                // binders may propagate some specific non-RemoteExceptions from the other side
                // through the binder as well - we cannot allow those to crash the system server
                Log.e(TAG, getLogPrefix() + " exception running on " + component, e);
            }
        }
    }

    @Override
    public final void onServiceDisconnected(ComponentName component) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (mBinder == null) {
            return;
        }

        if (D) {
            Log.d(TAG, getLogPrefix() + " disconnected from " + component.toShortString());
        }

        mBinder = null;
        if (mOnUnbind != null) {
            mOnUnbind.run();
        }
    }

    @Override
    public final void onBindingDied(ComponentName component) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        Log.i(TAG, getLogPrefix() + " " + component.toShortString() + " died");

        onBestServiceChanged(true);
    }

    @Override
    public final void onNullBinding(ComponentName component) {
        Log.e(TAG, getLogPrefix() + " " + component.toShortString() + " has null binding");
    }

    void onUserSwitched(@UserIdInt int userId) {
        mCurrentUserId = userId;
        onBestServiceChanged(false);
    }

    void onUserUnlocked(@UserIdInt int userId) {
        if (userId == mCurrentUserId) {
            onBestServiceChanged(false);
        }
    }

    /**
     * Runs the given function asynchronously if and only if currently connected. Suppresses any
     * RemoteException thrown during execution.
     */
    public final void runOnBinder(BinderRunner runner) {
        mHandler.post(() -> {
            if (mBinder == null) {
                runner.onError();
                return;
            }

            try {
                runner.run(mBinder);
            } catch (RuntimeException | RemoteException e) {
                // binders may propagate some specific non-RemoteExceptions from the other side
                // through the binder as well - we cannot allow those to crash the system server
                Log.e(TAG, getLogPrefix() + " exception running on " + mTargetService, e);
                runner.onError();
            }
        });
    }

    private String getLogPrefix() {
        return "[" + mIntent.getAction() + "]";
    }

    @Override
    public String toString() {
        return mTargetService.toString();
    }

    /**
     * Dump for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("target service=" + mTargetService);
        pw.println("connected=" + (mBinder != null));
    }
}
