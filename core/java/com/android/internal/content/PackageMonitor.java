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

import android.annotation.NonNull;
import android.app.Activity;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.Flags;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Helper class for monitoring the state of packages: adding, removing,
 * updating, and disappearing and reappearing on the SD card.
 */
public abstract class PackageMonitor extends android.content.BroadcastReceiver {
    static final String TAG = "PackageMonitor";

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

    PackageMonitorCallback mPackageMonitorCallback;

    private Executor mExecutor;

    final boolean mSupportsPackageRestartQuery;

    @UnsupportedAppUsage
    public PackageMonitor() {
        // If the feature flag is enabled, set mSupportsPackageRestartQuery to false by default
        this(!Flags.packageRestartQueryDisabledByDefault());
    }

    /**
     * The constructor of PackageMonitor whose parameters clearly indicate whether support
     * querying package restart event.
     */
    public PackageMonitor(boolean supportsPackageRestartQuery) {
        mSupportsPackageRestartQuery = supportsPackageRestartQuery;
    }

    private IntentFilter getPackageFilter() {
        final boolean isCore = UserHandle.isCore(android.os.Process.myUid());

        IntentFilter filter = new IntentFilter();
        // Settings app sends the broadcast
        filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        filter.addDataScheme("package");
        if (isCore) {
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        }
        return filter;
    }

    @UnsupportedAppUsage
    public void register(Context context, Looper thread, boolean externalStorage) {
        register(context, thread, null, externalStorage);
    }

    @UnsupportedAppUsage
    public void register(Context context, Looper thread, UserHandle user,
            boolean externalStorage) {
        register(context, user,
                (thread == null) ? BackgroundThread.getHandler() : new Handler(thread));
    }

    /**
     * Register for notifications of package changes such as install, removal and other events.
     */
    public void register(Context context, UserHandle user, Handler handler) {
        if (mRegisteredContext != null) {
            throw new IllegalStateException("Already registered");
        }
        mRegisteredContext = context;
        mRegisteredHandler = Objects.requireNonNull(handler);
        if (mSupportsPackageRestartQuery) {
            final IntentFilter filter = getPackageFilter();
            if (user != null) {
                context.registerReceiverAsUser(this, user, filter, null, mRegisteredHandler);
            } else {
                context.registerReceiver(this, filter, null, mRegisteredHandler);
            }
        }
        if (mPackageMonitorCallback == null) {
            PackageManager pm = mRegisteredContext.getPackageManager();
            if (pm != null) {
                mExecutor = new HandlerExecutor(mRegisteredHandler);
                mPackageMonitorCallback = new PackageMonitorCallback(this);
                int userId = user != null ? user.getIdentifier() : mRegisteredContext.getUserId();
                pm.registerPackageMonitorCallback(mPackageMonitorCallback, userId);
            }
        }
    }

    public Handler getRegisteredHandler() {
        return mRegisteredHandler;
    }

    @UnsupportedAppUsage
    public void unregister() {
        if (mRegisteredContext == null) {
            throw new IllegalStateException("Not registered");
        }
        if (mSupportsPackageRestartQuery) {
            mRegisteredContext.unregisterReceiver(this);
        }

        PackageManager pm = mRegisteredContext.getPackageManager();
        if (pm != null && mPackageMonitorCallback != null) {
            pm.unregisterPackageMonitorCallback(mPackageMonitorCallback);
        }
        mPackageMonitorCallback = null;
        mRegisteredContext = null;
        mExecutor = null;
    }

    public void onBeginPackageChanges() {
    }

    /**
     * Called when a package is really added (and not replaced).
     */
    public void onPackageAdded(String packageName, int uid) {
    }

    /**
     * Same as {@link #onPackageAdded(String, int)}, but this callback
     * has extras passed in.
     */
    public void onPackageAddedWithExtras(String packageName, int uid, Bundle extras) {
    }

    /**
     * Called when a package is really removed (and not replaced).
     */
    @UnsupportedAppUsage
    public void onPackageRemoved(String packageName, int uid) {
    }

    /**
     * Same as {@link #onPackageRemoved(String, int)}, but this callback
     * has extras passed in.
     */
    public void onPackageRemovedWithExtras(String packageName, int uid, Bundle extras) {
    }

    /**
     * Called when a package is really removed (and not replaced) for
     * all users on the device.
     */
    public void onPackageRemovedAllUsers(String packageName, int uid) {
    }

    /**
     * Same as {@link #onPackageRemovedAllUsers(String, int)}, but this callback
     * has extras passed in.
     */
    public void onPackageRemovedAllUsersWithExtras(String packageName, int uid, Bundle extras) {
    }

    public void onPackageUpdateStarted(String packageName, int uid) {
    }

    /**
     * Same as {@link #onPackageUpdateStarted(String, int)}, but this callback
     * has extras passed in.
     */
    public void onPackageUpdateStartedWithExtras(String packageName, int uid, Bundle extras) {
    }

    public void onPackageUpdateFinished(String packageName, int uid) {
    }

    /**
     * Same as {@link #onPackageUpdateFinished(String, int)}, but this callback
     * has extras passed in.
     */
    public void onPackageUpdateFinishedWithExtras(String packageName, int uid, Bundle extras) {
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
    @UnsupportedAppUsage
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

    /**
     * Direct reflection of {@link Intent#ACTION_PACKAGE_CHANGED
     * Intent.ACTION_PACKAGE_CHANGED} being received, this callback
     * has extras passed in.
     */
    public void onPackageChangedWithExtras(String packageName, Bundle extras) {
    }

    public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit,
            Bundle extras) {
        return onHandleForceStop(intent, packages, uid, doit);
    }

    public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
        return false;
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
     * Called when a package disappears with extras passed in.
     */
    public void onPackageDisappearedWithExtras(String packageName, Bundle extras) {
    }

    /**
     * Called when a package appears for any reason.
     */
    public void onPackageAppeared(String packageName, int reason) {
    }


    /**
     * Called when a package appears with extras passed in.
     */
    public void onPackageAppearedWithExtras(String packageName, Bundle extras) {
    }

    /**
     * Called when an existing package is updated or its disabled state changes.
     */
    public void onPackageModified(@NonNull String packageName) {
    }

    /**
     * Same as {@link #onPackageModified(String)}, but this callback
     * has extras passed in.
     */
    public void onPackageModifiedWithExtras(@NonNull String packageName, Bundle extras) {
    }

    /**
     * Called when a package in the stopped state is started for some reason.
     *
     * @param packageName Name of the package that was unstopped
     * @param uid UID of the package that was unstopped
     */
    public void onPackageUnstopped(String packageName, int uid, Bundle extras) {
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
    
    @UnsupportedAppUsage
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

    @UnsupportedAppUsage
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

    /**
     * Callback to indicate the package's state has changed.
     * @param packageName Name of an installed package
     * @param uid The UID the package runs under.
     */
    public void onPackageStateChanged(String packageName, int uid) {}

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
        doHandlePackageEvent(intent);
    }


    private void postHandlePackageEvent(Intent intent) {
        if (mExecutor != null) {
            mExecutor.execute(() -> doHandlePackageEvent(intent));
        }
    }

    /**
     * Handle the package related event
     * @param intent the intent that contains package related event information
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public final void doHandlePackageEvent(Intent intent) {
        mChangeUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                UserHandle.USER_NULL);
        if (mChangeUserId == UserHandle.USER_NULL) {
            Slog.w(TAG, "Intent broadcast does not contain user handle: " + intent);
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
                    onPackageUpdateFinishedWithExtras(pkg, uid, intent.getExtras());
                    onPackageModified(pkg);
                    onPackageModifiedWithExtras(pkg, intent.getExtras());
                } else {
                    mChangeType = PACKAGE_PERMANENT_CHANGE;
                    onPackageAdded(pkg, uid);
                    onPackageAddedWithExtras(pkg, uid, intent.getExtras());
                }
                onPackageAppearedWithExtras(pkg, intent.getExtras());
                onPackageAppeared(pkg, mChangeType);
            }
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            String pkg = getPackageName(intent);
            int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
            if (pkg != null) {
                mDisappearingPackages = mTempArray;
                mTempArray[0] = pkg;
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    mChangeType = PACKAGE_UPDATING;
                    onPackageUpdateStarted(pkg, uid);
                    onPackageUpdateStartedWithExtras(pkg, uid, intent.getExtras());
                    if (intent.getBooleanExtra(Intent.EXTRA_ARCHIVAL, false)) {
                        // In case it is a removal event due to archiving, we trigger package
                        // update event to refresh details like icons, title etc. corresponding to
                        // the archived app.
                        onPackageModified(pkg);
                        onPackageModifiedWithExtras(pkg, intent.getExtras());
                    }
                } else {
                    mChangeType = PACKAGE_PERMANENT_CHANGE;
                    // We only consider something to have changed if this is
                    // not a replace; for a replace, we just need to consider
                    // it when it is re-added.
                    mSomePackagesChanged = true;
                    onPackageRemoved(pkg, uid);
                    onPackageRemovedWithExtras(pkg, uid, intent.getExtras());
                    if (intent.getBooleanExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS, false)) {
                        onPackageRemovedAllUsers(pkg, uid);
                        onPackageRemovedAllUsersWithExtras(pkg, uid, intent.getExtras());
                    }
                }
                onPackageDisappearedWithExtras(pkg, intent.getExtras());
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
                onPackageChangedWithExtras(pkg, intent.getExtras());
                onPackageModified(pkg);
                onPackageModifiedWithExtras(pkg, intent.getExtras());
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
                    intent.getIntExtra(Intent.EXTRA_UID, 0), false, intent.getExtras());
            if (canRestart) setResultCode(Activity.RESULT_OK);
        } else if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
            mDisappearingPackages = new String[] {getPackageName(intent)};
            mChangeType = PACKAGE_TEMPORARY_CHANGE;
            onHandleForceStop(intent, mDisappearingPackages,
                    intent.getIntExtra(Intent.EXTRA_UID, 0), true, intent.getExtras());
        } else if (Intent.ACTION_UID_REMOVED.equals(action)) {
            onUidRemoved(intent.getIntExtra(Intent.EXTRA_UID, 0));
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
        } else if (Intent.ACTION_PACKAGE_UNSTOPPED.equals(action)) {
            final String pkgName = getPackageName(intent);
            mAppearingPackages = new String[] {pkgName};
            mChangeType = PACKAGE_TEMPORARY_CHANGE;
            onPackageUnstopped(pkgName, intent.getIntExtra(Intent.EXTRA_UID, 0),
                    intent.getExtras());
        }

        if (mSomePackagesChanged) {
            onSomePackagesChanged();
        }

        onFinishPackageChanges();
        mChangeUserId = UserHandle.USER_NULL;
    }

    private static final class PackageMonitorCallback extends IRemoteCallback.Stub {
        private final WeakReference<PackageMonitor> mMonitorWeakReference;

        PackageMonitorCallback(PackageMonitor monitor) {
            mMonitorWeakReference = new WeakReference<>(monitor);
        }

        @Override
        public void sendResult(Bundle data) throws RemoteException {
            onHandlePackageMonitorCallback(data);
        }

        private void onHandlePackageMonitorCallback(Bundle bundle) {
            Intent intent = bundle.getParcelable(
                    PackageManager.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT, Intent.class);
            if (intent == null) {
                Log.w(TAG, "No intent is set for PackageMonitorCallback");
                return;
            }
            PackageMonitor monitor = mMonitorWeakReference.get();
            if (monitor != null) {
                monitor.postHandlePackageEvent(intent);
            }
        }
    }
}
