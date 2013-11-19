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

import com.android.internal.content.PackageMonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

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

    private Object mLock = new Object();

    // all fields below synchronized on mLock
    private IBinder mBinder;   // connected service
    private String mPackageName;  // current best package
    private int mVersion = Integer.MIN_VALUE;  // current best version
    /**
     * Whether the currently-connected service is multiuser-aware. This can change at run-time
     * when switching from one version of a service to another.
     */
    private boolean mIsMultiuser = false;

    public static ArrayList<HashSet<Signature>> getSignatureSets(Context context,
            List<String> initialPackageNames) {
        PackageManager pm = context.getPackageManager();
        ArrayList<HashSet<Signature>> sigSets = new ArrayList<HashSet<Signature>>();
        for (int i = 0, size = initialPackageNames.size(); i < size; i++) {
            String pkg = initialPackageNames.get(i);
            try {
                HashSet<Signature> set = new HashSet<Signature>();
                Signature[] sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
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
        ArrayList<String>  initialPackageNames = new ArrayList<String>();
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

    public boolean start() {
        synchronized (mLock) {
            if (!bindBestPackageLocked(mServicePackageName)) return false;
        }

        // listen for user change
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser();
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
     * Searches and binds to the best package, or do nothing
     * if the best package is already bound.
     * Only checks the named package, or checks all packages if it
     * is null.
     * Return true if a new package was found to bind to.
     */
    private boolean bindBestPackageLocked(String justCheckThisPackage) {
        Intent intent = new Intent(mAction);
        if (justCheckThisPackage != null) {
            intent.setPackage(justCheckThisPackage);
        }
        List<ResolveInfo> rInfos = mPm.queryIntentServicesAsUser(intent,
                PackageManager.GET_META_DATA, UserHandle.USER_OWNER);
        int bestVersion = Integer.MIN_VALUE;
        String bestPackage = null;
        boolean bestIsMultiuser = false;
        if (rInfos != null) {
            for (ResolveInfo rInfo : rInfos) {
                String packageName = rInfo.serviceInfo.packageName;

                // check signature
                try {
                    PackageInfo pInfo;
                    pInfo = mPm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
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

                if (version > mVersion) {
                    bestVersion = version;
                    bestPackage = packageName;
                    bestIsMultiuser = isMultiuser;
                }
            }

            if (D) {
                Log.d(mTag, String.format("bindBestPackage for %s : %s found %d, %s", mAction,
                        (justCheckThisPackage == null ? ""
                                : "(" + justCheckThisPackage + ") "), rInfos.size(),
                        (bestPackage == null ? "no new best package"
                                : "new best package: " + bestPackage)));
            }
        } else {
            if (D) Log.d(mTag, "Unable to query intent services for action: " + mAction);
        }
        if (bestPackage != null) {
            bindToPackageLocked(bestPackage, bestVersion, bestIsMultiuser);
            return true;
        }
        return false;
    }

    private void unbindLocked() {
        String pkg;
        pkg = mPackageName;
        mPackageName = null;
        mVersion = Integer.MIN_VALUE;
        mIsMultiuser = false;
        if (pkg != null) {
            if (D) Log.d(mTag, "unbinding " + pkg);
            mContext.unbindService(this);
        }
    }

    private void bindToPackageLocked(String packageName, int version, boolean isMultiuser) {
        unbindLocked();
        Intent intent = new Intent(mAction);
        intent.setPackage(packageName);
        mPackageName = packageName;
        mVersion = version;
        mIsMultiuser = isMultiuser;
        if (D) Log.d(mTag, "binding " + packageName + " (version " + version + ") ("
                + (isMultiuser ? "multi" : "single") + "-user)");
        mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                | Context.BIND_NOT_VISIBLE, mIsMultiuser ? UserHandle.OWNER : UserHandle.CURRENT);
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
                if (packageName.equals(mPackageName)) {
                    // package updated, make sure to rebind
                    unbindLocked();
                }
                // Need to check all packages because this method is also called when a
                // system app is uninstalled and the stock version in reinstalled.
                bindBestPackageLocked(null);
            }
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            synchronized (mLock) {
                if (packageName.equals(mPackageName)) {
                    // package updated, make sure to rebind
                    unbindLocked();
                }
                // check the new package is case it is better
                bindBestPackageLocked(null);
            }
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            synchronized (mLock) {
                if (packageName.equals(mPackageName)) {
                    unbindLocked();
                    // the currently bound package was removed,
                    // need to search for a new package
                    bindBestPackageLocked(null);
                }
            }
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            synchronized (mLock) {
                if (packageName.equals(mPackageName)) {
                    // service enabled or disabled, make sure to rebind
                    unbindLocked();
                }
                // the service might be disabled, need to search for a new
                // package
                bindBestPackageLocked(null);
            }
            return super.onPackageChanged(packageName, uid, components);
        }
    };

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        synchronized (mLock) {
            String packageName = name.getPackageName();
            if (packageName.equals(mPackageName)) {
                if (D) Log.d(mTag, packageName + " connected");
                mBinder = binder;
                if (mHandler !=null && mNewServiceWork != null) {
                    mHandler.post(mNewServiceWork);
                }
            } else {
                Log.w(mTag, "unexpected onServiceConnected: " + packageName);
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (mLock) {
            String packageName = name.getPackageName();
            if (D) Log.d(mTag, packageName + " disconnected");

            if (packageName.equals(mPackageName)) {
                mBinder = null;
            }
        }
    }

    public String getBestPackageName() {
        synchronized (mLock) {
            return mPackageName;
        }
    }

    public int getBestVersion() {
        synchronized (mLock) {
            return mVersion;
        }
    }

    public IBinder getBinder() {
        synchronized (mLock) {
            return mBinder;
        }
    }

    public void switchUser() {
        synchronized (mLock) {
            if (!mIsMultiuser) {
                unbindLocked();
                bindBestPackageLocked(mServicePackageName);
            }
        }
    }
}
