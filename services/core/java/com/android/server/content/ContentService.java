/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.content;

import android.Manifest;
import android.accounts.Account;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentResolver.SyncExemption;
import android.content.Context;
import android.content.IContentService;
import android.content.ISyncStatusObserver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PeriodicSync;
import android.content.SyncAdapterType;
import android.content.SyncInfo;
import android.content.SyncRequest;
import android.content.SyncStatusInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProviderInfo;
import android.database.IContentObserver;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * {@hide}
 */
public final class ContentService extends IContentService.Stub {
    static final String TAG = "ContentService";
    static final boolean DEBUG = false;

    public static class Lifecycle extends SystemService {
        private ContentService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            final boolean factoryTest = (FactoryTest
                    .getMode() == FactoryTest.FACTORY_TEST_LOW_LEVEL);
            mService = new ContentService(getContext(), factoryTest);
            publishBinderService(ContentResolver.CONTENT_SERVICE_NAME, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            mService.onBootPhase(phase);
        }


        @Override
        public void onStartUser(int userHandle) {
            mService.onStartUser(userHandle);
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mService.onUnlockUser(userHandle);
        }

        @Override
        public void onStopUser(int userHandle) {
            mService.onStopUser(userHandle);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            synchronized (mService.mCache) {
                mService.mCache.remove(userHandle);
            }
        }
    }

    private Context mContext;
    private boolean mFactoryTest;

    private final ObserverNode mRootNode = new ObserverNode("");

    private SyncManager mSyncManager = null;
    private final Object mSyncManagerLock = new Object();

    /**
     * Map from userId to providerPackageName to [clientPackageName, uri] to
     * value. This structure is carefully optimized to keep invalidation logic
     * as cheap as possible.
     */
    @GuardedBy("mCache")
    private final SparseArray<ArrayMap<String, ArrayMap<Pair<String, Uri>, Bundle>>>
            mCache = new SparseArray<>();

    private BroadcastReceiver mCacheReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mCache) {
                if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                    mCache.clear();
                } else {
                    final Uri data = intent.getData();
                    if (data != null) {
                        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                                UserHandle.USER_NULL);
                        final String packageName = data.getSchemeSpecificPart();
                        invalidateCacheLocked(userId, packageName, null);
                    }
                }
            }
        }
    };

    private SyncManager getSyncManager() {
        synchronized(mSyncManagerLock) {
            try {
                // Try to create the SyncManager, return null if it fails (which it shouldn't).
                if (mSyncManager == null) mSyncManager = new SyncManager(mContext, mFactoryTest);
            } catch (SQLiteException e) {
                Log.e(TAG, "Can't create SyncManager", e);
            }
            return mSyncManager;
        }
    }

    void onStartUser(int userHandle) {
        if (mSyncManager != null) mSyncManager.onStartUser(userHandle);
    }

    void onUnlockUser(int userHandle) {
        if (mSyncManager != null) mSyncManager.onUnlockUser(userHandle);
    }

    void onStopUser(int userHandle) {
        if (mSyncManager != null) mSyncManager.onStopUser(userHandle);
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw_, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw_)) return;
        final IndentingPrintWriter pw = new IndentingPrintWriter(pw_, "  ");

        final boolean dumpAll = ArrayUtils.contains(args, "-a");

        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        final long identityToken = clearCallingIdentity();
        try {
            if (mSyncManager == null) {
                pw.println("SyncManager not available yet");
            } else {
                mSyncManager.dump(fd, pw, dumpAll);
            }
            pw.println();
            pw.println("Observer tree:");
            synchronized (mRootNode) {
                int[] counts = new int[2];
                final SparseIntArray pidCounts = new SparseIntArray();
                mRootNode.dumpLocked(fd, pw, args, "", "  ", counts, pidCounts);
                pw.println();
                ArrayList<Integer> sorted = new ArrayList<Integer>();
                for (int i=0; i<pidCounts.size(); i++) {
                    sorted.add(pidCounts.keyAt(i));
                }
                Collections.sort(sorted, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer lhs, Integer rhs) {
                        int lc = pidCounts.get(lhs);
                        int rc = pidCounts.get(rhs);
                        if (lc < rc) {
                            return 1;
                        } else if (lc > rc) {
                            return -1;
                        }
                        return 0;
                    }

                });
                for (int i=0; i<sorted.size(); i++) {
                    int pid = sorted.get(i);
                    pw.print("  pid "); pw.print(pid); pw.print(": ");
                    pw.print(pidCounts.get(pid)); pw.println(" observers");
                }
                pw.println();
                pw.print(" Total number of nodes: "); pw.println(counts[0]);
                pw.print(" Total number of observers: "); pw.println(counts[1]);
            }

            synchronized (mCache) {
                pw.println();
                pw.println("Cached content:");
                pw.increaseIndent();
                for (int i = 0; i < mCache.size(); i++) {
                    pw.println("User " + mCache.keyAt(i) + ":");
                    pw.increaseIndent();
                    pw.println(mCache.valueAt(i));
                    pw.decreaseIndent();
                }
                pw.decreaseIndent();
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /*package*/ ContentService(Context context, boolean factoryTest) {
        mContext = context;
        mFactoryTest = factoryTest;

        // Let the package manager query for the sync adapters for a given authority
        // as we grant default permissions to sync adapters for specific authorities.
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.setSyncAdapterPackagesprovider(
                new PackageManagerInternal.SyncAdapterPackagesProvider() {
                    @Override
                    public String[] getPackages(String authority, int userId) {
                        return getSyncAdapterPackagesForAuthorityAsUser(authority, userId);
                    }
                });

        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(mCacheReceiver, UserHandle.ALL,
                packageFilter, null, null);

        final IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiverAsUser(mCacheReceiver, UserHandle.ALL,
                localeFilter, null, null);
    }

    void onBootPhase(int phase) {
        switch (phase) {
            case SystemService.PHASE_ACTIVITY_MANAGER_READY:
                getSyncManager();
                break;
        }
        if (mSyncManager != null) {
            mSyncManager.onBootPhase(phase);
        }
    }

    /**
     * Register a content observer tied to a specific user's view of the provider.
     * @param userHandle the user whose view of the provider is to be observed.  May be
     *     the calling user without requiring any permission, otherwise the caller needs to
     *     hold the INTERACT_ACROSS_USERS_FULL permission or hold a read uri grant to the uri.
     *     Pseudousers USER_ALL and USER_CURRENT are properly handled; all other pseudousers
     *     are forbidden.
     */
    @Override
    public void registerContentObserver(Uri uri, boolean notifyForDescendants,
            IContentObserver observer, int userHandle, int targetSdkVersion) {
        if (observer == null || uri == null) {
            throw new IllegalArgumentException("You must pass a valid uri and observer");
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();

        userHandle = handleIncomingUser(uri, pid, uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION, true, userHandle);

        final String msg = LocalServices.getService(ActivityManagerInternal.class)
                .checkContentProviderAccess(uri.getAuthority(), userHandle);
        if (msg != null) {
            if (targetSdkVersion >= Build.VERSION_CODES.O) {
                throw new SecurityException(msg);
            } else {
                if (msg.startsWith("Failed to find provider")) {
                    // Sigh, we need to quietly let apps targeting older API
                    // levels notify on non-existent providers.
                } else {
                    Log.w(TAG, "Ignoring content changes for " + uri + " from " + uid + ": " + msg);
                    return;
                }
            }
        }

        synchronized (mRootNode) {
            mRootNode.addObserverLocked(uri, observer, notifyForDescendants, mRootNode,
                    uid, pid, userHandle);
            if (false) Log.v(TAG, "Registered observer " + observer + " at " + uri +
                    " with notifyForDescendants " + notifyForDescendants);
        }
    }

    public void registerContentObserver(Uri uri, boolean notifyForDescendants,
                                        IContentObserver observer) {
        registerContentObserver(uri, notifyForDescendants, observer,
                UserHandle.getCallingUserId(), Build.VERSION_CODES.CUR_DEVELOPMENT);
    }

    @Override
    public void unregisterContentObserver(IContentObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("You must pass a valid observer");
        }
        synchronized (mRootNode) {
            mRootNode.removeObserverLocked(observer);
            if (false) Log.v(TAG, "Unregistered observer " + observer);
        }
    }

    /**
     * Notify observers of a particular user's view of the provider.
     * @param userHandle the user whose view of the provider is to be notified.  May be
     *     the calling user without requiring any permission, otherwise the caller needs to
     *     hold the INTERACT_ACROSS_USERS_FULL permission or hold a write uri grant to the uri.
     *     Pseudousers USER_ALL and USER_CURRENT are properly interpreted; no other pseudousers are
     *     allowed.
     */
    @Override
    public void notifyChange(Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, int flags, int userHandle,
            int targetSdkVersion, String callingPackage) {
        if (DEBUG) Slog.d(TAG, "Notifying update of " + uri + " for user " + userHandle
                + " from observer " + observer + ", flags " + Integer.toHexString(flags));

        if (uri == null) {
            throw new NullPointerException("Uri must not be null");
        }

        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int callingUserHandle = UserHandle.getCallingUserId();

        userHandle = handleIncomingUser(uri, callingPid, callingUid,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true, userHandle);

        final String msg = LocalServices.getService(ActivityManagerInternal.class)
                .checkContentProviderAccess(uri.getAuthority(), userHandle);
        if (msg != null) {
            if (targetSdkVersion >= Build.VERSION_CODES.O) {
                throw new SecurityException(msg);
            } else {
                if (msg.startsWith("Failed to find provider")) {
                    // Sigh, we need to quietly let apps targeting older API
                    // levels notify on non-existent providers.
                } else {
                    Log.w(TAG, "Ignoring notify for " + uri + " from " + callingUid + ": " + msg);
                    return;
                }
            }
        }

        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            ArrayList<ObserverCall> calls = new ArrayList<ObserverCall>();
            synchronized (mRootNode) {
                mRootNode.collectObserversLocked(uri, 0, observer, observerWantsSelfNotifications,
                        flags, userHandle, calls);
            }
            final int numCalls = calls.size();
            for (int i=0; i<numCalls; i++) {
                ObserverCall oc = calls.get(i);
                try {
                    oc.mObserver.onChange(oc.mSelfChange, uri, userHandle);
                    if (DEBUG) Slog.d(TAG, "Notified " + oc.mObserver + " of " + "update at "
                            + uri);
                } catch (RemoteException ex) {
                    synchronized (mRootNode) {
                        Log.w(TAG, "Found dead observer, removing");
                        IBinder binder = oc.mObserver.asBinder();
                        final ArrayList<ObserverNode.ObserverEntry> list
                                = oc.mNode.mObservers;
                        int numList = list.size();
                        for (int j=0; j<numList; j++) {
                            ObserverNode.ObserverEntry oe = list.get(j);
                            if (oe.observer.asBinder() == binder) {
                                list.remove(j);
                                j--;
                                numList--;
                            }
                        }
                    }
                }
            }
            if ((flags&ContentResolver.NOTIFY_SYNC_TO_NETWORK) != 0) {
                SyncManager syncManager = getSyncManager();
                if (syncManager != null) {
                    syncManager.scheduleLocalSync(null /* all accounts */, callingUserHandle,
                            callingUid,
                            uri.getAuthority(), getSyncExemptionForCaller(callingUid),
                            callingUid, callingPid, callingPackage);
                }
            }

            synchronized (mCache) {
                final String providerPackageName = getProviderPackageName(uri);
                invalidateCacheLocked(userHandle, providerPackageName, uri);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, int userHandle) {
        try {
            return ActivityManager.getService().checkUriPermission(
                    uri, pid, uid, modeFlags, userHandle, null);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    public void notifyChange(Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, boolean syncToNetwork,
            String callingPackage) {
        notifyChange(uri, observer, observerWantsSelfNotifications,
                syncToNetwork ? ContentResolver.NOTIFY_SYNC_TO_NETWORK : 0,
                UserHandle.getCallingUserId(), Build.VERSION_CODES.CUR_DEVELOPMENT, callingPackage);
    }

    /**
     * Hide this class since it is not part of api,
     * but current unittest framework requires it to be public
     * @hide
     *
     */
    public static final class ObserverCall {
        final ObserverNode mNode;
        final IContentObserver mObserver;
        final boolean mSelfChange;
        final int mObserverUserId;

        ObserverCall(ObserverNode node, IContentObserver observer, boolean selfChange, int observerUserId) {
            mNode = node;
            mObserver = observer;
            mSelfChange = selfChange;
            mObserverUserId = observerUserId;
        }
    }

    @Override
    public void requestSync(Account account, String authority, Bundle extras,
            String callingPackage) {
        Bundle.setDefusable(extras, true);
        ContentResolver.validateSyncExtrasBundle(extras);
        int userId = UserHandle.getCallingUserId();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        validateExtras(callingUid, extras);
        final int syncExemption = getSyncExemptionAndCleanUpExtrasForCaller(callingUid, extras);

        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.scheduleSync(account, userId, callingUid, authority, extras,
                        SyncStorageEngine.AuthorityInfo.UNDEFINED,
                        syncExemption, callingUid, callingPid, callingPackage);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Request a sync with a generic {@link android.content.SyncRequest} object. This will be
     * either:
     *   periodic OR one-off sync.
     * and
     *   anonymous OR provider sync.
     * Depending on the request, we enqueue to suit in the SyncManager.
     * @param request The request object. Validation of this object is done by its builder.
     */
    @Override
    public void sync(SyncRequest request, String callingPackage) {
        syncAsUser(request, UserHandle.getCallingUserId(), callingPackage);
    }

    private long clampPeriod(long period) {
        long minPeriod = JobInfo.getMinPeriodMillis() / 1000;
        if (period < minPeriod) {
            Slog.w(TAG, "Requested poll frequency of " + period
                    + " seconds being rounded up to " + minPeriod + "s.");
            period = minPeriod;
        }
        return period;
    }

    /**
     * If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Override
    public void syncAsUser(SyncRequest request, int userId, String callingPackage) {
        enforceCrossUserPermission(userId, "no permission to request sync as user: " + userId);
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        final Bundle extras = request.getBundle();

        validateExtras(callingUid, extras);
        final int syncExemption = getSyncExemptionAndCleanUpExtrasForCaller(callingUid, extras);

        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager == null) {
                return;
            }
            long flextime = request.getSyncFlexTime();
            long runAtTime = request.getSyncRunTime();
            if (request.isPeriodic()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.WRITE_SYNC_SETTINGS,
                        "no permission to write the sync settings");
                SyncStorageEngine.EndPoint info;
                info = new SyncStorageEngine.EndPoint(
                        request.getAccount(), request.getProvider(), userId);

                runAtTime = clampPeriod(runAtTime);
                // Schedule periodic sync.
                getSyncManager().updateOrAddPeriodicSync(info, runAtTime,
                        flextime, extras);
            } else {
                syncManager.scheduleSync(
                        request.getAccount(), userId, callingUid, request.getProvider(), extras,
                        SyncStorageEngine.AuthorityInfo.UNDEFINED,
                        syncExemption, callingUid, callingPid, callingPackage);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Clear all scheduled sync operations that match the uri and cancel the active sync
     * if they match the authority and account, if they are present.
     *
     * @param account filter the pending and active syncs to cancel using this account, or null.
     * @param authority filter the pending and active syncs to cancel using this authority, or
     * null.
     * @param cname cancel syncs running on this service, or null for provider/account.
     */
    @Override
    public void cancelSync(Account account, String authority, ComponentName cname) {
        cancelSyncAsUser(account, authority, cname, UserHandle.getCallingUserId());
    }

    /**
     * Clear all scheduled sync operations that match the uri and cancel the active sync
     * if they match the authority and account, if they are present.
     *
     * <p> If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param account filter the pending and active syncs to cancel using this account, or null.
     * @param authority filter the pending and active syncs to cancel using this authority, or
     * null.
     * @param userId the user id for which to cancel sync operations.
     * @param cname cancel syncs running on this service, or null for provider/account.
     */
    @Override
    public void cancelSyncAsUser(Account account, String authority, ComponentName cname,
                                 int userId) {
        if (authority != null && authority.length() == 0) {
            throw new IllegalArgumentException("Authority must be non-empty");
        }
        enforceCrossUserPermission(userId,
                "no permission to modify the sync settings for user " + userId);
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        if (cname != null) {
            Slog.e(TAG, "cname not null.");
            return;
        }
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                SyncStorageEngine.EndPoint info;
                info = new SyncStorageEngine.EndPoint(account, authority, userId);
                syncManager.clearScheduledSyncOperations(info);
                syncManager.cancelActiveSync(info, null /* all syncs for this adapter */, "API");
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void cancelRequest(SyncRequest request) {
        SyncManager syncManager = getSyncManager();
        if (syncManager == null) return;
        int userId = UserHandle.getCallingUserId();
        final int callingUid = Binder.getCallingUid();

        if (request.isPeriodic()) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                    "no permission to write the sync settings");
        }

        Bundle extras = new Bundle(request.getBundle());
        validateExtras(callingUid, extras);

        long identityToken = clearCallingIdentity();
        try {
            SyncStorageEngine.EndPoint info;

            Account account = request.getAccount();
            String provider = request.getProvider();
            info = new SyncStorageEngine.EndPoint(account, provider, userId);
            if (request.isPeriodic()) {
                // Remove periodic sync.
                getSyncManager().removePeriodicSync(info, extras,
                        "cancelRequest() by uid=" + callingUid);
            }
            // Cancel active syncs and clear pending syncs from the queue.
            syncManager.cancelScheduledSyncOperation(info, extras);
            syncManager.cancelActiveSync(info, extras, "API");
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Get information about the SyncAdapters that are known to the system.
     * @return an array of SyncAdapters that have registered with the system
     */
    @Override
    public SyncAdapterType[] getSyncAdapterTypes() {
        return getSyncAdapterTypesAsUser(UserHandle.getCallingUserId());
    }

    /**
     * Get information about the SyncAdapters that are known to the system for a particular user.
     *
     * <p> If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     *
     * @return an array of SyncAdapters that have registered with the system
     */
    @Override
    public SyncAdapterType[] getSyncAdapterTypesAsUser(int userId) {
        enforceCrossUserPermission(userId,
                "no permission to read sync settings for user " + userId);
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        final long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            return syncManager.getSyncAdapterTypes(userId);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public String[] getSyncAdapterPackagesForAuthorityAsUser(String authority, int userId) {
        enforceCrossUserPermission(userId,
                "no permission to read sync settings for user " + userId);
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        final long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            return syncManager.getSyncAdapterPackagesForAuthorityAsUser(authority, userId);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean getSyncAutomatically(Account account, String providerName) {
        return getSyncAutomaticallyAsUser(account, providerName, UserHandle.getCallingUserId());
    }

    /**
     * If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Override
    public boolean getSyncAutomaticallyAsUser(Account account, String providerName, int userId) {
        enforceCrossUserPermission(userId,
                "no permission to read the sync settings for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine()
                        .getSyncAutomatically(account, userId, providerName);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return false;
    }

    @Override
    public void setSyncAutomatically(Account account, String providerName, boolean sync) {
        setSyncAutomaticallyAsUser(account, providerName, sync, UserHandle.getCallingUserId());
    }

    @Override
    public void setSyncAutomaticallyAsUser(Account account, String providerName, boolean sync,
                                           int userId) {
        if (TextUtils.isEmpty(providerName)) {
            throw new IllegalArgumentException("Authority must be non-empty");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");
        enforceCrossUserPermission(userId,
                "no permission to modify the sync settings for user " + userId);
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int syncExemptionFlag = getSyncExemptionForCaller(callingUid);

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setSyncAutomatically(account, userId,
                        providerName, sync, syncExemptionFlag, callingUid, callingPid);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /** Old API. Schedule periodic sync with default flexMillis time. */
    @Override
    public void addPeriodicSync(Account account, String authority, Bundle extras,
                                long pollFrequency) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("Authority must not be empty.");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");

        validateExtras(Binder.getCallingUid(), extras);

        int userId = UserHandle.getCallingUserId();

        pollFrequency = clampPeriod(pollFrequency);
        long defaultFlex = SyncStorageEngine.calculateDefaultFlexTime(pollFrequency);

        long identityToken = clearCallingIdentity();
        try {
            SyncStorageEngine.EndPoint info =
                    new SyncStorageEngine.EndPoint(account, authority, userId);
            getSyncManager().updateOrAddPeriodicSync(info, pollFrequency,
                    defaultFlex, extras);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void removePeriodicSync(Account account, String authority, Bundle extras) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");

        validateExtras(Binder.getCallingUid(), extras);

        final int callingUid = Binder.getCallingUid();

        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            getSyncManager()
                    .removePeriodicSync(
                            new SyncStorageEngine.EndPoint(account, authority, userId),
                            extras, "removePeriodicSync() by uid=" + callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public List<PeriodicSync> getPeriodicSyncs(Account account, String providerName,
                                               ComponentName cname) {
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (TextUtils.isEmpty(providerName)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");

        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            return getSyncManager().getPeriodicSyncs(
                    new SyncStorageEngine.EndPoint(account, providerName, userId));
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public int getIsSyncable(Account account, String providerName) {
        return getIsSyncableAsUser(account, providerName, UserHandle.getCallingUserId());
    }

    /**
     * If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Override
    public int getIsSyncableAsUser(Account account, String providerName, int userId) {
        enforceCrossUserPermission(userId,
                "no permission to read the sync settings for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.computeSyncable(
                        account, userId, providerName, false);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return -1;
    }

    @Override
    public void setIsSyncable(Account account, String providerName, int syncable) {
        setIsSyncableAsUser(account, providerName, syncable, UserHandle.getCallingUserId());
    }

    /**
     * @hide
     */
    @Override
    public void setIsSyncableAsUser(Account account, String providerName, int syncable,
            int userId) {
        if (TextUtils.isEmpty(providerName)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        enforceCrossUserPermission(userId,
                "no permission to set the sync settings for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");

        syncable = normalizeSyncable(syncable);
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setIsSyncable(
                        account, userId, providerName, syncable, callingUid, callingPid);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean getMasterSyncAutomatically() {
        return getMasterSyncAutomaticallyAsUser(UserHandle.getCallingUserId());
    }

    /**
     * If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Override
    public boolean getMasterSyncAutomaticallyAsUser(int userId) {
        enforceCrossUserPermission(userId,
                "no permission to read the sync settings for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getMasterSyncAutomatically(userId);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return false;
    }

    @Override
    public void setMasterSyncAutomatically(boolean flag) {
        setMasterSyncAutomaticallyAsUser(flag, UserHandle.getCallingUserId());
    }

    @Override
    public void setMasterSyncAutomaticallyAsUser(boolean flag, int userId) {
        enforceCrossUserPermission(userId,
                "no permission to set the sync status for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");

        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setMasterSyncAutomatically(flag, userId,
                        getSyncExemptionForCaller(callingUid), callingUid, callingPid);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean isSyncActive(Account account, String authority, ComponentName cname) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager == null) {
                return false;
            }
            return syncManager.getSyncStorageEngine().isSyncActive(
                    new SyncStorageEngine.EndPoint(account, authority, userId));
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public List<SyncInfo> getCurrentSyncs() {
        return getCurrentSyncsAsUser(UserHandle.getCallingUserId());
    }

    /**
     * If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Override
    public List<SyncInfo> getCurrentSyncsAsUser(int userId) {
        enforceCrossUserPermission(userId,
                "no permission to read the sync settings for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");

        final boolean canAccessAccounts =
            mContext.checkCallingOrSelfPermission(Manifest.permission.GET_ACCOUNTS)
                == PackageManager.PERMISSION_GRANTED;
        long identityToken = clearCallingIdentity();
        try {
            return getSyncManager().getSyncStorageEngine()
                .getCurrentSyncsCopy(userId, canAccessAccounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public SyncStatusInfo getSyncStatus(Account account, String authority, ComponentName cname) {
        return getSyncStatusAsUser(account, authority, cname, UserHandle.getCallingUserId());
    }

    /**
     * If the user id supplied is different to the calling user, the caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Override
    public SyncStatusInfo getSyncStatusAsUser(Account account, String authority,
                                              ComponentName cname, int userId) {
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }

        enforceCrossUserPermission(userId,
                "no permission to read the sync stats for user " + userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");

        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager == null) {
                return null;
            }
            SyncStorageEngine.EndPoint info;
            if (!(account == null || authority == null)) {
                info = new SyncStorageEngine.EndPoint(account, authority, userId);
            } else {
                throw new IllegalArgumentException("Must call sync status with valid authority");
            }
            return syncManager.getSyncStorageEngine().getStatusByAuthority(info);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean isSyncPending(Account account, String authority, ComponentName cname) {
        return isSyncPendingAsUser(account, authority, cname, UserHandle.getCallingUserId());
    }

    @Override
    public boolean isSyncPendingAsUser(Account account, String authority, ComponentName cname,
                                       int userId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");
        enforceCrossUserPermission(userId,
                "no permission to retrieve the sync settings for user " + userId);
        long identityToken = clearCallingIdentity();
        SyncManager syncManager = getSyncManager();
        if (syncManager == null) return false;

        try {
            SyncStorageEngine.EndPoint info;
            if (!(account == null || authority == null)) {
                info = new SyncStorageEngine.EndPoint(account, authority, userId);
            } else {
                throw new IllegalArgumentException("Invalid authority specified");
            }
            return syncManager.getSyncStorageEngine().isSyncPending(info);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void addStatusChangeListener(int mask, ISyncStatusObserver callback) {
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null && callback != null) {
                syncManager.getSyncStorageEngine().addStatusChangeListener(mask, callback);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void removeStatusChangeListener(ISyncStatusObserver callback) {
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null && callback != null) {
                syncManager.getSyncStorageEngine().removeStatusChangeListener(callback);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private @Nullable String getProviderPackageName(Uri uri) {
        final ProviderInfo pi = mContext.getPackageManager()
                .resolveContentProvider(uri.getAuthority(), 0);
        return (pi != null) ? pi.packageName : null;
    }

    @GuardedBy("mCache")
    private ArrayMap<Pair<String, Uri>, Bundle> findOrCreateCacheLocked(int userId,
            String providerPackageName) {
        ArrayMap<String, ArrayMap<Pair<String, Uri>, Bundle>> userCache = mCache.get(userId);
        if (userCache == null) {
            userCache = new ArrayMap<>();
            mCache.put(userId, userCache);
        }
        ArrayMap<Pair<String, Uri>, Bundle> packageCache = userCache.get(providerPackageName);
        if (packageCache == null) {
            packageCache = new ArrayMap<>();
            userCache.put(providerPackageName, packageCache);
        }
        return packageCache;
    }

    @GuardedBy("mCache")
    private void invalidateCacheLocked(int userId, String providerPackageName, Uri uri) {
        ArrayMap<String, ArrayMap<Pair<String, Uri>, Bundle>> userCache = mCache.get(userId);
        if (userCache == null) return;

        ArrayMap<Pair<String, Uri>, Bundle> packageCache = userCache.get(providerPackageName);
        if (packageCache == null) return;

        if (uri != null) {
            for (int i = 0; i < packageCache.size();) {
                final Pair<String, Uri> key = packageCache.keyAt(i);
                if (key.second != null && key.second.toString().startsWith(uri.toString())) {
                    if (DEBUG) Slog.d(TAG, "Invalidating cache for key " + key);
                    packageCache.removeAt(i);
                } else {
                    i++;
                }
            }
        } else {
            if (DEBUG) Slog.d(TAG, "Invalidating cache for package " + providerPackageName);
            packageCache.clear();
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CACHE_CONTENT)
    public void putCache(String packageName, Uri key, Bundle value, int userId) {
        Bundle.setDefusable(value, true);
        enforceCrossUserPermission(userId, TAG);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CACHE_CONTENT, TAG);
        mContext.getSystemService(AppOpsManager.class).checkPackage(Binder.getCallingUid(),
                packageName);

        final String providerPackageName = getProviderPackageName(key);
        final Pair<String, Uri> fullKey = Pair.create(packageName, key);

        synchronized (mCache) {
            final ArrayMap<Pair<String, Uri>, Bundle> cache = findOrCreateCacheLocked(userId,
                    providerPackageName);
            if (value != null) {
                cache.put(fullKey, value);
            } else {
                cache.remove(fullKey);
            }
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CACHE_CONTENT)
    public Bundle getCache(String packageName, Uri key, int userId) {
        enforceCrossUserPermission(userId, TAG);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CACHE_CONTENT, TAG);
        mContext.getSystemService(AppOpsManager.class).checkPackage(Binder.getCallingUid(),
                packageName);

        final String providerPackageName = getProviderPackageName(key);
        final Pair<String, Uri> fullKey = Pair.create(packageName, key);

        synchronized (mCache) {
            final ArrayMap<Pair<String, Uri>, Bundle> cache = findOrCreateCacheLocked(userId,
                    providerPackageName);
            return cache.get(fullKey);
        }
    }

    private int handleIncomingUser(Uri uri, int pid, int uid, int modeFlags, boolean allowNonFull,
            int userId) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        if (userId == UserHandle.USER_ALL) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, "No access to " + uri);
        } else if (userId < 0) {
            throw new IllegalArgumentException("Invalid user: " + userId);
        } else if (userId != UserHandle.getCallingUserId()) {
            if (checkUriPermission(uri, pid, uid, modeFlags,
                    userId) != PackageManager.PERMISSION_GRANTED) {
                boolean allow = false;
                if (mContext.checkCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                                == PackageManager.PERMISSION_GRANTED) {
                    allow = true;
                } else if (allowNonFull && mContext.checkCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS)
                                == PackageManager.PERMISSION_GRANTED) {
                    allow = true;
                }
                if (!allow) {
                    final String permissions = allowNonFull
                            ? (Manifest.permission.INTERACT_ACROSS_USERS_FULL + " or " +
                                    Manifest.permission.INTERACT_ACROSS_USERS)
                            : Manifest.permission.INTERACT_ACROSS_USERS_FULL;
                    throw new SecurityException("No access to " + uri + ": neither user " + uid
                            + " nor current process has " + permissions);
                }
            }
        }

        return userId;
    }

    /**
     * Checks if the request is from the system or an app that has INTERACT_ACROSS_USERS_FULL
     * permission, if the userHandle is not for the caller.
     *
     * @param userHandle the user handle of the user we want to act on behalf of.
     * @param message the message to log on security exception.
     */
    private void enforceCrossUserPermission(int userHandle, String message) {
        final int callingUser = UserHandle.getCallingUserId();
        if (callingUser != userHandle) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
        }
    }

    private static int normalizeSyncable(int syncable) {
        if (syncable > 0) {
            return SyncStorageEngine.AuthorityInfo.SYNCABLE;
        } else if (syncable == 0) {
            return SyncStorageEngine.AuthorityInfo.NOT_SYNCABLE;
        }
        return SyncStorageEngine.AuthorityInfo.UNDEFINED;
    }

    private void validateExtras(int callingUid, Bundle extras) {
        if (extras.containsKey(ContentResolver.SYNC_VIRTUAL_EXTRAS_EXEMPTION_FLAG)) {
            switch (callingUid) {
                case Process.ROOT_UID:
                case Process.SHELL_UID:
                case Process.SYSTEM_UID:
                    break; // Okay
                default:
                    final String msg = "Invalid extras specified.";
                    Log.w(TAG, msg + " requestsync -f/-F needs to run on 'adb shell'");
                    throw new SecurityException(msg);
            }
        }
    }

    @SyncExemption
    private int getSyncExemptionForCaller(int callingUid) {
        return getSyncExemptionAndCleanUpExtrasForCaller(callingUid, null);
    }

    @SyncExemption
    private int getSyncExemptionAndCleanUpExtrasForCaller(int callingUid, Bundle extras) {
        if (extras != null) {
            final int exemption =
                    extras.getInt(ContentResolver.SYNC_VIRTUAL_EXTRAS_EXEMPTION_FLAG, -1);

            // Need to remove the virtual extra.
            extras.remove(ContentResolver.SYNC_VIRTUAL_EXTRAS_EXEMPTION_FLAG);
            if (exemption != -1) {
                return exemption;
            }
        }
        final ActivityManagerInternal ami =
                LocalServices.getService(ActivityManagerInternal.class);
        if (ami == null) {
            return ContentResolver.SYNC_EXEMPTION_NONE;
        }
        final int procState = ami.getUidProcessState(callingUid);
        final boolean isUidActive = ami.isUidActive(callingUid);

        if (procState <= ActivityManager.PROCESS_STATE_TOP) {
            return ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP;
        }
        if (procState <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND || isUidActive) {
            return ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET;
        }
        return ContentResolver.SYNC_EXEMPTION_NONE;
    }

    /**
     * Hide this class since it is not part of api,
     * but current unittest framework requires it to be public
     * @hide
     */
    public static final class ObserverNode {
        private class ObserverEntry implements IBinder.DeathRecipient {
            public final IContentObserver observer;
            public final int uid;
            public final int pid;
            public final boolean notifyForDescendants;
            private final int userHandle;
            private final Object observersLock;

            public ObserverEntry(IContentObserver o, boolean n, Object observersLock,
                                 int _uid, int _pid, int _userHandle) {
                this.observersLock = observersLock;
                observer = o;
                uid = _uid;
                pid = _pid;
                userHandle = _userHandle;
                notifyForDescendants = n;
                try {
                    observer.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                }
            }

            @Override
            public void binderDied() {
                synchronized (observersLock) {
                    removeObserverLocked(observer);
                }
            }

            public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args,
                                   String name, String prefix, SparseIntArray pidCounts) {
                pidCounts.put(pid, pidCounts.get(pid)+1);
                pw.print(prefix); pw.print(name); pw.print(": pid=");
                pw.print(pid); pw.print(" uid=");
                pw.print(uid); pw.print(" user=");
                pw.print(userHandle); pw.print(" target=");
                pw.println(Integer.toHexString(System.identityHashCode(
                        observer != null ? observer.asBinder() : null)));
            }
        }

        public static final int INSERT_TYPE = 0;
        public static final int UPDATE_TYPE = 1;
        public static final int DELETE_TYPE = 2;

        private String mName;
        private ArrayList<ObserverNode> mChildren = new ArrayList<ObserverNode>();
        private ArrayList<ObserverEntry> mObservers = new ArrayList<ObserverEntry>();

        public ObserverNode(String name) {
            mName = name;
        }

        public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args,
                               String name, String prefix, int[] counts, SparseIntArray pidCounts) {
            String innerName = null;
            if (mObservers.size() > 0) {
                if ("".equals(name)) {
                    innerName = mName;
                } else {
                    innerName = name + "/" + mName;
                }
                for (int i=0; i<mObservers.size(); i++) {
                    counts[1]++;
                    mObservers.get(i).dumpLocked(fd, pw, args, innerName, prefix,
                            pidCounts);
                }
            }
            if (mChildren.size() > 0) {
                if (innerName == null) {
                    if ("".equals(name)) {
                        innerName = mName;
                    } else {
                        innerName = name + "/" + mName;
                    }
                }
                for (int i=0; i<mChildren.size(); i++) {
                    counts[0]++;
                    mChildren.get(i).dumpLocked(fd, pw, args, innerName, prefix,
                            counts, pidCounts);
                }
            }
        }

        private String getUriSegment(Uri uri, int index) {
            if (uri != null) {
                if (index == 0) {
                    return uri.getAuthority();
                } else {
                    return uri.getPathSegments().get(index - 1);
                }
            } else {
                return null;
            }
        }

        private int countUriSegments(Uri uri) {
            if (uri == null) {
                return 0;
            }
            return uri.getPathSegments().size() + 1;
        }

        // Invariant:  userHandle is either a hard user number or is USER_ALL
        public void addObserverLocked(Uri uri, IContentObserver observer,
                                      boolean notifyForDescendants, Object observersLock,
                                      int uid, int pid, int userHandle) {
            addObserverLocked(uri, 0, observer, notifyForDescendants, observersLock,
                    uid, pid, userHandle);
        }

        private void addObserverLocked(Uri uri, int index, IContentObserver observer,
                                       boolean notifyForDescendants, Object observersLock,
                                       int uid, int pid, int userHandle) {
            // If this is the leaf node add the observer
            if (index == countUriSegments(uri)) {
                mObservers.add(new ObserverEntry(observer, notifyForDescendants, observersLock,
                        uid, pid, userHandle));
                return;
            }

            // Look to see if the proper child already exists
            String segment = getUriSegment(uri, index);
            if (segment == null) {
                throw new IllegalArgumentException("Invalid Uri (" + uri + ") used for observer");
            }
            int N = mChildren.size();
            for (int i = 0; i < N; i++) {
                ObserverNode node = mChildren.get(i);
                if (node.mName.equals(segment)) {
                    node.addObserverLocked(uri, index + 1, observer, notifyForDescendants,
                            observersLock, uid, pid, userHandle);
                    return;
                }
            }

            // No child found, create one
            ObserverNode node = new ObserverNode(segment);
            mChildren.add(node);
            node.addObserverLocked(uri, index + 1, observer, notifyForDescendants,
                    observersLock, uid, pid, userHandle);
        }

        public boolean removeObserverLocked(IContentObserver observer) {
            int size = mChildren.size();
            for (int i = 0; i < size; i++) {
                boolean empty = mChildren.get(i).removeObserverLocked(observer);
                if (empty) {
                    mChildren.remove(i);
                    i--;
                    size--;
                }
            }

            IBinder observerBinder = observer.asBinder();
            size = mObservers.size();
            for (int i = 0; i < size; i++) {
                ObserverEntry entry = mObservers.get(i);
                if (entry.observer.asBinder() == observerBinder) {
                    mObservers.remove(i);
                    // We no longer need to listen for death notifications. Remove it.
                    observerBinder.unlinkToDeath(entry, 0);
                    break;
                }
            }

            if (mChildren.size() == 0 && mObservers.size() == 0) {
                return true;
            }
            return false;
        }

        private void collectMyObserversLocked(boolean leaf, IContentObserver observer,
                                              boolean observerWantsSelfNotifications, int flags,
                                              int targetUserHandle, ArrayList<ObserverCall> calls) {
            int N = mObservers.size();
            IBinder observerBinder = observer == null ? null : observer.asBinder();
            for (int i = 0; i < N; i++) {
                ObserverEntry entry = mObservers.get(i);

                // Don't notify the observer if it sent the notification and isn't interested
                // in self notifications
                boolean selfChange = (entry.observer.asBinder() == observerBinder);
                if (selfChange && !observerWantsSelfNotifications) {
                    continue;
                }

                // Does this observer match the target user?
                if (targetUserHandle == UserHandle.USER_ALL
                        || entry.userHandle == UserHandle.USER_ALL
                        || targetUserHandle == entry.userHandle) {
                    // Make sure the observer is interested in the notification
                    if (leaf) {
                        // If we are at the leaf: we always report, unless the sender has asked
                        // to skip observers that are notifying for descendants (since they will
                        // be sending another more specific URI for them).
                        if ((flags&ContentResolver.NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS) != 0
                                && entry.notifyForDescendants) {
                            if (DEBUG) Slog.d(TAG, "Skipping " + entry.observer
                                    + ": skip notify for descendants");
                            continue;
                        }
                    } else {
                        // If we are not at the leaf: we report if the observer says it wants
                        // to be notified for all descendants.
                        if (!entry.notifyForDescendants) {
                            if (DEBUG) Slog.d(TAG, "Skipping " + entry.observer
                                    + ": not monitor descendants");
                            continue;
                        }
                    }
                    if (DEBUG) Slog.d(TAG, "Reporting to " + entry.observer + ": leaf=" + leaf
                            + " flags=" + Integer.toHexString(flags)
                            + " desc=" + entry.notifyForDescendants);
                    calls.add(new ObserverCall(this, entry.observer, selfChange,
                            UserHandle.getUserId(entry.uid)));
                }
            }
        }

        /**
         * targetUserHandle is either a hard user handle or is USER_ALL
         */
        public void collectObserversLocked(Uri uri, int index, IContentObserver observer,
                                           boolean observerWantsSelfNotifications, int flags,
                                           int targetUserHandle, ArrayList<ObserverCall> calls) {
            String segment = null;
            int segmentCount = countUriSegments(uri);
            if (index >= segmentCount) {
                // This is the leaf node, notify all observers
                if (DEBUG) Slog.d(TAG, "Collecting leaf observers @ #" + index + ", node " + mName);
                collectMyObserversLocked(true, observer, observerWantsSelfNotifications,
                        flags, targetUserHandle, calls);
            } else if (index < segmentCount){
                segment = getUriSegment(uri, index);
                if (DEBUG) Slog.d(TAG, "Collecting non-leaf observers @ #" + index + " / "
                        + segment);
                // Notify any observers at this level who are interested in descendants
                collectMyObserversLocked(false, observer, observerWantsSelfNotifications,
                        flags, targetUserHandle, calls);
            }

            int N = mChildren.size();
            for (int i = 0; i < N; i++) {
                ObserverNode node = mChildren.get(i);
                if (segment == null || node.mName.equals(segment)) {
                    // We found the child,
                    node.collectObserversLocked(uri, index + 1, observer,
                            observerWantsSelfNotifications, flags, targetUserHandle, calls);
                    if (segment != null) {
                        break;
                    }
                }
            }
        }
    }

    private void enforceShell(String method) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            throw new SecurityException("Non-shell user attempted to call " + method);
        }
    }

    @Override
    public void resetTodayStats() {
        enforceShell("resetTodayStats");

        if (mSyncManager != null) {
            final long token = Binder.clearCallingIdentity();
            try {
                mSyncManager.resetTodayStats();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public void onDbCorruption(String tag, String message, String stacktrace) {
        Slog.e(tag, message);
        Slog.e(tag, "at " + stacktrace);

        // TODO: Figure out a better way to report it. b/117886381
        Slog.wtf(tag, message);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new ContentShellCommand(this)).exec(this, in, out, err, args, callback, resultReceiver);
    }
}
