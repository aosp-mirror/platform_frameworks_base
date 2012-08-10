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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.IBinder;
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
    private static final String EXTRA_VERSION = "version";

    private final String mTag;
    private final Context mContext;
    private final PackageManager mPm;
    private final List<HashSet<Signature>> mSignatureSets;
    private final String mAction;
    private final Runnable mNewServiceWork;
    private final Handler mHandler;

    private Object mLock = new Object();

    // all fields below synchronized on mLock
    private IBinder mBinder;   // connected service
    private String mPackageName;  // current best package
    private int mVersion;  // current best version

    public ServiceWatcher(Context context, String logTag, String action,
            List<String> initialPackageNames, Runnable newServiceWork, Handler handler) {
        mContext = context;
        mTag = logTag;
        mAction = action;
        mPm = mContext.getPackageManager();
        mNewServiceWork = newServiceWork;
        mHandler = handler;

        mSignatureSets = new ArrayList<HashSet<Signature>>();
        for (int i=0; i < initialPackageNames.size(); i++) {
            String pkg = initialPackageNames.get(i);
            HashSet<Signature> set = new HashSet<Signature>();
            try {
                Signature[] sigs =
                        mPm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
                set.addAll(Arrays.asList(sigs));
                mSignatureSets.add(set);
            } catch (NameNotFoundException e) {
                Log.w(logTag, pkg + " not found");
            }
        }

    }

    public boolean start() {
        if (!bindBestPackage(null)) return false;

        mPackageMonitor.register(mContext, null, true);
        return true;
    }

    /**
     * Searches and binds to the best package, or do nothing
     * if the best package is already bound.
     * Only checks the named package, or checks all packages if it
     * is null.
     * Return true if a new package was found to bind to.
     */
    private boolean bindBestPackage(String justCheckThisPackage) {
        Intent intent = new Intent(mAction);
        if (justCheckThisPackage != null) {
            intent.setPackage(justCheckThisPackage);
        }
        List<ResolveInfo> rInfos = mPm.queryIntentServices(new Intent(mAction),
                PackageManager.GET_META_DATA);
        int bestVersion = Integer.MIN_VALUE;
        String bestPackage = null;
        for (ResolveInfo rInfo : rInfos) {
            String packageName = rInfo.serviceInfo.packageName;

            // check signature
            try {
                PackageInfo pInfo;
                pInfo = mPm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                if (!isSignatureMatch(pInfo.signatures)) {
                    Log.w(mTag, packageName + " resolves service " + mAction +
                            ", but has wrong signature, ignoring");
                    continue;
                }
            } catch (NameNotFoundException e) {
                Log.wtf(mTag, e);
                continue;
            }

            // check version
            int version = 0;
            if (rInfo.serviceInfo.metaData != null) {
                version = rInfo.serviceInfo.metaData.getInt(EXTRA_VERSION, 0);
            }
            if (version > mVersion) {
                bestVersion = version;
                bestPackage = packageName;
            }
        }

        if (D) Log.d(mTag, String.format("bindBestPackage %s found %d, %s",
                (justCheckThisPackage == null ? "" : "(" + justCheckThisPackage + ") "),
                rInfos.size(),
                (bestPackage == null ? "no new best package" : "new best packge: " + bestPackage)));

        if (bestPackage != null) {
            bindToPackage(bestPackage, bestVersion);
            return true;
        }
        return false;
    }

    private void unbind() {
        String pkg;
        synchronized (mLock) {
            pkg = mPackageName;
            mPackageName = null;
            mVersion = Integer.MIN_VALUE;
        }
        if (pkg != null) {
            if (D) Log.d(mTag, "unbinding " + pkg);
            mContext.unbindService(this);
        }
    }

    private void bindToPackage(String packageName, int version) {
        unbind();
        Intent intent = new Intent(mAction);
        intent.setPackage(packageName);
        synchronized (mLock) {
            mPackageName = packageName;
            mVersion = version;
        }
        if (D) Log.d(mTag, "binding " + packageName + " (version " + version + ")");
        mContext.bindService(intent, this, Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                | Context.BIND_ALLOW_OOM_MANAGEMENT);
    }

    private boolean isSignatureMatch(Signature[] signatures) {
        if (signatures == null) return false;

        // build hashset of input to test against
        HashSet<Signature> inputSet = new HashSet<Signature>();
        for (Signature s : signatures) {
            inputSet.add(s);
        }

        // test input against each of the signature sets
        for (HashSet<Signature> referenceSet : mSignatureSets) {
            if (referenceSet.equals(inputSet)) {
                return true;
            }
        }
        return false;
    }

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        /**
         * Called when package has been reinstalled
         */
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            if (packageName.equals(mPackageName)) {
                // package updated, make sure to rebind
                unbind();
            }
            // check the updated package in case it is better
            bindBestPackage(packageName);
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            if (packageName.equals(mPackageName)) {
                // package updated, make sure to rebind
                unbind();
            }
            // check the new package is case it is better
            bindBestPackage(packageName);
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            if (packageName.equals(mPackageName)) {
                unbind();
                // the currently bound package was removed,
                // need to search for a new package
                bindBestPackage(null);
            }
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
}
