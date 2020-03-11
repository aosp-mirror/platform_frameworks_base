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

package com.android.server;

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_NOT_FOREGROUND;
import static android.content.Context.BIND_NOT_VISIBLE;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.BoolRes;
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
import android.util.Log;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * Maintains a binding to the best service that matches the given intent information. Bind and
 * unbind callbacks, as well as all binder operations, will all be run on the given handler.
 */
public class ServiceWatcher implements ServiceConnection {

    private static final String TAG = "ServiceWatcher";
    private static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    private static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";

    /** Function to run on binder interface. */
    public interface BinderRunner {
        /** Called to run client code with the binder. */
        void run(IBinder binder) throws RemoteException;
        /** Called if an error occurred and the function could not be run. */
        default void onError() {}
    }

    /**
     * Information on the service ServiceWatcher has selected as the best option for binding.
     */
    public static final class ServiceInfo implements Comparable<ServiceInfo> {

        public static final ServiceInfo NONE = new ServiceInfo(Integer.MIN_VALUE, null,
                UserHandle.USER_NULL);

        public final int version;
        @Nullable public final ComponentName component;
        @UserIdInt public final int userId;

        private ServiceInfo(ResolveInfo resolveInfo, int currentUserId) {
            Preconditions.checkArgument(resolveInfo.serviceInfo.getComponentName() != null);

            Bundle metadata = resolveInfo.serviceInfo.metaData;
            boolean isMultiuser;
            if (metadata != null) {
                version = metadata.getInt(EXTRA_SERVICE_VERSION, Integer.MIN_VALUE);
                isMultiuser = metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false);
            } else {
                version = Integer.MIN_VALUE;
                isMultiuser = false;
            }

            component = resolveInfo.serviceInfo.getComponentName();
            userId = isMultiuser ? UserHandle.USER_SYSTEM : currentUserId;
        }

        private ServiceInfo(int version, @Nullable ComponentName component, int userId) {
            Preconditions.checkArgument(component != null || version == Integer.MIN_VALUE);
            this.version = version;
            this.component = component;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ServiceInfo)) {
                return false;
            }
            ServiceInfo that = (ServiceInfo) o;
            return version == that.version && userId == that.userId
                    && Objects.equals(component, that.component);
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, component, userId);
        }

        @Override
        public int compareTo(ServiceInfo that) {
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
                    if (userId != UserHandle.USER_SYSTEM && that.userId == UserHandle.USER_SYSTEM) {
                        ret = -1;
                    } else if (userId == UserHandle.USER_SYSTEM
                            && that.userId != UserHandle.USER_SYSTEM) {
                        ret = 1;
                    }
                }
            }
            return ret;
        }

        @Override
        public String toString() {
            return component.toShortString() + "@" + version + "[u" + userId + "]";
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Intent mIntent;

    @Nullable private final BinderRunner mOnBind;
    @Nullable private final Runnable mOnUnbind;

    // read/write from handler thread only
    private int mCurrentUserId;

    // write from handler thread only, read anywhere
    private volatile ServiceInfo mServiceInfo;
    private volatile IBinder mBinder;

    public ServiceWatcher(Context context, Handler handler, String action,
            @Nullable BinderRunner onBind, @Nullable Runnable onUnbind,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        mContext = context;
        mHandler = FgThread.getHandler();
        mIntent = new Intent(Objects.requireNonNull(action));

        Resources resources = context.getResources();
        boolean enableOverlay = resources.getBoolean(enableOverlayResId);
        if (!enableOverlay) {
            mIntent.setPackage(resources.getString(nonOverlayPackageResId));
        }

        mOnBind = onBind;
        mOnUnbind = onUnbind;

        mCurrentUserId = UserHandle.USER_NULL;

        mServiceInfo = ServiceInfo.NONE;
        mBinder = null;
    }

    /**
     * Register this class, which will start the process of determining the best matching service
     * and maintaining a binding to it. Will return false and fail if there are no possible matching
     * services at the time this functions is called.
     */
    public boolean register() {
        if (mContext.getPackageManager().queryIntentServicesAsUser(mIntent,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE | MATCH_SYSTEM_ONLY,
                UserHandle.USER_SYSTEM).isEmpty()) {
            return false;
        }

        new PackageMonitor() {
            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                ServiceWatcher.this.onPackageChanged(packageName);
            }

            @Override
            public void onPackageAdded(String packageName, int uid) {
                ServiceWatcher.this.onPackageChanged(packageName);
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                ServiceWatcher.this.onPackageChanged(packageName);
            }

            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                ServiceWatcher.this.onPackageChanged(packageName);
                return super.onPackageChanged(packageName, uid, components);
            }
        }.register(mContext, UserHandle.ALL, true, mHandler);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
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
        }, UserHandle.ALL, intentFilter, null, mHandler);

        mCurrentUserId = ActivityManager.getCurrentUser();

        mHandler.post(() -> onBestServiceChanged(false));
        return true;
    }

    /**
     * Returns information on the currently selected service.
     */
    public ServiceInfo getBoundService() {
        return mServiceInfo;
    }

    private void onBestServiceChanged(boolean forceRebind) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        List<ResolveInfo> resolveInfos = mContext.getPackageManager().queryIntentServicesAsUser(
                mIntent,
                GET_META_DATA | MATCH_DIRECT_BOOT_AUTO | MATCH_SYSTEM_ONLY,
                mCurrentUserId);

        ServiceInfo bestServiceInfo = ServiceInfo.NONE;
        for (ResolveInfo resolveInfo : resolveInfos) {
            ServiceInfo serviceInfo = new ServiceInfo(resolveInfo, mCurrentUserId);
            if (serviceInfo.compareTo(bestServiceInfo) > 0) {
                bestServiceInfo = serviceInfo;
            }
        }

        if (forceRebind || !bestServiceInfo.equals(mServiceInfo)) {
            rebind(bestServiceInfo);
        }
    }

    private void rebind(ServiceInfo newServiceInfo) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (!mServiceInfo.equals(ServiceInfo.NONE)) {
            if (D) {
                Log.i(TAG, "[" + mIntent.getAction() + "] unbinding from " + mServiceInfo);
            }

            mContext.unbindService(this);
            mServiceInfo = ServiceInfo.NONE;
        }

        mServiceInfo = newServiceInfo;
        if (mServiceInfo.equals(ServiceInfo.NONE)) {
            return;
        }

        Preconditions.checkState(mServiceInfo.component != null);

        if (D) {
            Log.i(TAG, getLogPrefix() + " binding to " + mServiceInfo);
        }

        Intent bindIntent = new Intent(mIntent).setComponent(mServiceInfo.component);
        mContext.bindServiceAsUser(bindIntent, this,
                BIND_AUTO_CREATE | BIND_NOT_FOREGROUND | BIND_NOT_VISIBLE,
                mHandler, UserHandle.of(mServiceInfo.userId));
    }

    @Override
    public final void onServiceConnected(ComponentName component, IBinder binder) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (D) {
            Log.i(TAG, getLogPrefix() + " connected to " + component.toShortString());
        }

        mBinder = binder;

        // we always run the on bind callback even if we know that the binder is dead already so
        // that there are always balance pairs of bind/unbind callbacks
        if (mOnBind != null) {
            try {
                mOnBind.run(binder);
            } catch (RuntimeException | RemoteException e) {
                // binders may propagate some specific non-RemoteExceptions from the other side
                // through the binder as well - we cannot allow those to crash the system server
                Log.e(TAG, getLogPrefix() + " exception running on " + mServiceInfo, e);
            }
        }

        try {
            // setting the binder to null lets us skip queued transactions
            binder.linkToDeath(() -> mBinder = null, 0);
        } catch (RemoteException e) {
            mBinder = null;
        }
    }

    @Override
    public final void onServiceDisconnected(ComponentName component) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (D) {
            Log.i(TAG, getLogPrefix() + " disconnected from " + component.toShortString());
        }

        mBinder = null;
        if (mOnUnbind != null) {
            mOnUnbind.run();
        }
    }

    @Override
    public void onBindingDied(ComponentName component) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (D) {
            Log.i(TAG, getLogPrefix() + " " + component.toShortString() + " died");
        }

        onBestServiceChanged(true);
    }

    private void onUserSwitched(@UserIdInt int userId) {
        mCurrentUserId = userId;
        onBestServiceChanged(false);
    }

    private void onUserUnlocked(@UserIdInt int userId) {
        if (userId == mCurrentUserId) {
            onBestServiceChanged(false);
        }
    }

    private void onPackageChanged(String packageName) {
        // force a rebind if the changed package was the currently connected package
        String currentPackageName =
                mServiceInfo.component != null ? mServiceInfo.component.getPackageName() : null;
        onBestServiceChanged(packageName.equals(currentPackageName));
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
                Log.e(TAG, getLogPrefix() + " exception running on " + mServiceInfo, e);
                runner.onError();
            }
        });
    }

    private String getLogPrefix() {
        return "[" + mIntent.getAction() + "]";
    }

    @Override
    public String toString() {
        return mServiceInfo.toString();
    }

    /**
     * Dump for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("service=" + mServiceInfo);
        pw.println("connected=" + (mBinder != null));
    }
}
