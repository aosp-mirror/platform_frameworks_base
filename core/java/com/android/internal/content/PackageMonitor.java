/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.content;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Slog;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;

import java.util.HashSet;

/**
 * Helper class for monitoring the state of packages: adding, removing,
 * updating, and disappearing and reappearing on the SD card.
 */
public abstract class PackageMonitor extends android.content.BroadcastReceiver {
    static final IntentFilter sPackageFilt = new IntentFilter();
    static final IntentFilter sNonDataFilt = new IntentFilter();
    static final IntentFilter sExternalFilt = new IntentFilter();

    static {
        sPackageFilt.addAction(Intent.ACTION_PACKAGE_ADDED);
        sPackageFilt.addAction(Intent.ACTION_PACKAGE_REMOVED);
        sPackageFilt.addAction(Intent.ACTION_PACKAGE_CHANGED);
        sPackageFilt.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        sPackageFilt.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        sPackageFilt.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        sPackageFilt.addDataScheme("package");
        sNonDataFilt.addAction(Intent.ACTION_UID_REMOVED);
        sNonDataFilt.addAction(Intent.ACTION_USER_STOPPED);
        sNonDataFilt.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        sNonDataFilt.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        sExternalFilt.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sExternalFilt.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
    }
    
    final HashSet<String> mUpdatingPackages = new HashSet<String>();
    
    Context mRegisteredContext;
    Handler mRegisteredHandler;
    String[] mDisappearingPackages;
    String[] mAppearingPackages;
    String[] mModifiedPackages;
    int mChangeType;
    int mChangeUserId = UserHandle.USER_NULL;
    boolean mSomePackagesChanged;
    String[] mModifiedComponents;

    String[] mTempArray = new String[1];

    public void register(Context context, Looper thread, boolean externalStorage) {
        register(context, thread, null, externalStorage);
    }

    public void register(Context context, Looper thread, UserHandle user,
            boolean externalStorage) {
        register(context, user, externalStorage,
                (thread == null) ? BackgroundThread.getHandler() : new Handler(thread));
    }

    public void register(Context context, UserHandle user,
        boolean externalStorage, Handler handler) {
        if (mRegisteredContext != null) {
            throw new IllegalStateException("Already registered");
        }
        mRegisteredContext = context;
        mRegisteredHandler = Preconditions.checkNotNull(handler);
        if (user != null) {
            context.registerReceiverAsUser(this, user, sPackageFilt, null, mRegisteredHandler);
            context.registerReceiverAsUser(this, user, sNonDataFilt, null, mRegisteredHandler);
            if (externalStorage) {
                context.registerReceiverAsUser(this, user, sExternalFilt, null,
                        mRegisteredHandler);
            }
        } else {
            context.registerReceiver(this, sPackageFilt, null, mRegisteredHandler);
            context.registerReceiver(this, sNonDataFilt, null, mRegisteredHandler);
            if (externalStorage) {
                context.registerReceiver(this, sExternalFilt, null, mRegisteredHandler);
            }
        }
    }

    public Handler getRegisteredHandler() {
        return mRegisteredHandler;
    }

    public void unregister() {
        if (mRegisteredContext == null) {
            throw new IllegalStateException("Not registered");
        }
        mRegisteredContext.unregisterReceiver(this);
        mRegisteredContext = null;
    }
    
    //not yet implemented
    boolean isPackageUpdating(String packageName) {
        synchronized (mUpdatingPackages) {
            return mUpdatingPackages.contains(packageName);
        }
    }
    
    public void onBeginPackageChanges() {
    }

    /**
     * Called when a package is really added (and not replaced).
     */
    public void onPackageAdded(String packageName, int uid) {
    }

    /**
     * Called when a package is really removed (and not replaced).
     */
    public void onPackageRemoved(String packageName, int uid) {
    }

    /**
     * Called when a package is really removed (and not replaced) for
     * all users on the device.
     */
    public void onPackageRemovedAllUsers(String packageName, int uid) {
    }

    public void onPackageUpdateStarted(String packageName, int uid) {
    }

    public void onPackageUpdateFinished(String packageName, int uid) {
    }

    /**
     * Direct reflection of {@link Intent#ACTION_PACKAGE_CHANGED
     * Intent.ACTION_PACKAGE_CHANGED} being received, informing you of
     * changes to the enabled/disabled state of components in a package
     * and/or of the overall package.
     *
     * @param packageName The name of the package that is changing.
     * @param uid The user ID the package runs under.
     * @param components Any components in the package that are changing.  If
     * the overall package is changing, this will contain an entry of the
     * package name itself.
     * @return Return true to indicate you care about this change, which will
     * result in {@link #onSomePackagesChanged()} being called later.  If you
     * return false, no further callbacks will happen about this change.  The
     * default implementation returns true if this is a change to the entire
     * package.
     */
    public boolean onPackageChanged(String packageName, int uid, String[] components) {
        if (components != null) {
            for (String name : components) {
                if (packageName.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
        return false;
    }

    public void onHandleUserStop(Intent intent, int userHandle) {
    }
    
    public void onUidRemoved(int uid) {
    }
    
    public void onPackagesAvailable(String[] packages) {
    }
    
    public void onPackagesUnavailable(String[] packages) {
    }

    public void onPackagesSuspended(String[] packages) {
    }

    public void onPackagesUnsuspended(String[] packages) {
    }

    public static final int PACKAGE_UNCHANGED = 0;
    public static final int PACKAGE_UPDATING = 1;
    public static final int PACKAGE_TEMPORARY_CHANGE = 2;
    public static final int PACKAGE_PERMANENT_CHANGE = 3;

    /**
     * Called when a package disappears for any reason.
     */
    public void onPackageDisappeared(String packageName, int reason) {
    }

    /**
     * Called when a package appears for any reason.
     */
    public void onPackageAppeared(String packageName, int reason) {
    }

    /**
     * Called when an existing package is updated or its disabled state changes.
     */
    public void onPackageModified(String packageName) {
    }
    
    public boolean didSomePackagesChange() {
        return mSomePackagesChanged;
    }
    
    public int isPackageAppearing(String packageName) {
        if (mAppearingPackages != null) {
            for (int i=mAppearingPackages.length-1; i>=0; i--) {
                if (packageName.equals(mAppearingPackages[i])) {
                    return mChangeType;
                }
            }
        }
        return PACKAGE_UNCHANGED;
    }
    
    public boolean anyPackagesAppearing() {
        return mAppearingPackages != null;
    }
    
    public int isPackageDisappearing(String packageName) {
        if (mDisappearingPackages != null) {
            for (int i=mDisappearingPackages.length-1; i>=0; i--) {
                if (packageName.equals(mDisappearingPackages[i])) {
                    return mChangeType;
                }
            }
        }
        return PACKAGE_UNCHANGED;
    }
    
    public boolean anyPackagesDisappearing() {
        return mDisappearingPackages != null;
    }

    public boolean isReplacing() {
        return mChangeType == PACKAGE_UPDATING;
    }

    public boolean isPackageModified(String packageName) {
        if (mModifiedPackages != null) {
            for (int i=mModifiedPackages.length-1; i>=0; i--) {
                if (packageName.equals(mModifiedPackages[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isComponentModified(String className) {
        if (className == null || mModifiedComponents == null) {
            return false;
        }
        for (int i = mModifiedComponents.length - 1; i >= 0; i--) {
            if (className.equals(mModifiedComponents[i])) {
                return true;
            }
        }
        return false;
    }
    
    public void onSomePackagesChanged() {
    }
    
    public void onFinishPackageChanges() {
    }

    public void onPackageDataCleared(String packageName, int uid) {
    }

    public int getChangingUserId() {
        return mChangeUserId;
    }

    String getPackageName(Intent intent) {
        Uri uri = intent.getData();
        String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
        return pkg;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        mChangeUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                UserHandle.USER_NULL);
        if (mChangeUserId == UserHandle.USER_NULL) {
            Slog.w("PackageMonitor", "Intent broadcast does not contain user handle: " + intent);
            return;
        }
        onBeginPackageChanges();
        
        mDisappearingPackages = mAppearingPackages = null;
        mSomePackagesChanged = false;
        mModifiedComponents = null;
        
        String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            String pkg = getPackageName(intent);
            int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
            // We consider something to have changed regardless of whether
            // this is just an update, because the update is now finished
            // and the contents of the package may have changed.
            mSomePackagesChanged = true;
            if (pkg != null) {
                mAppearingPackages = mTempArray;
                mTempArray[0] = pkg;
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    mModifiedPackages = mTempArray;
                    mChangeType = PACKAGE_UPDATING;
                    onPackageUpdateFinished(pkg, uid);
                    onPackageModified(pkg);
                } else {
                    mChangeType = PACKAGE_PERMANENT_CHANGE;
                    onPackageAdded(pkg, uid);
                }
                onPackageAppeared(pkg, mChangeType);
                if (mChangeType == PACKAGE_UPDATING) {
                    synchronized (mUpdatingPackages) {
                        mUpdatingPackages.remove(pkg);
                    }
                }
            }
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            String pkg = getPackageName(intent);
            int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
            if (pkg != null) {
                mDisappearingPackages = mTempArray;
                mTempArray[0] = pkg;
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    mChangeType = PACKAGE_UPDATING;
                    synchronized (mUpdatingPackages) {
                        //not used for now
                        //mUpdatingPackages.add(pkg);
                    }
                    onPackageUpdateStarted(pkg, uid);
                } else {
                    mChangeType = PACKAGE_PERMANENT_CHANGE;
                    // We only consider something to have changed if this is
                    // not a replace; for a replace, we just need to consider
                    // it when it is re-added.
                    mSomePackagesChanged = true;
                    onPackageRemoved(pkg, uid);
                    if (intent.getBooleanExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS, false)) {
                        onPackageRemovedAllUsers(pkg, uid);
                    }
                }
                onPackageDisappeared(pkg, mChangeType);
            }
        } else if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
            String pkg = getPackageName(intent);
            int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
            mModifiedComponents = intent.getStringArrayExtra(
                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
            if (pkg != null) {
                mModifiedPackages = mTempArray;
                mTempArray[0] = pkg;
                mChangeType = PACKAGE_PERMANENT_CHANGE;
                if (onPackageChanged(pkg, uid, mModifiedComponents)) {
                    mSomePackagesChanged = true;
                }
                onPackageModified(pkg);
            }
        } else if (Intent.ACTION_PACKAGE_DATA_CLEARED.equals(action)) {
            String pkg = getPackageName(intent);
            int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
            if (pkg != null) {
                onPackageDataCleared(pkg, uid);
            }
        } else if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
            mDisappearingPackages = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
            mChangeType = PACKAGE_TEMPORARY_CHANGE;
            boolean canRestart = onHandleForceStop(intent,
                    mDisappearingPackages,
                    intent.getIntExtra(Intent.EXTRA_UID, 0), false);
            if (canRestart) setResultCode(Activity.RESULT_OK);
        } else if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
            mDisappearingPackages = new String[] {getPackageName(intent)};
            mChangeType = PACKAGE_TEMPORARY_CHANGE;
            onHandleForceStop(intent, mDisappearingPackages,
                    intent.getIntExtra(Intent.EXTRA_UID, 0), true);
        } else if (Intent.ACTION_UID_REMOVED.equals(action)) {
            onUidRemoved(intent.getIntExtra(Intent.EXTRA_UID, 0));
        } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
            if (intent.hasExtra(Intent.EXTRA_USER_HANDLE)) {
                onHandleUserStop(intent, intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
            }
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
            String[] pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            mAppearingPackages = pkgList;
            mChangeType = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    ? PACKAGE_UPDATING : PACKAGE_TEMPORARY_CHANGE;
            mSomePackagesChanged = true;
            if (pkgList != null) {
                onPackagesAvailable(pkgList);
                for (int i=0; i<pkgList.length; i++) {
                    onPackageAppeared(pkgList[i], mChangeType);
                }
            }
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
            String[] pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            mDisappearingPackages = pkgList;
            mChangeType = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    ? PACKAGE_UPDATING : PACKAGE_TEMPORARY_CHANGE;
            mSomePackagesChanged = true;
            if (pkgList != null) {
                onPackagesUnavailable(pkgList);
                for (int i=0; i<pkgList.length; i++) {
                    onPackageDisappeared(pkgList[i], mChangeType);
                }
            }
        } else if (Intent.ACTION_PACKAGES_SUSPENDED.equals(action)) {
            String[] pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            mSomePackagesChanged = true;
            onPackagesSuspended(pkgList);
        } else if (Intent.ACTION_PACKAGES_UNSUSPENDED.equals(action)) {
            String[] pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            mSomePackagesChanged = true;
            onPackagesUnsuspended(pkgList);
        }

        if (mSomePackagesChanged) {
            onSomePackagesChanged();
        }
        
        onFinishPackageChanges();
        mChangeUserId = UserHandle.USER_NULL;
    }
}
