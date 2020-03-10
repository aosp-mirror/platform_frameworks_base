/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.stats;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.StatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IStatsCompanionService;
import android.os.IStatsd;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.StatsFrameworkInitializer;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 *
 * @hide
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {

    private static final long MILLIS_IN_A_DAY = TimeUnit.DAYS.toMillis(1);

    public static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";
    public static final String CONFIG_DIR = "/data/misc/stats-service";

    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = false;
    /**
     * Hard coded field ids of frameworks/base/cmds/statsd/src/uid_data.proto
     * to be used in ProtoOutputStream.
     */
    private static final int APPLICATION_INFO_FIELD_ID = 1;
    private static final int UID_FIELD_ID = 1;
    private static final int VERSION_FIELD_ID = 2;
    private static final int VERSION_STRING_FIELD_ID = 3;
    private static final int PACKAGE_NAME_FIELD_ID = 4;
    private static final int INSTALLER_FIELD_ID = 5;

    public static final int DEATH_THRESHOLD = 10;

    static final class CompanionHandler extends Handler {
        CompanionHandler(Looper looper) {
            super(looper);
        }
    }

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @GuardedBy("sStatsdLock")
    private static IStatsd sStatsd;
    private static final Object sStatsdLock = new Object();

    private final OnAlarmListener mAnomalyAlarmListener = new AnomalyAlarmListener();
    private final OnAlarmListener mPullingAlarmListener = new PullingAlarmListener();
    private final OnAlarmListener mPeriodicAlarmListener = new PeriodicAlarmListener();

    private StatsManagerService mStatsManagerService;

    @GuardedBy("sStatsdLock")
    private final HashSet<Long> mDeathTimeMillis = new HashSet<>();
    @GuardedBy("sStatsdLock")
    private final HashMap<Long, String> mDeletedFiles = new HashMap<>();
    private final CompanionHandler mHandler;

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        if (DEBUG) Log.d(TAG, "Registered receiver for ACTION_PACKAGE_REPLACED and ADDED.");
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new CompanionHandler(handlerThread.getLooper());

    }

    private final static int[] toIntArray(List<Integer> list) {
        int[] ret = new int[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    private final static long[] toLongArray(List<Long> list) {
        long[] ret = new long[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    /**
     * Non-blocking call to retrieve a reference to statsd
     *
     * @return IStatsd object if statsd is ready, null otherwise.
     */
    private static IStatsd getStatsdNonblocking() {
        synchronized (sStatsdLock) {
            return sStatsd;
        }
    }

    private static void informAllUidsLocked(Context context) throws RemoteException {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        PackageManager pm = context.getPackageManager();
        final List<UserHandle> users = um.getUserHandles(true);
        if (DEBUG) {
            Log.d(TAG, "Iterating over " + users.size() + " userHandles.");
        }

        ParcelFileDescriptor[] fds;
        try {
            fds = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create a pipe to send uid map data.", e);
            return;
        }
        sStatsd.informAllUidData(fds[0]);
        try {
            fds[0].close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to close the read side of the pipe.", e);
        }
        final ParcelFileDescriptor writeFd = fds[1];
        HandlerThread backgroundThread = new HandlerThread(
                "statsCompanionService.bg", THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        Handler handler = new Handler(backgroundThread.getLooper());
        handler.post(() -> {
            FileOutputStream fout = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd);
            try {
                ProtoOutputStream output = new ProtoOutputStream(fout);
                int numRecords = 0;
                // Add in all the apps for every user/profile.
                for (UserHandle userHandle : users) {
                    List<PackageInfo> pi =
                            pm.getInstalledPackagesAsUser(PackageManager.MATCH_UNINSTALLED_PACKAGES
                                            | PackageManager.MATCH_ANY_USER,
                                    userHandle.getIdentifier());
                    for (int j = 0; j < pi.size(); j++) {
                        if (pi.get(j).applicationInfo != null) {
                            String installer;
                            try {
                                installer = pm.getInstallerPackageName(pi.get(j).packageName);
                            } catch (IllegalArgumentException e) {
                                installer = "";
                            }
                            long applicationInfoToken =
                                    output.start(ProtoOutputStream.FIELD_TYPE_MESSAGE
                                            | ProtoOutputStream.FIELD_COUNT_REPEATED
                                                    | APPLICATION_INFO_FIELD_ID);
                            output.write(ProtoOutputStream.FIELD_TYPE_INT32
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE | UID_FIELD_ID,
                                            pi.get(j).applicationInfo.uid);
                            output.write(ProtoOutputStream.FIELD_TYPE_INT64
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                            | VERSION_FIELD_ID, pi.get(j).getLongVersionCode());
                            output.write(ProtoOutputStream.FIELD_TYPE_STRING
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                    | VERSION_STRING_FIELD_ID,
                                            pi.get(j).versionName);
                            output.write(ProtoOutputStream.FIELD_TYPE_STRING
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                            | PACKAGE_NAME_FIELD_ID, pi.get(j).packageName);
                            output.write(ProtoOutputStream.FIELD_TYPE_STRING
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                            | INSTALLER_FIELD_ID,
                                                    installer == null ? "" : installer);
                            numRecords++;
                            output.end(applicationInfoToken);
                        }
                    }
                }
                output.flush();
                if (DEBUG) {
                    Log.d(TAG, "Sent data for " + numRecords + " apps");
                }
            } finally {
                IoUtils.closeQuietly(fout);
                backgroundThread.quit();
                backgroundThread.interrupt();
            }
        });
    }

    private final static class AppUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * App updates actually consist of REMOVE, ADD, and then REPLACE broadcasts. To avoid
             * waste, we ignore the REMOVE and ADD broadcasts that contain the replacing flag.
             * If we can't find the value for EXTRA_REPLACING, we default to false.
             */
            if (!intent.getAction().equals(Intent.ACTION_PACKAGE_REPLACED)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return; // Keep only replacing or normal add and remove.
            }
            if (DEBUG) Log.d(TAG, "StatsCompanionService noticed an app was updated.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Log.w(TAG, "Could not access statsd to inform it of an app update");
                    return;
                }
                try {
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        if (!replacing) {
                            // Don't bother sending an update if we're right about to get another
                            // intent for the new version that's added.
                            String app = intent.getData().getSchemeSpecificPart();
                            sStatsd.informOnePackageRemoved(app, uid);
                        }
                    } else {
                        PackageManager pm = context.getPackageManager();
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        String app = intent.getData().getSchemeSpecificPart();
                        PackageInfo pi = pm.getPackageInfo(app, PackageManager.MATCH_ANY_USER);
                        String installer;
                        try {
                            installer = pm.getInstallerPackageName(app);
                        } catch (IllegalArgumentException e) {
                            installer = "";
                        }
                        sStatsd.informOnePackage(
                                app,
                                uid,
                                pi.getLongVersionCode(),
                                pi.versionName == null ? "" : pi.versionName,
                                installer == null ? "" : installer);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to inform statsd of an app update", e);
                }
            }
        }
    }

    private static final class UserUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Log.w(TAG, "Could not access statsd for UserUpdateReceiver");
                    return;
                }
                try {
                    // Pull the latest state of UID->app name, version mapping.
                    // Needed since the new user basically has a version of every app.
                    informAllUidsLocked(context);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to inform statsd latest update of all apps", e);
                }
            }
        }
    }

    public static final class AnomalyAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Log.i(TAG, "StatsCompanionService believes an anomaly has occurred at time "
                        + System.currentTimeMillis() + "ms.");
            }
            IStatsd statsd = getStatsdNonblocking();
            if (statsd == null) {
                Log.w(TAG, "Could not access statsd to inform it of anomaly alarm firing");
                return;
            }
            try {
                // Two-way call to statsd to retain AlarmManager wakelock
                statsd.informAnomalyAlarmFired();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to inform statsd of anomaly alarm firing", e);
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class PullingAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Log.d(TAG, "Time to poll something.");
            }
            IStatsd statsd = getStatsdNonblocking();
            if (statsd == null) {
                Log.w(TAG, "Could not access statsd to inform it of pulling alarm firing.");
                return;
            }
            try {
                // Two-way call to statsd to retain AlarmManager wakelock
                statsd.informPollAlarmFired();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to inform statsd of pulling alarm firing.", e);
            }
        }
    }

    public final static class PeriodicAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Log.d(TAG, "Time to trigger periodic alarm.");
            }
            IStatsd statsd = getStatsdNonblocking();
            if (statsd == null) {
                Log.w(TAG, "Could not access statsd to inform it of periodic alarm firing.");
                return;
            }
            try {
                // Two-way call to statsd to retain AlarmManager wakelock
                statsd.informAlarmForSubscriberTriggeringFired();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to inform statsd of periodic alarm firing.", e);
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class ShutdownEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * Skip immediately if intent is not relevant to device shutdown.
             */
            if (!intent.getAction().equals(Intent.ACTION_REBOOT)
                    && !(intent.getAction().equals(Intent.ACTION_SHUTDOWN)
                    && (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0)) {
                return;
            }

            if (DEBUG) {
                Log.i(TAG, "StatsCompanionService noticed a shutdown.");
            }
            IStatsd statsd = getStatsdNonblocking();
            if (statsd == null) {
                Log.w(TAG, "Could not access statsd to inform it of a shutdown event.");
                return;
            }
            try {
                // two way binder call
                statsd.informDeviceShutdown();
            } catch (Exception e) {
                Log.w(TAG, "Failed to inform statsd of a shutdown event.", e);
            }
        }
    }

    @Override // Binder call
    public void setAnomalyAlarm(long timestampMs) {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) Log.d(TAG, "Setting anomaly alarm for " + timestampMs);
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            // AlarmManager will automatically cancel any previous mAnomalyAlarmListener alarm.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, timestampMs, TAG + ".anomaly",
                    mAnomalyAlarmListener, mHandler);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAnomalyAlarm() {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) Log.d(TAG, "Cancelling anomaly alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mAnomalyAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setAlarmForSubscriberTriggering(long timestampMs) {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) {
            Log.d(TAG,
                    "Setting periodic alarm in about " + (timestampMs
                            - SystemClock.elapsedRealtime()));
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, timestampMs, TAG + ".periodic",
                    mPeriodicAlarmListener, mHandler);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAlarmForSubscriberTriggering() {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) {
            Log.d(TAG, "Cancelling periodic alarm");
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPeriodicAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setPullingAlarm(long nextPullTimeMs) {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) {
            Log.d(TAG, "Setting pulling alarm in about "
                    + (nextPullTimeMs - SystemClock.elapsedRealtime()));
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, nextPullTimeMs, TAG + ".pull",
                    mPullingAlarmListener, mHandler);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelPullingAlarm() {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) {
            Log.d(TAG, "Cancelling pulling alarm");
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPullingAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void statsdReady() {
        StatsCompanion.enforceStatsdCallingUid();
        if (DEBUG) {
            Log.d(TAG, "learned that statsdReady");
        }
        sayHiToStatsd(); // tell statsd that we're ready too and link to it

        final Intent intent = new Intent(StatsManager.ACTION_STATSD_STARTED);
        // Retrieve list of broadcast receivers for this broadcast & send them directed broadcasts
        // to wake them up (if they're in background).
        List<ResolveInfo> resolveInfos =
                mContext.getPackageManager().queryBroadcastReceiversAsUser(
                        intent, 0, UserHandle.SYSTEM);
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return; // No need to send broadcast.
        }

        for (ResolveInfo resolveInfo : resolveInfos) {
            Intent intentToSend = new Intent(intent);
            intentToSend.setComponent(new ComponentName(
                    resolveInfo.activityInfo.applicationInfo.packageName,
                    resolveInfo.activityInfo.name));
            mContext.sendBroadcastAsUser(intentToSend, UserHandle.SYSTEM,
                    android.Manifest.permission.DUMP);
        }
    }

    @Override // Binder call
    public void triggerUidSnapshot() {
        StatsCompanion.enforceStatsdCallingUid();
        synchronized (sStatsdLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                informAllUidsLocked(mContext);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to trigger uid snapshot.", e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override // Binder call
    public boolean checkPermission(String permission, int pid, int uid) {
        StatsCompanion.enforceStatsdCallingUid();
        return mContext.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_GRANTED;
    }

    // Statsd related code

    /**
     * Fetches the statsd IBinder service. This is a blocking call.
     * Note: This should only be called from {@link #sayHiToStatsd()}. All other clients should use
     * the cached sStatsd via {@link #getStatsdNonblocking()}.
     */
    private IStatsd fetchStatsdService(StatsdDeathRecipient deathRecipient) {
        synchronized (sStatsdLock) {
            if (sStatsd == null) {
                sStatsd = IStatsd.Stub.asInterface(StatsFrameworkInitializer
                        .getStatsServiceManager()
                        .getStatsdServiceRegisterer()
                        .get());
                if (sStatsd != null) {
                    try {
                        sStatsd.asBinder().linkToDeath(deathRecipient, /* flags */ 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "linkToDeath(StatsdDeathRecipient) failed");
                        statsdNotReadyLocked();
                    }
                }
            }
            return sStatsd;
        }
    }

    /**
     * Now that the android system is ready, StatsCompanion is ready too, so inform statsd.
     */
    void systemReady() {
        if (DEBUG) Log.d(TAG, "Learned that systemReady");
        sayHiToStatsd();
    }

    void setStatsManagerService(StatsManagerService statsManagerService) {
        mStatsManagerService = statsManagerService;
    }

    /**
     * Tells statsd that statscompanion is ready. If the binder call returns, link to
     * statsd.
     */
    private void sayHiToStatsd() {
        if (getStatsdNonblocking() != null) {
            Log.e(TAG, "Trying to fetch statsd, but it was already fetched",
                    new IllegalStateException(
                            "sStatsd is not null when being fetched"));
            return;
        }
        StatsdDeathRecipient deathRecipient = new StatsdDeathRecipient();
        IStatsd statsd = fetchStatsdService(deathRecipient);
        if (statsd == null) {
            Log.i(TAG,
                    "Could not yet find statsd to tell it that StatsCompanion is "
                            + "alive.");
            return;
        }
        mStatsManagerService.statsdReady(statsd);
        if (DEBUG) Log.d(TAG, "Saying hi to statsd");
        try {
            statsd.statsCompanionReady();

            cancelAnomalyAlarm();
            cancelPullingAlarm();

            BroadcastReceiver appUpdateReceiver = new AppUpdateReceiver();
            BroadcastReceiver userUpdateReceiver = new UserUpdateReceiver();
            BroadcastReceiver shutdownEventReceiver = new ShutdownEventReceiver();

            // Setup broadcast receiver for updates.
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiverForAllUsers(appUpdateReceiver, filter, null, null);

            // Setup receiver for user initialize (which happens once for a new user)
            // and
            // if a user is removed.
            filter = new IntentFilter(Intent.ACTION_USER_INITIALIZE);
            filter.addAction(Intent.ACTION_USER_REMOVED);
            mContext.registerReceiverForAllUsers(userUpdateReceiver, filter, null, null);

            // Setup receiver for device reboots or shutdowns.
            filter = new IntentFilter(Intent.ACTION_REBOOT);
            filter.addAction(Intent.ACTION_SHUTDOWN);
            mContext.registerReceiverForAllUsers(
                    shutdownEventReceiver, filter, null, null);

            // Only add the receivers if the registration is successful.
            deathRecipient.addRegisteredBroadcastReceivers(
                    List.of(appUpdateReceiver, userUpdateReceiver, shutdownEventReceiver));

            final long token = Binder.clearCallingIdentity();
            try {
                // Pull the latest state of UID->app name, version mapping when
                // statsd starts.
                informAllUidsLocked(mContext);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            Log.i(TAG, "Told statsd that StatsCompanionService is alive.");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to inform statsd that statscompanion is ready", e);
        }
    }

    private class StatsdDeathRecipient implements IBinder.DeathRecipient {

        private List<BroadcastReceiver> mReceiversToUnregister;

        StatsdDeathRecipient() {
            mReceiversToUnregister = new ArrayList<>();
        }

        public void addRegisteredBroadcastReceivers(List<BroadcastReceiver> receivers) {
            synchronized (sStatsdLock) {
                mReceiversToUnregister.addAll(receivers);
            }
        }

        @Override
        public void binderDied() {
            Log.i(TAG, "Statsd is dead - erase all my knowledge, except pullers");
            synchronized (sStatsdLock) {
                long now = SystemClock.elapsedRealtime();
                for (Long timeMillis : mDeathTimeMillis) {
                    long ageMillis = now - timeMillis;
                    if (ageMillis > MILLIS_IN_A_DAY) {
                        mDeathTimeMillis.remove(timeMillis);
                    }
                }
                for (Long timeMillis : mDeletedFiles.keySet()) {
                    long ageMillis = now - timeMillis;
                    if (ageMillis > MILLIS_IN_A_DAY * 7) {
                        mDeletedFiles.remove(timeMillis);
                    }
                }
                mDeathTimeMillis.add(now);
                if (mDeathTimeMillis.size() >= DEATH_THRESHOLD) {
                    mDeathTimeMillis.clear();
                    File[] configs = new File(CONFIG_DIR).listFiles();
                    if (configs != null && configs.length > 0) {
                        String fileName = configs[0].getName();
                        if (configs[0].delete()) {
                            mDeletedFiles.put(now, fileName);
                        }
                    }
                }
                // We only unregister in binder death becaseu receivers can only be unregistered
                // once, or an IllegalArgumentException is thrown.
                for (BroadcastReceiver receiver: mReceiversToUnregister) {
                    mContext.unregisterReceiver(receiver);
                }
                statsdNotReadyLocked();
            }
        }
    }

    private void statsdNotReadyLocked() {
        sStatsd = null;
        mStatsManagerService.statsdNotReady();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        synchronized (sStatsdLock) {
            writer.println(
                    "Number of configuration files deleted: " + mDeletedFiles.size());
            if (mDeletedFiles.size() > 0) {
                writer.println("  timestamp, deleted file name");
            }
            long lastBootMillis =
                    SystemClock.currentThreadTimeMillis() - SystemClock.elapsedRealtime();
            for (Long elapsedMillis : mDeletedFiles.keySet()) {
                long deletionMillis = lastBootMillis + elapsedMillis;
                writer.println(
                        "  " + deletionMillis + ", " + mDeletedFiles.get(elapsedMillis));
            }
        }
    }
}
