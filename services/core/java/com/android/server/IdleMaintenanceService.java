/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.maintenance.IIdleCallback;
import android.app.maintenance.IIdleService;
import android.app.maintenance.IdleService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * This service observes the device state and when applicable sends
 * broadcasts at the beginning and at the end of a period during which
 * observers can perform idle maintenance tasks. Typical use of the
 * idle maintenance is to perform somehow expensive tasks that can be
 * postponed to a moment when they will not degrade user experience.
 *
 * The current implementation is very simple. The start of a maintenance
 * window is announced if: the screen is off or showing a dream AND the
 * battery level is more than twenty percent AND at least one hour passed
 * activity).
 *
 * The end of a maintenance window is announced only if: a start was
 * announced AND the screen turned on or a dream was stopped.
 *
 * Method naming note:
 * Methods whose name ends with "Tm" must only be called from the main thread.
 */
public class IdleMaintenanceService extends BroadcastReceiver {

    private static final boolean DEBUG = false;

    private static final String TAG = IdleMaintenanceService.class.getSimpleName();

    private static final int LAST_USER_ACTIVITY_TIME_INVALID = -1;

    private static final long MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS = 24 * 60 * 60 * 1000; // 1 day

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_CHARGING = 30; // percent

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_NOT_CHARGING = 80; // percent

    private static final int MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_RUNNING = 20; // percent

    private static final long MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START = 71 * 60 * 1000; // 71 min

    private static final long MAX_IDLE_MAINTENANCE_DURATION = 71 * 60 * 1000; // 71 min

    private static final String ACTION_UPDATE_IDLE_MAINTENANCE_STATE =
        "com.android.server.IdleMaintenanceService.action.UPDATE_IDLE_MAINTENANCE_STATE";

    private static final String ACTION_FORCE_IDLE_MAINTENANCE =
        "com.android.server.IdleMaintenanceService.action.FORCE_IDLE_MAINTENANCE";

    static final int MSG_OP_COMPLETE = 1;
    static final int MSG_IDLE_FINISHED = 2;
    static final int MSG_TIMEOUT = 3;

    // when a timeout happened, what were we expecting?
    static final int VERB_BINDING = 1;
    static final int VERB_IDLING = 2;
    static final int VERB_ENDING = 3;

    // What are our relevant timeouts / allocated slices?
    static final long OP_TIMEOUT = 8 * 1000;  // 8 seconds to bind or ack the start
    static final long IDLE_TIMESLICE = 10 * 60 * 1000;  // ten minutes for each idler

    private final AlarmManager mAlarmService;
    private final BatteryService mBatteryService;
    private final PendingIntent mUpdateIdleMaintenanceStatePendingIntent;
    private final Context mContext;
    private final WakeLock mWakeLock;
    private final WorkSource mSystemWorkSource = new WorkSource(Process.myUid());

    private long mLastIdleMaintenanceStartTimeMillis;
    private long mLastUserActivityElapsedTimeMillis = LAST_USER_ACTIVITY_TIME_INVALID;
    private boolean mIdleMaintenanceStarted;

    final IdleCallback mCallback;
    final Handler mHandler;

    final Random mTokenGenerator = new Random();

    int makeToken() {
        int token;
        do  {
            token = mTokenGenerator.nextInt(Integer.MAX_VALUE);
        } while (token == 0);
        return token;
    }

    class ActiveTask {
        public IdleServiceInfo who;
        public int verb;
        public int token;

        ActiveTask(IdleServiceInfo target, int action) {
            who = target;
            verb = action;
            token = makeToken();
        }

        @Override
        public String toString() {
            return "ActiveTask{" + Integer.toHexString(this.hashCode())
                    + " : verb=" + verb
                    + " : token=" + token
                    + " : "+ who + "}";
        }
    }

    // What operations are in flight?
    final SparseArray<ActiveTask> mPendingOperations = new SparseArray<ActiveTask>();

    // Idle service queue management
    class IdleServiceInfo {
        public final ComponentName componentName;
        public final int uid;
        public IIdleService service;

        IdleServiceInfo(ResolveInfo info, ComponentName cname) {
            componentName = cname;  // derived from 'info' but this avoids an extra object
            uid = info.serviceInfo.applicationInfo.uid;
            service = null;
        }

        @Override
        public int hashCode() {
            return componentName.hashCode();
        }

        @Override
        public String toString() {
            return "IdleServiceInfo{" + componentName
                    + " / " + (service == null ? "null" : service.asBinder()) + "}";
        }
    }

    final ArrayMap<ComponentName, IdleServiceInfo> mIdleServices =
            new ArrayMap<ComponentName, IdleServiceInfo>();
    final LinkedList<IdleServiceInfo> mIdleServiceQueue = new LinkedList<IdleServiceInfo>();
    IdleServiceInfo mCurrentIdler;  // set when we've committed to launching an idler
    IdleServiceInfo mLastIdler;     // end of queue when idling begins

    void reportNoTimeout(int token, boolean result) {
        final Message msg = mHandler.obtainMessage(MSG_OP_COMPLETE, result ? 1 : 0, token);
        mHandler.sendMessage(msg);
    }

    // Binder acknowledgment trampoline
    class IdleCallback extends IIdleCallback.Stub {
        @Override
        public void acknowledgeStart(int token, boolean result) throws RemoteException {
            reportNoTimeout(token, result);
        }

        @Override
        public void acknowledgeStop(int token) throws RemoteException {
            reportNoTimeout(token, false);
        }

        @Override
        public void idleFinished(int token) throws RemoteException {
            if (DEBUG) {
                Slog.v(TAG, "idleFinished: " + token);
            }
            final Message msg = mHandler.obtainMessage(MSG_IDLE_FINISHED, 0, token);
            mHandler.sendMessage(msg);
        }
    }

    // Stuff that we run on a Handler
    class IdleHandler extends Handler {
        public IdleHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int token = msg.arg2;

            switch (msg.what) {
                case MSG_OP_COMPLETE: {
                    if (DEBUG) {
                        Slog.i(TAG, "MSG_OP_COMPLETE of " + token);
                    }
                    ActiveTask task = mPendingOperations.get(token);
                    if (task != null) {
                        mPendingOperations.remove(token);
                        removeMessages(MSG_TIMEOUT);

                        handleOpCompleteTm(task, msg.arg1);
                    } else {
                        // Can happen in a race between timeout and actual
                        // (belated) completion of a "begin idling" or similar
                        // operation.  In that state we've already processed the
                        // timeout, so we intentionally no-op here.
                        if (DEBUG) {
                            Slog.w(TAG, "Belated op-complete of " + token);
                        }
                    }
                    break;
                }

                case MSG_IDLE_FINISHED: {
                    if (DEBUG) {
                        Slog.i(TAG, "MSG_IDLE_FINISHED of " + token);
                    }
                    ActiveTask task = mPendingOperations.get(token);
                    if (task != null) {
                        if (DEBUG) {
                            Slog.i(TAG, "... removing task " + token);
                        }
                        mPendingOperations.remove(token);
                        removeMessages(MSG_TIMEOUT);

                        handleIdleFinishedTm(task);
                    } else {
                        // Can happen "legitimately" from an app explicitly calling
                        // idleFinished() after already having been told that its slice
                        // has ended.
                        if (DEBUG) {
                            Slog.w(TAG, "Belated idle-finished of " + token);
                        }
                    }
                    break;
                }

                case MSG_TIMEOUT: {
                    if (DEBUG) {
                        Slog.i(TAG, "MSG_TIMEOUT of " + token);
                    }
                    ActiveTask task = mPendingOperations.get(token);
                    if (task != null) {
                        mPendingOperations.remove(token);
                        removeMessages(MSG_OP_COMPLETE);

                        handleTimeoutTm(task);
                    } else {
                        // This one should not happen; we flushed timeout messages
                        // whenever we entered a state after which we have established
                        // that they are not appropriate.
                        Slog.w(TAG, "Unexpected timeout of " + token);
                    }
                    break;
                }

                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    void handleTimeoutTm(ActiveTask task) {
        switch (task.verb) {
        case VERB_BINDING: {
            // We were trying to bind to this service, but it wedged or otherwise
            // failed to respond in time.  Let it stay in the queue for the next
            // time around, but just give up on it for now and go on to the next.
            startNextIdleServiceTm();
            break;
        }
        case VERB_IDLING: {
            // The service has reached the end of its designated idle timeslice.
            // This is not considered an error.
            if (DEBUG) {
                Slog.i(TAG, "Idler reached end of timeslice: " + task.who);
            }
            sendEndIdleTm(task.who);
            break;
        }
        case VERB_ENDING: {
            if (mCurrentIdler == task.who) {
                if (DEBUG) {
                    Slog.i(TAG, "Task timed out when ending; unbind needed");
                }
                handleIdleFinishedTm(task);
            } else {
                if (DEBUG) {
                    Slog.w(TAG, "Ending timeout for non-current idle service!");
                }
            }
            break;
        }
        default: {
            Slog.w(TAG, "Unknown timeout state " + task.verb);
            break;
        }
        }
    }

    void handleOpCompleteTm(ActiveTask task, int result) {
        if (DEBUG) {
            Slog.i(TAG, "handleOpComplete : task=" + task + " result=" + result);
        }
        if (task.verb == VERB_IDLING) {
            // If the service was told to begin idling and responded positively, then
            // it has begun idling and will eventually either explicitly finish, or
            // reach the end of its allotted timeslice.  It's running free now, so we
            // just schedule the idle-expiration timeout under the token it's already been
            // given and let it keep going.
            if (result != 0) {
                scheduleOpTimeoutTm(task);
            } else {
                // The idle service has indicated that it does not, in fact,
                // need to run at present, so we immediately indicate that it's
                // to finish idling, and go on to the next idler.
                if (DEBUG) {
                    Slog.i(TAG, "Idler declined idling; moving along");
                }
                sendEndIdleTm(task.who);
            }
        } else {
            // In the idling case, the task will be cleared either as the result of a timeout
            // or of an explicit idleFinished().  For all other operations (binding, ending) we
            // are done with the task as such, so we remove it from our bookkeeping.
            if (DEBUG) {
                Slog.i(TAG, "Clearing task " + task);
            }
            mPendingOperations.remove(task.token);
            if (task.verb == VERB_ENDING) {
                // The last bit of handshaking around idle cessation for this target
                handleIdleFinishedTm(task);
            }
        }
    }

    void handleIdleFinishedTm(ActiveTask task) {
        final IdleServiceInfo who = task.who;
        if (who == mCurrentIdler) {
            if (DEBUG) {
                Slog.i(TAG, "Current idler has finished: " + who);
                Slog.i(TAG, "Attributing wakelock to system work source");
            }
            mContext.unbindService(mConnection);
            startNextIdleServiceTm();
        } else {
            Slog.w(TAG, "finish from non-current idle service? " + who);
        }
    }

    void updateIdleServiceQueueTm() {
        if (DEBUG) {
            Slog.i(TAG, "Updating idle service queue");
        }
        PackageManager pm = mContext.getPackageManager();
        Intent idleIntent = new Intent(IdleService.SERVICE_INTERFACE);
        List<ResolveInfo> services = pm.queryIntentServices(idleIntent, 0);
        for (ResolveInfo info : services) {
            if (info.serviceInfo != null) {
                if (IdleService.PERMISSION_BIND.equals(info.serviceInfo.permission)) {
                    final ComponentName componentName = new ComponentName(
                            info.serviceInfo.packageName,
                            info.serviceInfo.name);
                    if (DEBUG) {
                        Slog.i(TAG, "   - " + componentName);
                    }
                    if (!mIdleServices.containsKey(componentName)) {
                        if (DEBUG) {
                            Slog.i(TAG, "      + not known; adding");
                        }
                        IdleServiceInfo serviceInfo = new IdleServiceInfo(info, componentName);
                        mIdleServices.put(componentName, serviceInfo);
                        mIdleServiceQueue.add(serviceInfo);
                    }
                } else {
                    if (DEBUG) {
                        Slog.i(TAG, "Idle service " + info.serviceInfo
                                + " does not have required permission; ignoring");
                    }
                }
            }
        }
    }

    void startNextIdleServiceTm() {
        mWakeLock.setWorkSource(mSystemWorkSource);

        if (mLastIdler == null) {
            // we've run the queue; nothing more to do until the next idle interval.
            if (DEBUG) {
                Slog.i(TAG, "Queue already drained; nothing more to do");
            }
            return;
        }

        if (DEBUG) {
            Slog.i(TAG, "startNextIdleService : last=" + mLastIdler + " cur=" + mCurrentIdler);
            if (mIdleServiceQueue.size() > 0) {
                int i = 0;
                Slog.i(TAG, "Queue (" + mIdleServiceQueue.size() + "):");
                for (IdleServiceInfo info : mIdleServiceQueue) {
                    Slog.i(TAG, "   " + i + " : " + info);
                    i++;
                }
            }
        }
        if (mCurrentIdler != mLastIdler) {
            if (mIdleServiceQueue.size() > 0) {
                IdleServiceInfo target = mIdleServiceQueue.pop();
                if (DEBUG) {
                    Slog.i(TAG, "starting next idle service " + target);
                }
                Intent idleIntent = new Intent(IdleService.SERVICE_INTERFACE);
                idleIntent.setComponent(target.componentName);
                mCurrentIdler = target;
                ActiveTask task = new ActiveTask(target, VERB_BINDING);
                scheduleOpTimeoutTm(task);
                boolean bindOk = mContext.bindServiceAsUser(idleIntent, mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY, UserHandle.OWNER);
                if (!bindOk) {
                    if (DEBUG) {
                        Slog.w(TAG, "bindService() to " + target.componentName
                                + " failed");
                    }
                } else {
                    mIdleServiceQueue.add(target);  // at the end for next time
                    if (DEBUG) { Slog.i(TAG, "Attributing wakelock to target uid " + target.uid); }
                    mWakeLock.setWorkSource(new WorkSource(target.uid));
                }
            } else {
                // Queue is empty but mLastIdler is non-null -- eeep.  Clear *everything*
                // and wind up until the next time around.
                Slog.e(TAG, "Queue unexpectedly empty; resetting.  last="
                        + mLastIdler + " cur=" + mCurrentIdler);
                mHandler.removeMessages(MSG_TIMEOUT);
                mPendingOperations.clear();
                stopIdleMaintenanceTm();
            }
        } else {
            // we've reached the place we started, so mark the queue as drained
            if (DEBUG) {
                Slog.i(TAG, "Reached end of queue.");
            }
            stopIdleMaintenanceTm();
        }
    }

    void sendStartIdleTm(IdleServiceInfo who) {
        ActiveTask task = new ActiveTask(who, VERB_IDLING);
        scheduleOpTimeoutTm(task);
        try {
            who.service.startIdleMaintenance(mCallback, task.token);
        } catch (RemoteException e) {
            // We bound to it, but now we can't reach it.  Bail and go on to the
            // next service.
            mContext.unbindService(mConnection);
            if (DEBUG) { Slog.i(TAG, "Attributing wakelock to system work source"); }
            mHandler.removeMessages(MSG_TIMEOUT);
            startNextIdleServiceTm();
        }
    }

    void sendEndIdleTm(IdleServiceInfo who) {
        ActiveTask task = new ActiveTask(who, VERB_ENDING);
        scheduleOpTimeoutTm(task);
        if (DEBUG) {
            Slog.i(TAG, "Sending end-idle to " + who);
        }
        try {
            who.service.stopIdleMaintenance(mCallback, task.token);
        } catch (RemoteException e) {
            // We bound to it, but now we can't reach it.  Bail and go on to the
            // next service.
            mContext.unbindService(mConnection);
            if (DEBUG) { Slog.i(TAG, "Attributing wakelock to system work source"); }
            mHandler.removeMessages(MSG_TIMEOUT);
            startNextIdleServiceTm();
        }
    }

    ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Slog.i(TAG, "onServiceConnected(" + name + ")");
            }
            IdleServiceInfo info = mIdleServices.get(name);
            if (info != null) {
                // Bound!  Cancel the bind timeout
                mHandler.removeMessages(MSG_TIMEOUT);
                // Now tell it to start its idle work
                info.service = IIdleService.Stub.asInterface(service);
                sendStartIdleTm(info);
            } else {
                // We bound to a service we don't know about.  That's ungood.
                Slog.e(TAG, "Connected to unexpected component " + name);
                mContext.unbindService(this);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Slog.i(TAG, "onServiceDisconnected(" + name + ")");
            }
            IdleServiceInfo who = mIdleServices.get(name);
            if (who == mCurrentIdler) {
                // Hm, okay; they didn't tell us they were finished but they
                // went away.  Crashed, probably.  Oh well.  They're gone, so
                // we can't finish them cleanly; just force things along.
                Slog.w(TAG, "Idler unexpectedly vanished: " + mCurrentIdler);
                mContext.unbindService(this);
                mHandler.removeMessages(MSG_TIMEOUT);
                startNextIdleServiceTm();
            } else {
                // Not the current idler, so we don't interrupt our process...
                if (DEBUG) {
                    Slog.w(TAG, "Disconnect of abandoned or unexpected service " + name);
                }
            }
        }
    };

    // Schedules a timeout / end-of-work based on the task verb
    void scheduleOpTimeoutTm(ActiveTask task) {
        final long timeoutMillis = (task.verb == VERB_IDLING) ? IDLE_TIMESLICE : OP_TIMEOUT;
        if (DEBUG) {
            Slog.i(TAG, "Scheduling timeout (token " + task.token
                    + " : verb " + task.verb + ") for " + task + " in " + timeoutMillis);
        }
        mPendingOperations.put(task.token, task);
        mHandler.removeMessages(MSG_TIMEOUT);
        final Message msg = mHandler.obtainMessage(MSG_TIMEOUT, 0, task.token);
        mHandler.sendMessageDelayed(msg, timeoutMillis);
    }

    // -------------------------------------------------------------------------------
    public IdleMaintenanceService(Context context, BatteryService batteryService) {
        mContext = context;
        mBatteryService = batteryService;

        mAlarmService = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mHandler = new IdleHandler(mContext.getMainLooper());
        mCallback = new IdleCallback();

        Intent intent = new Intent(ACTION_UPDATE_IDLE_MAINTENANCE_STATE);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mUpdateIdleMaintenanceStatePendingIntent = PendingIntent.getBroadcast(mContext, 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);

        register(mHandler);
    }

    public void register(Handler handler) {
        IntentFilter intentFilter = new IntentFilter();

        // Alarm actions.
        intentFilter.addAction(ACTION_UPDATE_IDLE_MAINTENANCE_STATE);

        // Battery actions.
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);

        // Screen actions.
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);

        // Dream actions.
        intentFilter.addAction(Intent.ACTION_DREAMING_STARTED);
        intentFilter.addAction(Intent.ACTION_DREAMING_STOPPED);

        mContext.registerReceiverAsUser(this, UserHandle.ALL,
                intentFilter, null, mHandler);

        intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_FORCE_IDLE_MAINTENANCE);
        mContext.registerReceiverAsUser(this, UserHandle.ALL,
                intentFilter, android.Manifest.permission.SET_ACTIVITY_WATCHER, mHandler);
    }

    private void scheduleUpdateIdleMaintenanceState(long delayMillis) {
        final long triggetRealTimeMillis = SystemClock.elapsedRealtime() + delayMillis;
        mAlarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggetRealTimeMillis,
                mUpdateIdleMaintenanceStatePendingIntent);
    }

    private void unscheduleUpdateIdleMaintenanceState() {
        mAlarmService.cancel(mUpdateIdleMaintenanceStatePendingIntent);
    }

    private void updateIdleMaintenanceStateTm(boolean noisy) {
        if (mIdleMaintenanceStarted) {
            // Idle maintenance can be interrupted by user activity, or duration
            // time out, or low battery.
            final boolean batteryOk
                    = batteryLevelAndMaintenanceTimeoutPermitsIdleMaintenanceRunning();
            if (!lastUserActivityPermitsIdleMaintenanceRunning() || !batteryOk) {
                unscheduleUpdateIdleMaintenanceState();
                mIdleMaintenanceStarted = false;
                // We stopped since we don't have enough battery or timed out but the
                // user is not using the device, so we should be able to run maintenance
                // in the next maintenance window since the battery may be charged
                // without interaction and the min interval between maintenances passed.
                if (!batteryOk) {
                    scheduleUpdateIdleMaintenanceState(
                            getNextIdleMaintenanceIntervalStartFromNow());
                }

                EventLogTags.writeIdleMaintenanceWindowFinish(SystemClock.elapsedRealtime(),
                        mLastUserActivityElapsedTimeMillis, mBatteryService.getBatteryLevel(),
                        isBatteryCharging() ? 1 : 0);
                scheduleIdleFinishTm();
            }
        } else if (deviceStatePermitsIdleMaintenanceStart(noisy)
                && lastUserActivityPermitsIdleMaintenanceStart(noisy)
                && lastRunPermitsIdleMaintenanceStart(noisy)) {
            // Now that we started idle maintenance, we should schedule another
            // update for the moment when the idle maintenance times out.
            scheduleUpdateIdleMaintenanceState(MAX_IDLE_MAINTENANCE_DURATION);
            mIdleMaintenanceStarted = true;
            EventLogTags.writeIdleMaintenanceWindowStart(SystemClock.elapsedRealtime(),
                    mLastUserActivityElapsedTimeMillis, mBatteryService.getBatteryLevel(),
                    isBatteryCharging() ? 1 : 0);
            mLastIdleMaintenanceStartTimeMillis = SystemClock.elapsedRealtime();
            startIdleMaintenanceTm();
        } else if (lastUserActivityPermitsIdleMaintenanceStart(noisy)) {
             if (lastRunPermitsIdleMaintenanceStart(noisy)) {
                // The user does not use the device and we did not run maintenance in more
                // than the min interval between runs, so schedule an update - maybe the
                // battery will be charged latter.
                scheduleUpdateIdleMaintenanceState(MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
             } else {
                 // The user does not use the device but we have run maintenance in the min
                 // interval between runs, so schedule an update after the min interval ends.
                 scheduleUpdateIdleMaintenanceState(
                         getNextIdleMaintenanceIntervalStartFromNow());
             }
        }
    }

    void startIdleMaintenanceTm() {
        if (DEBUG) {
            Slog.i(TAG, "*** Starting idle maintenance ***");
        }
        if (DEBUG) { Slog.i(TAG, "Attributing wakelock to system work source"); }
        mWakeLock.setWorkSource(mSystemWorkSource);
        mWakeLock.acquire();
        updateIdleServiceQueueTm();
        mCurrentIdler = null;
        mLastIdler = (mIdleServiceQueue.size() > 0) ? mIdleServiceQueue.peekLast() : null;
        startNextIdleServiceTm();
    }

    // Start a graceful wind-down of the idle maintenance state: end the current idler
    // and pretend that we've finished running the queue.  If there's no current idler,
    // this is a no-op.
    void scheduleIdleFinishTm() {
        if (mCurrentIdler != null) {
            if (DEBUG) {
                Slog.i(TAG, "*** Finishing idle maintenance ***");
            }
            mLastIdler = mCurrentIdler;
            sendEndIdleTm(mCurrentIdler);
        } else {
            if (DEBUG) {
                Slog.w(TAG, "Asked to finish idle maintenance but we're done already");
            }
        }
    }

    // Actual finalization of the idle maintenance sequence
    void stopIdleMaintenanceTm() {
        if (mLastIdler != null) {
            if (DEBUG) {
                Slog.i(TAG, "*** Idle maintenance shutdown ***");
            }
            mWakeLock.setWorkSource(mSystemWorkSource);
            mLastIdler = mCurrentIdler = null;
            updateIdleMaintenanceStateTm(false);   // resets 'started' and schedules next window
            mWakeLock.release();
        } else {
            Slog.e(TAG, "ERROR: idle shutdown but invariants not held.  last=" + mLastIdler
                    + " cur=" + mCurrentIdler + " size=" + mIdleServiceQueue.size());
        }
    }

    private long getNextIdleMaintenanceIntervalStartFromNow() {
        return mLastIdleMaintenanceStartTimeMillis + MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS
                - SystemClock.elapsedRealtime();
    }

    private boolean deviceStatePermitsIdleMaintenanceStart(boolean noisy) {
        final int minBatteryLevel = isBatteryCharging()
                ? MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_CHARGING
                : MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_START_NOT_CHARGING;
        boolean allowed = (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID
                && mBatteryService.getBatteryLevel() > minBatteryLevel);
        if (!allowed && noisy) {
            Slog.i("IdleMaintenance", "Idle maintenance not allowed due to power");
        }
        return allowed;
    }

    private boolean lastUserActivityPermitsIdleMaintenanceStart(boolean noisy) {
        // The last time the user poked the device is above the threshold.
        boolean allowed = (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID
                && SystemClock.elapsedRealtime() - mLastUserActivityElapsedTimeMillis
                    > MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
        if (!allowed && noisy) {
            Slog.i("IdleMaintenance", "Idle maintenance not allowed due to last user activity");
        }
        return allowed;
    }

    private boolean lastRunPermitsIdleMaintenanceStart(boolean noisy) {
        // Enough time passed since the last maintenance run.
        boolean allowed = SystemClock.elapsedRealtime() - mLastIdleMaintenanceStartTimeMillis
                > MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS;
        if (!allowed && noisy) {
            Slog.i("IdleMaintenance", "Idle maintenance not allowed due time since last");
        }
        return allowed;
    }

    private boolean lastUserActivityPermitsIdleMaintenanceRunning() {
        // The user is not using the device.
        return (mLastUserActivityElapsedTimeMillis != LAST_USER_ACTIVITY_TIME_INVALID);
    }

    private boolean batteryLevelAndMaintenanceTimeoutPermitsIdleMaintenanceRunning() {
        // Battery not too low and the maintenance duration did not timeout.
        return (mBatteryService.getBatteryLevel() > MIN_BATTERY_LEVEL_IDLE_MAINTENANCE_RUNNING
                && mLastIdleMaintenanceStartTimeMillis + MAX_IDLE_MAINTENANCE_DURATION
                        > SystemClock.elapsedRealtime());
    }

    private boolean isBatteryCharging() {
        return mBatteryService.getPlugType() > 0
                && mBatteryService.getInvalidCharger() == 0;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) {
            Log.i(TAG, intent.getAction());
        }
        String action = intent.getAction();
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            // We care about battery only if maintenance is in progress so we can
            // stop it if battery is too low. Note that here we assume that the
            // maintenance clients are properly holding a wake lock. We will
            // refactor the maintenance to use services instead of intents for the
            // next release. The only client for this for now is internal an holds
            // a wake lock correctly.
            if (mIdleMaintenanceStarted) {
                updateIdleMaintenanceStateTm(false);
            }
        } else if (Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_DREAMING_STOPPED.equals(action)) {
            mLastUserActivityElapsedTimeMillis = LAST_USER_ACTIVITY_TIME_INVALID;
            // Unschedule any future updates since we already know that maintenance
            // cannot be performed since the user is back.
            unscheduleUpdateIdleMaintenanceState();
            // If the screen went on/stopped dreaming, we know the user is using the
            // device which means that idle maintenance should be stopped if running.
            updateIdleMaintenanceStateTm(false);
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)
                || Intent.ACTION_DREAMING_STARTED.equals(action)) {
            mLastUserActivityElapsedTimeMillis = SystemClock.elapsedRealtime();
            // If screen went off/started dreaming, we may be able to start idle maintenance
            // after the minimal user inactivity elapses. We schedule an alarm for when
            // this timeout elapses since the device may go to sleep by then.
            scheduleUpdateIdleMaintenanceState(MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START);
        } else if (ACTION_UPDATE_IDLE_MAINTENANCE_STATE.equals(action)) {
            updateIdleMaintenanceStateTm(false);
        } else if (ACTION_FORCE_IDLE_MAINTENANCE.equals(action)) {
            long now = SystemClock.elapsedRealtime() - 1;
            mLastUserActivityElapsedTimeMillis = now - MIN_USER_INACTIVITY_IDLE_MAINTENANCE_START;
            mLastIdleMaintenanceStartTimeMillis = now - MIN_IDLE_MAINTENANCE_INTERVAL_MILLIS;
            updateIdleMaintenanceStateTm(true);
        }
    }
}
