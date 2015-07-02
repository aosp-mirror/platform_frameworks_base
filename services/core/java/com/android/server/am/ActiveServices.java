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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.*;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.TransactionTooLargeException;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.app.ProcessStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.FastPrintWriter;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.am.ActivityManagerService.NeededUriGrants;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

public final class ActiveServices {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActiveServices" : TAG_AM;
    private static final String TAG_MU = TAG + POSTFIX_MU;
    private static final String TAG_SERVICE = TAG + POSTFIX_SERVICE;
    private static final String TAG_SERVICE_EXECUTING = TAG + POSTFIX_SERVICE_EXECUTING;

    private static final boolean DEBUG_DELAYED_SERVICE = DEBUG_SERVICE;
    private static final boolean DEBUG_DELAYED_STARTS = DEBUG_DELAYED_SERVICE;

    private static final boolean LOG_SERVICE_START_STOP = false;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20*1000;

    // How long we wait for a service to finish executing.
    static final int SERVICE_BACKGROUND_TIMEOUT = SERVICE_TIMEOUT * 10;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    static final int SERVICE_RESTART_DURATION = 1*1000;

    // How long a service needs to be running until it will start back at
    // SERVICE_RESTART_DURATION after being killed.
    static final int SERVICE_RESET_RUN_DURATION = 60*1000;

    // Multiplying factor to increase restart duration time by, for each time
    // a service is killed before it has run for SERVICE_RESET_RUN_DURATION.
    static final int SERVICE_RESTART_DURATION_FACTOR = 4;

    // The minimum amount of time between restarting services that we allow.
    // That is, when multiple services are restarting, we won't allow each
    // to restart less than this amount of time from the last one.
    static final int SERVICE_MIN_RESTART_TIME_BETWEEN = 10*1000;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    static final int MAX_SERVICE_INACTIVITY = 30*60*1000;

    // How long we wait for a background started service to stop itself before
    // allowing the next pending start to run.
    static final int BG_START_TIMEOUT = 15*1000;

    final ActivityManagerService mAm;

    // Maximum number of services that we allow to start in the background
    // at the same time.
    final int mMaxStartingBackground;

    final SparseArray<ServiceMap> mServiceMap = new SparseArray<>();

    /**
     * All currently bound service connections.  Keys are the IBinder of
     * the client's IServiceConnection.
     */
    final ArrayMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections = new ArrayMap<>();

    /**
     * List of services that we have been asked to start,
     * but haven't yet been able to.  It is used to hold start requests
     * while waiting for their corresponding application thread to get
     * going.
     */
    final ArrayList<ServiceRecord> mPendingServices = new ArrayList<>();

    /**
     * List of services that are scheduled to restart following a crash.
     */
    final ArrayList<ServiceRecord> mRestartingServices = new ArrayList<>();

    /**
     * List of services that are in the process of being destroyed.
     */
    final ArrayList<ServiceRecord> mDestroyingServices = new ArrayList<>();

    /** Temporary list for holding the results of calls to {@link #collectPackageServicesLocked} */
    private ArrayList<ServiceRecord> mTmpCollectionResults = null;

    /** Amount of time to allow a last ANR message to exist before freeing the memory. */
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 2 * 60 * 60 * 1000; // Two hours

    String mLastAnrDump;

    final Runnable mLastAnrDumpClearer = new Runnable() {
        @Override public void run() {
            synchronized (mAm) {
                mLastAnrDump = null;
            }
        }
    };

    /**
     * Information about services for a single user.
     */
    class ServiceMap extends Handler {
        final int mUserId;
        final ArrayMap<ComponentName, ServiceRecord> mServicesByName
                = new ArrayMap<ComponentName, ServiceRecord>();
        final ArrayMap<Intent.FilterComparison, ServiceRecord> mServicesByIntent
                = new ArrayMap<Intent.FilterComparison, ServiceRecord>();

        final ArrayList<ServiceRecord> mDelayedStartList
                = new ArrayList<ServiceRecord>();
        /* XXX eventually I'd like to have this based on processes instead of services.
         * That is, if we try to start two services in a row both running in the same
         * process, this should be one entry in mStartingBackground for that one process
         * that remains until all services in it are done.
        final ArrayMap<ProcessRecord, DelayingProcess> mStartingBackgroundMap
                = new ArrayMap<ProcessRecord, DelayingProcess>();
        final ArrayList<DelayingProcess> mStartingProcessList
                = new ArrayList<DelayingProcess>();
        */

        final ArrayList<ServiceRecord> mStartingBackground
                = new ArrayList<ServiceRecord>();

        static final int MSG_BG_START_TIMEOUT = 1;

        ServiceMap(Looper looper, int userId) {
            super(looper);
            mUserId = userId;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BG_START_TIMEOUT: {
                    synchronized (mAm) {
                        rescheduleDelayedStarts();
                    }
                } break;
            }
        }

        void ensureNotStartingBackground(ServiceRecord r) {
            if (mStartingBackground.remove(r)) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "No longer background starting: " + r);
                rescheduleDelayedStarts();
            }
            if (mDelayedStartList.remove(r)) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "No longer delaying start: " + r);
            }
        }

        void rescheduleDelayedStarts() {
            removeMessages(MSG_BG_START_TIMEOUT);
            final long now = SystemClock.uptimeMillis();
            for (int i=0, N=mStartingBackground.size(); i<N; i++) {
                ServiceRecord r = mStartingBackground.get(i);
                if (r.startingBgTimeout <= now) {
                    Slog.i(TAG, "Waited long enough for: " + r);
                    mStartingBackground.remove(i);
                    N--;
                    i--;
                }
            }
            while (mDelayedStartList.size() > 0
                    && mStartingBackground.size() < mMaxStartingBackground) {
                ServiceRecord r = mDelayedStartList.remove(0);
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "REM FR DELAY LIST (exec next): " + r);
                if (r.pendingStarts.size() <= 0) {
                    Slog.w(TAG, "**** NO PENDING STARTS! " + r + " startReq=" + r.startRequested
                            + " delayedStop=" + r.delayedStop);
                }
                if (DEBUG_DELAYED_SERVICE) {
                    if (mDelayedStartList.size() > 0) {
                        Slog.v(TAG_SERVICE, "Remaining delayed list:");
                        for (int i=0; i<mDelayedStartList.size(); i++) {
                            Slog.v(TAG_SERVICE, "  #" + i + ": " + mDelayedStartList.get(i));
                        }
                    }
                }
                r.delayed = false;
                try {
                    startServiceInnerLocked(this, r.pendingStarts.get(0).intent, r, false, true);
                } catch (TransactionTooLargeException e) {
                    // Ignore, nobody upstack cares.
                }
            }
            if (mStartingBackground.size() > 0) {
                ServiceRecord next = mStartingBackground.get(0);
                long when = next.startingBgTimeout > now ? next.startingBgTimeout : now;
                if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Top bg start is " + next
                        + ", can delay others up to " + when);
                Message msg = obtainMessage(MSG_BG_START_TIMEOUT);
                sendMessageAtTime(msg, when);
            }
            if (mStartingBackground.size() < mMaxStartingBackground) {
                mAm.backgroundServicesFinishedLocked(mUserId);
            }
        }
    }

    public ActiveServices(ActivityManagerService service) {
        mAm = service;
        int maxBg = 0;
        try {
            maxBg = Integer.parseInt(SystemProperties.get("ro.config.max_starting_bg", "0"));
        } catch(RuntimeException e) {
        }
        mMaxStartingBackground = maxBg > 0
                ? maxBg : ActivityManager.isLowRamDeviceStatic() ? 1 : 8;
    }

    ServiceRecord getServiceByName(ComponentName name, int callingUser) {
        // TODO: Deal with global services
        if (DEBUG_MU)
            Slog.v(TAG_MU, "getServiceByName(" + name + "), callingUser = " + callingUser);
        return getServiceMap(callingUser).mServicesByName.get(name);
    }

    boolean hasBackgroundServices(int callingUser) {
        ServiceMap smap = mServiceMap.get(callingUser);
        return smap != null ? smap.mStartingBackground.size() >= mMaxStartingBackground : false;
    }

    private ServiceMap getServiceMap(int callingUser) {
        ServiceMap smap = mServiceMap.get(callingUser);
        if (smap == null) {
            smap = new ServiceMap(mAm.mHandler.getLooper(), callingUser);
            mServiceMap.put(callingUser, smap);
        }
        return smap;
    }

    ArrayMap<ComponentName, ServiceRecord> getServices(int callingUser) {
        return getServiceMap(callingUser).mServicesByName;
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, String callingPackage, int userId)
            throws TransactionTooLargeException {
        if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "startService: " + service
                + " type=" + resolvedType + " args=" + service.getExtras());

        final boolean callerFg;
        if (caller != null) {
            final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when starting service " + service);
            }
            callerFg = callerApp.setSchedGroup != Process.THREAD_GROUP_BG_NONINTERACTIVE;
        } else {
            callerFg = true;
        }


        ServiceLookupResult res =
            retrieveServiceLocked(service, resolvedType, callingPackage,
                    callingPid, callingUid, userId, true, callerFg);
        if (res == null) {
            return null;
        }
        if (res.record == null) {
            return new ComponentName("!", res.permission != null
                    ? res.permission : "private to package");
        }

        ServiceRecord r = res.record;

        if (!mAm.getUserManagerLocked().exists(r.userId)) {
            Slog.d(TAG, "Trying to start service with non-existent user! " + r.userId);
            return null;
        }

        NeededUriGrants neededGrants = mAm.checkGrantUriPermissionFromIntentLocked(
                callingUid, r.packageName, service, service.getFlags(), null, r.userId);
        if (unscheduleServiceRestartLocked(r, callingUid, false)) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "START SERVICE WHILE RESTART PENDING: " + r);
        }
        r.lastActivity = SystemClock.uptimeMillis();
        r.startRequested = true;
        r.delayedStop = false;
        r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                service, neededGrants));

        final ServiceMap smap = getServiceMap(r.userId);
        boolean addToStarting = false;
        if (!callerFg && r.app == null && mAm.mStartedUsers.get(r.userId) != null) {
            ProcessRecord proc = mAm.getProcessRecordLocked(r.processName, r.appInfo.uid, false);
            if (proc == null || proc.curProcState > ActivityManager.PROCESS_STATE_RECEIVER) {
                // If this is not coming from a foreground caller, then we may want
                // to delay the start if there are already other background services
                // that are starting.  This is to avoid process start spam when lots
                // of applications are all handling things like connectivity broadcasts.
                // We only do this for cached processes, because otherwise an application
                // can have assumptions about calling startService() for a service to run
                // in its own process, and for that process to not be killed before the
                // service is started.  This is especially the case for receivers, which
                // may start a service in onReceive() to do some additional work and have
                // initialized some global state as part of that.
                if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Potential start delay of "
                        + r + " in " + proc);
                if (r.delayed) {
                    // This service is already scheduled for a delayed start; just leave
                    // it still waiting.
                    if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Continuing to delay: " + r);
                    return r.name;
                }
                if (smap.mStartingBackground.size() >= mMaxStartingBackground) {
                    // Something else is starting, delay!
                    Slog.i(TAG_SERVICE, "Delaying start of: " + r);
                    smap.mDelayedStartList.add(r);
                    r.delayed = true;
                    return r.name;
                }
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Not delaying: " + r);
                addToStarting = true;
            } else if (proc.curProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
                // We slightly loosen when we will enqueue this new service as a background
                // starting service we are waiting for, to also include processes that are
                // currently running other services or receivers.
                addToStarting = true;
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Not delaying, but counting as bg: " + r);
            } else if (DEBUG_DELAYED_STARTS) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("Not potential delay (state=").append(proc.curProcState)
                        .append(' ').append(proc.adjType);
                String reason = proc.makeAdjReason();
                if (reason != null) {
                    sb.append(' ');
                    sb.append(reason);
                }
                sb.append("): ");
                sb.append(r.toString());
                Slog.v(TAG_SERVICE, sb.toString());
            }
        } else if (DEBUG_DELAYED_STARTS) {
            if (callerFg) {
                Slog.v(TAG_SERVICE, "Not potential delay (callerFg=" + callerFg + " uid="
                        + callingUid + " pid=" + callingPid + "): " + r);
            } else if (r.app != null) {
                Slog.v(TAG_SERVICE, "Not potential delay (cur app=" + r.app + "): " + r);
            } else {
                Slog.v(TAG_SERVICE,
                        "Not potential delay (user " + r.userId + " not started): " + r);
            }
        }

        return startServiceInnerLocked(smap, service, r, callerFg, addToStarting);
    }

    ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r,
            boolean callerFg, boolean addToStarting) throws TransactionTooLargeException {
        ProcessStats.ServiceState stracker = r.getTracker();
        if (stracker != null) {
            stracker.setStarted(true, mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
        }
        r.callStart = false;
        synchronized (r.stats.getBatteryStats()) {
            r.stats.startRunningLocked();
        }
        String error = bringUpServiceLocked(r, service.getFlags(), callerFg, false);
        if (error != null) {
            return new ComponentName("!!", error);
        }

        if (r.startRequested && addToStarting) {
            boolean first = smap.mStartingBackground.size() == 0;
            smap.mStartingBackground.add(r);
            r.startingBgTimeout = SystemClock.uptimeMillis() + BG_START_TIMEOUT;
            if (DEBUG_DELAYED_SERVICE) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r, here);
            } else if (DEBUG_DELAYED_STARTS) {
                Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r);
            }
            if (first) {
                smap.rescheduleDelayedStarts();
            }
        } else if (callerFg) {
            smap.ensureNotStartingBackground(r);
        }

        return r.name;
    }

    private void stopServiceLocked(ServiceRecord service) {
        if (service.delayed) {
            // If service isn't actually running, but is is being held in the
            // delayed list, then we need to keep it started but note that it
            // should be stopped once no longer delayed.
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Delaying stop of pending: " + service);
            service.delayedStop = true;
            return;
        }
        synchronized (service.stats.getBatteryStats()) {
            service.stats.stopRunningLocked();
        }
        service.startRequested = false;
        if (service.tracker != null) {
            service.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                    SystemClock.uptimeMillis());
        }
        service.callStart = false;
        bringDownServiceIfNeededLocked(service, false, false);
    }

    int stopServiceLocked(IApplicationThread caller, Intent service,
            String resolvedType, int userId) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopService: " + service
                + " type=" + resolvedType);

        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (caller != null && callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when stopping service " + service);
        }

        // If this service is active, make sure it is stopped.
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType, null,
                Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false);
        if (r != null) {
            if (r.record != null) {
                final long origId = Binder.clearCallingIdentity();
                try {
                    stopServiceLocked(r.record);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
                return 1;
            }
            return -1;
        }

        return 0;
    }

    IBinder peekServiceLocked(Intent service, String resolvedType, String callingPackage) {
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType, callingPackage,
                Binder.getCallingPid(), Binder.getCallingUid(),
                UserHandle.getCallingUserId(), false, false);

        IBinder ret = null;
        if (r != null) {
            // r.record is null if findServiceLocked() failed the caller permission check
            if (r.record == null) {
                throw new SecurityException(
                        "Permission Denial: Accessing service"
                        + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + r.permission);
            }
            IntentBindRecord ib = r.record.bindings.get(r.record.intent);
            if (ib != null) {
                ret = ib.binder;
            }
        }

        return ret;
    }

    boolean stopServiceTokenLocked(ComponentName className, IBinder token,
            int startId) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopServiceToken: " + className
                + " " + token + " startId=" + startId);
        ServiceRecord r = findServiceLocked(className, token, UserHandle.getCallingUserId());
        if (r != null) {
            if (startId >= 0) {
                // Asked to only stop if done with all work.  Note that
                // to avoid leaks, we will take this as dropping all
                // start items up to and including this one.
                ServiceRecord.StartItem si = r.findDeliveredStart(startId, false);
                if (si != null) {
                    while (r.deliveredStarts.size() > 0) {
                        ServiceRecord.StartItem cur = r.deliveredStarts.remove(0);
                        cur.removeUriPermissionsLocked();
                        if (cur == si) {
                            break;
                        }
                    }
                }

                if (r.getLastStartId() != startId) {
                    return false;
                }

                if (r.deliveredStarts.size() > 0) {
                    Slog.w(TAG, "stopServiceToken startId " + startId
                            + " is last, but have " + r.deliveredStarts.size()
                            + " remaining args");
                }
            }

            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopRunningLocked();
            }
            r.startRequested = false;
            if (r.tracker != null) {
                r.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
            }
            r.callStart = false;
            final long origId = Binder.clearCallingIdentity();
            bringDownServiceIfNeededLocked(r, false, false);
            Binder.restoreCallingIdentity(origId);
            return true;
        }
        return false;
    }

    public void setServiceForegroundLocked(ComponentName className, IBinder token,
            int id, Notification notification, boolean removeNotification) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                if (id != 0) {
                    if (notification == null) {
                        throw new IllegalArgumentException("null notification");
                    }
                    if (r.foregroundId != id) {
                        r.cancelNotification();
                        r.foregroundId = id;
                    }
                    notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
                    r.foregroundNoti = notification;
                    r.isForeground = true;
                    r.postNotification();
                    if (r.app != null) {
                        updateServiceForegroundLocked(r.app, true);
                    }
                    getServiceMap(r.userId).ensureNotStartingBackground(r);
                } else {
                    if (r.isForeground) {
                        r.isForeground = false;
                        if (r.app != null) {
                            mAm.updateLruProcessLocked(r.app, false, null);
                            updateServiceForegroundLocked(r.app, true);
                        }
                    }
                    if (removeNotification) {
                        r.cancelNotification();
                        r.foregroundId = 0;
                        r.foregroundNoti = null;
                    } else if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
                        r.stripForegroundServiceFlagFromNotification();
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (int i=proc.services.size()-1; i>=0; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            if (sr.isForeground) {
                anyForeground = true;
                break;
            }
        }
        mAm.updateProcessForegroundLocked(proc, anyForeground, oomAdj);
    }

    public void updateServiceConnectionActivitiesLocked(ProcessRecord clientProc) {
        ArraySet<ProcessRecord> updatedProcesses = null;
        for (int i=0; i<clientProc.connections.size(); i++) {
            final ConnectionRecord conn = clientProc.connections.valueAt(i);
            final ProcessRecord proc = conn.binding.service.app;
            if (proc == null || proc == clientProc) {
                continue;
            } else if (updatedProcesses == null) {
                updatedProcesses = new ArraySet<>();
            } else if (updatedProcesses.contains(proc)) {
                continue;
            }
            updatedProcesses.add(proc);
            updateServiceClientActivitiesLocked(proc, null, false);
        }
    }

    private boolean updateServiceClientActivitiesLocked(ProcessRecord proc,
            ConnectionRecord modCr, boolean updateLru) {
        if (modCr != null && modCr.binding.client != null) {
            if (modCr.binding.client.activities.size() <= 0) {
                // This connection is from a client without activities, so adding
                // and removing is not interesting.
                return false;
            }
        }

        boolean anyClientActivities = false;
        for (int i=proc.services.size()-1; i>=0 && !anyClientActivities; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            for (int conni=sr.connections.size()-1; conni>=0 && !anyClientActivities; conni--) {
                ArrayList<ConnectionRecord> clist = sr.connections.valueAt(conni);
                for (int cri=clist.size()-1; cri>=0; cri--) {
                    ConnectionRecord cr = clist.get(cri);
                    if (cr.binding.client == null || cr.binding.client == proc) {
                        // Binding to ourself is not interesting.
                        continue;
                    }
                    if (cr.binding.client.activities.size() > 0) {
                        anyClientActivities = true;
                        break;
                    }
                }
            }
        }
        if (anyClientActivities != proc.hasClientActivities) {
            proc.hasClientActivities = anyClientActivities;
            if (updateLru) {
                mAm.updateLruProcessLocked(proc, anyClientActivities, null);
            }
            return true;
        }
        return false;
    }

    int bindServiceLocked(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, IServiceConnection connection, int flags,
            String callingPackage, int userId) throws TransactionTooLargeException {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "bindService: " + service
                + " type=" + resolvedType + " conn=" + connection.asBinder()
                + " flags=0x" + Integer.toHexString(flags));
        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when binding service " + service);
        }

        ActivityRecord activity = null;
        if (token != null) {
            activity = ActivityRecord.isInStackLocked(token);
            if (activity == null) {
                Slog.w(TAG, "Binding with unknown activity: " + token);
                return 0;
            }
        }

        int clientLabel = 0;
        PendingIntent clientIntent = null;

        if (callerApp.info.uid == Process.SYSTEM_UID) {
            // Hacky kind of thing -- allow system stuff to tell us
            // what they are, so we can report this elsewhere for
            // others to know why certain services are running.
            try {
                clientIntent = service.getParcelableExtra(Intent.EXTRA_CLIENT_INTENT);
            } catch (RuntimeException e) {
            }
            if (clientIntent != null) {
                clientLabel = service.getIntExtra(Intent.EXTRA_CLIENT_LABEL, 0);
                if (clientLabel != 0) {
                    // There are no useful extras in the intent, trash them.
                    // System code calling with this stuff just needs to know
                    // this will happen.
                    service = service.cloneFilter();
                }
            }
        }

        if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
            mAm.enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                    "BIND_TREAT_LIKE_ACTIVITY");
        }

        final boolean callerFg = callerApp.setSchedGroup != Process.THREAD_GROUP_BG_NONINTERACTIVE;

        ServiceLookupResult res =
            retrieveServiceLocked(service, resolvedType, callingPackage,
                    Binder.getCallingPid(), Binder.getCallingUid(), userId, true, callerFg);
        if (res == null) {
            return 0;
        }
        if (res.record == null) {
            return -1;
        }
        ServiceRecord s = res.record;

        final long origId = Binder.clearCallingIdentity();

        try {
            if (unscheduleServiceRestartLocked(s, callerApp.info.uid, false)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "BIND SERVICE WHILE RESTART PENDING: "
                        + s);
            }

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (!s.hasAutoCreateConnections()) {
                    // This is the first binding, let the tracker know.
                    ProcessStats.ServiceState stracker = s.getTracker();
                    if (stracker != null) {
                        stracker.setBound(true, mAm.mProcessStats.getMemFactorLocked(),
                                s.lastActivity);
                    }
                }
            }

            mAm.startAssociationLocked(callerApp.uid, callerApp.processName,
                    s.appInfo.uid, s.name, s.processName);

            AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags, clientLabel, clientIntent);

            IBinder binder = connection.asBinder();
            ArrayList<ConnectionRecord> clist = s.connections.get(binder);
            if (clist == null) {
                clist = new ArrayList<ConnectionRecord>();
                s.connections.put(binder, clist);
            }
            clist.add(c);
            b.connections.add(c);
            if (activity != null) {
                if (activity.connections == null) {
                    activity.connections = new HashSet<ConnectionRecord>();
                }
                activity.connections.add(c);
            }
            b.client.connections.add(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                b.client.hasAboveClient = true;
            }
            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app, c, true);
            }
            clist = mServiceConnections.get(binder);
            if (clist == null) {
                clist = new ArrayList<ConnectionRecord>();
                mServiceConnections.put(binder, clist);
            }
            clist.add(c);

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (bringUpServiceLocked(s, service.getFlags(), callerFg, false) != null) {
                    return 0;
                }
            }

            if (s.app != null) {
                if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                    s.app.treatLikeActivity = true;
                }
                // This could have made the service more important.
                mAm.updateLruProcessLocked(s.app, s.app.hasClientActivities
                        || s.app.treatLikeActivity, b.client);
                mAm.updateOomAdjLocked(s.app);
            }

            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.
                try {
                    c.conn.connected(s.name, b.intent.binder);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure sending service " + s.shortName
                            + " to connection " + c.conn.asBinder()
                            + " (in " + c.binding.client.processName + ")", e);
                }

                // If this is the first app connected back to this binding,
                // and the service had previously asked to be told when
                // rebound, then do so.
                if (b.intent.apps.size() == 1 && b.intent.doRebind) {
                    requestServiceBindingLocked(s, b.intent, callerFg, true);
                }
            } else if (!b.intent.requested) {
                requestServiceBindingLocked(s, b.intent, callerFg, false);
            }

            getServiceMap(s.userId).ensureNotStartingBackground(s);

        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return 1;
    }

    void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "PUBLISHING " + r
                    + " " + intent + ": " + service);
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (b != null && !b.received) {
                    b.binder = service;
                    b.requested = true;
                    b.received = true;
                    for (int conni=r.connections.size()-1; conni>=0; conni--) {
                        ArrayList<ConnectionRecord> clist = r.connections.valueAt(conni);
                        for (int i=0; i<clist.size(); i++) {
                            ConnectionRecord c = clist.get(i);
                            if (!filter.equals(c.binding.intent.intent)) {
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Not publishing to: " + c);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Bound intent: " + c.binding.intent.intent);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Published intent: " + intent);
                                continue;
                            }
                            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Publishing to: " + c);
                            try {
                                c.conn.connected(r.name, service);
                            } catch (Exception e) {
                                Slog.w(TAG, "Failure sending service " + r.name +
                                      " to connection " + c.conn.asBinder() +
                                      " (in " + c.binding.client.processName + ")", e);
                            }
                        }
                    }
                }

                serviceDoneExecutingLocked(r, mDestroyingServices.contains(r), false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean unbindServiceLocked(IServiceConnection connection) {
        IBinder binder = connection.asBinder();
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "unbindService: conn=" + binder);
        ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
        if (clist == null) {
            Slog.w(TAG, "Unbind failed: could not find connection for "
                  + connection.asBinder());
            return false;
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            while (clist.size() > 0) {
                ConnectionRecord r = clist.get(0);
                removeConnectionLocked(r, null, null);
                if (clist.size() > 0 && clist.get(0) == r) {
                    // In case it didn't get removed above, do it now.
                    Slog.wtf(TAG, "Connection " + r + " not removed for binder " + binder);
                    clist.remove(0);
                }

                if (r.binding.service.app != null) {
                    // This could have made the service less important.
                    if ((r.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                        r.binding.service.app.treatLikeActivity = true;
                        mAm.updateLruProcessLocked(r.binding.service.app,
                                r.binding.service.app.hasClientActivities
                                || r.binding.service.app.treatLikeActivity, null);
                    }
                    mAm.updateOomAdjLocked(r.binding.service.app);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return true;
    }

    void unbindFinishedLocked(ServiceRecord r, Intent intent, boolean doRebind) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "unbindFinished in " + r
                        + " at " + b + ": apps="
                        + (b != null ? b.apps.size() : 0));

                boolean inDestroying = mDestroyingServices.contains(r);
                if (b != null) {
                    if (b.apps.size() > 0 && !inDestroying) {
                        // Applications have already bound since the last
                        // unbind, so just rebind right here.
                        boolean inFg = false;
                        for (int i=b.apps.size()-1; i>=0; i--) {
                            ProcessRecord client = b.apps.valueAt(i).client;
                            if (client != null && client.setSchedGroup
                                    != Process.THREAD_GROUP_BG_NONINTERACTIVE) {
                                inFg = true;
                                break;
                            }
                        }
                        try {
                            requestServiceBindingLocked(r, b, inFg, true);
                        } catch (TransactionTooLargeException e) {
                            // Don't pass this back to ActivityThread, it's unrelated.
                        }
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                serviceDoneExecutingLocked(r, inDestroying, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final ServiceRecord findServiceLocked(ComponentName name,
            IBinder token, int userId) {
        ServiceRecord r = getServiceByName(name, userId);
        return r == token ? r : null;
    }

    private final class ServiceLookupResult {
        final ServiceRecord record;
        final String permission;

        ServiceLookupResult(ServiceRecord _record, String _permission) {
            record = _record;
            permission = _permission;
        }
    }

    private class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        void setService(ServiceRecord service) {
            mService = service;
        }

        public void run() {
            synchronized(mAm) {
                performServiceRestartLocked(mService);
            }
        }
    }

    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String resolvedType, String callingPackage, int callingPid, int callingUid, int userId,
            boolean createIfNeeded, boolean callingFromFg) {
        ServiceRecord r = null;
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "retrieveServiceLocked: " + service
                + " type=" + resolvedType + " callingUid=" + callingUid);

        userId = mAm.handleIncomingUser(callingPid, callingUid, userId,
                false, ActivityManagerService.ALLOW_NON_FULL_IN_PROFILE, "service", null);

        ServiceMap smap = getServiceMap(userId);
        final ComponentName comp = service.getComponent();
        if (comp != null) {
            r = smap.mServicesByName.get(comp);
        }
        if (r == null) {
            Intent.FilterComparison filter = new Intent.FilterComparison(service);
            r = smap.mServicesByIntent.get(filter);
        }
        if (r == null) {
            try {
                ResolveInfo rInfo =
                    AppGlobals.getPackageManager().resolveService(
                                service, resolvedType,
                                ActivityManagerService.STOCK_PM_FLAGS, userId);
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Slog.w(TAG_SERVICE, "Unable to start service " + service + " U=" + userId +
                          ": not found");
                    return null;
                }
                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                if (userId > 0) {
                    if (mAm.isSingleton(sInfo.processName, sInfo.applicationInfo,
                            sInfo.name, sInfo.flags)
                            && mAm.isValidSingletonCall(callingUid, sInfo.applicationInfo.uid)) {
                        userId = 0;
                        smap = getServiceMap(0);
                    }
                    sInfo = new ServiceInfo(sInfo);
                    sInfo.applicationInfo = mAm.getAppInfoForUser(sInfo.applicationInfo, userId);
                }
                r = smap.mServicesByName.get(name);
                if (r == null && createIfNeeded) {
                    Intent.FilterComparison filter
                            = new Intent.FilterComparison(service.cloneFilter());
                    ServiceRestarter res = new ServiceRestarter();
                    BatteryStatsImpl.Uid.Pkg.Serv ss = null;
                    BatteryStatsImpl stats = mAm.mBatteryStatsService.getActiveStatistics();
                    synchronized (stats) {
                        ss = stats.getServiceStatsLocked(
                                sInfo.applicationInfo.uid, sInfo.packageName,
                                sInfo.name);
                    }
                    r = new ServiceRecord(mAm, ss, name, filter, sInfo, callingFromFg, res);
                    res.setService(r);
                    smap.mServicesByName.put(name, r);
                    smap.mServicesByIntent.put(filter, r);

                    // Make sure this component isn't in the pending list.
                    for (int i=mPendingServices.size()-1; i>=0; i--) {
                        ServiceRecord pr = mPendingServices.get(i);
                        if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                                && pr.name.equals(name)) {
                            mPendingServices.remove(i);
                        }
                    }
                }
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            if (mAm.checkComponentPermission(r.permission,
                    callingPid, callingUid, r.appInfo.uid, r.exported)
                    != PackageManager.PERMISSION_GRANTED) {
                if (!r.exported) {
                    Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " that is not exported from uid " + r.appInfo.uid);
                    return new ServiceLookupResult(null, "not exported from uid "
                            + r.appInfo.uid);
                }
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            } else if (r.permission != null && callingPackage != null) {
                final int opCode = AppOpsManager.permissionToOpCode(r.permission);
                if (opCode != AppOpsManager.OP_NONE && mAm.mAppOpsService.noteOperation(
                        opCode, callingUid, callingPackage) != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: Accessing service " + r.name
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " requires appop " + AppOpsManager.opToName(opCode));
                    return null;
                }
            }

            if (!mAm.mIntentFirewall.checkService(r.name, service, callingUid, callingPid,
                    resolvedType, r.appInfo)) {
                return null;
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r, boolean fg, String why) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, ">>> EXECUTING "
                + why + " of " + r + " in app " + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING, ">>> EXECUTING "
                + why + " of " + r.shortName);
        long now = SystemClock.uptimeMillis();
        if (r.executeNesting == 0) {
            r.executeFg = fg;
            ProcessStats.ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setExecuting(true, mAm.mProcessStats.getMemFactorLocked(), now);
            }
            if (r.app != null) {
                r.app.executingServices.add(r);
                r.app.execServicesFg |= fg;
                if (r.app.executingServices.size() == 1) {
                    scheduleServiceTimeoutLocked(r.app);
                }
            }
        } else if (r.app != null && fg && !r.app.execServicesFg) {
            r.app.execServicesFg = true;
            scheduleServiceTimeoutLocked(r.app);
        }
        r.executeFg |= fg;
        r.executeNesting++;
        r.executingStart = now;
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r, IntentBindRecord i,
            boolean execInFg, boolean rebind) throws TransactionTooLargeException {
        if (r.app == null || r.app.thread == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, execInFg, "bind");
                r.app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind,
                        r.app.repProcState);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (TransactionTooLargeException e) {
                // Keep the executeNesting count accurate.
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r, e);
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                throw e;
            } catch (RemoteException e) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r);
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                return false;
            }
        }
        return true;
    }

    private final boolean scheduleServiceRestartLocked(ServiceRecord r,
            boolean allowCancel) {
        boolean canceled = false;

        ServiceMap smap = getServiceMap(r.userId);
        if (smap.mServicesByName.get(r.name) != r) {
            ServiceRecord cur = smap.mServicesByName.get(r.name);
            Slog.wtf(TAG, "Attempting to schedule restart of " + r
                    + " when found in map: " + cur);
            return false;
        }

        final long now = SystemClock.uptimeMillis();

        if ((r.serviceInfo.applicationInfo.flags
                &ApplicationInfo.FLAG_PERSISTENT) == 0) {
            long minDuration = SERVICE_RESTART_DURATION;
            long resetTime = SERVICE_RESET_RUN_DURATION;

            // Any delivered but not yet finished starts should be put back
            // on the pending list.
            final int N = r.deliveredStarts.size();
            if (N > 0) {
                for (int i=N-1; i>=0; i--) {
                    ServiceRecord.StartItem si = r.deliveredStarts.get(i);
                    si.removeUriPermissionsLocked();
                    if (si.intent == null) {
                        // We'll generate this again if needed.
                    } else if (!allowCancel || (si.deliveryCount < ServiceRecord.MAX_DELIVERY_COUNT
                            && si.doneExecutingCount < ServiceRecord.MAX_DONE_EXECUTING_COUNT)) {
                        r.pendingStarts.add(0, si);
                        long dur = SystemClock.uptimeMillis() - si.deliveredTime;
                        dur *= 2;
                        if (minDuration < dur) minDuration = dur;
                        if (resetTime < dur) resetTime = dur;
                    } else {
                        Slog.w(TAG, "Canceling start item " + si.intent + " in service "
                                + r.name);
                        canceled = true;
                    }
                }
                r.deliveredStarts.clear();
            }

            r.totalRestartCount++;
            if (r.restartDelay == 0) {
                r.restartCount++;
                r.restartDelay = minDuration;
            } else {
                // If it has been a "reasonably long time" since the service
                // was started, then reset our restart duration back to
                // the beginning, so we don't infinitely increase the duration
                // on a service that just occasionally gets killed (which is
                // a normal case, due to process being killed to reclaim memory).
                if (now > (r.restartTime+resetTime)) {
                    r.restartCount = 1;
                    r.restartDelay = minDuration;
                } else {
                    r.restartDelay *= SERVICE_RESTART_DURATION_FACTOR;
                    if (r.restartDelay < minDuration) {
                        r.restartDelay = minDuration;
                    }
                }
            }

            r.nextRestartTime = now + r.restartDelay;

            // Make sure that we don't end up restarting a bunch of services
            // all at the same time.
            boolean repeat;
            do {
                repeat = false;
                for (int i=mRestartingServices.size()-1; i>=0; i--) {
                    ServiceRecord r2 = mRestartingServices.get(i);
                    if (r2 != r && r.nextRestartTime
                            >= (r2.nextRestartTime-SERVICE_MIN_RESTART_TIME_BETWEEN)
                            && r.nextRestartTime
                            < (r2.nextRestartTime+SERVICE_MIN_RESTART_TIME_BETWEEN)) {
                        r.nextRestartTime = r2.nextRestartTime + SERVICE_MIN_RESTART_TIME_BETWEEN;
                        r.restartDelay = r.nextRestartTime - now;
                        repeat = true;
                        break;
                    }
                }
            } while (repeat);

        } else {
            // Persistent processes are immediately restarted, so there is no
            // reason to hold of on restarting their services.
            r.totalRestartCount++;
            r.restartCount = 0;
            r.restartDelay = 0;
            r.nextRestartTime = now;
        }

        if (!mRestartingServices.contains(r)) {
            r.createdFromFg = false;
            mRestartingServices.add(r);
            r.makeRestarting(mAm.mProcessStats.getMemFactorLocked(), now);
        }

        r.cancelNotification();

        mAm.mHandler.removeCallbacks(r.restarter);
        mAm.mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = SystemClock.uptimeMillis() + r.restartDelay;
        Slog.w(TAG, "Scheduling restart of crashed service "
                + r.shortName + " in " + r.restartDelay + "ms");
        EventLog.writeEvent(EventLogTags.AM_SCHEDULE_SERVICE_RESTART,
                r.userId, r.shortName, r.restartDelay);

        return canceled;
    }

    final void performServiceRestartLocked(ServiceRecord r) {
        if (!mRestartingServices.contains(r)) {
            return;
        }
        try {
            bringUpServiceLocked(r, r.intent.getIntent().getFlags(), r.createdFromFg, true);
        } catch (TransactionTooLargeException e) {
            // Ignore, it's been logged and nothing upstack cares.
        }
    }

    private final boolean unscheduleServiceRestartLocked(ServiceRecord r, int callingUid,
            boolean force) {
        if (!force && r.restartDelay == 0) {
            return false;
        }
        // Remove from the restarting list; if the service is currently on the
        // restarting list, or the call is coming from another app, then this
        // service has become of much more interest so we reset the restart interval.
        boolean removed = mRestartingServices.remove(r);
        if (removed || callingUid != r.appInfo.uid) {
            r.resetRestartCounter();
        }
        if (removed) {
            clearRestartingIfNeededLocked(r);
        }
        mAm.mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private void clearRestartingIfNeededLocked(ServiceRecord r) {
        if (r.restartTracker != null) {
            // If this is the last restarting record with this tracker, then clear
            // the tracker's restarting state.
            boolean stillTracking = false;
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                if (mRestartingServices.get(i).restartTracker == r.restartTracker) {
                    stillTracking = true;
                    break;
                }
            }
            if (!stillTracking) {
                r.restartTracker.setRestarting(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
                r.restartTracker = null;
            }
        }
    }

    private final String bringUpServiceLocked(ServiceRecord r, int intentFlags, boolean execInFg,
            boolean whileRestarting) throws TransactionTooLargeException {
        //Slog.i(TAG, "Bring up service:");
        //r.dump("  ");

        if (r.app != null && r.app.thread != null) {
            sendServiceArgsLocked(r, execInFg, false);
            return null;
        }

        if (!whileRestarting && r.restartDelay > 0) {
            // If waiting for a restart, then do nothing.
            return null;
        }

        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing up " + r + " " + r.intent);

        // We are now bringing the service up, so no longer in the
        // restarting state.
        if (mRestartingServices.remove(r)) {
            r.resetRestartCounter();
            clearRestartingIfNeededLocked(r);
        }

        // Make sure this service is no longer considered delayed, we are starting it now.
        if (r.delayed) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (bring up): " + r);
            getServiceMap(r.userId).mDelayedStartList.remove(r);
            r.delayed = false;
        }

        // Make sure that the user who owns this service is started.  If not,
        // we don't want to allow it to run.
        if (mAm.mStartedUsers.get(r.userId) == null) {
            String msg = "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": user " + r.userId + " is stopped";
            Slog.w(TAG, msg);
            bringDownServiceLocked(r);
            return msg;
        }

        // Service is now being launched, its package can't be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.packageName, false, r.userId);
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.packageName + ": " + e);
        }

        final boolean isolated = (r.serviceInfo.flags&ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
        final String procName = r.processName;
        ProcessRecord app;

        if (!isolated) {
            app = mAm.getProcessRecordLocked(procName, r.appInfo.uid, false);
            if (DEBUG_MU) Slog.v(TAG_MU, "bringUpServiceLocked: appInfo.uid=" + r.appInfo.uid
                        + " app=" + app);
            if (app != null && app.thread != null) {
                try {
                    app.addPackage(r.appInfo.packageName, r.appInfo.versionCode, mAm.mProcessStats);
                    realStartServiceLocked(r, app, execInFg);
                    return null;
                } catch (TransactionTooLargeException e) {
                    throw e;
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception when starting service " + r.shortName, e);
                }

                // If a dead object exception was thrown -- fall through to
                // restart the application.
            }
        } else {
            // If this service runs in an isolated process, then each time
            // we call startProcessLocked() we will get a new isolated
            // process, starting another process if we are currently waiting
            // for a previous process to come up.  To deal with this, we store
            // in the service any current isolated process it is running in or
            // waiting to have come up.
            app = r.isolatedProc;
        }

        // Not running -- get it started, and enqueue this service record
        // to be executed when the app comes up.
        if (app == null) {
            if ((app=mAm.startProcessLocked(procName, r.appInfo, true, intentFlags,
                    "service", r.name, false, isolated, false)) == null) {
                String msg = "Unable to launch app "
                        + r.appInfo.packageName + "/"
                        + r.appInfo.uid + " for service "
                        + r.intent.getIntent() + ": process is bad";
                Slog.w(TAG, msg);
                bringDownServiceLocked(r);
                return msg;
            }
            if (isolated) {
                r.isolatedProc = app;
            }
        }

        if (!mPendingServices.contains(r)) {
            mPendingServices.add(r);
        }

        if (r.delayedStop) {
            // Oh and hey we've already been asked to stop!
            r.delayedStop = false;
            if (r.startRequested) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Applying delayed stop (in bring up): " + r);
                stopServiceLocked(r);
            }
        }

        return null;
    }

    private final void requestServiceBindingsLocked(ServiceRecord r, boolean execInFg)
            throws TransactionTooLargeException {
        for (int i=r.bindings.size()-1; i>=0; i--) {
            IntentBindRecord ibr = r.bindings.valueAt(i);
            if (!requestServiceBindingLocked(r, ibr, execInFg, false)) {
                break;
            }
        }
    }

    private final void realStartServiceLocked(ServiceRecord r,
            ProcessRecord app, boolean execInFg) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }
        if (DEBUG_MU)
            Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid
                    + ", ProcessRecord.uid = " + app.uid);
        r.app = app;
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

        final boolean newService = app.services.add(r);
        bumpServiceExecutingLocked(r, execInFg, "create");
        mAm.updateLruProcessLocked(app, false, null);
        mAm.updateOomAdjLocked();

        boolean created = false;
        try {
            if (LOG_SERVICE_START_STOP) {
                String nameTerm;
                int lastPeriod = r.shortName.lastIndexOf('.');
                nameTerm = lastPeriod >= 0 ? r.shortName.substring(lastPeriod) : r.shortName;
                EventLogTags.writeAmCreateService(
                        r.userId, System.identityHashCode(r), nameTerm, r.app.uid, r.app.pid);
            }
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startLaunchedLocked();
            }
            mAm.ensurePackageDexOpt(r.serviceInfo.packageName);
            app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
            app.thread.scheduleCreateService(r, r.serviceInfo,
                    mAm.compatibilityInfoForPackageLocked(r.serviceInfo.applicationInfo),
                    app.repProcState);
            r.postNotification();
            created = true;
        } catch (DeadObjectException e) {
            Slog.w(TAG, "Application dead when creating service " + r);
            mAm.appDiedLocked(app);
            throw e;
        } finally {
            if (!created) {
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);

                // Cleanup.
                if (newService) {
                    app.services.remove(r);
                    r.app = null;
                }

                // Retry.
                if (!inDestroying) {
                    scheduleServiceRestartLocked(r, false);
                }
            }
        }

        requestServiceBindingsLocked(r, execInFg);

        updateServiceClientActivitiesLocked(app, null, true);

        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    null, null));
        }

        sendServiceArgsLocked(r, execInFg, true);

        if (r.delayed) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (new proc): " + r);
            getServiceMap(r.userId).mDelayedStartList.remove(r);
            r.delayed = false;
        }

        if (r.delayedStop) {
            // Oh and hey we've already been asked to stop!
            r.delayedStop = false;
            if (r.startRequested) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Applying delayed stop (from start): " + r);
                stopServiceLocked(r);
            }
        }
    }

    private final void sendServiceArgsLocked(ServiceRecord r, boolean execInFg,
            boolean oomAdjusted) throws TransactionTooLargeException {
        final int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }

        while (r.pendingStarts.size() > 0) {
            Exception caughtException = null;
            ServiceRecord.StartItem si;
            try {
                si = r.pendingStarts.remove(0);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Sending arguments to: "
                        + r + " " + r.intent + " args=" + si.intent);
                if (si.intent == null && N > 1) {
                    // If somehow we got a dummy null intent in the middle,
                    // then skip it.  DO NOT skip a null intent when it is
                    // the only one in the list -- this is to support the
                    // onStartCommand(null) case.
                    continue;
                }
                si.deliveredTime = SystemClock.uptimeMillis();
                r.deliveredStarts.add(si);
                si.deliveryCount++;
                if (si.neededGrants != null) {
                    mAm.grantUriPermissionUncheckedFromIntentLocked(si.neededGrants,
                            si.getUriPermissionsLocked());
                }
                bumpServiceExecutingLocked(r, execInFg, "start");
                if (!oomAdjusted) {
                    oomAdjusted = true;
                    mAm.updateOomAdjLocked(r.app);
                }
                int flags = 0;
                if (si.deliveryCount > 1) {
                    flags |= Service.START_FLAG_RETRY;
                }
                if (si.doneExecutingCount > 0) {
                    flags |= Service.START_FLAG_REDELIVERY;
                }
                r.app.thread.scheduleServiceArgs(r, si.taskRemoved, si.id, flags, si.intent);
            } catch (TransactionTooLargeException e) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Transaction too large: intent="
                        + si.intent);
                caughtException = e;
            } catch (RemoteException e) {
                // Remote process gone...  we'll let the normal cleanup take care of this.
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while sending args: " + r);
                caughtException = e;
            } catch (Exception e) {
                Slog.w(TAG, "Unexpected exception", e);
                caughtException = e;
            }

            if (caughtException != null) {
                // Keep nesting count correct
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                if (caughtException instanceof TransactionTooLargeException) {
                    throw (TransactionTooLargeException)caughtException;
                }
                break;
            }
        }
    }

    private final boolean isServiceNeeded(ServiceRecord r, boolean knowConn, boolean hasConn) {
        // Are we still explicitly being asked to run?
        if (r.startRequested) {
            return true;
        }

        // Is someone still bound to us keepign us running?
        if (!knowConn) {
            hasConn = r.hasAutoCreateConnections();
        }
        if (hasConn) {
            return true;
        }

        return false;
    }

    private final void bringDownServiceIfNeededLocked(ServiceRecord r, boolean knowConn,
            boolean hasConn) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        if (isServiceNeeded(r, knowConn, hasConn)) {
            return;
        }

        // Are we in the process of launching?
        if (mPendingServices.contains(r)) {
            return;
        }

        bringDownServiceLocked(r);
    }

    private final void bringDownServiceLocked(ServiceRecord r) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Report to all of the connections that the service is no longer
        // available.
        for (int conni=r.connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> c = r.connections.valueAt(conni);
            for (int i=0; i<c.size(); i++) {
                ConnectionRecord cr = c.get(i);
                // There is still a connection to the service that is
                // being brought down.  Mark it as dead.
                cr.serviceDead = true;
                try {
                    cr.conn.connected(r.name, null);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure disconnecting service " + r.name +
                          " to connection " + c.get(i).conn.asBinder() +
                          " (in " + c.get(i).binding.client.processName + ")", e);
                }
            }
        }

        // Tell the service that it has been unbound.
        if (r.app != null && r.app.thread != null) {
            for (int i=r.bindings.size()-1; i>=0; i--) {
                IntentBindRecord ibr = r.bindings.valueAt(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r, false, "bring down unbind");
                        mAm.updateOomAdjLocked(r.app);
                        ibr.hasBound = false;
                        r.app.thread.scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortName, e);
                        serviceProcessGoneLocked(r);
                    }
                }
            }
        }

        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing down " + r + " " + r.intent);
        r.destroyTime = SystemClock.uptimeMillis();
        if (LOG_SERVICE_START_STOP) {
            EventLogTags.writeAmDestroyService(
                    r.userId, System.identityHashCode(r), (r.app != null) ? r.app.pid : -1);
        }

        final ServiceMap smap = getServiceMap(r.userId);
        smap.mServicesByName.remove(r.name);
        smap.mServicesByIntent.remove(r.intent);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r, 0, true);

        // Also make sure it is not on the pending list.
        for (int i=mPendingServices.size()-1; i>=0; i--) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Removed pending: " + r);
            }
        }

        r.cancelNotification();
        r.isForeground = false;
        r.foregroundId = 0;
        r.foregroundNoti = null;

        // Clear start entries.
        r.clearDeliveredStartsLocked();
        r.pendingStarts.clear();

        if (r.app != null) {
            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopLaunchedLocked();
            }
            r.app.services.remove(r);
            if (r.app.thread != null) {
                updateServiceForegroundLocked(r.app, false);
                try {
                    bumpServiceExecutingLocked(r, false, "destroy");
                    mDestroyingServices.add(r);
                    r.destroying = true;
                    mAm.updateOomAdjLocked(r.app);
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when destroying service "
                            + r.shortName, e);
                    serviceProcessGoneLocked(r);
                }
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG_SERVICE, "Removed service that has no process: " + r);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG_SERVICE, "Removed service that is not running: " + r);
        }

        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }

        if (r.restarter instanceof ServiceRestarter) {
           ((ServiceRestarter)r.restarter).setService(null);
        }

        int memFactor = mAm.mProcessStats.getMemFactorLocked();
        long now = SystemClock.uptimeMillis();
        if (r.tracker != null) {
            r.tracker.setStarted(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            if (r.executeNesting == 0) {
                r.tracker.clearCurrentOwner(r, false);
                r.tracker = null;
            }
        }

        smap.ensureNotStartingBackground(r);
    }

    void removeConnectionLocked(
        ConnectionRecord c, ProcessRecord skipApp, ActivityRecord skipAct) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        ArrayList<ConnectionRecord> clist = s.connections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                s.connections.remove(binder);
            }
        }
        b.connections.remove(c);
        if (c.activity != null && c.activity != skipAct) {
            if (c.activity.connections != null) {
                c.activity.connections.remove(c);
            }
        }
        if (b.client != skipApp) {
            b.client.connections.remove(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                b.client.updateHasAboveClientLocked();
            }
            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app, c, true);
            }
        }
        clist = mServiceConnections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                mServiceConnections.remove(binder);
            }
        }

        mAm.stopAssociationLocked(b.client.uid, b.client.processName, s.appInfo.uid, s.name);

        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (!c.serviceDead) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Disconnecting binding " + b.intent
                    + ": shouldUnbind=" + b.intent.hasBound);
            if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0
                    && b.intent.hasBound) {
                try {
                    bumpServiceExecutingLocked(s, false, "unbind");
                    if (b.client != s.app && (c.flags&Context.BIND_WAIVE_PRIORITY) == 0
                            && s.app.setProcState <= ActivityManager.PROCESS_STATE_RECEIVER) {
                        // If this service's process is not already in the cached list,
                        // then update it in the LRU list here because this may be causing
                        // it to go down there and we want it to start out near the top.
                        mAm.updateLruProcessLocked(s.app, false, null);
                    }
                    mAm.updateOomAdjLocked(s.app);
                    b.intent.hasBound = false;
                    // Assume the client doesn't want to know about a rebind;
                    // we will deal with that later if it asks for one.
                    b.intent.doRebind = false;
                    s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when unbinding service " + s.shortName, e);
                    serviceProcessGoneLocked(s);
                }
            }

            if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
                boolean hasAutoCreate = s.hasAutoCreateConnections();
                if (!hasAutoCreate) {
                    if (s.tracker != null) {
                        s.tracker.setBound(false, mAm.mProcessStats.getMemFactorLocked(),
                                SystemClock.uptimeMillis());
                    }
                }
                bringDownServiceIfNeededLocked(s, true, hasAutoCreate);
            }
        }
    }

    void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res) {
        boolean inDestroying = mDestroyingServices.contains(r);
        if (r != null) {
            if (type == ActivityThread.SERVICE_DONE_EXECUTING_START) {
                // This is a call from a service start...  take care of
                // book-keeping.
                r.callStart = true;
                switch (res) {
                    case Service.START_STICKY_COMPATIBILITY:
                    case Service.START_STICKY: {
                        // We are done with the associated start arguments.
                        r.findDeliveredStart(startId, true);
                        // Don't stop if killed.
                        r.stopIfKilled = false;
                        break;
                    }
                    case Service.START_NOT_STICKY: {
                        // We are done with the associated start arguments.
                        r.findDeliveredStart(startId, true);
                        if (r.getLastStartId() == startId) {
                            // There is no more work, and this service
                            // doesn't want to hang around if killed.
                            r.stopIfKilled = true;
                        }
                        break;
                    }
                    case Service.START_REDELIVER_INTENT: {
                        // We'll keep this item until they explicitly
                        // call stop for it, but keep track of the fact
                        // that it was delivered.
                        ServiceRecord.StartItem si = r.findDeliveredStart(startId, false);
                        if (si != null) {
                            si.deliveryCount = 0;
                            si.doneExecutingCount++;
                            // Don't stop if killed.
                            r.stopIfKilled = true;
                        }
                        break;
                    }
                    case Service.START_TASK_REMOVED_COMPLETE: {
                        // Special processing for onTaskRemoved().  Don't
                        // impact normal onStartCommand() processing.
                        r.findDeliveredStart(startId, true);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException(
                                "Unknown service start result: " + res);
                }
                if (res == Service.START_STICKY_COMPATIBILITY) {
                    r.callStart = false;
                }
            } else if (type == ActivityThread.SERVICE_DONE_EXECUTING_STOP) {
                // This is the final call from destroying the service...  we should
                // actually be getting rid of the service at this point.  Do some
                // validation of its state, and ensure it will be fully removed.
                if (!inDestroying) {
                    // Not sure what else to do with this...  if it is not actually in the
                    // destroying list, we don't need to make sure to remove it from it.
                    Slog.wtfStack(TAG, "Service done with onDestroy, but not inDestroying: "
                            + r);
                } else if (r.executeNesting != 1) {
                    Slog.wtfStack(TAG, "Service done with onDestroy, but executeNesting="
                            + r.executeNesting + ": " + r);
                    // Fake it to keep from ANR due to orphaned entry.
                    r.executeNesting = 1;
                }
            }
            final long origId = Binder.clearCallingIdentity();
            serviceDoneExecutingLocked(r, inDestroying, inDestroying);
            Binder.restoreCallingIdentity(origId);
        } else {
            Slog.w(TAG, "Done executing unknown service from pid "
                    + Binder.getCallingPid());
        }
    }

    private void serviceProcessGoneLocked(ServiceRecord r) {
        if (r.tracker != null) {
            int memFactor = mAm.mProcessStats.getMemFactorLocked();
            long now = SystemClock.uptimeMillis();
            r.tracker.setExecuting(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            r.tracker.setStarted(false, memFactor, now);
        }
        serviceDoneExecutingLocked(r, true, true);
    }

    private void serviceDoneExecutingLocked(ServiceRecord r, boolean inDestroying,
            boolean finishing) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "<<< DONE EXECUTING " + r
                + ": nesting=" + r.executeNesting
                + ", inDestroying=" + inDestroying + ", app=" + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                "<<< DONE EXECUTING " + r.shortName);
        r.executeNesting--;
        if (r.executeNesting <= 0) {
            if (r.app != null) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                        "Nesting at 0 of " + r.shortName);
                r.app.execServicesFg = false;
                r.app.executingServices.remove(r);
                if (r.app.executingServices.size() == 0) {
                    if (DEBUG_SERVICE || DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                            "No more executingServices of " + r.shortName);
                    mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_TIMEOUT_MSG, r.app);
                } else if (r.executeFg) {
                    // Need to re-evaluate whether the app still needs to be in the foreground.
                    for (int i=r.app.executingServices.size()-1; i>=0; i--) {
                        if (r.app.executingServices.valueAt(i).executeFg) {
                            r.app.execServicesFg = true;
                            break;
                        }
                    }
                }
                if (inDestroying) {
                    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                            "doneExecuting remove destroying " + r);
                    mDestroyingServices.remove(r);
                    r.bindings.clear();
                }
                mAm.updateOomAdjLocked(r.app);
            }
            r.executeFg = false;
            if (r.tracker != null) {
                r.tracker.setExecuting(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
                if (finishing) {
                    r.tracker.clearCurrentOwner(r, false);
                    r.tracker = null;
                }
            }
            if (finishing) {
                if (r.app != null && !r.app.persistent) {
                    r.app.services.remove(r);
                }
                r.app = null;
            }
        }
    }

    boolean attachApplicationLocked(ProcessRecord proc, String processName)
            throws RemoteException {
        boolean didSomething = false;
        // Collect any services that are waiting for this process to come up.
        if (mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            try {
                for (int i=0; i<mPendingServices.size(); i++) {
                    sr = mPendingServices.get(i);
                    if (proc != sr.isolatedProc && (proc.uid != sr.appInfo.uid
                            || !processName.equals(sr.processName))) {
                        continue;
                    }

                    mPendingServices.remove(i);
                    i--;
                    proc.addPackage(sr.appInfo.packageName, sr.appInfo.versionCode,
                            mAm.mProcessStats);
                    realStartServiceLocked(sr, proc, sr.createdFromFg);
                    didSomething = true;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception in new application when starting service "
                        + sr.shortName, e);
                throw e;
            }
        }
        // Also, if there are any services that are waiting to restart and
        // would run in this process, now is a good time to start them.  It would
        // be weird to bring up the process but arbitrarily not let the services
        // run at this point just because their restart time hasn't come up.
        if (mRestartingServices.size() > 0) {
            ServiceRecord sr = null;
            for (int i=0; i<mRestartingServices.size(); i++) {
                sr = mRestartingServices.get(i);
                if (proc != sr.isolatedProc && (proc.uid != sr.appInfo.uid
                        || !processName.equals(sr.processName))) {
                    continue;
                }
                mAm.mHandler.removeCallbacks(sr.restarter);
                mAm.mHandler.post(sr.restarter);
            }
        }
        return didSomething;
    }

    void processStartTimedOutLocked(ProcessRecord proc) {
        for (int i=0; i<mPendingServices.size(); i++) {
            ServiceRecord sr = mPendingServices.get(i);
            if ((proc.uid == sr.appInfo.uid
                    && proc.processName.equals(sr.processName))
                    || sr.isolatedProc == proc) {
                Slog.w(TAG, "Forcing bringing down service: " + sr);
                sr.isolatedProc = null;
                mPendingServices.remove(i);
                i--;
                bringDownServiceLocked(sr);
            }
        }
    }

    private boolean collectPackageServicesLocked(String packageName, Set<String> filterByClasses,
            boolean evenPersistent, boolean doit, ArrayMap<ComponentName, ServiceRecord> services) {
        boolean didSomething = false;
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord service = services.valueAt(i);
            final boolean sameComponent = packageName == null
                    || (service.packageName.equals(packageName)
                        && (filterByClasses == null
                            || filterByClasses.contains(service.name.getClassName())));
            if (sameComponent
                    && (service.app == null || evenPersistent || !service.app.persistent)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force stopping service " + service);
                if (service.app != null) {
                    service.app.removed = true;
                    if (!service.app.persistent) {
                        service.app.services.remove(service);
                    }
                }
                service.app = null;
                service.isolatedProc = null;
                if (mTmpCollectionResults == null) {
                    mTmpCollectionResults = new ArrayList<>();
                }
                mTmpCollectionResults.add(service);
            }
        }
        return didSomething;
    }

    boolean bringDownDisabledPackageServicesLocked(String packageName, Set<String> filterByClasses,
            int userId, boolean evenPersistent, boolean doit) {
        boolean didSomething = false;

        if (mTmpCollectionResults != null) {
            mTmpCollectionResults.clear();
        }

        if (userId == UserHandle.USER_ALL) {
            for (int i = mServiceMap.size() - 1; i >= 0; i--) {
                didSomething |= collectPackageServicesLocked(packageName, filterByClasses,
                        evenPersistent, doit, mServiceMap.valueAt(i).mServicesByName);
                if (!doit && didSomething) {
                    return true;
                }
            }
        } else {
            ServiceMap smap = mServiceMap.get(userId);
            if (smap != null) {
                ArrayMap<ComponentName, ServiceRecord> items = smap.mServicesByName;
                didSomething = collectPackageServicesLocked(packageName, filterByClasses,
                        evenPersistent, doit, items);
            }
        }

        if (mTmpCollectionResults != null) {
            for (int i = mTmpCollectionResults.size() - 1; i >= 0; i--) {
                bringDownServiceLocked(mTmpCollectionResults.get(i));
            }
            mTmpCollectionResults.clear();
        }
        return didSomething;
    }

    void cleanUpRemovedTaskLocked(TaskRecord tr, ComponentName component, Intent baseIntent) {
        ArrayList<ServiceRecord> services = new ArrayList<>();
        ArrayMap<ComponentName, ServiceRecord> alls = getServices(tr.userId);
        for (int i = alls.size() - 1; i >= 0; i--) {
            ServiceRecord sr = alls.valueAt(i);
            if (sr.packageName.equals(component.getPackageName())) {
                services.add(sr);
            }
        }

        // Take care of any running services associated with the app.
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = services.get(i);
            if (sr.startRequested) {
                if ((sr.serviceInfo.flags&ServiceInfo.FLAG_STOP_WITH_TASK) != 0) {
                    Slog.i(TAG, "Stopping service " + sr.shortName + ": remove task");
                    stopServiceLocked(sr);
                } else {
                    sr.pendingStarts.add(new ServiceRecord.StartItem(sr, true,
                            sr.makeNextStartId(), baseIntent, null));
                    if (sr.app != null && sr.app.thread != null) {
                        // We always run in the foreground, since this is called as
                        // part of the "remove task" UI operation.
                        try {
                            sendServiceArgsLocked(sr, true, false);
                        } catch (TransactionTooLargeException e) {
                            // Ignore, keep going.
                        }
                    }
                }
            }
        }
    }

    final void killServicesLocked(ProcessRecord app, boolean allowRestart) {
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            if (app.services.size() > 0) {
                Iterator<ServiceRecord> it = app.services.iterator();
                while (it.hasNext()) {
                    ServiceRecord r = it.next();
                    for (int conni=r.connections.size()-1; conni>=0; conni--) {
                        ArrayList<ConnectionRecord> cl = r.connections.valueAt(conni);
                        for (int i=0; i<cl.size(); i++) {
                            ConnectionRecord c = cl.get(i);
                            if (c.binding.client != app) {
                                try {
                                    //c.conn.connected(r.className, null);
                                } catch (Exception e) {
                                    // todo: this should be asynchronous!
                                    Slog.w(TAG, "Exception thrown disconnected servce "
                                          + r.shortName
                                          + " from app " + app.processName, e);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clean up any connections this application has to other services.
        for (int i = app.connections.size() - 1; i >= 0; i--) {
            ConnectionRecord r = app.connections.valueAt(i);
            removeConnectionLocked(r, app, null);
        }
        updateServiceConnectionActivitiesLocked(app);
        app.connections.clear();

        // Clear app state from services.
        for (int i = app.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = app.services.valueAt(i);
            synchronized (sr.stats.getBatteryStats()) {
                sr.stats.stopLaunchedLocked();
            }
            if (sr.app != app && sr.app != null && !sr.app.persistent) {
                sr.app.services.remove(sr);
            }
            sr.app = null;
            sr.isolatedProc = null;
            sr.executeNesting = 0;
            sr.forceClearTracker();
            if (mDestroyingServices.remove(sr)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }

            final int numClients = sr.bindings.size();
            for (int bindingi=numClients-1; bindingi>=0; bindingi--) {
                IntentBindRecord b = sr.bindings.valueAt(bindingi);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Killing binding " + b
                        + ": shouldUnbind=" + b.hasBound);
                b.binder = null;
                b.requested = b.received = b.hasBound = false;
                // If this binding is coming from a cached process and is asking to keep
                // the service created, then we'll kill the cached process as well -- we
                // don't want to be thrashing around restarting processes that are only
                // there to be cached.
                for (int appi=b.apps.size()-1; appi>=0; appi--) {
                    final ProcessRecord proc = b.apps.keyAt(appi);
                    // If the process is already gone, skip it.
                    if (proc.killedByAm || proc.thread == null) {
                        continue;
                    }
                    // Only do this for processes that have an auto-create binding;
                    // otherwise the binding can be left, because it won't cause the
                    // service to restart.
                    final AppBindRecord abind = b.apps.valueAt(appi);
                    boolean hasCreate = false;
                    for (int conni=abind.connections.size()-1; conni>=0; conni--) {
                        ConnectionRecord conn = abind.connections.valueAt(conni);
                        if ((conn.flags&(Context.BIND_AUTO_CREATE|Context.BIND_ALLOW_OOM_MANAGEMENT
                                |Context.BIND_WAIVE_PRIORITY)) == Context.BIND_AUTO_CREATE) {
                            hasCreate = true;
                            break;
                        }
                    }
                    if (!hasCreate) {
                        continue;
                    }
                    // XXX turned off for now until we have more time to get a better policy.
                    if (false && proc != null && !proc.persistent && proc.thread != null
                            && proc.pid != 0 && proc.pid != ActivityManagerService.MY_PID
                            && proc.setProcState >= ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
                        proc.kill("bound to service " + sr.name.flattenToShortString()
                                + " in dying proc " + (app != null ? app.processName : "??"), true);
                    }
                }
            }
        }

        ServiceMap smap = getServiceMap(app.userId);

        // Now do remaining service cleanup.
        for (int i=app.services.size()-1; i>=0; i--) {
            ServiceRecord sr = app.services.valueAt(i);

            // Unless the process is persistent, this process record is going away,
            // so make sure the service is cleaned out of it.
            if (!app.persistent) {
                app.services.removeAt(i);
            }

            // Sanity check: if the service listed for the app is not one
            // we actually are maintaining, just let it drop.
            final ServiceRecord curRec = smap.mServicesByName.get(sr.name);
            if (curRec != sr) {
                if (curRec != null) {
                    Slog.wtf(TAG, "Service " + sr + " in process " + app
                            + " not same as in map: " + curRec);
                }
                continue;
            }

            // Any services running in the application may need to be placed
            // back in the pending list.
            if (allowRestart && sr.crashCount >= 2 && (sr.serviceInfo.applicationInfo.flags
                    &ApplicationInfo.FLAG_PERSISTENT) == 0) {
                Slog.w(TAG, "Service crashed " + sr.crashCount
                        + " times, stopping: " + sr);
                EventLog.writeEvent(EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH,
                        sr.userId, sr.crashCount, sr.shortName, app.pid);
                bringDownServiceLocked(sr);
            } else if (!allowRestart || !mAm.isUserRunningLocked(sr.userId, false)) {
                bringDownServiceLocked(sr);
            } else {
                boolean canceled = scheduleServiceRestartLocked(sr, true);

                // Should the service remain running?  Note that in the
                // extreme case of so many attempts to deliver a command
                // that it failed we also will stop it here.
                if (sr.startRequested && (sr.stopIfKilled || canceled)) {
                    if (sr.pendingStarts.size() == 0) {
                        sr.startRequested = false;
                        if (sr.tracker != null) {
                            sr.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                                    SystemClock.uptimeMillis());
                        }
                        if (!sr.hasAutoCreateConnections()) {
                            // Whoops, no reason to restart!
                            bringDownServiceLocked(sr);
                        }
                    }
                }
            }
        }

        if (!allowRestart) {
            app.services.clear();

            // Make sure there are no more restarting services for this process.
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                ServiceRecord r = mRestartingServices.get(i);
                if (r.processName.equals(app.processName) &&
                        r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mRestartingServices.remove(i);
                    clearRestartingIfNeededLocked(r);
                }
            }
            for (int i=mPendingServices.size()-1; i>=0; i--) {
                ServiceRecord r = mPendingServices.get(i);
                if (r.processName.equals(app.processName) &&
                        r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mPendingServices.remove(i);
                }
            }
        }

        // Make sure we have no more records on the stopping list.
        int i = mDestroyingServices.size();
        while (i > 0) {
            i--;
            ServiceRecord sr = mDestroyingServices.get(i);
            if (sr.app == app) {
                sr.forceClearTracker();
                mDestroyingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }
        }

        app.executingServices.clear();
    }

    ActivityManager.RunningServiceInfo makeRunningServiceInfoLocked(ServiceRecord r) {
        ActivityManager.RunningServiceInfo info =
            new ActivityManager.RunningServiceInfo();
        info.service = r.name;
        if (r.app != null) {
            info.pid = r.app.pid;
        }
        info.uid = r.appInfo.uid;
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createTime;
        info.started = r.startRequested;
        info.clientCount = r.connections.size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
        if (r.isForeground) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_FOREGROUND;
        }
        if (r.startRequested) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_STARTED;
        }
        if (r.app != null && r.app.pid == ActivityManagerService.MY_PID) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS;
        }
        if (r.app != null && r.app.persistent) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS;
        }

        for (int conni=r.connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> connl = r.connections.valueAt(conni);
            for (int i=0; i<connl.size(); i++) {
                ConnectionRecord conn = connl.get(i);
                if (conn.clientLabel != 0) {
                    info.clientPackage = conn.binding.client.info.packageName;
                    info.clientLabel = conn.clientLabel;
                    return info;
                }
            }
        }
        return info;
    }

    List<ActivityManager.RunningServiceInfo> getRunningServiceInfoLocked(int maxNum,
            int flags) {
        ArrayList<ActivityManager.RunningServiceInfo> res
                = new ArrayList<ActivityManager.RunningServiceInfo>();

        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            if (ActivityManager.checkUidPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    uid) == PackageManager.PERMISSION_GRANTED) {
                int[] users = mAm.getUsersLocked();
                for (int ui=0; ui<users.length && res.size() < maxNum; ui++) {
                    ArrayMap<ComponentName, ServiceRecord> alls = getServices(users[ui]);
                    for (int i=0; i<alls.size() && res.size() < maxNum; i++) {
                        ServiceRecord sr = alls.valueAt(i);
                        res.add(makeRunningServiceInfoLocked(sr));
                    }
                }

                for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    ActivityManager.RunningServiceInfo info =
                            makeRunningServiceInfoLocked(r);
                    info.restarting = r.nextRestartTime;
                    res.add(info);
                }
            } else {
                int userId = UserHandle.getUserId(uid);
                ArrayMap<ComponentName, ServiceRecord> alls = getServices(userId);
                for (int i=0; i<alls.size() && res.size() < maxNum; i++) {
                    ServiceRecord sr = alls.valueAt(i);
                    res.add(makeRunningServiceInfoLocked(sr));
                }

                for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    if (r.userId == userId) {
                        ActivityManager.RunningServiceInfo info =
                                makeRunningServiceInfoLocked(r);
                        info.restarting = r.nextRestartTime;
                        res.add(info);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return res;
    }

    public PendingIntent getRunningServiceControlPanelLocked(ComponentName name) {
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        ServiceRecord r = getServiceByName(name, userId);
        if (r != null) {
            for (int conni=r.connections.size()-1; conni>=0; conni--) {
                ArrayList<ConnectionRecord> conn = r.connections.valueAt(conni);
                for (int i=0; i<conn.size(); i++) {
                    if (conn.get(i).clientIntent != null) {
                        return conn.get(i).clientIntent;
                    }
                }
            }
        }
        return null;
    }

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;

        synchronized(mAm) {
            if (proc.executingServices.size() == 0 || proc.thread == null) {
                return;
            }
            final long now = SystemClock.uptimeMillis();
            final long maxTime =  now -
                    (proc.execServicesFg ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
            ServiceRecord timeout = null;
            long nextTime = 0;
            for (int i=proc.executingServices.size()-1; i>=0; i--) {
                ServiceRecord sr = proc.executingServices.valueAt(i);
                if (sr.executingStart < maxTime) {
                    timeout = sr;
                    break;
                }
                if (sr.executingStart > nextTime) {
                    nextTime = sr.executingStart;
                }
            }
            if (timeout != null && mAm.mLruProcesses.contains(proc)) {
                Slog.w(TAG, "Timeout executing service: " + timeout);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                pw.println(timeout);
                timeout.dump(pw, "    ");
                pw.close();
                mLastAnrDump = sw.toString();
                mAm.mHandler.removeCallbacks(mLastAnrDumpClearer);
                mAm.mHandler.postDelayed(mLastAnrDumpClearer, LAST_ANR_LIFETIME_DURATION_MSECS);
                anrMessage = "executing service " + timeout.shortName;
            } else {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mAm.mHandler.sendMessageAtTime(msg, proc.execServicesFg
                        ? (nextTime+SERVICE_TIMEOUT) : (nextTime + SERVICE_BACKGROUND_TIMEOUT));
            }
        }

        if (anrMessage != null) {
            mAm.appNotResponding(proc, null, null, false, anrMessage);
        }
    }

    void scheduleServiceTimeoutLocked(ProcessRecord proc) {
        if (proc.executingServices.size() == 0 || proc.thread == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_TIMEOUT_MSG);
        msg.obj = proc;
        mAm.mHandler.sendMessageAtTime(msg,
                proc.execServicesFg ? (now+SERVICE_TIMEOUT) : (now+ SERVICE_BACKGROUND_TIMEOUT));
    }

    /**
     * Prints a list of ServiceRecords (dumpsys activity services)
     */
    void dumpServicesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        boolean needSep = false;
        boolean printedAnything = false;

        ItemMatcher matcher = new ItemMatcher();
        matcher.build(args, opti);

        pw.println("ACTIVITY MANAGER SERVICES (dumpsys activity services)");
        try {
            if (mLastAnrDump != null) {
                pw.println("  Last ANR service:");
                pw.print(mLastAnrDump);
                pw.println();
            }
            int[] users = mAm.getUsersLocked();
            for (int user : users) {
                ServiceMap smap = getServiceMap(user);
                boolean printed = false;
                if (smap.mServicesByName.size() > 0) {
                    long nowReal = SystemClock.elapsedRealtime();
                    needSep = false;
                    for (int si=0; si<smap.mServicesByName.size(); si++) {
                        ServiceRecord r = smap.mServicesByName.valueAt(si);
                        if (!matcher.match(r, r.name)) {
                            continue;
                        }
                        if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                            continue;
                        }
                        if (!printed) {
                            if (printedAnything) {
                                pw.println();
                            }
                            pw.println("  User " + user + " active services:");
                            printed = true;
                        }
                        printedAnything = true;
                        if (needSep) {
                            pw.println();
                        }
                        pw.print("  * ");
                        pw.println(r);
                        if (dumpAll) {
                            r.dump(pw, "    ");
                            needSep = true;
                        } else {
                            pw.print("    app=");
                            pw.println(r.app);
                            pw.print("    created=");
                            TimeUtils.formatDuration(r.createTime, nowReal, pw);
                            pw.print(" started=");
                            pw.print(r.startRequested);
                            pw.print(" connections=");
                            pw.println(r.connections.size());
                            if (r.connections.size() > 0) {
                                pw.println("    Connections:");
                                for (int conni=0; conni<r.connections.size(); conni++) {
                                    ArrayList<ConnectionRecord> clist = r.connections.valueAt(conni);
                                    for (int i = 0; i < clist.size(); i++) {
                                        ConnectionRecord conn = clist.get(i);
                                        pw.print("      ");
                                        pw.print(conn.binding.intent.intent.getIntent()
                                                .toShortString(false, false, false, false));
                                        pw.print(" -> ");
                                        ProcessRecord proc = conn.binding.client;
                                        pw.println(proc != null ? proc.toShortString() : "null");
                                    }
                                }
                            }
                        }
                        if (dumpClient && r.app != null && r.app.thread != null) {
                            pw.println("    Client:");
                            pw.flush();
                            try {
                                TransferPipe tp = new TransferPipe();
                                try {
                                    r.app.thread.dumpService(tp.getWriteFd().getFileDescriptor(),
                                            r, args);
                                    tp.setBufferPrefix("      ");
                                    // Short timeout, since blocking here can
                                    // deadlock with the application.
                                    tp.go(fd, 2000);
                                } finally {
                                    tp.kill();
                                }
                            } catch (IOException e) {
                                pw.println("      Failure while dumping the service: " + e);
                            } catch (RemoteException e) {
                                pw.println("      Got a RemoteException while dumping the service");
                            }
                            needSep = true;
                        }
                    }
                    needSep |= printed;
                }
                printed = false;
                for (int si=0, SN=smap.mDelayedStartList.size(); si<SN; si++) {
                    ServiceRecord r = smap.mDelayedStartList.get(si);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    if (!printed) {
                        if (printedAnything) {
                            pw.println();
                        }
                        pw.println("  User " + user + " delayed start services:");
                        printed = true;
                    }
                    printedAnything = true;
                    pw.print("  * Delayed start "); pw.println(r);
                }
                printed = false;
                for (int si=0, SN=smap.mStartingBackground.size(); si<SN; si++) {
                    ServiceRecord r = smap.mStartingBackground.get(si);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    if (!printed) {
                        if (printedAnything) {
                            pw.println();
                        }
                        pw.println("  User " + user + " starting in background:");
                        printed = true;
                    }
                    printedAnything = true;
                    pw.print("  * Starting bg "); pw.println(r);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Exception in dumpServicesLocked", e);
        }

        if (mPendingServices.size() > 0) {
            boolean printed = false;
            for (int i=0; i<mPendingServices.size(); i++) {
                ServiceRecord r = mPendingServices.get(i);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                printedAnything = true;
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Pending services:");
                    printed = true;
                }
                pw.print("  * Pending "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mRestartingServices.size() > 0) {
            boolean printed = false;
            for (int i=0; i<mRestartingServices.size(); i++) {
                ServiceRecord r = mRestartingServices.get(i);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                printedAnything = true;
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Restarting services:");
                    printed = true;
                }
                pw.print("  * Restarting "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mDestroyingServices.size() > 0) {
            boolean printed = false;
            for (int i=0; i< mDestroyingServices.size(); i++) {
                ServiceRecord r = mDestroyingServices.get(i);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                printedAnything = true;
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Destroying services:");
                    printed = true;
                }
                pw.print("  * Destroy "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (dumpAll) {
            boolean printed = false;
            for (int ic=0; ic<mServiceConnections.size(); ic++) {
                ArrayList<ConnectionRecord> r = mServiceConnections.valueAt(ic);
                for (int i=0; i<r.size(); i++) {
                    ConnectionRecord cr = r.get(i);
                    if (!matcher.match(cr.binding.service, cr.binding.service.name)) {
                        continue;
                    }
                    if (dumpPackage != null && (cr.binding.client == null
                            || !dumpPackage.equals(cr.binding.client.info.packageName))) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Connection bindings to services:");
                        printed = true;
                    }
                    pw.print("  * "); pw.println(cr);
                    cr.dump(pw, "    ");
                }
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    /**
     * There are three ways to call this:
     *  - no service specified: dump all the services
     *  - a flattened component name that matched an existing service was specified as the
     *    first arg: dump that one service
     *  - the first arg isn't the flattened component name of an existing service:
     *    dump all services whose component contains the first arg as a substring
     */
    protected boolean dumpService(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();

        synchronized (mAm) {
            int[] users = mAm.getUsersLocked();
            if ("all".equals(name)) {
                for (int user : users) {
                    ServiceMap smap = mServiceMap.get(user);
                    if (smap == null) {
                        continue;
                    }
                    ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByName;
                    for (int i=0; i<alls.size(); i++) {
                        ServiceRecord r1 = alls.valueAt(i);
                        services.add(r1);
                    }
                }
            } else {
                ComponentName componentName = name != null
                        ? ComponentName.unflattenFromString(name) : null;
                int objectId = 0;
                if (componentName == null) {
                    // Not a '/' separated full component name; maybe an object ID?
                    try {
                        objectId = Integer.parseInt(name, 16);
                        name = null;
                        componentName = null;
                    } catch (RuntimeException e) {
                    }
                }

                for (int user : users) {
                    ServiceMap smap = mServiceMap.get(user);
                    if (smap == null) {
                        continue;
                    }
                    ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByName;
                    for (int i=0; i<alls.size(); i++) {
                        ServiceRecord r1 = alls.valueAt(i);
                        if (componentName != null) {
                            if (r1.name.equals(componentName)) {
                                services.add(r1);
                            }
                        } else if (name != null) {
                            if (r1.name.flattenToString().contains(name)) {
                                services.add(r1);
                            }
                        } else if (System.identityHashCode(r1) == objectId) {
                            services.add(r1);
                        }
                    }
                }
            }
        }

        if (services.size() <= 0) {
            return false;
        }

        boolean needSep = false;
        for (int i=0; i<services.size(); i++) {
            if (needSep) {
                pw.println();
            }
            needSep = true;
            dumpService("", fd, pw, services.get(i), args, dumpAll);
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpService() on the thread of the specified service if
     * there is a thread associated with the service.
     */
    private void dumpService(String prefix, FileDescriptor fd, PrintWriter pw,
            final ServiceRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        synchronized (mAm) {
            pw.print(prefix); pw.print("SERVICE ");
                    pw.print(r.shortName); pw.print(" ");
                    pw.print(Integer.toHexString(System.identityHashCode(r)));
                    pw.print(" pid=");
                    if (r.app != null) pw.println(r.app.pid);
                    else pw.println("(not running)");
            if (dumpAll) {
                r.dump(pw, innerPrefix);
            }
        }
        if (r.app != null && r.app.thread != null) {
            pw.print(prefix); pw.println("  Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    r.app.thread.dumpService(tp.getWriteFd().getFileDescriptor(), r, args);
                    tp.setBufferPrefix(prefix + "    ");
                    tp.go(fd);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println(prefix + "    Failure while dumping the service: " + e);
            } catch (RemoteException e) {
                pw.println(prefix + "    Got a RemoteException while dumping the service");
            }
        }
    }

}
