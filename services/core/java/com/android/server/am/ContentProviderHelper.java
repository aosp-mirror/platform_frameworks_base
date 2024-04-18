/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.am;

import static android.Manifest.permission.GET_ANY_PROVIDER_TYPE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_GET_PROVIDER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_REMOVE_PROVIDER;
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_PROVIDER;
import static android.content.ContentProvider.isAuthorityRedirectedForCloneProfile;
import static android.os.Process.PROC_CHAR;
import static android.os.Process.PROC_OUT_LONG;
import static android.os.Process.PROC_PARENS;
import static android.os.Process.PROC_SPACE_TERM;
import static android.os.Process.SYSTEM_UID;

import static com.android.internal.util.FrameworkStatsLog.GET_TYPE_ACCESSED_WITHOUT_PERMISSION;
import static com.android.internal.util.FrameworkStatsLog.GET_TYPE_ACCESSED_WITHOUT_PERMISSION__LOCATION__AM_FRAMEWORK_PERMISSION;
import static com.android.internal.util.FrameworkStatsLog.PROVIDER_ACQUISITION_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
import static com.android.internal.util.FrameworkStatsLog.PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED;
import static com.android.internal.util.FrameworkStatsLog.PROVIDER_ACQUISITION_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.PROVIDER_ACQUISITION_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerService.TAG_MU;
import static com.android.server.am.Flags.serviceBindingOomAdjPolicy;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.app.usage.UsageEvents.Event;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.RescueParty;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.sdksandbox.SdkSandboxManagerLocal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Activity manager code dealing with content providers.
 */
public class ContentProviderHelper {
    private static final String TAG = "ContentProviderHelper";

    private final ActivityManagerService mService;

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    private final ArrayList<ContentProviderRecord> mLaunchingProviders = new ArrayList<>();
    private final ProviderMap mProviderMap;
    private boolean mSystemProvidersInstalled;

    private final Map<String, Boolean> mCloneProfileAuthorityRedirectionCache = new HashMap<>();

    ContentProviderHelper(ActivityManagerService service, boolean createProviderMap) {
        mService = service;
        mProviderMap = createProviderMap ? new ProviderMap(mService) : null;
    }

    ProviderMap getProviderMap() {
        return mProviderMap;
    }

    ContentProviderHolder getContentProvider(IApplicationThread caller, String callingPackage,
            String name, int userId, boolean stable) {
        mService.enforceNotIsolatedCaller("getContentProvider");
        if (caller == null) {
            String msg = "null IApplicationThread when getting content provider " + name;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        // The incoming user check is now handled in checkContentProviderPermissionLocked() to deal
        // with cross-user grant.
        final int callingUid = Binder.getCallingUid();
        if (callingPackage != null && mService.mAppOpsService.checkPackage(
                callingUid, callingPackage) != AppOpsManager.MODE_ALLOWED) {
            throw new SecurityException("Given calling package " + callingPackage
                    + " does not match caller's uid " + callingUid);
        }
        return getContentProviderImpl(caller, name, null, callingUid, callingPackage,
                null, stable, userId);
    }

    ContentProviderHolder getContentProviderExternal(
            String name, int userId, IBinder token, String tag) {
        mService.enforceCallingPermission(
                android.Manifest.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY,
                "Do not have permission in call getContentProviderExternal()");
        userId = mService.mUserController.handleIncomingUser(
                Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ActivityManagerInternal.ALLOW_FULL_ONLY, "getContentProvider", null);
        return getContentProviderExternalUnchecked(name, token, Binder.getCallingUid(),
                tag != null ? tag : "*external*", userId);
    }

    ContentProviderHolder getContentProviderExternalUnchecked(String name,
            IBinder token, int callingUid, String callingTag, int userId) {
        return getContentProviderImpl(null, name, token, callingUid, null, callingTag,
                true, userId);
    }

    private ContentProviderHolder getContentProviderImpl(IApplicationThread caller,
            String name, IBinder token, int callingUid, String callingPackage, String callingTag,
            boolean stable, int userId) {
        ContentProviderRecord cpr = null;
        ContentProviderConnection conn = null;
        ProviderInfo cpi = null;
        boolean providerRunning = false;
        final int expectedUserId = userId;
        synchronized (mService) {
            long startTime = SystemClock.uptimeMillis();
            long startElapsedTimeNs = SystemClock.elapsedRealtimeNanos();

            ProcessRecord r = null;
            if (caller != null) {
                r = mService.getRecordForAppLOSP(caller);
                if (r == null) {
                    throw new SecurityException("Unable to find app for caller " + caller
                            + " (pid=" + Binder.getCallingPid() + ") when getting content provider "
                            + name);
                }
            }

            boolean checkCrossUser = true;

            checkTime(startTime, "getContentProviderImpl: getProviderByName");

            UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);

            /*
             For clone user profile and allowed authority, skipping finding provider and redirecting
             it to owner profile. Ideally clone profile should not have MediaProvider instance
             installed and mProviderMap would not have entry for clone user. This is just fallback
             check to ensure even if MediaProvider is installed in Clone Profile, it should not be
             used and redirect to owner user's MediaProvider.
             */
            //todo(b/236121588) MediaProvider should not be installed in clone profile.
            final UserProperties userProps = umInternal.getUserProperties(userId);
            final boolean isMediaSharedWithParent =
                    userProps != null && userProps.isMediaSharedWithParent();
            if (!isAuthorityRedirectedForCloneProfileCached(name) || !isMediaSharedWithParent) {
                // First check if this content provider has been published...
                cpr = mProviderMap.getProviderByName(name, userId);
                // In case we are on media authority and callingUid is cloned app asking to access
                // the contentProvider of user 0 by specifying content as
                // content://<parent-id>@media/external/file, we skip checkUser.
                if (isAuthorityRedirectedForCloneProfileCached(name)) {
                    final int callingUserId = UserHandle.getUserId(callingUid);
                    final UserProperties userPropsCallingUser =
                            umInternal.getUserProperties(callingUserId);
                    final boolean isMediaSharedWithParentForCallingUser =
                            userPropsCallingUser != null
                                    && userPropsCallingUser.isMediaSharedWithParent();
                    if (isMediaSharedWithParentForCallingUser
                            && umInternal.getProfileParentId(callingUserId) == userId) {
                        checkCrossUser = false;
                    }
                }
            }
            // If that didn't work, check if it exists for user 0 and then
            // verify that it's a singleton provider before using it.
            if (cpr == null && userId != UserHandle.USER_SYSTEM) {
                cpr = mProviderMap.getProviderByName(name, UserHandle.USER_SYSTEM);
                if (cpr != null) {
                    cpi = cpr.info;

                    if (mService.isSingleton(
                            cpi.processName, cpi.applicationInfo, cpi.name, cpi.flags)
                                && mService.isValidSingletonCall(
                                        r == null ? callingUid : r.uid, cpi.applicationInfo.uid)) {
                        userId = UserHandle.USER_SYSTEM;
                        checkCrossUser = false;
                    } else if (isAuthorityRedirectedForCloneProfileCached(name)) {
                        if (isMediaSharedWithParent) {
                            userId = umInternal.getProfileParentId(userId);
                            checkCrossUser = false;
                        }
                    } else {
                        cpr = null;
                        cpi = null;
                    }
                }
            }

            ProcessRecord dyingProc = null;
            if (cpr != null && cpr.proc != null) {
                providerRunning = !cpr.proc.isKilled();

                // Note if killedByAm is also set, this means the provider process has just been
                // killed by AM (in ProcessRecord.kill()), but appDiedLocked() hasn't been called
                // yet. So we need to call appDiedLocked() here and let it clean up.
                // (See the commit message on I2c4ba1e87c2d47f2013befff10c49b3dc337a9a7 to see
                // how to test this case.)
                if (cpr.proc.isKilled() && cpr.proc.isKilledByAm()) {
                    Slog.wtf(TAG, cpr.proc.toString() + " was killed by AM but isn't really dead");
                    // Now we are going to wait for the death before starting the new process.
                    dyingProc = cpr.proc;
                }
            }

            final int callingProcessState = r != null
                    ? r.mState.getCurProcState() : ActivityManager.PROCESS_STATE_UNKNOWN;

            if (providerRunning) {
                cpi = cpr.info;


                if (r != null && cpr.canRunHere(r)) {
                    checkAssociationAndPermissionLocked(r, cpi, callingUid, userId, checkCrossUser,
                            cpr.name.flattenToShortString(), startTime);

                    // This provider has been published or is in the process
                    // of being published...  but it is also allowed to run
                    // in the caller's process, so don't make a connection
                    // and just let the caller instantiate its own instance.
                    ContentProviderHolder holder = cpr.newHolder(null, true);
                    // don't give caller the provider object, it needs to make its own.
                    holder.provider = null;
                    FrameworkStatsLog.write(
                            PROVIDER_ACQUISITION_EVENT_REPORTED,
                            r.uid, callingUid,
                            PROVIDER_ACQUISITION_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM,
                            PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL,
                            cpi.packageName, callingPackage,
                            callingProcessState, callingProcessState,
                            false, 0L);
                    return holder;
                }

                // Don't expose providers between normal apps and instant apps; enforce limited
                // package visibility (introduced in Android 11); etc.
                try {
                    if (AppGlobals.getPackageManager()
                            .resolveContentProvider(name, /*flags=*/ 0, userId) == null) {
                        return null;
                    }
                } catch (RemoteException e) {
                }

                checkAssociationAndPermissionLocked(r, cpi, callingUid, userId, checkCrossUser,
                        cpr.name.flattenToShortString(), startTime);

                final int providerProcessState = cpr.proc.mState.getCurProcState();

                final long origId = Binder.clearCallingIdentity();
                try {
                    checkTime(startTime, "getContentProviderImpl: incProviderCountLocked");

                    // Return the provider instance right away since it already exists.
                    conn = incProviderCountLocked(r, cpr, token, callingUid, callingPackage,
                            callingTag, stable, true, startTime, mService.mProcessList,
                            expectedUserId);

                    checkTime(startTime, "getContentProviderImpl: before updateOomAdj");
                    final int verifiedAdj = cpr.proc.mState.getVerifiedAdj();
                    boolean success = !serviceBindingOomAdjPolicy()
                            || mService.mOomAdjuster.evaluateProviderConnectionAdd(r, cpr.proc)
                            ? mService.updateOomAdjLocked(cpr.proc, OOM_ADJ_REASON_GET_PROVIDER)
                            : true;
                    // XXX things have changed so updateOomAdjLocked doesn't actually tell us
                    // if the process has been successfully adjusted.  So to reduce races with
                    // it, we will check whether the process still exists.  Note that this doesn't
                    // completely get rid of races with LMK killing the process, but should make
                    // them much smaller.
                    if (success && verifiedAdj != cpr.proc.mState.getSetAdj()
                            && !isProcessAliveLocked(cpr.proc)) {
                        success = false;
                    }
                    maybeUpdateProviderUsageStatsLocked(r, cpr.info.packageName, name);
                    checkTime(startTime, "getContentProviderImpl: after updateOomAdj");
                    if (ActivityManagerDebugConfig.DEBUG_PROVIDER) {
                        Slog.i(TAG, "Adjust success: " + success);
                    }
                    // NOTE: there is still a race here where a signal could be
                    // pending on the process even though we managed to update its
                    // adj level.  Not sure what to do about this, but at least
                    // the race is now smaller.
                    if (!success) {
                        // Uh oh...  it looks like the provider's process
                        // has been killed on us.  We need to wait for a new
                        // process to be started, and make sure its death
                        // doesn't kill our process.
                        Slog.wtf(TAG, "Existing provider " + cpr.name.flattenToShortString()
                                + " is crashing; detaching " + r);
                        boolean lastRef = decProviderCountLocked(conn, cpr, token, stable,
                                false, false);
                        if (!lastRef) {
                            // This wasn't the last ref our process had on
                            // the provider...  we will be killed during cleaning up, bail.
                            return null;
                        }
                        // We'll just start a new process to host the content provider
                        providerRunning = false;
                        conn = null;
                        dyingProc = cpr.proc;
                    } else {
                        cpr.proc.mState.setVerifiedAdj(cpr.proc.mState.getSetAdj());
                        FrameworkStatsLog.write(
                                PROVIDER_ACQUISITION_EVENT_REPORTED,
                                cpr.proc.uid, callingUid,
                                PROVIDER_ACQUISITION_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM,
                                PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL,
                                cpi.packageName, callingPackage,
                                callingProcessState, providerProcessState, false, 0L);
                    }
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }

            if (!providerRunning) {
                try {
                    checkTime(startTime, "getContentProviderImpl: before resolveContentProvider");
                    cpi = AppGlobals.getPackageManager().resolveContentProvider(name,
                            ActivityManagerService.STOCK_PM_FLAGS
                                    | PackageManager.GET_URI_PERMISSION_PATTERNS,
                            userId);
                    checkTime(startTime, "getContentProviderImpl: after resolveContentProvider");
                } catch (RemoteException ex) {
                }
                if (cpi == null) {
                    return null;
                }
                // If the provider is a singleton AND
                // (it's a call within the same user || the provider is a privileged app)
                // Then allow connecting to the singleton provider
                boolean singleton = mService.isSingleton(
                        cpi.processName, cpi.applicationInfo, cpi.name, cpi.flags)
                            && mService.isValidSingletonCall(
                                    r == null ? callingUid : r.uid, cpi.applicationInfo.uid);
                if (singleton) {
                    userId = UserHandle.USER_SYSTEM;
                }
                cpi.applicationInfo = mService.getAppInfoForUser(cpi.applicationInfo, userId);
                checkTime(startTime, "getContentProviderImpl: got app info for user");

                checkAssociationAndPermissionLocked(r, cpi, callingUid, userId, !singleton,
                        name, startTime);

                if (!mService.mProcessesReady && !cpi.processName.equals("system")) {
                    // If this content provider does not run in the system
                    // process, and the system is not yet ready to run other
                    // processes, then fail fast instead of hanging.
                    throw new IllegalArgumentException(
                            "Attempt to launch content provider before system ready");
                }

                // If system providers are not installed yet we aggressively crash to avoid
                // creating multiple instance of these providers and then bad things happen!
                synchronized (this) {
                    if (!mSystemProvidersInstalled && cpi.applicationInfo.isSystemApp()
                            && "system".equals(cpi.processName)) {
                        throw new IllegalStateException("Cannot access system provider: '"
                                + cpi.authority + "' before system providers are installed!");
                    }
                }

                // Make sure that the user who owns this provider is running.  If not,
                // we don't want to allow it to run.
                if (!mService.mUserController.isUserRunning(userId, 0)) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/" + cpi.applicationInfo.uid
                            + " for provider " + name + ": user " + userId + " is stopped");
                    return null;
                }

                ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                checkTime(startTime, "getContentProviderImpl: before getProviderByClass");
                cpr = mProviderMap.getProviderByClass(comp, userId);
                checkTime(startTime, "getContentProviderImpl: after getProviderByClass");

                // The old stable connection's client should be killed during proc cleaning up,
                // so do not re-use the old ContentProviderRecord, otherwise the new clients
                // could get killed unexpectedly. Meanwhile, we should retrieve the latest
                // application info from package manager instead of reusing the info from
                // the dying one, as the package could have been updated.
                boolean firstClass = cpr == null || (dyingProc == cpr.proc && dyingProc != null);
                if (firstClass) {
                    final long ident = Binder.clearCallingIdentity();

                    // If permissions need a review before any of the app components can run,
                    // we return no provider and launch a review activity if the calling app
                    // is in the foreground.
                    if (!requestTargetProviderPermissionsReviewIfNeededLocked(
                            cpi, r, userId, mService.mContext)) {
                        return null;
                    }

                    try {
                        checkTime(startTime, "getContentProviderImpl: before getApplicationInfo");
                        ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(
                                cpi.applicationInfo.packageName,
                                ActivityManagerService.STOCK_PM_FLAGS, userId);
                        checkTime(startTime, "getContentProviderImpl: after getApplicationInfo");
                        if (ai == null) {
                            Slog.w(TAG, "No package info for content provider " + cpi.name);
                            return null;
                        }
                        ai = mService.getAppInfoForUser(ai, userId);
                        cpr = new ContentProviderRecord(mService, cpi, ai, comp, singleton);
                    } catch (RemoteException ex) {
                        // pm is in same process, this will never happen.
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }

                checkTime(startTime, "getContentProviderImpl: now have ContentProviderRecord");

                if (r != null && cpr.canRunHere(r)) {
                    // If this is a multiprocess provider, then just return its
                    // info and allow the caller to instantiate it.  Only do
                    // this if the provider is the same user as the caller's
                    // process, or can run as root (so can be in any process).
                    return cpr.newHolder(null, true);
                }

                if (ActivityManagerDebugConfig.DEBUG_PROVIDER) {
                    Slog.w(TAG, "LAUNCHING REMOTE PROVIDER (myuid " + (r != null ? r.uid : null)
                            + " pruid " + cpr.appInfo.uid + "): " + cpr.info.name
                            + " callers=" + Debug.getCallers(6));
                }

                // This is single process, and our app is now connecting to it.
                // See if we are already in the process of launching this provider.
                final int numLaunchingProviders = mLaunchingProviders.size();
                int i;
                for (i = 0; i < numLaunchingProviders; i++) {
                    if (mLaunchingProviders.get(i) == cpr) {
                        break;
                    }
                }

                // If the provider is not already being launched, then get it started.
                if (i >= numLaunchingProviders) {
                    final long origId = Binder.clearCallingIdentity();

                    try {
                        if (!TextUtils.equals(cpr.appInfo.packageName, callingPackage)) {
                            // Report component used since a content provider is being bound.
                            mService.mUsageStatsService.reportEvent(
                                    cpr.appInfo.packageName, userId, Event.APP_COMPONENT_USED);
                        }

                        try {
                            checkTime(startTime,
                                    "getContentProviderImpl: before set stopped state");
                            mService.mPackageManagerInt.notifyComponentUsed(
                                    cpr.appInfo.packageName, userId, callingPackage,
                                    cpr.toString());
                            checkTime(startTime, "getContentProviderImpl: after set stopped state");
                        } catch (IllegalArgumentException e) {
                            Slog.w(TAG, "Failed trying to unstop package "
                                    + cpr.appInfo.packageName + ": " + e);
                        }

                        // Use existing process if already started
                        checkTime(startTime, "getContentProviderImpl: looking for process record");
                        ProcessRecord proc = mService.getProcessRecordLocked(
                                cpi.processName, cpr.appInfo.uid);
                        IApplicationThread thread;
                        if (proc != null && (thread = proc.getThread()) != null
                                && !proc.isKilled()) {
                            if (ActivityManagerDebugConfig.DEBUG_PROVIDER) {
                                Slog.d(TAG, "Installing in existing process " + proc);
                            }
                            final ProcessProviderRecord pr = proc.mProviders;
                            if (!pr.hasProvider(cpi.name)) {
                                checkTime(startTime, "getContentProviderImpl: scheduling install");
                                pr.installProvider(cpi.name, cpr);
                                try {
                                    thread.scheduleInstallProvider(cpi);
                                } catch (RemoteException e) {
                                }
                            }
                            FrameworkStatsLog.write(
                                    PROVIDER_ACQUISITION_EVENT_REPORTED,
                                    proc.uid, callingUid,
                                    PROVIDER_ACQUISITION_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM,
                                    PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL,
                                    cpi.packageName, callingPackage,
                                    callingProcessState, proc.mState.getCurProcState(),
                                    false, 0L);
                        } else {
                            final boolean stopped =
                                    (cpr.appInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0;
                            final int packageState = stopped
                                    ? PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED
                                    : PROVIDER_ACQUISITION_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
                            final boolean firstLaunch = !mService.wasPackageEverLaunched(
                                    cpi.packageName, userId);
                            checkTime(startTime, "getContentProviderImpl: before start process");
                            proc = mService.startProcessLocked(
                                    cpi.processName, cpr.appInfo, false, 0,
                                    new HostingRecord(HostingRecord.HOSTING_TYPE_CONTENT_PROVIDER,
                                        new ComponentName(
                                                cpi.applicationInfo.packageName, cpi.name)),
                                    Process.ZYGOTE_POLICY_FLAG_EMPTY, false, false);
                            checkTime(startTime, "getContentProviderImpl: after start process");
                            if (proc == null) {
                                Slog.w(TAG, "Unable to launch app "
                                        + cpi.applicationInfo.packageName + "/"
                                        + cpi.applicationInfo.uid + " for provider " + name
                                        + ": process is bad");
                                return null;
                            }
                            if (DEBUG_PROCESSES) {
                                Slog.d(TAG, "Logging provider access for " + cpi.packageName
                                        + ", stopped=" + stopped + ", firstLaunch=" + firstLaunch);
                            }
                            FrameworkStatsLog.write(
                                    PROVIDER_ACQUISITION_EVENT_REPORTED,
                                    proc.uid, callingUid,
                                    PROVIDER_ACQUISITION_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD,
                                    packageState, cpi.packageName, callingPackage,
                                    callingProcessState, ActivityManager.PROCESS_STATE_NONEXISTENT,
                                    firstLaunch,
                                    0L /* TODO: stoppedDuration */);
                            mService.mProcessList.getAppStartInfoTracker()
                                    .handleProcessContentProviderStart(startElapsedTimeNs, proc);
                        }
                        cpr.launchingApp = proc;
                        mLaunchingProviders.add(cpr);
                    } finally {
                        Binder.restoreCallingIdentity(origId);
                    }
                }

                checkTime(startTime, "getContentProviderImpl: updating data structures");

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProviderMap.putProviderByClass(comp, cpr);
                }

                mProviderMap.putProviderByName(name, cpr);
                conn = incProviderCountLocked(r, cpr, token, callingUid, callingPackage, callingTag,
                        stable, false, startTime, mService.mProcessList, expectedUserId);
                if (conn != null) {
                    conn.waiting = true;
                }
            }
            checkTime(startTime, "getContentProviderImpl: done!");

            mService.grantImplicitAccess(userId, null, callingUid,
                    UserHandle.getAppId(cpi.applicationInfo.uid));

            if (caller != null) {
                // The client will be waiting, and we'll notify it when the provider is ready.
                synchronized (cpr) {
                    if (cpr.provider == null) {
                        if (cpr.launchingApp == null) {
                            Slog.w(TAG, "Unable to launch app "
                                    + cpi.applicationInfo.packageName + "/"
                                    + cpi.applicationInfo.uid + " for provider "
                                    + name + ": launching app became null");
                            EventLogTags.writeAmProviderLostProcess(
                                    UserHandle.getUserId(cpi.applicationInfo.uid),
                                    cpi.applicationInfo.packageName,
                                    cpi.applicationInfo.uid, name);
                            return null;
                        }

                        if (conn != null) {
                            conn.waiting = true;
                        }
                        Message msg = mService.mHandler.obtainMessage(
                                ActivityManagerService.WAIT_FOR_CONTENT_PROVIDER_TIMEOUT_MSG);
                        msg.obj = cpr;
                        mService.mHandler.sendMessageDelayed(msg,
                                ContentResolver.CONTENT_PROVIDER_READY_TIMEOUT_MILLIS);
                    }
                }
                // Return a holder instance even if we are waiting for the publishing of the
                // provider, client will check for the holder.provider to see if it needs to wait
                // for it.
                return cpr.newHolder(conn, false);
            }
        }

        // Because of the provider's external client (i.e., SHELL), we'll have to wait right here.
        // Wait for the provider to be published...
        final long timeout =
                SystemClock.uptimeMillis() + ContentResolver.CONTENT_PROVIDER_READY_TIMEOUT_MILLIS;
        boolean timedOut = false;
        synchronized (cpr) {
            while (cpr.provider == null) {
                if (cpr.launchingApp == null) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/" + cpi.applicationInfo.uid
                            + " for provider " + name + ": launching app became null");
                    EventLogTags.writeAmProviderLostProcess(
                            UserHandle.getUserId(cpi.applicationInfo.uid),
                            cpi.applicationInfo.packageName, cpi.applicationInfo.uid, name);
                    return null;
                }
                try {
                    final long wait = Math.max(0L, timeout - SystemClock.uptimeMillis());
                    if (DEBUG_MU) {
                        Slog.v(TAG_MU, "Waiting to start provider " + cpr
                                + " launchingApp=" + cpr.launchingApp + " for " + wait + " ms");
                    }
                    if (conn != null) {
                        conn.waiting = true;
                    }
                    cpr.wait(wait);
                    if (cpr.provider == null) {
                        timedOut = true;
                        break;
                    }
                } catch (InterruptedException ex) {
                } finally {
                    if (conn != null) {
                        conn.waiting = false;
                    }
                }
            }
        }
        if (timedOut) {
            // Note we do it after releasing the lock.
            String callerName = "unknown";
            if (caller != null) {
                synchronized (mService.mProcLock) {
                    final ProcessRecord record =
                            mService.mProcessList.getLRURecordForAppLOSP(caller);
                    if (record != null) {
                        callerName = record.processName;
                    }
                }
            }

            Slog.wtf(TAG, "Timeout waiting for provider "
                    + cpi.applicationInfo.packageName + "/" + cpi.applicationInfo.uid
                    + " for provider " + name + " providerRunning=" + providerRunning
                    + " caller=" + callerName + "/" + Binder.getCallingUid());
            return null;
        }
        return cpr.newHolder(conn, false);
    }

    private void checkAssociationAndPermissionLocked(ProcessRecord callingApp, ProviderInfo cpi,
            int callingUid, int userId, boolean checkUser, String cprName, long startTime) {
        String msg;
        if ((msg = checkContentProviderAssociation(callingApp, callingUid, cpi)) != null) {
            throw new SecurityException("Content provider lookup " + cprName
                    + " failed: association not allowed with package " + msg);
        }
        checkTime(startTime, "getContentProviderImpl: before checkContentProviderPermission");
        if ((msg = checkContentProviderPermission(
                    cpi, Binder.getCallingPid(), Binder.getCallingUid(), userId, checkUser,
                    callingApp != null ? callingApp.toString() : null))
                != null) {
            throw new SecurityException(msg);
        }
        checkTime(startTime, "getContentProviderImpl: after checkContentProviderPermission");
    }

    void publishContentProviders(IApplicationThread caller, List<ContentProviderHolder> providers) {
        if (providers == null) {
            return;
        }

        mService.enforceNotIsolatedOrSdkSandboxCaller("publishContentProviders");
        synchronized (mService) {
            final ProcessRecord r = mService.getRecordForAppLOSP(caller);
            if (DEBUG_MU) {
                Slog.v(TAG_MU, "ProcessRecord uid = " + r.uid);
            }
            if (r == null) {
                throw new SecurityException("Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when publishing content providers");
            }

            final long origId = Binder.clearCallingIdentity();
            boolean providersPublished = false;
            for (int i = 0, size = providers.size(); i < size; i++) {
                ContentProviderHolder src = providers.get(i);
                if (src == null || src.info == null || src.provider == null) {
                    continue;
                }
                ContentProviderRecord dst = r.mProviders.getProvider(src.info.name);
                if (dst == null) {
                    continue;
                }
                if (DEBUG_MU) {
                    Slog.v(TAG_MU, "ContentProviderRecord uid = " + dst.uid);
                }
                providersPublished = true;

                ComponentName comp = new ComponentName(dst.info.packageName, dst.info.name);
                mProviderMap.putProviderByClass(comp, dst);
                String[] names = dst.info.authority.split(";");
                for (int j = 0; j < names.length; j++) {
                    mProviderMap.putProviderByName(names[j], dst);
                }

                boolean wasInLaunchingProviders = false;
                for (int j = 0, numLaunching = mLaunchingProviders.size(); j < numLaunching; j++) {
                    if (mLaunchingProviders.get(j) == dst) {
                        mLaunchingProviders.remove(j);
                        wasInLaunchingProviders = true;
                        j--;
                        numLaunching--;
                    }
                }
                if (wasInLaunchingProviders) {
                    mService.mHandler.removeMessages(
                            ActivityManagerService.WAIT_FOR_CONTENT_PROVIDER_TIMEOUT_MSG, dst);
                    mService.mHandler.removeMessages(
                            ActivityManagerService.CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG, r);
                }
                // Make sure the package is associated with the process.
                // XXX We shouldn't need to do this, since we have added the package
                // when we generated the providers in generateApplicationProvidersLocked().
                // But for some reason in some cases we get here with the package no longer
                // added...  for now just patch it in to make things happy.
                r.addPackage(dst.info.applicationInfo.packageName,
                        dst.info.applicationInfo.longVersionCode, mService.mProcessStats);
                synchronized (dst) {
                    dst.provider = src.provider;
                    dst.setProcess(r);
                    dst.notifyAll();
                    dst.onProviderPublishStatusLocked(true);
                }
                dst.mRestartCount = 0;
                if (hasProviderConnectionLocked(r)) {
                    r.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_PROVIDER);
                }
            }

            // update the app's oom adj value and each provider's usage stats
            if (providersPublished) {
                mService.updateOomAdjLocked(r, OOM_ADJ_REASON_GET_PROVIDER);
                for (int i = 0, size = providers.size(); i < size; i++) {
                    ContentProviderHolder src = providers.get(i);
                    if (src == null || src.info == null || src.provider == null) {
                        continue;
                    }
                    maybeUpdateProviderUsageStatsLocked(r,
                            src.info.packageName, src.info.authority);
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Drop a content provider from a ProcessRecord's bookkeeping
     */
    void removeContentProvider(IBinder connection, boolean stable) {
        mService.enforceNotIsolatedCaller("removeContentProvider");
        final long ident = Binder.clearCallingIdentity();
        try {
            ContentProviderConnection conn;
            try {
                conn = (ContentProviderConnection) connection;
            } catch (ClassCastException e) {
                String msg = "removeContentProvider: " + connection
                        + " not a ContentProviderConnection";
                Slog.w(TAG, msg);
                throw new IllegalArgumentException(msg);
            }
            if (conn == null) {
                throw new NullPointerException("connection is null");
            }
            ActivityManagerService.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "removeContentProvider: ",
                    (conn.provider != null && conn.provider.info != null
                    ? conn.provider.info.authority : ""));
            try {
                synchronized (mService) {
                    decProviderCountLocked(conn, null, null, stable, true, true);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void removeContentProviderExternalAsUser(String name, IBinder token, int userId) {
        mService.enforceCallingPermission(
                android.Manifest.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY,
                "Do not have permission in call removeContentProviderExternal()");
        final long ident = Binder.clearCallingIdentity();
        try {
            removeContentProviderExternalUnchecked(name, token, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void removeContentProviderExternalUnchecked(String name, IBinder token, int userId) {
        synchronized (mService) {
            ContentProviderRecord cpr = mProviderMap.getProviderByName(name, userId);
            if (cpr == null) {
                //remove from mProvidersByClass
                if (ActivityManagerDebugConfig.DEBUG_ALL) {
                    Slog.v(TAG, name + " content provider not found in providers list");
                }
                return;
            }

            // update content provider record entry info
            ComponentName comp = new ComponentName(cpr.info.packageName, cpr.info.name);
            ContentProviderRecord localCpr = mProviderMap.getProviderByClass(comp, userId);
            if (localCpr.hasExternalProcessHandles()) {
                if (localCpr.removeExternalProcessHandleLocked(token)) {
                    mService.updateOomAdjLocked(localCpr.proc, OOM_ADJ_REASON_REMOVE_PROVIDER);
                } else {
                    Slog.e(TAG, "Attempt to remove content provider " + localCpr
                            + " with no external reference for token: " + token + ".");
                }
            } else {
                Slog.e(TAG, "Attempt to remove content provider: " + localCpr
                        + " with no external references.");
            }
        }
    }

    boolean refContentProvider(IBinder connection, int stable, int unstable) {
        ContentProviderConnection conn;
        try {
            conn = (ContentProviderConnection) connection;
        } catch (ClassCastException e) {
            String msg = "refContentProvider: " + connection + " not a ContentProviderConnection";
            Slog.w(TAG, msg);
            throw new IllegalArgumentException(msg);
        }
        if (conn == null) {
            throw new NullPointerException("connection is null");
        }

        ActivityManagerService.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "refContentProvider: ",
                (conn.provider != null && conn.provider.info != null
                ? conn.provider.info.authority : ""));
        try {
            conn.adjustCounts(stable, unstable);
            return !conn.dead;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    void unstableProviderDied(IBinder connection) {
        ContentProviderConnection conn;
        try {
            conn = (ContentProviderConnection) connection;
        } catch (ClassCastException e) {
            String msg = "refContentProvider: " + connection + " not a ContentProviderConnection";
            Slog.w(TAG, msg);
            throw new IllegalArgumentException(msg);
        }
        if (conn == null) {
            throw new NullPointerException("connection is null");
        }

        ActivityManagerService.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "unstableProviderDied: ",
                (conn.provider != null && conn.provider.info != null
                ? conn.provider.info.authority : ""));

        try {
            // Safely retrieve the content provider associated with the connection.
            IContentProvider provider;
            synchronized (mService) {
                provider = conn.provider.provider;
            }

            if (provider == null) {
                // Um, yeah, we're way ahead of you.
                return;
            }

            // Make sure the caller is being honest with us.
            if (provider.asBinder().pingBinder()) {
                // Er, no, still looks good to us.
                synchronized (mService) {
                    Slog.w(TAG, "unstableProviderDied: caller " + Binder.getCallingUid()
                            + " says " + conn + " died, but we don't agree");
                    return;
                }
            }

            // Well look at that!  It's dead!
            synchronized (mService) {
                if (conn.provider.provider != provider) {
                    // But something changed...  good enough.
                    return;
                }

                ProcessRecord proc = conn.provider.proc;
                if (proc == null || proc.getThread() == null) {
                    // Seems like the process is already cleaned up.
                    return;
                }

                // As far as we're concerned, this is just like receiving a
                // death notification...  just a bit prematurely.
                mService.reportUidInfoMessageLocked(TAG, "Process " + proc.processName
                                + " (pid " + proc.getPid() + ") early provider death",
                                proc.info.uid);
                final long token = Binder.clearCallingIdentity();
                try {
                    mService.appDiedLocked(proc, "unstable content provider");
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    void appNotRespondingViaProvider(IBinder connection) {
        mService.enforceCallingPermission(android.Manifest.permission.REMOVE_TASKS,
                "appNotRespondingViaProvider()");

        final ContentProviderConnection conn = (ContentProviderConnection) connection;
        if (conn == null) {
            Slog.w(TAG, "ContentProviderConnection is null");
            return;
        }

        ActivityManagerService.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "appNotRespondingViaProvider: ",
                (conn.provider != null && conn.provider.info != null
                ? conn.provider.info.authority : ""));
        try {
            final ProcessRecord host = conn.provider.proc;
            if (host == null) {
                Slog.w(TAG, "Failed to find hosting ProcessRecord");
                return;
            }

            TimeoutRecord timeoutRecord = TimeoutRecord.forContentProvider(
                    "ContentProvider not responding");
            mService.mAnrHelper.appNotResponding(host, timeoutRecord);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    /**
     * Filters calls to getType based on permission. If the caller has required permission,
     * then it returns the contentProvider#getType.
     * Else, it returns the contentProvider#getTypeAnonymous, which does not
     * reveal any internal information which should be protected by any permission.
     */
    void getMimeTypeFilterAsync(Uri uri, int userId, RemoteCallback resultCallback) {
        mService.enforceNotIsolatedCaller("getProviderMimeTypeAsync");
        final String name = uri.getAuthority();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int safeUserId = mService.mUserController.unsafeConvertIncomingUser(userId);
        final long ident = canClearIdentity(callingPid, callingUid, safeUserId)
                ? Binder.clearCallingIdentity() : 0;
        final ContentProviderHolder holder;
        try {
            holder = getContentProviderExternalUnchecked(name, null /* token */, callingUid,
                    "*getmimetype*", safeUserId);
        } finally {
            if (ident != 0) {
                Binder.restoreCallingIdentity(ident);
            }
        }

        try {
            if (isHolderVisibleToCaller(holder, callingUid, safeUserId)) {
                if (checkGetAnyTypePermission(callingUid, callingPid)) {
                    final AttributionSource attributionSource =
                            new AttributionSource.Builder(callingUid).build();
                    holder.provider.getTypeAsync(attributionSource,
                            uri, new RemoteCallback(result -> {
                                final long identity = Binder.clearCallingIdentity();
                                try {
                                    removeContentProviderExternalUnchecked(name, null, safeUserId);
                                } finally {
                                    Binder.restoreCallingIdentity(identity);
                                }
                                resultCallback.sendResult(result);
                            }));
                } else {
                    holder.provider.getTypeAnonymousAsync(uri, new RemoteCallback(result -> {
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            removeContentProviderExternalUnchecked(name, null, safeUserId);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                        resultCallback.sendResult(result);
                        final String type = result.getPairValue();
                        if (type != null) {
                            logGetTypeData(callingUid, uri, type);
                        }
                    }));
                }
            } else {
                resultCallback.sendResult(Bundle.EMPTY);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Content provider dead retrieving " + uri, e);
            resultCallback.sendResult(Bundle.EMPTY);
        }
    }

    private boolean checkGetAnyTypePermission(int callingUid, int callingPid) {
        if (mService.checkPermission(GET_ANY_PROVIDER_TYPE, callingPid, callingUid)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    // Utility function to log the getTypeData calls
    private void logGetTypeData(int callingUid, Uri uri, String type) {
        FrameworkStatsLog.write(GET_TYPE_ACCESSED_WITHOUT_PERMISSION,
                GET_TYPE_ACCESSED_WITHOUT_PERMISSION__LOCATION__AM_FRAMEWORK_PERMISSION,
                callingUid, uri.getAuthority(), type);
    }

    private boolean canClearIdentity(int callingPid, int callingUid, int userId) {
        if (UserHandle.getUserId(callingUid) == userId) {
            return true;
        }
        return ActivityManagerService.checkComponentPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS, callingPid,
                callingUid, -1, true) == PackageManager.PERMISSION_GRANTED
                || ActivityManagerService.checkComponentPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, callingPid,
                        callingUid, -1, true) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isHolderVisibleToCaller(@Nullable ContentProviderHolder holder, int callingUid,
            @UserIdInt int userId) {
        if (holder == null || holder.info == null) {
            return false;
        }

        if (isAuthorityRedirectedForCloneProfileCached(holder.info.authority)
                && resolveParentUserIdForCloneProfile(userId) != userId) {
            // Since clone profile shares certain providers with its parent and the access is
            // re-directed as well, the holder may not actually be installed on the clone profile.
            return !mService.getPackageManagerInternal().filterAppAccess(holder.info.packageName,
                    callingUid, userId, false /* filterUninstalled */);
        }

        return !mService.getPackageManagerInternal().filterAppAccess(holder.info.packageName,
                callingUid, userId);
    }

    private static @UserIdInt int resolveParentUserIdForCloneProfile(@UserIdInt int userId) {
        final UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
        final UserInfo userInfo = umInternal.getUserInfo(userId);

        if (userInfo == null || !userInfo.isCloneProfile()) {
            return userId;
        }

        return umInternal.getProfileParentId(userId);
    }

    /**
     * Check if the calling UID has a possible chance at accessing the provider
     * at the given authority and user.
     */
    String checkContentProviderAccess(String authority, int userId) {
        boolean checkUser = true;
        if (userId == UserHandle.USER_ALL) {
            mService.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
            userId = UserHandle.getCallingUserId();
        }

        if (isAuthorityRedirectedForCloneProfileCached(authority)) {
            UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
            UserInfo userInfo = umInternal.getUserInfo(userId);

            if (userInfo != null && userInfo.isCloneProfile()) {
                userId = umInternal.getProfileParentId(userId);
                checkUser = false;
            }
        }

        ProviderInfo cpi = null;
        try {
            cpi = AppGlobals.getPackageManager().resolveContentProvider(authority,
                    ActivityManagerService.STOCK_PM_FLAGS
                            | PackageManager.GET_URI_PERMISSION_PATTERNS
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    userId);
        } catch (RemoteException ignored) {
        }
        if (cpi == null) {
            return "Failed to find provider " + authority + " for user " + userId
                    + "; expected to find a valid ContentProvider for this authority";
        }

        final int callingPid = Binder.getCallingPid();
        ProcessRecord r;
        final String appName;
        synchronized (mService.mPidsSelfLocked) {
            r = mService.mPidsSelfLocked.get(callingPid);
            if (r == null) {
                return "Failed to find PID " + callingPid;
            }
            appName = r.toString();
        }

        return checkContentProviderPermission(cpi, callingPid, Binder.getCallingUid(),
                userId, checkUser, appName);
    }

    int checkContentProviderUriPermission(Uri uri, int userId, int callingUid, int modeFlags) {
        if (Thread.holdsLock(mService.mActivityTaskManager.getGlobalLock())) {
            Slog.wtf(TAG, new IllegalStateException("Unable to check Uri permission"
                    + " because caller is holding WM lock; assuming permission denied"));
            return PackageManager.PERMISSION_DENIED;
        }

        final String name = uri.getAuthority();
        final long ident = Binder.clearCallingIdentity();
        ContentProviderHolder holder = null;
        try {
            holder = getContentProviderExternalUnchecked(name, null, callingUid,
                    "*checkContentProviderUriPermission*", userId);
            if (holder != null) {

                final AndroidPackage androidPackage = mService.getPackageManagerInternal()
                        .getPackage(Binder.getCallingUid());
                if (androidPackage == null) {
                    return PackageManager.PERMISSION_DENIED;
                }

                final AttributionSource attributionSource = new AttributionSource(
                        callingUid, androidPackage.getPackageName(), null);
                return holder.provider.checkUriPermission(attributionSource, uri, callingUid,
                        modeFlags);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Content provider dead retrieving " + uri, e);
            return PackageManager.PERMISSION_DENIED;
        } catch (Exception e) {
            Log.w(TAG, "Exception while determining type of " + uri, e);
            return PackageManager.PERMISSION_DENIED;
        } finally {
            try {
                if (holder != null) {
                    removeContentProviderExternalUnchecked(name, null, userId);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @GuardedBy("mService")
    void processContentProviderPublishTimedOutLocked(ProcessRecord app) {
        cleanupAppInLaunchingProvidersLocked(app, true);
        mService.mProcessList.removeProcessLocked(app, false, true,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.SUBREASON_UNKNOWN,
                "timeout publishing content providers");
    }

    List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
        final List<ProviderInfo> providers;
        try {
            providers = AppGlobals.getPackageManager().queryContentProviders(
                                app.processName, app.uid, ActivityManagerService.STOCK_PM_FLAGS
                                    | PackageManager.GET_URI_PERMISSION_PATTERNS
                                    | PackageManager.MATCH_DIRECT_BOOT_AUTO, /*metaDataKey=*/ null)
                            .getList();
        } catch (RemoteException ex) {
            return null;
        }
        if (providers == null) {
            return null;
        }

        if (DEBUG_MU) {
            Slog.v(TAG_MU, "generateApplicationProvidersLocked, app.info.uid = " + app.uid);
        }

        int numProviders = providers.size();
        final ProcessProviderRecord pr = app.mProviders;
        pr.ensureProviderCapacity(numProviders + pr.numberOfProviders());
        for (int i = 0; i < numProviders; i++) {
            // NOTE: keep logic in sync with installEncryptionUnawareProviders
            ProviderInfo cpi = providers.get(i);
            boolean singleton = mService.isSingleton(cpi.processName, cpi.applicationInfo,
                    cpi.name, cpi.flags);
            if (isSingletonOrSystemUserOnly(cpi) && app.userId != UserHandle.USER_SYSTEM) {
                // This is a singleton or a SYSTEM user only provider, but a user besides the
                // SYSTEM user is asking to initialize a process it runs
                // in...  well, no, it doesn't actually run in this process,
                // it runs in the process of the default user.  Get rid of it.
                providers.remove(i);
                numProviders--;
                i--;
                continue;
            }
            final boolean isInstantApp = cpi.applicationInfo.isInstantApp();
            final boolean splitInstalled = cpi.splitName == null || ArrayUtils.contains(
                    cpi.applicationInfo.splitNames, cpi.splitName);
            if (isInstantApp && !splitInstalled) {
                // For instant app, allow provider that is defined in the provided split apk.
                // Skipping it if the split apk is not installed.
                providers.remove(i);
                numProviders--;
                i--;
                continue;
            }

            ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
            ContentProviderRecord cpr = mProviderMap.getProviderByClass(comp, app.userId);
            if (cpr == null) {
                cpr = new ContentProviderRecord(mService, cpi, app.info, comp, singleton);
                mProviderMap.putProviderByClass(comp, cpr);
            }
            if (DEBUG_MU) {
                Slog.v(TAG_MU, "generateApplicationProvidersLocked, cpi.uid = " + cpr.uid);
            }
            pr.installProvider(cpi.name, cpr);
            if (!cpi.multiprocess || !"android".equals(cpi.packageName)) {
                // Don't add this if it is a platform component that is marked
                // to run in multiple processes, because this is actually
                // part of the framework so doesn't make sense to track as a
                // separate apk in the process.
                app.addPackage(cpi.applicationInfo.packageName, cpi.applicationInfo.longVersionCode,
                        mService.mProcessStats);
            }
            mService.notifyPackageUse(cpi.applicationInfo.packageName,
                    PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER);
        }
        return providers.isEmpty() ? null : providers;
    }

    private final class DevelopmentSettingsObserver extends ContentObserver {
        private final Uri mUri = Settings.Global.getUriFor(
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);

        private final ComponentName mBugreportStorageProvider = new ComponentName(
                "com.android.shell", "com.android.shell.BugreportStorageProvider");

        DevelopmentSettingsObserver() {
            super(mService.mHandler);
            mService.mContext.getContentResolver().registerContentObserver(mUri, false, this,
                    UserHandle.USER_ALL);
            // Always kick once to ensure that we match current state
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        private void onChange() {
            final boolean enabled = Settings.Global.getInt(mService.mContext.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Build.IS_ENG ? 1 : 0) != 0;
            mService.mContext.getPackageManager().setComponentEnabledSetting(
                    mBugreportStorageProvider,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    0);
        }
    }

    public final void installSystemProviders() {
        List<ProviderInfo> providers;
        synchronized (mService) {
            ProcessRecord app = mService.mProcessList
                    .getProcessNamesLOSP().get("system", SYSTEM_UID);
            providers = generateApplicationProvidersLocked(app);
            if (providers != null) {
                for (int i = providers.size() - 1; i >= 0; i--) {
                    ProviderInfo pi = providers.get(i);
                    if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        Slog.w(TAG, "Not installing system proc provider " + pi.name
                                + ": not system .apk");
                        providers.remove(i);
                    }
                }
            }
        }

        if (providers != null) {
            mService.mSystemThread.installSystemProviders(providers);
        }
        synchronized (this) {
            mSystemProvidersInstalled = true;
        }

        mService.mConstants.start(mService.mContext.getContentResolver());
        mService.mCoreSettingsObserver = new CoreSettingsObserver(mService);
        mService.mActivityTaskManager.installSystemProviders();
        new DevelopmentSettingsObserver(); // init to observe developer settings enable/disable
        SettingsToPropertiesMapper.start(mService.mContext.getContentResolver());
        mService.mOomAdjuster.initSettings();

        // Now that the settings provider is published we can consider sending in a rescue party.
        RescueParty.onSettingsProviderPublished(mService.mContext);
    }

    /**
     * When a user is unlocked, we need to install encryption-unaware providers
     * belonging to any running apps.
     */
    void installEncryptionUnawareProviders(int userId) {
        // We're only interested in providers that are encryption unaware, and
        // we don't care about uninstalled apps, since there's no way they're
        // running at this point.
        final int matchFlags =
                PackageManager.GET_PROVIDERS | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        synchronized (mService.mProcLock) {
            final ArrayMap<String, SparseArray<ProcessRecord>> pmap =
                    mService.mProcessList.getProcessNamesLOSP().getMap();
            final int numProc = pmap.size();
            for (int iProc = 0; iProc < numProc; iProc++) {
                final SparseArray<ProcessRecord> apps = pmap.valueAt(iProc);
                for (int iApp = 0, numApps = apps.size(); iApp < numApps; iApp++) {
                    final ProcessRecord app = apps.valueAt(iApp);
                    if (app.userId != userId || app.getThread() == null || app.isUnlocked()) {
                        continue;
                    }

                    app.getPkgList().forEachPackage(pkgName -> {
                        try {
                            final PackageInfo pkgInfo = AppGlobals.getPackageManager()
                                    .getPackageInfo(pkgName, matchFlags, app.userId);
                            final IApplicationThread thread = app.getThread();
                            if (pkgInfo != null && !ArrayUtils.isEmpty(pkgInfo.providers)) {
                                for (ProviderInfo pi : pkgInfo.providers) {
                                    // NOTE: keep in sync with generateApplicationProvidersLocked
                                    final boolean processMatch =
                                            Objects.equals(pi.processName, app.processName)
                                            || pi.multiprocess;
                                    final boolean userMatch = !isSingletonOrSystemUserOnly(pi)
                                            || app.userId == UserHandle.USER_SYSTEM;
                                    final boolean isInstantApp = pi.applicationInfo.isInstantApp();
                                    final boolean splitInstalled = pi.splitName == null
                                            || ArrayUtils.contains(pi.applicationInfo.splitNames,
                                                    pi.splitName);
                                    if (processMatch && userMatch
                                            && (!isInstantApp || splitInstalled)) {
                                        Log.v(TAG, "Installing " + pi);
                                        thread.scheduleInstallProvider(pi);
                                    } else {
                                        Log.v(TAG, "Skipping " + pi);
                                    }
                                }
                            }
                        } catch (RemoteException ignored) {
                        }
                    });
                }
            }
        }
    }

    @GuardedBy("mService")
    private ContentProviderConnection incProviderCountLocked(ProcessRecord r,
            final ContentProviderRecord cpr, IBinder externalProcessToken, int callingUid,
            String callingPackage, String callingTag, boolean stable, boolean updateLru,
            long startTime, ProcessList processList, @UserIdInt int expectedUserId) {
        if (r == null) {
            cpr.addExternalProcessHandleLocked(externalProcessToken, callingUid, callingTag);
            return null;
        }


        final ProcessProviderRecord pr = r.mProviders;
        for (int i = 0, size = pr.numberOfProviderConnections(); i < size; i++) {
            ContentProviderConnection conn = pr.getProviderConnectionAt(i);
            if (conn.provider == cpr) {
                conn.incrementCount(stable);
                return conn;
            }
        }

        // Create a new ContentProviderConnection.  The reference count is known to be 1.
        ContentProviderConnection conn = new ContentProviderConnection(cpr, r, callingPackage,
                expectedUserId);
        conn.startAssociationIfNeeded();
        conn.initializeCount(stable);
        cpr.connections.add(conn);
        if (cpr.proc != null) {
            cpr.proc.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_PROVIDER);
        }
        pr.addProviderConnection(conn);
        mService.startAssociationLocked(r.uid, r.processName, r.mState.getCurProcState(),
                cpr.uid, cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
        if (updateLru && cpr.proc != null
                && r.mState.getSetAdj() <= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
            // If this is a perceptible app accessing the provider, make
            // sure to count it as being accessed and thus back up on
            // the LRU list.  This is good because content providers are
            // often expensive to start.  The calls to checkTime() use
            // the "getContentProviderImpl" tag here, because it's part
            // of the checktime log in getContentProviderImpl().
            checkTime(startTime, "getContentProviderImpl: before updateLruProcess");
            processList.updateLruProcessLocked(cpr.proc, false, null);
            checkTime(startTime, "getContentProviderImpl: after updateLruProcess");
        }
        return conn;
    }

    @GuardedBy("mService")
    private boolean decProviderCountLocked(ContentProviderConnection conn,
            ContentProviderRecord cpr, IBinder externalProcessToken, boolean stable,
            boolean enforceDelay, boolean updateOomAdj) {
        if (conn == null) {
            cpr.removeExternalProcessHandleLocked(externalProcessToken);
            return false;
        }

        if (conn.totalRefCount() > 1) {
            conn.decrementCount(stable);
            return false;
        }
        if (enforceDelay) {
            // delay the removal of the provider for 5 seconds - this optimizes for those cases
            // where providers are released and then quickly re-acquired, causing lots of churn.
            BackgroundThread.getHandler().postDelayed(() -> {
                handleProviderRemoval(conn, stable, updateOomAdj);
            }, 5 * 1000);
        } else {
            handleProviderRemoval(conn, stable, updateOomAdj);
        }
        return true;
    }

    @GuardedBy("mService")
    private boolean hasProviderConnectionLocked(ProcessRecord proc) {
        for (int i = proc.mProviders.numberOfProviders() - 1; i >= 0; i--) {
            if (!proc.mProviders.getProviderAt(i).connections.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void handleProviderRemoval(ContentProviderConnection conn, boolean stable,
            boolean updateOomAdj) {
        synchronized (mService) {
            // if the proc was already killed or this is not the last reference, simply exit.
            if (conn == null || conn.provider == null || conn.decrementCount(stable) != 0) {
                return;
            }

            final ContentProviderRecord cpr = conn.provider;
            conn.stopAssociation();
            cpr.connections.remove(conn);
            if (cpr.proc != null && !hasProviderConnectionLocked(cpr.proc)) {
                cpr.proc.mProfile.clearHostingComponentType(HOSTING_COMPONENT_TYPE_PROVIDER);
            }
            conn.client.mProviders.removeProviderConnection(conn);
            if (conn.client.mState.getSetProcState()
                    < ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
                // The client is more important than last activity -- note the time this
                // is happening, so we keep the old provider process around a bit as last
                // activity to avoid thrashing it.
                if (cpr.proc != null) {
                    cpr.proc.mProviders.setLastProviderTime(SystemClock.uptimeMillis());
                }
            }
            mService.stopAssociationLocked(conn.client.uid, conn.client.processName, cpr.uid,
                    cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
            if (updateOomAdj && (!serviceBindingOomAdjPolicy()
                    || mService.mOomAdjuster.evaluateProviderConnectionRemoval(conn.client,
                            cpr.proc))) {
                mService.updateOomAdjLocked(conn.provider.proc, OOM_ADJ_REASON_REMOVE_PROVIDER);
            }
        }
    }

    /**
     * Check if {@link ProcessRecord} has a possible chance at accessing the
     * given {@link ProviderInfo}. First permission checking is for enforcing
     * ContentProvider Restrictions from SdkSandboxManager.
     * Final permission checking is always done
     * in {@link ContentProvider}.
     */
    private String checkContentProviderPermission(ProviderInfo cpi, int callingPid, int callingUid,
            int userId, boolean checkUser, String appName) {
        if (!canAccessContentProviderFromSdkSandbox(cpi, callingUid)) {
            return "ContentProvider access not allowed from sdk sandbox UID. "
                    + "ProviderInfo: " + cpi.toString();
        }
        boolean checkedGrants = false;
        if (checkUser) {
            // Looking for cross-user grants before enforcing the typical cross-users permissions
            int tmpTargetUserId = mService.mUserController.unsafeConvertIncomingUser(userId);
            if (tmpTargetUserId != UserHandle.getUserId(callingUid)) {
                if (mService.mUgmInternal.checkAuthorityGrants(
                        callingUid, cpi, tmpTargetUserId, checkUser)) {
                    return null;
                }
                checkedGrants = true;
            }
            userId = mService.mUserController.handleIncomingUser(callingPid, callingUid, userId,
                    false, ActivityManagerInternal.ALLOW_NON_FULL,
                    "checkContentProviderPermissionLocked " + cpi.authority, null);
            if (userId != tmpTargetUserId) {
                // When we actually went to determine the final target user ID, this ended
                // up different than our initial check for the authority.  This is because
                // they had asked for USER_CURRENT_OR_SELF and we ended up switching to
                // SELF.  So we need to re-check the grants again.
                checkedGrants = false;
            }
        }
        if (ActivityManagerService.checkComponentPermission(cpi.readPermission,
                callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported)
                == PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        if (ActivityManagerService.checkComponentPermission(cpi.writePermission,
                callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported)
                == PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        PathPermission[] pps = cpi.pathPermissions;
        if (pps != null) {
            int i = pps.length;
            while (i > 0) {
                i--;
                PathPermission pp = pps[i];
                String pprperm = pp.getReadPermission();
                if (pprperm != null && ActivityManagerService.checkComponentPermission(pprperm,
                        callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
                String ppwperm = pp.getWritePermission();
                if (ppwperm != null && ActivityManagerService.checkComponentPermission(ppwperm,
                        callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
            }
        }
        if (!checkedGrants
                && mService.mUgmInternal.checkAuthorityGrants(callingUid, cpi, userId, checkUser)) {
            return null;
        }

        final String suffix;
        if (!cpi.exported) {
            suffix = " that is not exported from UID " + cpi.applicationInfo.uid;
        } else if (android.Manifest.permission.MANAGE_DOCUMENTS.equals(cpi.readPermission)) {
            suffix = " requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs";
        } else {
            suffix = " requires " + cpi.readPermission + " or " + cpi.writePermission;
        }
        final String msg = "Permission Denial: opening provider " + cpi.name
                + " from " + (appName != null ? appName : "(null)")
                + " (pid=" + callingPid + ", uid=" + callingUid + ")" + suffix;
        Slog.w(TAG, msg);
        return msg;
    }

    private String checkContentProviderAssociation(ProcessRecord callingApp, int callingUid,
            ProviderInfo cpi) {
        if (callingApp == null) {
            return mService.validateAssociationAllowedLocked(cpi.packageName,
                    cpi.applicationInfo.uid, null, callingUid) ? null : "<null>";
        }
        final String r = callingApp.getPkgList().searchEachPackage(pkgName -> {
            if (!mService.validateAssociationAllowedLocked(pkgName,
                        callingApp.uid, cpi.packageName, cpi.applicationInfo.uid)) {
                return cpi.packageName;
            }
            return null;
        });
        return r;
    }

    ProviderInfo getProviderInfoLocked(String authority, @UserIdInt int userId, int pmFlags) {
        ContentProviderRecord cpr = mProviderMap.getProviderByName(authority, userId);
        if (cpr != null) {
            return cpr.info;
        } else {
            try {
                return AppGlobals.getPackageManager().resolveContentProvider(
                        authority, PackageManager.GET_URI_PERMISSION_PATTERNS | pmFlags, userId);
            } catch (RemoteException ex) {
                return null;
            }
        }
    }

    private void maybeUpdateProviderUsageStatsLocked(ProcessRecord app, String providerPkgName,
            String authority) {
        if (app == null || app.mState.getCurProcState()
                > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
            return;
        }

        UserState userState = mService.mUserController.getStartedUserState(app.userId);
        if (userState == null) return;
        final long now = SystemClock.elapsedRealtime();
        Long lastReported = userState.mProviderLastReportedFg.get(authority);
        if (lastReported == null || lastReported < now - 60 * 1000L) {
            if (mService.mSystemReady) {
                // Cannot touch the user stats if not system ready
                mService.mUsageStatsService.reportContentProviderUsage(
                        authority, providerPkgName, app.userId);
            }
            userState.mProviderLastReportedFg.put(authority, now);
        }
    }

    private static final int[] PROCESS_STATE_STATS_FORMAT = new int[] {
            PROC_SPACE_TERM,
            PROC_SPACE_TERM | PROC_PARENS,
            PROC_SPACE_TERM | PROC_CHAR | PROC_OUT_LONG,        // 3: process state
    };

    private final long[] mProcessStateStatsLongs = new long[1];

    private boolean isProcessAliveLocked(ProcessRecord proc) {
        final int pid = proc.getPid();
        if (pid <= 0) {
            if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                Slog.d(ActivityManagerService.TAG, "Process hasn't started yet: " + proc);
            }
            return false;
        }
        final String procStatFile = "/proc/" + pid + "/stat";
        mProcessStateStatsLongs[0] = 0;
        if (!Process.readProcFile(procStatFile, PROCESS_STATE_STATS_FORMAT, null,
                mProcessStateStatsLongs, null)) {
            if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                Slog.d(ActivityManagerService.TAG,
                        "UNABLE TO RETRIEVE STATE FOR " + procStatFile);
            }
            return false;
        }
        final long state = mProcessStateStatsLongs[0];
        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
            Slog.d(ActivityManagerService.TAG,
                    "RETRIEVED STATE FOR " + procStatFile + ": " + (char) state);
        }
        if (state != 'Z' && state != 'X' && state != 'x' && state != 'K') {
            return Process.getUidForPid(pid) == proc.uid;
        }
        return false;
    }

    private static final class StartActivityRunnable implements Runnable {
        private final Context mContext;
        private final Intent mIntent;
        private final UserHandle mUserHandle;

        StartActivityRunnable(Context context, Intent intent, UserHandle userHandle) {
            this.mContext = context;
            this.mIntent = intent;
            this.mUserHandle = userHandle;
        }

        @Override
        public void run() {
            mContext.startActivityAsUser(mIntent, mUserHandle);
        }
    }

    private boolean requestTargetProviderPermissionsReviewIfNeededLocked(ProviderInfo cpi,
            ProcessRecord r, final int userId, Context context) {
        if (!mService.getPackageManagerInternal().isPermissionsReviewRequired(
                cpi.packageName, userId)) {
            return true;
        }

        final boolean callerForeground = r == null
                || r.mState.getSetSchedGroup() != ProcessList.SCHED_GROUP_BACKGROUND;

        // Show a permission review UI only for starting from a foreground app
        if (!callerForeground) {
            Slog.w(TAG, "u" + userId + " Instantiating a provider in package "
                    + cpi.packageName + " requires a permissions review");
            return false;
        }

        final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, cpi.packageName);

        if (ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW) {
            Slog.i(TAG, "u" + userId + " Launching permission review "
                    + "for package " + cpi.packageName);
        }

        final UserHandle userHandle = new UserHandle(userId);
        mService.mHandler.post(new StartActivityRunnable(context, intent, userHandle));

        return false;
    }

    /**
     * Remove the dying provider from known provider map and launching provider map.
     * @param proc The dying process recoder
     * @param cpr The provider to be removed.
     * @param always If true, remove the provider from launching map always, no more restart attempt
     * @return true if the given provider is in launching
     */
    boolean removeDyingProviderLocked(ProcessRecord proc, ContentProviderRecord cpr,
            boolean always) {
        boolean inLaunching = mLaunchingProviders.contains(cpr);
        if (inLaunching && !always && ++cpr.mRestartCount > ContentProviderRecord.MAX_RETRY_COUNT) {
            // It's being launched but we've reached maximum attempts, force the removal
            always = true;
        }

        if (!inLaunching || always) {
            synchronized (cpr) {
                cpr.launchingApp = null;
                cpr.notifyAll();
                cpr.onProviderPublishStatusLocked(false);
                mService.mHandler.removeMessages(
                        ActivityManagerService.WAIT_FOR_CONTENT_PROVIDER_TIMEOUT_MSG, cpr);
            }
            final int userId = UserHandle.getUserId(cpr.uid);
            // Don't remove from provider map if it doesn't match
            // could be a new content provider is starting
            if (mProviderMap.getProviderByClass(cpr.name, userId) == cpr) {
                mProviderMap.removeProviderByClass(cpr.name, userId);
            }
            String[] names = cpr.info.authority.split(";");
            for (int j = 0; j < names.length; j++) {
                // Don't remove from provider map if it doesn't match
                // could be a new content provider is starting
                if (mProviderMap.getProviderByName(names[j], userId) == cpr) {
                    mProviderMap.removeProviderByName(names[j], userId);
                }
            }
        }

        for (int i = cpr.connections.size() - 1; i >= 0; i--) {
            ContentProviderConnection conn = cpr.connections.get(i);
            if (conn.waiting) {
                // If this connection is waiting for the provider, then we don't
                // need to mess with its process unless we are always removing
                // or for some reason the provider is not currently launching.
                if (inLaunching && !always) {
                    continue;
                }
            }
            ProcessRecord capp = conn.client;
            final IApplicationThread thread = capp.getThread();
            conn.dead = true;
            if (conn.stableCount() > 0) {
                final int pid = capp.getPid();
                if (!capp.isPersistent() && thread != null
                        && pid != 0 && pid != ActivityManagerService.MY_PID) {
                    capp.killLocked(
                            "depends on provider " + cpr.name.flattenToShortString()
                            + " in dying proc " + (proc != null ? proc.processName : "??")
                            + " (adj " + (proc != null ? proc.mState.getSetAdj() : "??") + ")",
                            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                            ApplicationExitInfo.SUBREASON_UNKNOWN,
                            true);
                }
            } else if (thread != null && conn.provider.provider != null) {
                try {
                    thread.unstableProviderDied(conn.provider.provider.asBinder());
                } catch (RemoteException e) {
                }
                // In the protocol here, we don't expect the client to correctly
                // clean up this connection, we'll just remove it.
                cpr.connections.remove(i);
                if (cpr.proc != null && !hasProviderConnectionLocked(cpr.proc)) {
                    cpr.proc.mProfile.clearHostingComponentType(HOSTING_COMPONENT_TYPE_PROVIDER);
                }
                if (conn.client.mProviders.removeProviderConnection(conn)) {
                    mService.stopAssociationLocked(capp.uid, capp.processName,
                            cpr.uid, cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
                }
            }
        }

        if (inLaunching && always) {
            mLaunchingProviders.remove(cpr);
            cpr.mRestartCount = 0;
            inLaunching = false;
        }
        return inLaunching;
    }

    boolean checkAppInLaunchingProvidersLocked(ProcessRecord app) {
        for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                return true;
            }
        }
        return false;
    }

    boolean cleanupAppInLaunchingProvidersLocked(ProcessRecord app, boolean alwaysBad) {
        // Look through the content providers we are waiting to have launched,
        // and if any run in this process then either schedule a restart of
        // the process or kill the client waiting for it if this process has
        // gone bad.
        boolean restart = false;
        for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = mLaunchingProviders.get(i);
            if (cpr.launchingApp != app) {
                continue;
            }

            if (++cpr.mRestartCount > ContentProviderRecord.MAX_RETRY_COUNT) {
                // It's being launched but we've reached maximum attempts, mark it as bad
                alwaysBad = true;
            }
            if (!alwaysBad && !app.mErrorState.isBad() && cpr.hasConnectionOrHandle()) {
                restart = true;
            } else {
                removeDyingProviderLocked(app, cpr, true);
            }
        }
        return restart;
    }

    void cleanupLaunchingProvidersLocked() {
        for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = mLaunchingProviders.get(i);
            if (cpr.connections.size() <= 0 && !cpr.hasExternalProcessHandles()) {
                synchronized (cpr) {
                    cpr.launchingApp = null;
                    cpr.notifyAll();
                }
            }
        }
    }

    private void checkTime(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if ((now - startTime) > 50) {
            // If we are taking more than 50ms, log about it.
            Slog.w(TAG, "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    void dumpProvidersLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        ActivityManagerService.ItemMatcher matcher = new ActivityManagerService.ItemMatcher();
        matcher.build(args, opti);

        pw.println("ACTIVITY MANAGER CONTENT PROVIDERS (dumpsys activity providers)");

        boolean needSep = mProviderMap.dumpProvidersLocked(pw, dumpAll, dumpPackage);
        boolean printedAnything = needSep;

        if (mLaunchingProviders.size() > 0) {
            boolean printed = false;
            for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
                ContentProviderRecord r = mLaunchingProviders.get(i);
                if (dumpPackage != null && !dumpPackage.equals(r.name.getPackageName())) {
                    continue;
                }
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Launching content providers:");
                    printed = true;
                    printedAnything = true;
                }
                pw.print("  Launching #"); pw.print(i); pw.print(": ");
                pw.println(r);
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    private boolean canAccessContentProviderFromSdkSandbox(ProviderInfo cpi,
                                                                    int callingUid) {
        if (!Process.isSdkSandboxUid(callingUid)) {
            return true;
        }
        final SdkSandboxManagerLocal sdkSandboxManagerLocal =
                LocalManagerRegistry.getManager(SdkSandboxManagerLocal.class);
        if (sdkSandboxManagerLocal == null) {
            throw new IllegalStateException("SdkSandboxManagerLocal not found "
                    + "when checking whether SDK sandbox uid may "
                    + "access the contentprovider.");
        }
        return sdkSandboxManagerLocal.canAccessContentProviderFromSdkSandbox(cpi);
    }

    /**
     * There are three ways to call this:
     *  - no provider specified: dump all the providers
     *  - a flattened component name that matched an existing provider was specified as the
     *    first arg: dump that one provider
     *  - the first arg isn't the flattened component name of an existing provider:
     *    dump all providers whose component contains the first arg as a substring
     */
    protected boolean dumpProvider(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        return mProviderMap.dumpProvider(fd, pw, name, args, opti, dumpAll);
    }

    /**
     * Similar to the dumpProvider, but only dumps the first matching provider.
     * The provider is responsible for dumping as proto.
     */
    protected boolean dumpProviderProto(FileDescriptor fd, PrintWriter pw, String name,
            String[] args) {
        return mProviderMap.dumpProviderProto(fd, pw, name, args);
    }

    private boolean isAuthorityRedirectedForCloneProfileCached(String auth) {
        if (mCloneProfileAuthorityRedirectionCache.containsKey(auth)) {
            final Boolean retVal = mCloneProfileAuthorityRedirectionCache.get(auth);
            return retVal == null ? false : retVal.booleanValue();
        } else {
            boolean isAuthRedirected = isAuthorityRedirectedForCloneProfile(auth);
            mCloneProfileAuthorityRedirectionCache.put(auth, isAuthRedirected);
            return isAuthRedirected;
        }
    }

    /**
     * Returns true if Provider is either singleUser or systemUserOnly provider.
     */
    private boolean isSingletonOrSystemUserOnly(ProviderInfo pi) {
        return (android.multiuser.Flags.enableSystemUserOnlyForServicesAndProviders()
                && mService.isSystemUserOnly(pi.flags))
                || mService.isSingleton(pi.processName, pi.applicationInfo, pi.name, pi.flags);
    }
}
