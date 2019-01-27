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

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Find the best Service, and bind to it.
 * Handles run-time package changes.
 */
public class ServiceWatcher implements ServiceConnection {

    private static final String TAG = "ServiceWatcher";
    private static final boolean D = false;

    public static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    public static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";


    /** Function to run on binder interface. */
    public interface BinderRunner {
        /** Called to run client code with the binder. */
        void run(IBinder binder) throws RemoteException;
    }

    /**
     * Function to run on binder interface.
     * @param <T> Type to return.
     */
    public interface BlockingBinderRunner<T> {
        /** Called to run client code with the binder. */
        T run(IBinder binder) throws RemoteException;
    }

    public static ArrayList<HashSet<Signature>> getSignatureSets(Context context,
            String... packageNames) {
        PackageManager pm = context.getPackageManager();

        ArrayList<HashSet<Signature>> signatureSets = new ArrayList<>(packageNames.length);
        for (String packageName : packageNames) {
            try {
                Signature[] signatures = pm.getPackageInfo(packageName,
                        PackageManager.MATCH_SYSTEM_ONLY
                                | PackageManager.GET_SIGNATURES).signatures;

                HashSet<Signature> set = new HashSet<>();
                Collections.addAll(set, signatures);
                signatureSets.add(set);
            } catch (NameNotFoundException e) {
                Log.w(TAG, packageName + " not found");
            }
        }
        return signatureSets;
    }

    /** Checks if signatures match. */
    public static boolean isSignatureMatch(Signature[] signatures,
            List<HashSet<Signature>> sigSets) {
        if (signatures == null) return false;

        // build hashset of input to test against
        HashSet<Signature> inputSet = new HashSet<>();
        Collections.addAll(inputSet, signatures);

        // test input against each of the signature sets
        for (HashSet<Signature> referenceSet : sigSets) {
            if (referenceSet.equals(inputSet)) {
                return true;
            }
        }
        return false;
    }

    private final Context mContext;
    private final String mTag;
    private final String mAction;
    private final String mServicePackageName;
    private final List<HashSet<Signature>> mSignatureSets;

    private final Handler mHandler;

    // read/write from handler thread
    private IBinder mBestService;
    private int mCurrentUserId;

    // read from any thread, write from handler thread
    private volatile ComponentName mBestComponent;
    private volatile int mBestVersion;
    private volatile int mBestUserId;

    public ServiceWatcher(Context context, String logTag, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler) {
        Resources resources = context.getResources();

        mContext = context;
        mTag = logTag;
        mAction = action;

        boolean enableOverlay = resources.getBoolean(overlaySwitchResId);
        if (enableOverlay) {
            String[] pkgs = resources.getStringArray(initialPackageNamesResId);
            mServicePackageName = null;
            mSignatureSets = getSignatureSets(context, pkgs);
            if (D) Log.d(mTag, "Overlay enabled, packages=" + Arrays.toString(pkgs));
        } else {
            mServicePackageName = resources.getString(defaultServicePackageNameResId);
            mSignatureSets = getSignatureSets(context, mServicePackageName);
            if (D) Log.d(mTag, "Overlay disabled, default package=" + mServicePackageName);
        }

        mHandler = handler;

        mBestComponent = null;
        mBestVersion = Integer.MIN_VALUE;
        mBestUserId = UserHandle.USER_NULL;

        mBestService = null;
    }

    protected void onBind() {}

    protected void onUnbind() {}

    /**
     * Start this watcher, including binding to the current best match and
     * re-binding to any better matches down the road.
     * <p>
     * Note that if there are no matching encryption-aware services, we may not
     * bind to a real service until after the current user is unlocked.
     *
     * @return {@code true} if a potential service implementation was found.
     */
    public final boolean start() {
        // if we have to return false, do it before registering anything
        if (isServiceMissing()) return false;

        // listen for relevant package changes if service overlay is enabled on handler
        if (mServicePackageName == null) {
            new PackageMonitor() {
                @Override
                public void onPackageUpdateFinished(String packageName, int uid) {
                    bindBestPackage(Objects.equals(packageName, getCurrentPackageName()));
                }

                @Override
                public void onPackageAdded(String packageName, int uid) {
                    bindBestPackage(Objects.equals(packageName, getCurrentPackageName()));
                }

                @Override
                public void onPackageRemoved(String packageName, int uid) {
                    bindBestPackage(Objects.equals(packageName, getCurrentPackageName()));
                }

                @Override
                public boolean onPackageChanged(String packageName, int uid, String[] components) {
                    bindBestPackage(Objects.equals(packageName, getCurrentPackageName()));
                    return super.onPackageChanged(packageName, uid, components);
                }
            }.register(mContext, UserHandle.ALL, true, mHandler);
        }

        // listen for user change on handler
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    mCurrentUserId = userId;
                    bindBestPackage(false);
                } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    if (userId == mCurrentUserId) {
                        bindBestPackage(false);
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, mHandler);

        mCurrentUserId = ActivityManager.getCurrentUser();

        mHandler.post(() -> bindBestPackage(false));
        return true;
    }

    /** Returns the name of the currently connected package or null. */
    @Nullable
    public String getCurrentPackageName() {
        ComponentName bestComponent = mBestComponent;
        return bestComponent == null ? null : bestComponent.getPackageName();
    }

    private boolean isServiceMissing() {
        return mContext.getPackageManager().queryIntentServicesAsUser(new Intent(mAction),
                PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM).isEmpty();
    }

    private void bindBestPackage(boolean forceRebind) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        Intent intent = new Intent(mAction);
        if (mServicePackageName != null) {
            intent.setPackage(mServicePackageName);
        }

        List<ResolveInfo> rInfos = mContext.getPackageManager().queryIntentServicesAsUser(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AUTO,
                mCurrentUserId);
        if (rInfos == null) {
            rInfos = Collections.emptyList();
        }

        ComponentName bestComponent = null;
        int bestVersion = Integer.MIN_VALUE;
        boolean bestIsMultiuser = false;

        for (ResolveInfo rInfo : rInfos) {
            ComponentName component = rInfo.serviceInfo.getComponentName();
            String packageName = component.getPackageName();

            // check signature
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(packageName,
                        PackageManager.GET_SIGNATURES
                                | PackageManager.MATCH_DIRECT_BOOT_AUTO);
                if (!isSignatureMatch(pInfo.signatures, mSignatureSets)) {
                    Log.w(mTag, packageName + " resolves service " + mAction
                            + ", but has wrong signature, ignoring");
                    continue;
                }
            } catch (NameNotFoundException e) {
                Log.wtf(mTag, e);
                continue;
            }

            // check metadata
            Bundle metadata = rInfo.serviceInfo.metaData;
            int version = Integer.MIN_VALUE;
            boolean isMultiuser = false;
            if (metadata != null) {
                version = metadata.getInt(EXTRA_SERVICE_VERSION, Integer.MIN_VALUE);
                isMultiuser = metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false);
            }

            if (version > bestVersion) {
                bestComponent = component;
                bestVersion = version;
                bestIsMultiuser = isMultiuser;
            }
        }

        if (D) {
            Log.d(mTag, String.format("bindBestPackage for %s : %s found %d, %s", mAction,
                    (mServicePackageName == null ? ""
                            : "(" + mServicePackageName + ") "), rInfos.size(),
                    (bestComponent == null ? "no new best component"
                            : "new best component: " + bestComponent)));
        }

        if (bestComponent == null) {
            Slog.w(mTag, "Odd, no component found for service " + mAction);
            unbind();
            return;
        }

        int userId = bestIsMultiuser ? UserHandle.USER_SYSTEM : mCurrentUserId;
        boolean alreadyBound = Objects.equals(bestComponent, mBestComponent)
                && bestVersion == mBestVersion && userId == mBestUserId;
        if (forceRebind || !alreadyBound) {
            unbind();
            bind(bestComponent, bestVersion, userId);
        }
    }

    private void bind(ComponentName component, int version, int userId) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        Intent intent = new Intent(mAction);
        intent.setComponent(component);

        mBestComponent = component;
        mBestVersion = version;
        mBestUserId = userId;

        if (D) Log.d(mTag, "binding " + component + " (v" + version + ") (u" + userId + ")");
        mContext.bindServiceAsUser(intent, this,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_NOT_VISIBLE,
                UserHandle.of(userId));
    }

    private void unbind() {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (mBestComponent != null) {
            if (D) Log.d(mTag, "unbinding " + mBestComponent);
            mContext.unbindService(this);
        }

        mBestComponent = null;
        mBestVersion = Integer.MIN_VALUE;
        mBestUserId = UserHandle.USER_NULL;
    }

    /**
     * Runs the given function asynchronously if currently connected. Suppresses any RemoteException
     * thrown during execution.
     */
    public final void runOnBinder(BinderRunner runner) {
        runOnHandler(() -> {
            if (mBestService == null) {
                return;
            }

            try {
                runner.run(mBestService);
            } catch (RuntimeException e) {
                // the code being run is privileged, but may be outside the system server, and thus
                // we cannot allow runtime exceptions to crash the system server
                Log.e(TAG, "exception while while running " + runner + " on " + mBestService
                        + " from " + this, e);
            } catch (RemoteException e) {
                // do nothing
            }
        });
    }

    /**
     * Runs the given function synchronously if currently connected, and returns the default value
     * if not currently connected or if any exception is thrown.
     */
    public final <T> T runOnBinderBlocking(BlockingBinderRunner<T> runner, T defaultValue) {
        try {
            return runOnHandlerBlocking(() -> {
                if (mBestService == null) {
                    return defaultValue;
                }

                try {
                    return runner.run(mBestService);
                } catch (RemoteException e) {
                    return defaultValue;
                }
            });
        } catch (InterruptedException e) {
            return defaultValue;
        }
    }

    @Override
    public final void onServiceConnected(ComponentName component, IBinder binder) {
        runOnHandler(() -> {
            if (D) Log.d(mTag, component + " connected");
            mBestService = binder;
            onBind();
        });
    }

    @Override
    public final void onServiceDisconnected(ComponentName component) {
        runOnHandler(() -> {
            if (D) Log.d(mTag, component + " disconnected");
            mBestService = null;
            onUnbind();
        });
    }

    @Override
    public String toString() {
        ComponentName bestComponent = mBestComponent;
        return bestComponent == null ? "null" : bestComponent.toShortString() + "@" + mBestVersion;
    }

    private void runOnHandler(Runnable r) {
        if (Looper.myLooper() == mHandler.getLooper()) {
            r.run();
        } else {
            mHandler.post(r);
        }
    }

    private <T> T runOnHandlerBlocking(Callable<T> c) throws InterruptedException {
        if (Looper.myLooper() == mHandler.getLooper()) {
            try {
                return c.call();
            } catch (Exception e) {
                // Function cannot throw exception, this should never happen
                throw new IllegalStateException(e);
            }
        } else {
            FutureTask<T> task = new FutureTask<>(c);
            mHandler.post(task);
            try {
                return task.get();
            } catch (ExecutionException e) {
                // Function cannot throw exception, this should never happen
                throw new IllegalStateException(e);
            }
        }
    }
}
