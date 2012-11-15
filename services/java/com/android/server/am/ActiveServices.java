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

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.android.internal.os.BatteryStatsImpl;
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
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

public class ActiveServices {
    static final boolean DEBUG_SERVICE = ActivityManagerService.DEBUG_SERVICE;
    static final boolean DEBUG_SERVICE_EXECUTING = ActivityManagerService.DEBUG_SERVICE_EXECUTING;
    static final boolean DEBUG_MU = ActivityManagerService.DEBUG_MU;
    static final String TAG = ActivityManagerService.TAG;
    static final String TAG_MU = ActivityManagerService.TAG_MU;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20*1000;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    static final int SERVICE_RESTART_DURATION = 5*1000;

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

    final ActivityManagerService mAm;

    final ServiceMap mServiceMap = new ServiceMap();

    /**
     * All currently bound service connections.  Keys are the IBinder of
     * the client's IServiceConnection.
     */
    final HashMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections
            = new HashMap<IBinder, ArrayList<ConnectionRecord>>();

    /**
     * List of services that we have been asked to start,
     * but haven't yet been able to.  It is used to hold start requests
     * while waiting for their corresponding application thread to get
     * going.
     */
    final ArrayList<ServiceRecord> mPendingServices
            = new ArrayList<ServiceRecord>();

    /**
     * List of services that are scheduled to restart following a crash.
     */
    final ArrayList<ServiceRecord> mRestartingServices
            = new ArrayList<ServiceRecord>();

    /**
     * List of services that are in the process of being stopped.
     */
    final ArrayList<ServiceRecord> mStoppingServices
            = new ArrayList<ServiceRecord>();

    static class ServiceMap {

        private final SparseArray<HashMap<ComponentName, ServiceRecord>> mServicesByNamePerUser
                = new SparseArray<HashMap<ComponentName, ServiceRecord>>();
        private final SparseArray<HashMap<Intent.FilterComparison, ServiceRecord>>
                mServicesByIntentPerUser = new SparseArray<
                    HashMap<Intent.FilterComparison, ServiceRecord>>();

        ServiceRecord getServiceByName(ComponentName name, int callingUser) {
            // TODO: Deal with global services
            if (DEBUG_MU)
                Slog.v(TAG_MU, "getServiceByName(" + name + "), callingUser = " + callingUser);
            return getServices(callingUser).get(name);
        }

        ServiceRecord getServiceByName(ComponentName name) {
            return getServiceByName(name, -1);
        }

        ServiceRecord getServiceByIntent(Intent.FilterComparison filter, int callingUser) {
            // TODO: Deal with global services
            if (DEBUG_MU)
                Slog.v(TAG_MU, "getServiceByIntent(" + filter + "), callingUser = " + callingUser);
            return getServicesByIntent(callingUser).get(filter);
        }

        ServiceRecord getServiceByIntent(Intent.FilterComparison filter) {
            return getServiceByIntent(filter, -1);
        }

        void putServiceByName(ComponentName name, int callingUser, ServiceRecord value) {
            // TODO: Deal with global services
            getServices(callingUser).put(name, value);
        }

        void putServiceByIntent(Intent.FilterComparison filter, int callingUser,
                ServiceRecord value) {
            // TODO: Deal with global services
            getServicesByIntent(callingUser).put(filter, value);
        }

        void removeServiceByName(ComponentName name, int callingUser) {
            // TODO: Deal with global services
            ServiceRecord removed = getServices(callingUser).remove(name);
            if (DEBUG_MU)
                Slog.v(TAG, "removeServiceByName user=" + callingUser + " name=" + name
                        + " removed=" + removed);
        }

        void removeServiceByIntent(Intent.FilterComparison filter, int callingUser) {
            // TODO: Deal with global services
            ServiceRecord removed = getServicesByIntent(callingUser).remove(filter);
            if (DEBUG_MU)
                Slog.v(TAG_MU, "removeServiceByIntent user=" + callingUser + " intent=" + filter
                        + " removed=" + removed);
        }

        Collection<ServiceRecord> getAllServices(int callingUser) {
            // TODO: Deal with global services
            return getServices(callingUser).values();
        }

        private HashMap<ComponentName, ServiceRecord> getServices(int callingUser) {
            HashMap<ComponentName, ServiceRecord> map = mServicesByNamePerUser.get(callingUser);
            if (map == null) {
                map = new HashMap<ComponentName, ServiceRecord>();
                mServicesByNamePerUser.put(callingUser, map);
            }
            return map;
        }

        private HashMap<Intent.FilterComparison, ServiceRecord> getServicesByIntent(
                int callingUser) {
            HashMap<Intent.FilterComparison, ServiceRecord> map
                    = mServicesByIntentPerUser.get(callingUser);
            if (map == null) {
                map = new HashMap<Intent.FilterComparison, ServiceRecord>();
                mServicesByIntentPerUser.put(callingUser, map);
            }
            return map;
        }
    }

    public ActiveServices(ActivityManagerService service) {
        mAm = service;
    }

    ComponentName startServiceLocked(IApplicationThread caller,
            Intent service, String resolvedType,
            int callingPid, int callingUid, int userId) {
        if (DEBUG_SERVICE) Slog.v(TAG, "startService: " + service
                + " type=" + resolvedType + " args=" + service.getExtras());

        if (caller != null) {
            final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when starting service " + service);
            }
        }

        ServiceLookupResult res =
            retrieveServiceLocked(service, resolvedType,
                    callingPid, callingUid, userId, true);
        if (res == null) {
            return null;
        }
        if (res.record == null) {
            return new ComponentName("!", res.permission != null
                    ? res.permission : "private to package");
        }
        ServiceRecord r = res.record;
        NeededUriGrants neededGrants = mAm.checkGrantUriPermissionFromIntentLocked(
                callingUid, r.packageName, service, service.getFlags(), null);
        if (unscheduleServiceRestartLocked(r)) {
            if (DEBUG_SERVICE) Slog.v(TAG, "START SERVICE WHILE RESTART PENDING: " + r);
        }
        r.startRequested = true;
        r.callStart = false;
        r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                service, neededGrants));
        r.lastActivity = SystemClock.uptimeMillis();
        synchronized (r.stats.getBatteryStats()) {
            r.stats.startRunningLocked();
        }
        String error = bringUpServiceLocked(r, service.getFlags(), false);
        if (error != null) {
            return new ComponentName("!!", error);
        }
        return r.name;
    }

    private void stopServiceLocked(ServiceRecord service) {
        synchronized (service.stats.getBatteryStats()) {
            service.stats.stopRunningLocked();
        }
        service.startRequested = false;
        service.callStart = false;
        bringDownServiceLocked(service, false);
    }

    int stopServiceLocked(IApplicationThread caller, Intent service,
            String resolvedType, int userId) {
        if (DEBUG_SERVICE) Slog.v(TAG, "stopService: " + service
                + " type=" + resolvedType);

        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (caller != null && callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when stopping service " + service);
        }

        // If this service is active, make sure it is stopped.
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType,
                Binder.getCallingPid(), Binder.getCallingUid(), userId, false);
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

    IBinder peekServiceLocked(Intent service, String resolvedType) {
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType,
                Binder.getCallingPid(), Binder.getCallingUid(),
                UserHandle.getCallingUserId(), false);

        IBinder ret = null;
        if (r != null) {
            // r.record is null if findServiceLocked() failed the caller permission check
            if (r.record == null) {
                throw new SecurityException(
                        "Permission Denial: Accessing service " + r.record.name
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
        if (DEBUG_SERVICE) Slog.v(TAG, "stopServiceToken: " + className
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
                r.startRequested = false;
                r.callStart = false;
            }
            final long origId = Binder.clearCallingIdentity();
            bringDownServiceLocked(r, false);
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
                } else {
                    if (r.isForeground) {
                        r.isForeground = false;
                        if (r.app != null) {
                            mAm.updateLruProcessLocked(r.app, false);
                            updateServiceForegroundLocked(r.app, true);
                        }
                    }
                    if (removeNotification) {
                        r.cancelNotification();
                        r.foregroundId = 0;
                        r.foregroundNoti = null;
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (ServiceRecord sr : proc.services) {
            if (sr.isForeground) {
                anyForeground = true;
                break;
            }
        }
        if (anyForeground != proc.foregroundServices) {
            proc.foregroundServices = anyForeground;
            if (oomAdj) {
                mAm.updateOomAdjLocked();
            }
        }
    }

    int bindServiceLocked(IApplicationThread caller, IBinder token,
            Intent service, String resolvedType,
            IServiceConnection connection, int flags, int userId) {
        if (DEBUG_SERVICE) Slog.v(TAG, "bindService: " + service
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
            activity = mAm.mMainStack.isInStackLocked(token);
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
                clientIntent = (PendingIntent)service.getParcelableExtra(
                        Intent.EXTRA_CLIENT_INTENT);
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

        ServiceLookupResult res =
            retrieveServiceLocked(service, resolvedType,
                    Binder.getCallingPid(), Binder.getCallingUid(), userId, true);
        if (res == null) {
            return 0;
        }
        if (res.record == null) {
            return -1;
        }
        ServiceRecord s = res.record;

        final long origId = Binder.clearCallingIdentity();

        try {
            if (unscheduleServiceRestartLocked(s)) {
                if (DEBUG_SERVICE) Slog.v(TAG, "BIND SERVICE WHILE RESTART PENDING: "
                        + s);
            }

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
            clist = mServiceConnections.get(binder);
            if (clist == null) {
                clist = new ArrayList<ConnectionRecord>();
                mServiceConnections.put(binder, clist);
            }
            clist.add(c);

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (bringUpServiceLocked(s, service.getFlags(), false) != null) {
                    return 0;
                }
            }

            if (s.app != null) {
                // This could have made the service more important.
                mAm.updateOomAdjLocked(s.app);
            }

            if (DEBUG_SERVICE) Slog.v(TAG, "Bind " + s + " with " + b
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
                    requestServiceBindingLocked(s, b.intent, true);
                }
            } else if (!b.intent.requested) {
                requestServiceBindingLocked(s, b.intent, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return 1;
    }

    void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (DEBUG_SERVICE) Slog.v(TAG, "PUBLISHING " + r
                    + " " + intent + ": " + service);
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (b != null && !b.received) {
                    b.binder = service;
                    b.requested = true;
                    b.received = true;
                    if (r.connections.size() > 0) {
                        Iterator<ArrayList<ConnectionRecord>> it
                                = r.connections.values().iterator();
                        while (it.hasNext()) {
                            ArrayList<ConnectionRecord> clist = it.next();
                            for (int i=0; i<clist.size(); i++) {
                                ConnectionRecord c = clist.get(i);
                                if (!filter.equals(c.binding.intent.intent)) {
                                    if (DEBUG_SERVICE) Slog.v(
                                            TAG, "Not publishing to: " + c);
                                    if (DEBUG_SERVICE) Slog.v(
                                            TAG, "Bound intent: " + c.binding.intent.intent);
                                    if (DEBUG_SERVICE) Slog.v(
                                            TAG, "Published intent: " + intent);
                                    continue;
                                }
                                if (DEBUG_SERVICE) Slog.v(TAG, "Publishing to: " + c);
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
                }

                serviceDoneExecutingLocked(r, mStoppingServices.contains(r));
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean unbindServiceLocked(IServiceConnection connection) {
        IBinder binder = connection.asBinder();
        if (DEBUG_SERVICE) Slog.v(TAG, "unbindService: conn=" + binder);
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

                if (r.binding.service.app != null) {
                    // This could have made the service less important.
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
                if (DEBUG_SERVICE) Slog.v(TAG, "unbindFinished in " + r
                        + " at " + b + ": apps="
                        + (b != null ? b.apps.size() : 0));

                boolean inStopping = mStoppingServices.contains(r);
                if (b != null) {
                    if (b.apps.size() > 0 && !inStopping) {
                        // Applications have already bound since the last
                        // unbind, so just rebind right here.
                        requestServiceBindingLocked(r, b, true);
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                serviceDoneExecutingLocked(r, inStopping);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final ServiceRecord findServiceLocked(ComponentName name,
            IBinder token, int userId) {
        ServiceRecord r = mServiceMap.getServiceByName(name, userId);
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
            String resolvedType, int callingPid, int callingUid, int userId,
            boolean createIfNeeded) {
        ServiceRecord r = null;
        if (DEBUG_SERVICE) Slog.v(TAG, "retrieveServiceLocked: " + service
                + " type=" + resolvedType + " callingUid=" + callingUid);

        userId = mAm.handleIncomingUser(callingPid, callingUid, userId,
                false, true, "service", null);

        if (service.getComponent() != null) {
            r = mServiceMap.getServiceByName(service.getComponent(), userId);
        }
        if (r == null) {
            Intent.FilterComparison filter = new Intent.FilterComparison(service);
            r = mServiceMap.getServiceByIntent(filter, userId);
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
                    Slog.w(TAG, "Unable to start service " + service + " U=" + userId +
                          ": not found");
                    return null;
                }
                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                if (userId > 0) {
                    if (mAm.isSingleton(sInfo.processName, sInfo.applicationInfo,
                            sInfo.name, sInfo.flags)) {
                        userId = 0;
                    }
                    sInfo = new ServiceInfo(sInfo);
                    sInfo.applicationInfo = mAm.getAppInfoForUser(sInfo.applicationInfo, userId);
                }
                r = mServiceMap.getServiceByName(name, userId);
                if (r == null && createIfNeeded) {
                    Intent.FilterComparison filter = new Intent.FilterComparison(
                            service.cloneFilter());
                    ServiceRestarter res = new ServiceRestarter();
                    BatteryStatsImpl.Uid.Pkg.Serv ss = null;
                    BatteryStatsImpl stats = mAm.mBatteryStatsService.getActiveStatistics();
                    synchronized (stats) {
                        ss = stats.getServiceStatsLocked(
                                sInfo.applicationInfo.uid, sInfo.packageName,
                                sInfo.name);
                    }
                    r = new ServiceRecord(mAm, ss, name, filter, sInfo, res);
                    res.setService(r);
                    mServiceMap.putServiceByName(name, UserHandle.getUserId(r.appInfo.uid), r);
                    mServiceMap.putServiceByIntent(filter, UserHandle.getUserId(r.appInfo.uid), r);

                    // Make sure this component isn't in the pending list.
                    int N = mPendingServices.size();
                    for (int i=0; i<N; i++) {
                        ServiceRecord pr = mPendingServices.get(i);
                        if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                                && pr.name.equals(name)) {
                            mPendingServices.remove(i);
                            i--;
                            N--;
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
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r, String why) {
        if (DEBUG_SERVICE) Log.v(TAG, ">>> EXECUTING "
                + why + " of " + r + " in app " + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Log.v(TAG, ">>> EXECUTING "
                + why + " of " + r.shortName);
        long now = SystemClock.uptimeMillis();
        if (r.executeNesting == 0 && r.app != null) {
            if (r.app.executingServices.size() == 0) {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_TIMEOUT_MSG);
                msg.obj = r.app;
                mAm.mHandler.sendMessageAtTime(msg, now+SERVICE_TIMEOUT);
            }
            r.app.executingServices.add(r);
        }
        r.executeNesting++;
        r.executingStart = now;
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r,
            IntentBindRecord i, boolean rebind) {
        if (r.app == null || r.app.thread == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, "bind");
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (RemoteException e) {
                if (DEBUG_SERVICE) Slog.v(TAG, "Crashed while binding " + r);
                return false;
            }
        }
        return true;
    }

    private final boolean scheduleServiceRestartLocked(ServiceRecord r,
            boolean allowCancel) {
        boolean canceled = false;

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
                    if ((r.serviceInfo.applicationInfo.flags
                            &ApplicationInfo.FLAG_PERSISTENT) != 0) {
                        // Services in peristent processes will restart much more
                        // quickly, since they are pretty important.  (Think SystemUI).
                        r.restartDelay += minDuration/2;
                    } else {
                        r.restartDelay *= SERVICE_RESTART_DURATION_FACTOR;
                        if (r.restartDelay < minDuration) {
                            r.restartDelay = minDuration;
                        }
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
            // Persistent processes are immediately restrted, so there is no
            // reason to hold of on restarting their services.
            r.totalRestartCount++;
            r.restartCount = 0;
            r.restartDelay = 0;
            r.nextRestartTime = now;
        }

        if (!mRestartingServices.contains(r)) {
            mRestartingServices.add(r);
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
        bringUpServiceLocked(r, r.intent.getIntent().getFlags(), true);
    }

    private final boolean unscheduleServiceRestartLocked(ServiceRecord r) {
        if (r.restartDelay == 0) {
            return false;
        }
        r.resetRestartCounter();
        mRestartingServices.remove(r);
        mAm.mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private final String bringUpServiceLocked(ServiceRecord r,
            int intentFlags, boolean whileRestarting) {
        //Slog.i(TAG, "Bring up service:");
        //r.dump("  ");

        if (r.app != null && r.app.thread != null) {
            sendServiceArgsLocked(r, false);
            return null;
        }

        if (!whileRestarting && r.restartDelay > 0) {
            // If waiting for a restart, then do nothing.
            return null;
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Bringing up " + r + " " + r.intent);

        // We are now bringing the service up, so no longer in the
        // restarting state.
        mRestartingServices.remove(r);

        // Make sure that the user who owns this service is started.  If not,
        // we don't want to allow it to run.
        if (mAm.mStartedUsers.get(r.userId) == null) {
            String msg = "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": user " + r.userId + " is stopped";
            Slog.w(TAG, msg);
            bringDownServiceLocked(r, true);
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
            app = mAm.getProcessRecordLocked(procName, r.appInfo.uid);
            if (DEBUG_MU)
                Slog.v(TAG_MU, "bringUpServiceLocked: appInfo.uid=" + r.appInfo.uid + " app=" + app);
            if (app != null && app.thread != null) {
                try {
                    app.addPackage(r.appInfo.packageName);
                    realStartServiceLocked(r, app);
                    return null;
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
                    "service", r.name, false, isolated)) == null) {
                String msg = "Unable to launch app "
                        + r.appInfo.packageName + "/"
                        + r.appInfo.uid + " for service "
                        + r.intent.getIntent() + ": process is bad";
                Slog.w(TAG, msg);
                bringDownServiceLocked(r, true);
                return msg;
            }
            if (isolated) {
                r.isolatedProc = app;
            }
        }

        if (!mPendingServices.contains(r)) {
            mPendingServices.add(r);
        }

        return null;
    }

    private final void requestServiceBindingsLocked(ServiceRecord r) {
        Iterator<IntentBindRecord> bindings = r.bindings.values().iterator();
        while (bindings.hasNext()) {
            IntentBindRecord i = bindings.next();
            if (!requestServiceBindingLocked(r, i, false)) {
                break;
            }
        }
    }

    private final void realStartServiceLocked(ServiceRecord r,
            ProcessRecord app) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }
        if (DEBUG_MU)
            Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid
                    + ", ProcessRecord.uid = " + app.uid);
        r.app = app;
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

        app.services.add(r);
        bumpServiceExecutingLocked(r, "create");
        mAm.updateLruProcessLocked(app, true);

        boolean created = false;
        try {
            EventLogTags.writeAmCreateService(
                    r.userId, System.identityHashCode(r), r.shortName, r.app.pid);
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startLaunchedLocked();
            }
            mAm.ensurePackageDexOpt(r.serviceInfo.packageName);
            app.thread.scheduleCreateService(r, r.serviceInfo,
                    mAm.compatibilityInfoForPackageLocked(r.serviceInfo.applicationInfo));
            r.postNotification();
            created = true;
        } finally {
            if (!created) {
                app.services.remove(r);
                scheduleServiceRestartLocked(r, false);
            }
        }

        requestServiceBindingsLocked(r);

        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    null, null));
        }

        sendServiceArgsLocked(r, true);
    }

    private final void sendServiceArgsLocked(ServiceRecord r,
            boolean oomAdjusted) {
        final int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }

        while (r.pendingStarts.size() > 0) {
            try {
                ServiceRecord.StartItem si = r.pendingStarts.remove(0);
                if (DEBUG_SERVICE) Slog.v(TAG, "Sending arguments to: "
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
                bumpServiceExecutingLocked(r, "start");
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
            } catch (RemoteException e) {
                // Remote process gone...  we'll let the normal cleanup take
                // care of this.
                if (DEBUG_SERVICE) Slog.v(TAG, "Crashed while scheduling start: " + r);
                break;
            } catch (Exception e) {
                Slog.w(TAG, "Unexpected exception", e);
                break;
            }
        }
    }

    private final void bringDownServiceLocked(ServiceRecord r, boolean force) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Does it still need to run?
        if (!force && r.startRequested) {
            return;
        }
        if (r.connections.size() > 0) {
            if (!force) {
                // XXX should probably keep a count of the number of auto-create
                // connections directly in the service.
                Iterator<ArrayList<ConnectionRecord>> it = r.connections.values().iterator();
                while (it.hasNext()) {
                    ArrayList<ConnectionRecord> cr = it.next();
                    for (int i=0; i<cr.size(); i++) {
                        if ((cr.get(i).flags&Context.BIND_AUTO_CREATE) != 0) {
                            return;
                        }
                    }
                }
            }

            // Report to all of the connections that the service is no longer
            // available.
            Iterator<ArrayList<ConnectionRecord>> it = r.connections.values().iterator();
            while (it.hasNext()) {
                ArrayList<ConnectionRecord> c = it.next();
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
        }

        // Tell the service that it has been unbound.
        if (r.bindings.size() > 0 && r.app != null && r.app.thread != null) {
            Iterator<IntentBindRecord> it = r.bindings.values().iterator();
            while (it.hasNext()) {
                IntentBindRecord ibr = it.next();
                if (DEBUG_SERVICE) Slog.v(TAG, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (r.app != null && r.app.thread != null && ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r, "bring down unbind");
                        mAm.updateOomAdjLocked(r.app);
                        ibr.hasBound = false;
                        r.app.thread.scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortName, e);
                        serviceDoneExecutingLocked(r, true);
                    }
                }
            }
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Bringing down " + r + " " + r.intent);
        EventLogTags.writeAmDestroyService(
                r.userId, System.identityHashCode(r), (r.app != null) ? r.app.pid : -1);

        mServiceMap.removeServiceByName(r.name, r.userId);
        mServiceMap.removeServiceByIntent(r.intent, r.userId);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r);

        // Also make sure it is not on the pending list.
        int N = mPendingServices.size();
        for (int i=0; i<N; i++) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG, "Removed pending: " + r);
                i--;
                N--;
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
                try {
                    bumpServiceExecutingLocked(r, "stop");
                    mStoppingServices.add(r);
                    mAm.updateOomAdjLocked(r.app);
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when stopping service "
                            + r.shortName, e);
                    serviceDoneExecutingLocked(r, true);
                }
                updateServiceForegroundLocked(r.app, false);
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG, "Removed service that has no process: " + r);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG, "Removed service that is not running: " + r);
        }

        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }

        if (r.restarter instanceof ServiceRestarter) {
           ((ServiceRestarter)r.restarter).setService(null);
        }
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
        }
        clist = mServiceConnections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                mServiceConnections.remove(binder);
            }
        }

        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (!c.serviceDead) {
            if (DEBUG_SERVICE) Slog.v(TAG, "Disconnecting binding " + b.intent
                    + ": shouldUnbind=" + b.intent.hasBound);
            if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0
                    && b.intent.hasBound) {
                try {
                    bumpServiceExecutingLocked(s, "unbind");
                    mAm.updateOomAdjLocked(s.app);
                    b.intent.hasBound = false;
                    // Assume the client doesn't want to know about a rebind;
                    // we will deal with that later if it asks for one.
                    b.intent.doRebind = false;
                    s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when unbinding service " + s.shortName, e);
                    serviceDoneExecutingLocked(s, true);
                }
            }

            if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
                bringDownServiceLocked(s, false);
            }
        }
    }

    void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res) {
        boolean inStopping = mStoppingServices.contains(r);
        if (r != null) {
            if (type == 1) {
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
            }
            final long origId = Binder.clearCallingIdentity();
            serviceDoneExecutingLocked(r, inStopping);
            Binder.restoreCallingIdentity(origId);
        } else {
            Slog.w(TAG, "Done executing unknown service from pid "
                    + Binder.getCallingPid());
        }
    }

    private void serviceDoneExecutingLocked(ServiceRecord r, boolean inStopping) {
        if (DEBUG_SERVICE) Slog.v(TAG, "<<< DONE EXECUTING " + r
                + ": nesting=" + r.executeNesting
                + ", inStopping=" + inStopping + ", app=" + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG, "<<< DONE EXECUTING " + r.shortName);
        r.executeNesting--;
        if (r.executeNesting <= 0 && r.app != null) {
            if (DEBUG_SERVICE) Slog.v(TAG,
                    "Nesting at 0 of " + r.shortName);
            r.app.executingServices.remove(r);
            if (r.app.executingServices.size() == 0) {
                if (DEBUG_SERVICE || DEBUG_SERVICE_EXECUTING) Slog.v(TAG,
                        "No more executingServices of " + r.shortName);
                mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_TIMEOUT_MSG, r.app);
            }
            if (inStopping) {
                if (DEBUG_SERVICE) Slog.v(TAG,
                        "doneExecuting remove stopping " + r);
                mStoppingServices.remove(r);
                r.bindings.clear();
            }
            mAm.updateOomAdjLocked(r.app);
        }
    }

    boolean attachApplicationLocked(ProcessRecord proc, String processName) throws Exception {
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
                    realStartServiceLocked(sr, proc);
                    didSomething = true;
                }
            } catch (Exception e) {
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
                bringDownServiceLocked(sr, true);
            }
        }
    }

    private boolean collectForceStopServicesLocked(String name, int userId,
            boolean evenPersistent, boolean doit,
            HashMap<ComponentName, ServiceRecord> services,
            ArrayList<ServiceRecord> result) {
        boolean didSomething = false;
        for (ServiceRecord service : services.values()) {
            if ((name == null || service.packageName.equals(name))
                    && (service.app == null || evenPersistent || !service.app.persistent)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force stopping service " + service);
                if (service.app != null) {
                    service.app.removed = true;
                }
                service.app = null;
                service.isolatedProc = null;
                result.add(service);
            }
        }
        return didSomething;
    }

    boolean forceStopLocked(String name, int userId, boolean evenPersistent, boolean doit) {
        boolean didSomething = false;
        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();
        if (userId == UserHandle.USER_ALL) {
            for (int i=0; i<mServiceMap.mServicesByNamePerUser.size(); i++) {
                didSomething |= collectForceStopServicesLocked(name, userId, evenPersistent,
                        doit, mServiceMap.mServicesByNamePerUser.valueAt(i), services);
                if (!doit && didSomething) {
                    return true;
                }
            }
        } else {
            HashMap<ComponentName, ServiceRecord> items
                    = mServiceMap.mServicesByNamePerUser.get(userId);
            if (items != null) {
                didSomething = collectForceStopServicesLocked(name, userId, evenPersistent,
                        doit, items, services);
            }
        }

        int N = services.size();
        for (int i=0; i<N; i++) {
            bringDownServiceLocked(services.get(i), true);
        }
        return didSomething;
    }

    void cleanUpRemovedTaskLocked(TaskRecord tr, ComponentName component, Intent baseIntent) {
        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();
        for (ServiceRecord sr : mServiceMap.getAllServices(tr.userId)) {
            if (sr.packageName.equals(component.getPackageName())) {
                services.add(sr);
            }
        }

        // Take care of any running services associated with the app.
        for (int i=0; i<services.size(); i++) {
            ServiceRecord sr = services.get(i);
            if (sr.startRequested) {
                if ((sr.serviceInfo.flags&ServiceInfo.FLAG_STOP_WITH_TASK) != 0) {
                    Slog.i(TAG, "Stopping service " + sr.shortName + ": remove task");
                    stopServiceLocked(sr);
                } else {
                    sr.pendingStarts.add(new ServiceRecord.StartItem(sr, true,
                            sr.makeNextStartId(), baseIntent, null));
                    if (sr.app != null && sr.app.thread != null) {
                        sendServiceArgsLocked(sr, false);
                    }
                }
            }
        }
    }

    final void killServicesLocked(ProcessRecord app,
            boolean allowRestart) {
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            if (app.services.size() > 0) {
                Iterator<ServiceRecord> it = app.services.iterator();
                while (it.hasNext()) {
                    ServiceRecord r = it.next();
                    if (r.connections.size() > 0) {
                        Iterator<ArrayList<ConnectionRecord>> jt
                                = r.connections.values().iterator();
                        while (jt.hasNext()) {
                            ArrayList<ConnectionRecord> cl = jt.next();
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
        }

        // Clean up any connections this application has to other services.
        if (app.connections.size() > 0) {
            Iterator<ConnectionRecord> it = app.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord r = it.next();
                removeConnectionLocked(r, app, null);
            }
        }
        app.connections.clear();

        if (app.services.size() != 0) {
            // Any services running in the application need to be placed
            // back in the pending list.
            Iterator<ServiceRecord> it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
                synchronized (sr.stats.getBatteryStats()) {
                    sr.stats.stopLaunchedLocked();
                }
                sr.app = null;
                sr.isolatedProc = null;
                sr.executeNesting = 0;
                if (mStoppingServices.remove(sr)) {
                    if (DEBUG_SERVICE) Slog.v(TAG, "killServices remove stopping " + sr);
                }

                boolean hasClients = sr.bindings.size() > 0;
                if (hasClients) {
                    Iterator<IntentBindRecord> bindings
                            = sr.bindings.values().iterator();
                    while (bindings.hasNext()) {
                        IntentBindRecord b = bindings.next();
                        if (DEBUG_SERVICE) Slog.v(TAG, "Killing binding " + b
                                + ": shouldUnbind=" + b.hasBound);
                        b.binder = null;
                        b.requested = b.received = b.hasBound = false;
                    }
                }

                if (sr.crashCount >= 2 && (sr.serviceInfo.applicationInfo.flags
                        &ApplicationInfo.FLAG_PERSISTENT) == 0) {
                    Slog.w(TAG, "Service crashed " + sr.crashCount
                            + " times, stopping: " + sr);
                    EventLog.writeEvent(EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH,
                            sr.userId, sr.crashCount, sr.shortName, app.pid);
                    bringDownServiceLocked(sr, true);
                } else if (!allowRestart) {
                    bringDownServiceLocked(sr, true);
                } else {
                    boolean canceled = scheduleServiceRestartLocked(sr, true);

                    // Should the service remain running?  Note that in the
                    // extreme case of so many attempts to deliver a command
                    // that it failed we also will stop it here.
                    if (sr.startRequested && (sr.stopIfKilled || canceled)) {
                        if (sr.pendingStarts.size() == 0) {
                            sr.startRequested = false;
                            if (!hasClients) {
                                // Whoops, no reason to restart!
                                bringDownServiceLocked(sr, true);
                            }
                        }
                    }
                }
            }

            if (!allowRestart) {
                app.services.clear();
            }
        }

        // Make sure we have no more records on the stopping list.
        int i = mStoppingServices.size();
        while (i > 0) {
            i--;
            ServiceRecord sr = mStoppingServices.get(i);
            if (sr.app == app) {
                mStoppingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG, "killServices remove stopping " + sr);
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

        for (ArrayList<ConnectionRecord> connl : r.connections.values()) {
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
                    if (mServiceMap.getAllServices(users[ui]).size() > 0) {
                        Iterator<ServiceRecord> it = mServiceMap.getAllServices(
                                users[ui]).iterator();
                        while (it.hasNext() && res.size() < maxNum) {
                            res.add(makeRunningServiceInfoLocked(it.next()));
                        }
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
                if (mServiceMap.getAllServices(userId).size() > 0) {
                    Iterator<ServiceRecord> it
                            = mServiceMap.getAllServices(userId).iterator();
                    while (it.hasNext() && res.size() < maxNum) {
                        res.add(makeRunningServiceInfoLocked(it.next()));
                    }
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
        ServiceRecord r = mServiceMap.getServiceByName(name, userId);
        if (r != null) {
            for (ArrayList<ConnectionRecord> conn : r.connections.values()) {
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

        synchronized(this) {
            if (proc.executingServices.size() == 0 || proc.thread == null) {
                return;
            }
            long maxTime = SystemClock.uptimeMillis() - SERVICE_TIMEOUT;
            Iterator<ServiceRecord> it = proc.executingServices.iterator();
            ServiceRecord timeout = null;
            long nextTime = 0;
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
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
                anrMessage = "Executing service " + timeout.shortName;
            } else {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mAm.mHandler.sendMessageAtTime(msg, nextTime+SERVICE_TIMEOUT);
            }
        }

        if (anrMessage != null) {
            mAm.appNotResponding(proc, null, null, false, anrMessage);
        }
    }

    /**
     * Prints a list of ServiceRecords (dumpsys activity services)
     */
    boolean dumpServicesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        boolean needSep = false;

        ItemMatcher matcher = new ItemMatcher();
        matcher.build(args, opti);

        pw.println("ACTIVITY MANAGER SERVICES (dumpsys activity services)");
        try {
            int[] users = mAm.getUsersLocked();
            for (int user : users) {
                if (mServiceMap.getAllServices(user).size() > 0) {
                    boolean printed = false;
                    long nowReal = SystemClock.elapsedRealtime();
                    Iterator<ServiceRecord> it = mServiceMap.getAllServices(
                            user).iterator();
                    needSep = false;
                    while (it.hasNext()) {
                        ServiceRecord r = it.next();
                        if (!matcher.match(r, r.name)) {
                            continue;
                        }
                        if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                            continue;
                        }
                        if (!printed) {
                            if (user != 0) {
                                pw.println();
                            }
                            pw.println("  User " + user + " active services:");
                            printed = true;
                        }
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
                                for (ArrayList<ConnectionRecord> clist : r.connections.values()) {
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
                    needSep = printed;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception in dumpServicesLocked: " + e);
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
                if (!printed) {
                    if (needSep) pw.println(" ");
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
                if (!printed) {
                    if (needSep) pw.println(" ");
                    needSep = true;
                    pw.println("  Restarting services:");
                    printed = true;
                }
                pw.print("  * Restarting "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mStoppingServices.size() > 0) {
            boolean printed = false;
            for (int i=0; i<mStoppingServices.size(); i++) {
                ServiceRecord r = mStoppingServices.get(i);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) pw.println(" ");
                    needSep = true;
                    pw.println("  Stopping services:");
                    printed = true;
                }
                pw.print("  * Stopping "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (dumpAll) {
            if (mServiceConnections.size() > 0) {
                boolean printed = false;
                Iterator<ArrayList<ConnectionRecord>> it
                        = mServiceConnections.values().iterator();
                while (it.hasNext()) {
                    ArrayList<ConnectionRecord> r = it.next();
                    for (int i=0; i<r.size(); i++) {
                        ConnectionRecord cr = r.get(i);
                        if (!matcher.match(cr.binding.service, cr.binding.service.name)) {
                            continue;
                        }
                        if (dumpPackage != null && (cr.binding.client == null
                                || !dumpPackage.equals(cr.binding.client.info.packageName))) {
                            continue;
                        }
                        if (!printed) {
                            if (needSep) pw.println(" ");
                            needSep = true;
                            pw.println("  Connection bindings to services:");
                            printed = true;
                        }
                        pw.print("  * "); pw.println(cr);
                        cr.dump(pw, "    ");
                    }
                }
                needSep = true;
            }
        }

        return needSep;
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

        synchronized (this) {
            int[] users = mAm.getUsersLocked();
            if ("all".equals(name)) {
                for (int user : users) {
                    for (ServiceRecord r1 : mServiceMap.getAllServices(user)) {
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
                    for (ServiceRecord r1 : mServiceMap.getAllServices(user)) {
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
        synchronized (this) {
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
