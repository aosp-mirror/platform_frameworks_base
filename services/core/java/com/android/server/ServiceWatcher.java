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
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Find the best Service, and bind to it.
 * Handles run-time package changes.
 */
public class ServiceWatcher implements ServiceConnection {
    private static final boolean D = false;
    public static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    public static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";

    private final String mTag;
    private final Context mContext;
    private final PackageManager mPm;
    private final List<HashSet<Signature>> mSignatureSets;
    private final String mAction;

    /**
     * If mServicePackageName is not null, only this package will be searched for the service that
     * implements mAction. When null, all packages in the system that matches one of the signature
     * in mSignatureSets are searched.
     */
    private final String mServicePackageName;
    private final Runnable mNewServiceWork;
    private final Handler mHandler;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    @GuardedBy("mLock")
    private IBinder mBoundService;
    @GuardedBy("mLock")
    private ComponentName mBoundComponent;
    @GuardedBy("mLock")
    private String mBoundPackageName;
    @GuardedBy("mLock")
    private int mBoundVersion = Integer.MIN_VALUE;
    @GuardedBy("mLock")
    private int mBoundUserId = UserHandle.USER_NULL;

    public static ArrayList<HashSet<Signature>> getSignatureSets(Context context,
            List<String> initialPackageNames) {
        PackageManager pm = context.getPackageManager();
        ArrayList<HashSet<Signature>> sigSets = new ArrayList<HashSet<Signature>>();
        for (int i = 0, size = initialPackageNames.size(); i < size; i++) {
            String pkg = initialPackageNames.get(i);
            try {
                HashSet<Signature> set = new HashSet<Signature>();
                Signature[] sigs = pm.getPackageInfo(pkg, PackageManager.MATCH_SYSTEM_ONLY
                        | PackageManager.GET_SIGNATURES).signatures;
                set.addAll(Arrays.asList(sigs));
                sigSets.add(set);
            } catch (NameNotFoundException e) {
                Log.w("ServiceWatcher", pkg + " not found");
            }
        }
        return sigSets;
    }

    public ServiceWatcher(Context context, String logTag, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Runnable newServiceWork,
            Handler handler) {
        mContext = context;
        mTag = logTag;
        mAction = action;
        mPm = mContext.getPackageManager();
        mNewServiceWork = newServiceWork;
        mHandler = handler;
        Resources resources = context.getResources();

        // Whether to enable service overlay.
        boolean enableOverlay = resources.getBoolean(overlaySwitchResId);
        ArrayList<String> initialPackageNames = new ArrayList<String>();
        if (enableOverlay) {
            // A list of package names used to create the signatures.
            String[] pkgs = resources.getStringArray(initialPackageNamesResId);
            if (pkgs != null) initialPackageNames.addAll(Arrays.asList(pkgs));
            mServicePackageName = null;
            if (D) Log.d(mTag, "Overlay enabled, packages=" + Arrays.toString(pkgs));
        } else {
            // The default package name that is searched for service implementation when overlay is
            // disabled.
            String servicePackageName = resources.getString(defaultServicePackageNameResId);
            if (servicePackageName != null) initialPackageNames.add(servicePackageName);
            mServicePackageName = servicePackageName;
            if (D) Log.d(mTag, "Overlay disabled, default package=" + servicePackageName);
        }
        mSignatureSets = getSignatureSets(context, initialPackageNames);
    }

    /**
     * Start this watcher, including binding to the current best match and
     * re-binding to any better matches down the road.
     * <p>
     * Note that if there are no matching encryption-aware services, we may not
     * bind to a real service until after the current user is unlocked.
     *
     * @returns {@code true} if a potential service implementation was found.
     */
    public boolean start() {
        if (isServiceMissing()) return false;

        synchronized (mLock) {
            bindBestPackageLocked(mServicePackageName, false);
        }

        // listen for user change
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
                    switchUser(userId);
                } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    unlockUser(userId);
                }
            }
        }, UserHandle.ALL, intentFilter, null, mHandler);

        // listen for relevant package changes if service overlay is enabled.
        if (mServicePackageName == null) {
            mPackageMonitor.register(mContext, null, UserHandle.ALL, true);
        }

        return true;
    }

    /**
     * Check if any instance of this service is present on the device,
     * regardless of it being encryption-aware or not.
     */
    private boolean isServiceMissing() {
        final Intent intent = new Intent(mAction);
        final int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        return mPm.queryIntentServicesAsUser(intent, flags, mCurrentUserId).isEmpty();
    }

    /**
     * Searches and binds to the best package, or do nothing if the best package
     * is already bound, unless force rebinding is requested.
     *
     * @param justCheckThisPackage Only consider this package, or consider all
     *            packages if it is {@code null}.
     * @param forceRebind Force a rebinding to the best package if it's already
     *            bound.
     * @returns {@code true} if a valid package was found to bind to.
     */
    private boolean bindBestPackageLocked(String justCheckThisPackage, boolean forceRebind) {
        Intent intent = new Intent(mAction);
        if (justCheckThisPackage != null) {
            intent.setPackage(justCheckThisPackage);
        }
        final List<ResolveInfo> rInfos = mPm.queryIntentServicesAsUser(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                mCurrentUserId);
        int bestVersion = Integer.MIN_VALUE;
        ComponentName bestComponent = null;
        boolean bestIsMultiuser = false;
        if (rInfos != null) {
            for (ResolveInfo rInfo : rInfos) {
                final ComponentName component = rInfo.serviceInfo.getComponentName();
                final String packageName = component.getPackageName();

                // check signature
                try {
                    PackageInfo pInfo;
                    pInfo = mPm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES
                            | PackageManager.MATCH_DEBUG_TRIAGED_MISSING);
                    if (!isSignatureMatch(pInfo.signatures)) {
                        Log.w(mTag, packageName + " resolves service " + mAction
                                + ", but has wrong signature, ignoring");
                        continue;
                    }
                } catch (NameNotFoundException e) {
                    Log.wtf(mTag, e);
                    continue;
                }

                // check metadata
                int version = Integer.MIN_VALUE;
                boolean isMultiuser = false;
                if (rInfo.serviceInfo.metaData != null) {
                    version = rInfo.serviceInfo.metaData.getInt(
                            EXTRA_SERVICE_VERSION, Integer.MIN_VALUE);
                    isMultiuser = rInfo.serviceInfo.metaData.getBoolean(EXTRA_SERVICE_IS_MULTIUSER);
                }

                if (version > bestVersion) {
                    bestVersion = version;
                    bestComponent = component;
                    bestIsMultiuser = isMultiuser;
                }
            }

            if (D) {
                Log.d(mTag, String.format("bindBestPackage for %s : %s found %d, %s", mAction,
                        (justCheckThisPackage == null ? ""
                                : "(" + justCheckThisPackage + ") "), rInfos.size(),
                        (bestComponent == null ? "no new best component"
                                : "new best component: " + bestComponent)));
            }
        } else {
            if (D) Log.d(mTag, "Unable to query intent services for action: " + mAction);
        }

        if (bestComponent == null) {
            Slog.w(mTag, "Odd, no component found for service " + mAction);
            unbindLocked();
            return false;
        }

        final int userId = bestIsMultiuser ? UserHandle.USER_SYSTEM : mCurrentUserId;
        final boolean alreadyBound = Objects.equals(bestComponent, mBoundComponent)
                && bestVersion == mBoundVersion && userId == mBoundUserId;
        if (forceRebind || !alreadyBound) {
            unbindLocked();
            bindToPackageLocked(bestComponent, bestVersion, userId);
        }
        return true;
    }

    private void unbindLocked() {
        ComponentName component;
        component = mBoundComponent;
        mBoundComponent = null;
        mBoundPackageName = null;
        mBoundVersion = Integer.MIN_VALUE;
        mBoundUserId = UserHandle.USER_NULL;
        if (component != null) {
            if (D) Log.d(mTag, "unbinding " + component);
            mContext.unbindService(this);
        }
    }

    private void bindToPackageLocked(ComponentName component, int version, int userId) {
        Intent intent = new Intent(mAction);
        intent.setComponent(component);
        mBoundComponent = component;
        mBoundPackageName = component.getPackageName();
        mBoundVersion = version;
        mBoundUserId = userId;
        if (D) Log.d(mTag, "binding " + component + " (v" + version + ") (u" + userId + ")");
        mContext.bindServiceAsUser(intent, this,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_NOT_VISIBLE,
                new UserHandle(userId));
    }

    public static boolean isSignatureMatch(Signature[] signatures,
            List<HashSet<Signature>> sigSets) {
        if (signatures == null) return false;

        // build hashset of input to test against
        HashSet<Signature> inputSet = new HashSet<Signature>();
        for (Signature s : signatures) {
            inputSet.add(s);
        }

        // test input against each of the signature sets
        for (HashSet<Signature> referenceSet : sigSets) {
            if (referenceSet.equals(inputSet)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSignatureMatch(Signature[] signatures) {
        return isSignatureMatch(signatures, mSignatureSets);
    }

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        /**
         * Called when package has been reinstalled
         */
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            synchronized (mLock) {
                final boolean forceRebind = Objects.equals(packageName, mBoundPackageName);
                bindBestPackageLocked(null, forceRebind);
            }
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            synchronized (mLock) {
                final boolean forceRebind = Objects.equals(packageName, mBoundPackageName);
                bindBestPackageLocked(null, forceRebind);
            }
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            synchronized (mLock) {
                final boolean forceRebind = Objects.equals(packageName, mBoundPackageName);
                bindBestPackageLocked(null, forceRebind);
            }
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            synchronized (mLock) {
                final boolean forceRebind = Objects.equals(packageName, mBoundPackageName);
                bindBestPackageLocked(null, forceRebind);
            }
            return super.onPackageChanged(packageName, uid, components);
        }
    };

    @Override
    public void onServiceConnected(ComponentName component, IBinder binder) {
        synchronized (mLock) {
            if (component.equals(mBoundComponent)) {
                if (D) Log.d(mTag, component + " connected");
                mBoundService = binder;
                if (mHandler !=null && mNewServiceWork != null) {
                    mHandler.post(mNewServiceWork);
                }
            } else {
                Log.w(mTag, "unexpected onServiceConnected: " + component);
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName component) {
        synchronized (mLock) {
            if (D) Log.d(mTag, component + " disconnected");

            if (component.equals(mBoundComponent)) {
                mBoundService = null;
            }
        }
    }

    public @Nullable String getBestPackageName() {
        synchronized (mLock) {
            return mBoundPackageName;
        }
    }

    public int getBestVersion() {
        synchronized (mLock) {
            return mBoundVersion;
        }
    }

    public @Nullable IBinder getBinder() {
        synchronized (mLock) {
            return mBoundService;
        }
    }

    public void switchUser(int userId) {
        synchronized (mLock) {
            mCurrentUserId = userId;
            bindBestPackageLocked(mServicePackageName, false);
        }
    }

    public void unlockUser(int userId) {
        synchronized (mLock) {
            if (userId == mCurrentUserId) {
                bindBestPackageLocked(mServicePackageName, false);
            }
        }
    }
}
