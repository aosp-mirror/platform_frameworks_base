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

package com.android.server;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.BroadcastOptions;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.TreeSet;

import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.ELAPSED_REALTIME;

import com.android.internal.util.LocalLog;

class AlarmManagerService extends SystemService {
    private static final int RTC_WAKEUP_MASK = 1 << RTC_WAKEUP;
    private static final int RTC_MASK = 1 << RTC;
    private static final int ELAPSED_REALTIME_WAKEUP_MASK = 1 << ELAPSED_REALTIME_WAKEUP;
    private static final int ELAPSED_REALTIME_MASK = 1 << ELAPSED_REALTIME;
    static final int TIME_CHANGED_MASK = 1 << 16;
    static final int IS_WAKEUP_MASK = RTC_WAKEUP_MASK|ELAPSED_REALTIME_WAKEUP_MASK;

    // Mask for testing whether a given alarm type is wakeup vs non-wakeup
    static final int TYPE_NONWAKEUP_MASK = 0x1; // low bit => non-wakeup

    static final String TAG = "AlarmManager";
    static final boolean localLOGV = false;
    static final boolean DEBUG_BATCH = localLOGV || false;
    static final boolean DEBUG_VALIDATE = localLOGV || false;
    static final boolean DEBUG_ALARM_CLOCK = localLOGV || false;
    static final boolean RECORD_ALARMS_IN_HISTORY = true;
    static final int ALARM_EVENT = 1;
    static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    static final Intent mBackgroundIntent
            = new Intent().addFlags(Intent.FLAG_FROM_BACKGROUND);
    static final IncreasingTimeOrder sIncreasingTimeOrder = new IncreasingTimeOrder();
    
    static final boolean WAKEUP_STATS = false;

    private static final Intent NEXT_ALARM_CLOCK_CHANGED_INTENT = new Intent(
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);

    final LocalLog mLog = new LocalLog(TAG);

    final Object mLock = new Object();

    long mNativeData;
    private long mNextWakeup;
    private long mNextNonWakeup;
    int mBroadcastRefCount = 0;
    PowerManager.WakeLock mWakeLock;
    boolean mLastWakeLockUnimportantForLogging;
    ArrayList<Alarm> mPendingNonWakeupAlarms = new ArrayList<>();
    ArrayList<InFlight> mInFlight = new ArrayList<>();
    final AlarmHandler mHandler = new AlarmHandler();
    ClockReceiver mClockReceiver;
    InteractiveStateReceiver mInteractiveStateReceiver;
    private UninstallReceiver mUninstallReceiver;
    final ResultReceiver mResultReceiver = new ResultReceiver();
    PendingIntent mTimeTickSender;
    PendingIntent mDateChangeSender;
    Random mRandom;
    boolean mInteractive = true;
    long mNonInteractiveStartTime;
    long mNonInteractiveTime;
    long mLastAlarmDeliveryTime;
    long mStartCurrentDelayTime;
    long mNextNonWakeupDeliveryTime;
    long mLastTimeChangeClockTime;
    long mLastTimeChangeRealtime;
    long mAllowWhileIdleMinTime;
    int mNumTimeChanged;

    /**
     * For each uid, this is the last time we dispatched an "allow while idle" alarm,
     * used to determine the earliest we can dispatch the next such alarm.
     */
    final SparseLongArray mLastAllowWhileIdleDispatch = new SparseLongArray();

    /**
     * Broadcast options to use for FLAG_ALLOW_WHILE_IDLE.
     */
    Bundle mIdleOptions;

    private final SparseArray<AlarmManager.AlarmClockInfo> mNextAlarmClockForUser =
            new SparseArray<>();
    private final SparseArray<AlarmManager.AlarmClockInfo> mTmpSparseAlarmClockArray =
            new SparseArray<>();
    private final SparseBooleanArray mPendingSendNextAlarmClockChangedForUser =
            new SparseBooleanArray();
    private boolean mNextAlarmClockMayChange;

    // May only use on mHandler's thread, locking not required.
    private final SparseArray<AlarmManager.AlarmClockInfo> mHandlerSparseAlarmClockArray =
            new SparseArray<>();

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the AlarmManagerService.mLock lock.
     */
    private final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        private static final String KEY_MIN_FUTURITY = "min_futurity";
        private static final String KEY_MIN_INTERVAL = "min_interval";
        private static final String KEY_ALLOW_WHILE_IDLE_SHORT_TIME = "allow_while_idle_short_time";
        private static final String KEY_ALLOW_WHILE_IDLE_LONG_TIME = "allow_while_idle_long_time";
        private static final String KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION
                = "allow_while_idle_whitelist_duration";

        private static final long DEFAULT_MIN_FUTURITY = 5 * 1000;
        private static final long DEFAULT_MIN_INTERVAL = 60 * 1000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME = 60*1000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME = 15*60*1000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION = 10*1000;

        // Minimum futurity of a new alarm
        public long MIN_FUTURITY = DEFAULT_MIN_FUTURITY;

        // Minimum alarm recurrence interval
        public long MIN_INTERVAL = DEFAULT_MIN_INTERVAL;

        // Minimum time between ALLOW_WHILE_IDLE alarms when system is not idle.
        public long ALLOW_WHILE_IDLE_SHORT_TIME = DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME;

        // Minimum time between ALLOW_WHILE_IDLE alarms when system is idling.
        public long ALLOW_WHILE_IDLE_LONG_TIME = DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME;

        // BroadcastOptions.setTemporaryAppWhitelistDuration() to use for FLAG_ALLOW_WHILE_IDLE.
        public long ALLOW_WHILE_IDLE_WHITELIST_DURATION
                = DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION;

        private ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private long mLastAllowWhileIdleWhitelistDuration = -1;

        public Constants(Handler handler) {
            super(handler);
            updateAllowWhileIdleMinTimeLocked();
            updateAllowWhileIdleWhitelistDurationLocked();
        }

        public void start(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.ALARM_MANAGER_CONSTANTS), false, this);
            updateConstants();
        }

        public void updateAllowWhileIdleMinTimeLocked() {
            mAllowWhileIdleMinTime = mPendingIdleUntil != null
                    ? ALLOW_WHILE_IDLE_LONG_TIME : ALLOW_WHILE_IDLE_SHORT_TIME;
        }

        public void updateAllowWhileIdleWhitelistDurationLocked() {
            if (mLastAllowWhileIdleWhitelistDuration != ALLOW_WHILE_IDLE_WHITELIST_DURATION) {
                mLastAllowWhileIdleWhitelistDuration = ALLOW_WHILE_IDLE_WHITELIST_DURATION;
                BroadcastOptions opts = BroadcastOptions.makeBasic();
                opts.setTemporaryAppWhitelistDuration(ALLOW_WHILE_IDLE_WHITELIST_DURATION);
                mIdleOptions = opts.toBundle();
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (mLock) {
                try {
                    mParser.setString(Settings.Global.getString(mResolver,
                            Settings.Global.ALARM_MANAGER_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad device idle settings", e);
                }

                MIN_FUTURITY = mParser.getLong(KEY_MIN_FUTURITY, DEFAULT_MIN_FUTURITY);
                MIN_INTERVAL = mParser.getLong(KEY_MIN_INTERVAL, DEFAULT_MIN_INTERVAL);
                ALLOW_WHILE_IDLE_SHORT_TIME = mParser.getLong(KEY_ALLOW_WHILE_IDLE_SHORT_TIME,
                        DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME);
                ALLOW_WHILE_IDLE_LONG_TIME = mParser.getLong(KEY_ALLOW_WHILE_IDLE_LONG_TIME,
                        DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME);
                ALLOW_WHILE_IDLE_WHITELIST_DURATION = mParser.getLong(
                        KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION,
                        DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION);

                updateAllowWhileIdleMinTimeLocked();
                updateAllowWhileIdleWhitelistDurationLocked();
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");

            pw.print("    "); pw.print(KEY_MIN_FUTURITY); pw.print("=");
            TimeUtils.formatDuration(MIN_FUTURITY, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MIN_INTERVAL); pw.print("=");
            TimeUtils.formatDuration(MIN_INTERVAL, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_ALLOW_WHILE_IDLE_SHORT_TIME); pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_SHORT_TIME, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_ALLOW_WHILE_IDLE_LONG_TIME); pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_LONG_TIME, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION); pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_WHITELIST_DURATION, pw);
            pw.println();
        }
    }

    final Constants mConstants;

    // Alarm delivery ordering bookkeeping
    static final int PRIO_TICK = 0;
    static final int PRIO_WAKEUP = 1;
    static final int PRIO_NORMAL = 2;

    final class PriorityClass {
        int seq;
        int priority;

        PriorityClass() {
            seq = mCurrentSeq - 1;
            priority = PRIO_NORMAL;
        }
    }

    final HashMap<String, PriorityClass> mPriorities = new HashMap<>();
    int mCurrentSeq = 0;

    static final class WakeupEvent {
        public long when;
        public int uid;
        public String action;

        public WakeupEvent(long theTime, int theUid, String theAction) {
            when = theTime;
            uid = theUid;
            action = theAction;
        }
    }

    final LinkedList<WakeupEvent> mRecentWakeups = new LinkedList<WakeupEvent>();
    final long RECENT_WAKEUP_PERIOD = 1000L * 60 * 60 * 24; // one day

    final class Batch {
        long start;     // These endpoints are always in ELAPSED
        long end;
        int flags;      // Flags for alarms, such as FLAG_STANDALONE.

        final ArrayList<Alarm> alarms = new ArrayList<Alarm>();

        Batch() {
            start = 0;
            end = Long.MAX_VALUE;
            flags = 0;
        }

        Batch(Alarm seed) {
            start = seed.whenElapsed;
            end = seed.maxWhenElapsed;
            flags = seed.flags;
            alarms.add(seed);
        }

        int size() {
            return alarms.size();
        }

        Alarm get(int index) {
            return alarms.get(index);
        }

        boolean canHold(long whenElapsed, long maxWhen) {
            return (end >= whenElapsed) && (start <= maxWhen);
        }

        boolean add(Alarm alarm) {
            boolean newStart = false;
            // narrows the batch if necessary; presumes that canHold(alarm) is true
            int index = Collections.binarySearch(alarms, alarm, sIncreasingTimeOrder);
            if (index < 0) {
                index = 0 - index - 1;
            }
            alarms.add(index, alarm);
            if (DEBUG_BATCH) {
                Slog.v(TAG, "Adding " + alarm + " to " + this);
            }
            if (alarm.whenElapsed > start) {
                start = alarm.whenElapsed;
                newStart = true;
            }
            if (alarm.maxWhenElapsed < end) {
                end = alarm.maxWhenElapsed;
            }
            flags |= alarm.flags;

            if (DEBUG_BATCH) {
                Slog.v(TAG, "    => now " + this);
            }
            return newStart;
        }

        boolean remove(final PendingIntent operation) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            int newFlags = 0;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (alarm.operation.equals(operation)) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhenElapsed < newEnd) {
                        newEnd = alarm.maxWhenElapsed;
                    }
                    newFlags |= alarm.flags;
                    i++;
                }
            }
            if (didRemove) {
                // commit the new batch bounds
                start = newStart;
                end = newEnd;
                flags = newFlags;
            }
            return didRemove;
        }

        boolean remove(final String packageName) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            int newFlags = 0;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (alarm.operation.getTargetPackage().equals(packageName)) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhenElapsed < newEnd) {
                        newEnd = alarm.maxWhenElapsed;
                    }
                    newFlags |= alarm.flags;
                    i++;
                }
            }
            if (didRemove) {
                // commit the new batch bounds
                start = newStart;
                end = newEnd;
                flags = newFlags;
            }
            return didRemove;
        }

        boolean remove(final int userHandle) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (UserHandle.getUserId(alarm.operation.getCreatorUid()) == userHandle) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhenElapsed < newEnd) {
                        newEnd = alarm.maxWhenElapsed;
                    }
                    i++;
                }
            }
            if (didRemove) {
                // commit the new batch bounds
                start = newStart;
                end = newEnd;
            }
            return didRemove;
        }

        boolean hasPackage(final String packageName) {
            final int N = alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = alarms.get(i);
                if (a.operation.getTargetPackage().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasWakeups() {
            final int N = alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = alarms.get(i);
                // non-wakeup alarms are types 1 and 3, i.e. have the low bit set
                if ((a.type & TYPE_NONWAKEUP_MASK) == 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(40);
            b.append("Batch{"); b.append(Integer.toHexString(this.hashCode()));
            b.append(" num="); b.append(size());
            b.append(" start="); b.append(start);
            b.append(" end="); b.append(end);
            if (flags != 0) {
                b.append(" flgs=0x");
                b.append(Integer.toHexString(flags));
            }
            b.append('}');
            return b.toString();
        }
    }

    static class BatchTimeOrder implements Comparator<Batch> {
        public int compare(Batch b1, Batch b2) {
            long when1 = b1.start;
            long when2 = b2.start;
            if (when1 - when2 > 0) {
                return 1;
            }
            if (when1 - when2 < 0) {
                return -1;
            }
            return 0;
        }
    }

    final Comparator<Alarm> mAlarmDispatchComparator = new Comparator<Alarm>() {
        @Override
        public int compare(Alarm lhs, Alarm rhs) {
            // priority class trumps everything.  TICK < WAKEUP < NORMAL
            if (lhs.priorityClass.priority < rhs.priorityClass.priority) {
                return -1;
            } else if (lhs.priorityClass.priority > rhs.priorityClass.priority) {
                return 1;
            }

            // within each class, sort by nominal delivery time
            if (lhs.whenElapsed < rhs.whenElapsed) {
                return -1;
            } else if (lhs.whenElapsed > rhs.whenElapsed) {
                return 1;
            }

            // same priority class + same target delivery time
            return 0;
        }
    };

    void calculateDeliveryPriorities(ArrayList<Alarm> alarms) {
        final int N = alarms.size();
        for (int i = 0; i < N; i++) {
            Alarm a = alarms.get(i);

            final int alarmPrio;
            if (Intent.ACTION_TIME_TICK.equals(a.operation.getIntent().getAction())) {
                alarmPrio = PRIO_TICK;
            } else if (a.wakeup) {
                alarmPrio = PRIO_WAKEUP;
            } else {
                alarmPrio = PRIO_NORMAL;
            }

            PriorityClass packagePrio = a.priorityClass;
            if (packagePrio == null) packagePrio = mPriorities.get(a.operation.getCreatorPackage());
            if (packagePrio == null) {
                packagePrio = a.priorityClass = new PriorityClass(); // lowest prio & stale sequence
                mPriorities.put(a.operation.getCreatorPackage(), packagePrio);
            }
            a.priorityClass = packagePrio;

            if (packagePrio.seq != mCurrentSeq) {
                // first alarm we've seen in the current delivery generation from this package
                packagePrio.priority = alarmPrio;
                packagePrio.seq = mCurrentSeq;
            } else {
                // Multiple alarms from this package being delivered in this generation;
                // bump the package's delivery class if it's warranted.
                // TICK < WAKEUP < NORMAL
                if (alarmPrio < packagePrio.priority) {
                    packagePrio.priority = alarmPrio;
                }
            }
        }
    }

    // minimum recurrence period or alarm futurity for us to be able to fuzz it
    static final long MIN_FUZZABLE_INTERVAL = 10000;
    static final BatchTimeOrder sBatchOrder = new BatchTimeOrder();
    final ArrayList<Batch> mAlarmBatches = new ArrayList<>();

    // set to null if in idle mode; while in this mode, any alarms we don't want
    // to run during this time are placed in mPendingWhileIdleAlarms
    Alarm mPendingIdleUntil = null;
    Alarm mNextWakeFromIdle = null;
    ArrayList<Alarm> mPendingWhileIdleAlarms = new ArrayList<>();

    public AlarmManagerService(Context context) {
        super(context);
        mConstants = new Constants(mHandler);
    }

    static long convertToElapsed(long when, int type) {
        final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
        if (isRtc) {
            when -= System.currentTimeMillis() - SystemClock.elapsedRealtime();
        }
        return when;
    }

    // Apply a heuristic to { recurrence interval, futurity of the trigger time } to
    // calculate the end of our nominal delivery window for the alarm.
    static long maxTriggerTime(long now, long triggerAtTime, long interval) {
        // Current heuristic: batchable window is 75% of either the recurrence interval
        // [for a periodic alarm] or of the time from now to the desired delivery time,
        // with a minimum delay/interval of 10 seconds, under which we will simply not
        // defer the alarm.
        long futurity = (interval == 0)
                ? (triggerAtTime - now)
                : interval;
        if (futurity < MIN_FUZZABLE_INTERVAL) {
            futurity = 0;
        }
        return triggerAtTime + (long)(.75 * futurity);
    }

    // returns true if the batch was added at the head
    static boolean addBatchLocked(ArrayList<Batch> list, Batch newBatch) {
        int index = Collections.binarySearch(list, newBatch, sBatchOrder);
        if (index < 0) {
            index = 0 - index - 1;
        }
        list.add(index, newBatch);
        return (index == 0);
    }

    // Return the index of the matching batch, or -1 if none found.
    int attemptCoalesceLocked(long whenElapsed, long maxWhen) {
        final int N = mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            Batch b = mAlarmBatches.get(i);
            if ((b.flags&AlarmManager.FLAG_STANDALONE) == 0 && b.canHold(whenElapsed, maxWhen)) {
                return i;
            }
        }
        return -1;
    }

    // The RTC clock has moved arbitrarily, so we need to recalculate all the batching
    void rebatchAllAlarms() {
        synchronized (mLock) {
            rebatchAllAlarmsLocked(true);
        }
    }

    void rebatchAllAlarmsLocked(boolean doValidate) {
        ArrayList<Batch> oldSet = (ArrayList<Batch>) mAlarmBatches.clone();
        mAlarmBatches.clear();
        Alarm oldPendingIdleUntil = mPendingIdleUntil;
        final long nowElapsed = SystemClock.elapsedRealtime();
        final int oldBatches = oldSet.size();
        for (int batchNum = 0; batchNum < oldBatches; batchNum++) {
            Batch batch = oldSet.get(batchNum);
            final int N = batch.size();
            for (int i = 0; i < N; i++) {
                reAddAlarmLocked(batch.get(i), nowElapsed, doValidate);
            }
        }
        if (oldPendingIdleUntil != null && oldPendingIdleUntil != mPendingIdleUntil) {
            Slog.wtf(TAG, "Rebatching: idle until changed from " + oldPendingIdleUntil
                    + " to " + mPendingIdleUntil);
            if (mPendingIdleUntil == null) {
                // Somehow we lost this...  we need to restore all of the pending alarms.
                restorePendingWhileIdleAlarmsLocked();
            }
        }
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();
    }

    void reAddAlarmLocked(Alarm a, long nowElapsed, boolean doValidate) {
        a.when = a.origWhen;
        long whenElapsed = convertToElapsed(a.when, a.type);
        final long maxElapsed;
        if (a.windowLength == AlarmManager.WINDOW_EXACT) {
            // Exact
            maxElapsed = whenElapsed;
        } else {
            // Not exact.  Preserve any explicit window, otherwise recalculate
            // the window based on the alarm's new futurity.  Note that this
            // reflects a policy of preferring timely to deferred delivery.
            maxElapsed = (a.windowLength > 0)
                    ? (whenElapsed + a.windowLength)
                    : maxTriggerTime(nowElapsed, whenElapsed, a.repeatInterval);
        }
        a.whenElapsed = whenElapsed;
        a.maxWhenElapsed = maxElapsed;
        setImplLocked(a, true, doValidate);
    }

    void restorePendingWhileIdleAlarmsLocked() {
        // Bring pending alarms back into the main list.
        if (mPendingWhileIdleAlarms.size() > 0) {
            ArrayList<Alarm> alarms = mPendingWhileIdleAlarms;
            mPendingWhileIdleAlarms = new ArrayList<>();
            final long nowElapsed = SystemClock.elapsedRealtime();
            for (int i=alarms.size() - 1; i >= 0; i--) {
                Alarm a = alarms.get(i);
                reAddAlarmLocked(a, nowElapsed, false);
            }
        }

        // Make sure we are using the correct ALLOW_WHILE_IDLE min time.
        mConstants.updateAllowWhileIdleMinTimeLocked();

        // Reschedule everything.
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();

        // And send a TIME_TICK right now, since it is important to get the UI updated.
        try {
            mTimeTickSender.send();
        } catch (PendingIntent.CanceledException e) {
        }
    }

    static final class InFlight extends Intent {
        final PendingIntent mPendingIntent;
        final WorkSource mWorkSource;
        final String mTag;
        final BroadcastStats mBroadcastStats;
        final FilterStats mFilterStats;
        final int mAlarmType;

        InFlight(AlarmManagerService service, PendingIntent pendingIntent, WorkSource workSource,
                int alarmType, String tag, long nowELAPSED) {
            mPendingIntent = pendingIntent;
            mWorkSource = workSource;
            mTag = tag;
            mBroadcastStats = service.getStatsLocked(pendingIntent);
            FilterStats fs = mBroadcastStats.filterStats.get(mTag);
            if (fs == null) {
                fs = new FilterStats(mBroadcastStats, mTag);
                mBroadcastStats.filterStats.put(mTag, fs);
            }
            fs.lastTime = nowELAPSED;
            mFilterStats = fs;
            mAlarmType = alarmType;
        }
    }

    static final class FilterStats {
        final BroadcastStats mBroadcastStats;
        final String mTag;

        long lastTime;
        long aggregateTime;
        int count;
        int numWakeup;
        long startTime;
        int nesting;

        FilterStats(BroadcastStats broadcastStats, String tag) {
            mBroadcastStats = broadcastStats;
            mTag = tag;
        }
    }

    static final class BroadcastStats {
        final int mUid;
        final String mPackageName;

        long aggregateTime;
        int count;
        int numWakeup;
        long startTime;
        int nesting;
        final ArrayMap<String, FilterStats> filterStats = new ArrayMap<String, FilterStats>();

        BroadcastStats(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }
    }

    final SparseArray<ArrayMap<String, BroadcastStats>> mBroadcastStats
            = new SparseArray<ArrayMap<String, BroadcastStats>>();

    int mNumDelayedAlarms = 0;
    long mTotalDelayTime = 0;
    long mMaxDelayTime = 0;

    @Override
    public void onStart() {
        mNativeData = init();
        mNextWakeup = mNextNonWakeup = 0;

        // We have to set current TimeZone info to kernel
        // because kernel doesn't keep this after reboot
        setTimeZoneImpl(SystemProperties.get(TIMEZONE_PROPERTY));

        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*alarm*");

        mTimeTickSender = PendingIntent.getBroadcastAsUser(getContext(), 0,
                new Intent(Intent.ACTION_TIME_TICK).addFlags(
                        Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND), 0,
                        UserHandle.ALL);
        Intent intent = new Intent(Intent.ACTION_DATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        mDateChangeSender = PendingIntent.getBroadcastAsUser(getContext(), 0, intent,
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT, UserHandle.ALL);
        
        // now that we have initied the driver schedule the alarm
        mClockReceiver = new ClockReceiver();
        mClockReceiver.scheduleTimeTickEvent();
        mClockReceiver.scheduleDateChangedEvent();
        mInteractiveStateReceiver = new InteractiveStateReceiver();
        mUninstallReceiver = new UninstallReceiver();
        
        if (mNativeData != 0) {
            AlarmThread waitThread = new AlarmThread();
            waitThread.start();
        } else {
            Slog.w(TAG, "Failed to open alarm driver. Falling back to a handler.");
        }

        publishBinderService(Context.ALARM_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mConstants.start(getContext().getContentResolver());
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close(mNativeData);
        } finally {
            super.finalize();
        }
    }

    void setTimeZoneImpl(String tz) {
        if (TextUtils.isEmpty(tz)) {
            return;
        }

        TimeZone zone = TimeZone.getTimeZone(tz);
        // Prevent reentrant calls from stepping on each other when writing
        // the time zone property
        boolean timeZoneWasChanged = false;
        synchronized (this) {
            String current = SystemProperties.get(TIMEZONE_PROPERTY);
            if (current == null || !current.equals(zone.getID())) {
                if (localLOGV) {
                    Slog.v(TAG, "timezone changed: " + current + ", new=" + zone.getID());
                }
                timeZoneWasChanged = true;
                SystemProperties.set(TIMEZONE_PROPERTY, zone.getID());
            }

            // Update the kernel timezone information
            // Kernel tracks time offsets as 'minutes west of GMT'
            int gmtOffset = zone.getOffset(System.currentTimeMillis());
            setKernelTimezone(mNativeData, -(gmtOffset / 60000));
        }

        TimeZone.setDefault(null);

        if (timeZoneWasChanged) {
            Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra("time-zone", zone.getID());
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    void removeImpl(PendingIntent operation) {
        if (operation == null) {
            return;
        }
        synchronized (mLock) {
            removeLocked(operation);
        }
    }

    void setImpl(int type, long triggerAtTime, long windowLength, long interval,
            PendingIntent operation, int flags, WorkSource workSource,
            AlarmManager.AlarmClockInfo alarmClock, int callingUid) {
        if (operation == null) {
            Slog.w(TAG, "set/setRepeating ignored because there is no intent");
            return;
        }

        // Sanity check the window length.  This will catch people mistakenly
        // trying to pass an end-of-window timestamp rather than a duration.
        if (windowLength > AlarmManager.INTERVAL_HALF_DAY) {
            Slog.w(TAG, "Window length " + windowLength
                    + "ms suspiciously long; limiting to 1 hour");
            windowLength = AlarmManager.INTERVAL_HOUR;
        }

        // Sanity check the recurrence interval.  This will catch people who supply
        // seconds when the API expects milliseconds.
        final long minInterval = mConstants.MIN_INTERVAL;
        if (interval > 0 && interval < minInterval) {
            Slog.w(TAG, "Suspiciously short interval " + interval
                    + " millis; expanding to " + (minInterval/1000)
                    + " seconds");
            interval = minInterval;
        }

        if (type < RTC_WAKEUP || type > ELAPSED_REALTIME) {
            throw new IllegalArgumentException("Invalid alarm type " + type);
        }

        if (triggerAtTime < 0) {
            final long what = Binder.getCallingPid();
            Slog.w(TAG, "Invalid alarm trigger time! " + triggerAtTime + " from uid=" + callingUid
                    + " pid=" + what);
            triggerAtTime = 0;
        }

        final long nowElapsed = SystemClock.elapsedRealtime();
        final long nominalTrigger = convertToElapsed(triggerAtTime, type);
        // Try to prevent spamming by making sure we aren't firing alarms in the immediate future
        final long minTrigger = nowElapsed + mConstants.MIN_FUTURITY;
        final long triggerElapsed = (nominalTrigger > minTrigger) ? nominalTrigger : minTrigger;

        final long maxElapsed;
        if (windowLength == AlarmManager.WINDOW_EXACT) {
            maxElapsed = triggerElapsed;
        } else if (windowLength < 0) {
            maxElapsed = maxTriggerTime(nowElapsed, triggerElapsed, interval);
            // Fix this window in place, so that as time approaches we don't collapse it.
            windowLength = maxElapsed - triggerElapsed;
        } else {
            maxElapsed = triggerElapsed + windowLength;
        }

        synchronized (mLock) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "set(" + operation + ") : type=" + type
                        + " triggerAtTime=" + triggerAtTime + " win=" + windowLength
                        + " tElapsed=" + triggerElapsed + " maxElapsed=" + maxElapsed
                        + " interval=" + interval + " flags=0x" + Integer.toHexString(flags));
            }
            setImplLocked(type, triggerAtTime, triggerElapsed, windowLength, maxElapsed,
                    interval, operation, flags, true, workSource, alarmClock, callingUid);
        }
    }

    private void setImplLocked(int type, long when, long whenElapsed, long windowLength,
            long maxWhen, long interval, PendingIntent operation, int flags,
            boolean doValidate, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock,
            int uid) {
        Alarm a = new Alarm(type, when, whenElapsed, windowLength, maxWhen, interval,
                operation, workSource, flags, alarmClock, uid);
        removeLocked(operation);
        setImplLocked(a, false, doValidate);
    }

    private void setImplLocked(Alarm a, boolean rebatching, boolean doValidate) {
        if ((a.flags&AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            // This is a special alarm that will put the system into idle until it goes off.
            // The caller has given the time they want this to happen at, however we need
            // to pull that earlier if there are existing alarms that have requested to
            // bring us out of idle.
            if (mNextWakeFromIdle != null) {
                a.when = a.whenElapsed = a.maxWhenElapsed = mNextWakeFromIdle.whenElapsed;
            }
            // Add fuzz to make the alarm go off some time before the actual desired time.
            final long nowElapsed = SystemClock.elapsedRealtime();
            final int fuzz = fuzzForDuration(a.whenElapsed-nowElapsed);
            if (fuzz > 0) {
                if (mRandom == null) {
                    mRandom = new Random();
                }
                final int delta = mRandom.nextInt(fuzz);
                a.whenElapsed -= delta;
                if (false) {
                    Slog.d(TAG, "Alarm when: " + a.whenElapsed);
                    Slog.d(TAG, "Delta until alarm: " + (a.whenElapsed-nowElapsed));
                    Slog.d(TAG, "Applied fuzz: " + fuzz);
                    Slog.d(TAG, "Final delta: " + delta);
                    Slog.d(TAG, "Final when: " + a.whenElapsed);
                }
                a.when = a.maxWhenElapsed = a.whenElapsed;
            }

        } else if (mPendingIdleUntil != null) {
            // We currently have an idle until alarm scheduled; if the new alarm has
            // not explicitly stated it wants to run while idle, then put it on hold.
            if ((a.flags&(AlarmManager.FLAG_ALLOW_WHILE_IDLE
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED
                    | AlarmManager.FLAG_WAKE_FROM_IDLE))
                    == 0) {
                mPendingWhileIdleAlarms.add(a);
                return;
            }
        }

        int whichBatch = ((a.flags&AlarmManager.FLAG_STANDALONE) != 0)
                ? -1 : attemptCoalesceLocked(a.whenElapsed, a.maxWhenElapsed);
        if (whichBatch < 0) {
            Batch batch = new Batch(a);
            addBatchLocked(mAlarmBatches, batch);
        } else {
            Batch batch = mAlarmBatches.get(whichBatch);
            if (batch.add(a)) {
                // The start time of this batch advanced, so batch ordering may
                // have just been broken.  Move it to where it now belongs.
                mAlarmBatches.remove(whichBatch);
                addBatchLocked(mAlarmBatches, batch);
            }
        }

        if (a.alarmClock != null) {
            mNextAlarmClockMayChange = true;
        }

        boolean needRebatch = false;

        if ((a.flags&AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            mPendingIdleUntil = a;
            mConstants.updateAllowWhileIdleMinTimeLocked();
            needRebatch = true;
        } else if ((a.flags&AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
            if (mNextWakeFromIdle == null || mNextWakeFromIdle.whenElapsed > a.whenElapsed) {
                mNextWakeFromIdle = a;
                // If this wake from idle is earlier than whatever was previously scheduled,
                // and we are currently idling, then we need to rebatch alarms in case the idle
                // until time needs to be updated.
                if (mPendingIdleUntil != null) {
                    needRebatch = true;
                }
            }
        }

        if (!rebatching) {
            if (DEBUG_VALIDATE) {
                if (doValidate && !validateConsistencyLocked()) {
                    Slog.v(TAG, "Tipping-point operation: type=" + a.type + " when=" + a.when
                            + " when(hex)=" + Long.toHexString(a.when)
                            + " whenElapsed=" + a.whenElapsed
                            + " maxWhenElapsed=" + a.maxWhenElapsed
                            + " interval=" + a.repeatInterval + " op=" + a.operation
                            + " flags=0x" + Integer.toHexString(a.flags));
                    rebatchAllAlarmsLocked(false);
                    needRebatch = false;
                }
            }

            if (needRebatch) {
                rebatchAllAlarmsLocked(false);
            }

            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    private final IBinder mService = new IAlarmManager.Stub() {
        @Override
        public void set(int type, long triggerAtTime, long windowLength, long interval, int flags,
                PendingIntent operation, WorkSource workSource,
                AlarmManager.AlarmClockInfo alarmClock) {
            final int callingUid = Binder.getCallingUid();
            if (workSource != null) {
                getContext().enforcePermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS,
                        Binder.getCallingPid(), callingUid, "AlarmManager.set");
            }

            // No incoming callers can request either WAKE_FROM_IDLE or
            // ALLOW_WHILE_IDLE_UNRESTRICTED -- we will apply those later as appropriate.
            flags &= ~(AlarmManager.FLAG_WAKE_FROM_IDLE
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED);

            // Only the system can use FLAG_IDLE_UNTIL -- this is used to tell the alarm
            // manager when to come out of idle mode, which is only for DeviceIdleController.
            if (callingUid != Process.SYSTEM_UID) {
                flags &= ~AlarmManager.FLAG_IDLE_UNTIL;
            }

            // If the caller is a core system component, and not calling to do work on behalf
            // of someone else, then always set ALLOW_WHILE_IDLE_UNRESTRICTED.  This means we
            // will allow these alarms to go off as normal even while idle, with no timing
            // restrictions.
            if (callingUid < Process.FIRST_APPLICATION_UID && workSource == null) {
                flags |= AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
            }

            // If this is an exact time alarm, then it can't be batched with other alarms.
            if (windowLength == AlarmManager.WINDOW_EXACT) {
                flags |= AlarmManager.FLAG_STANDALONE;
            }

            // If this alarm is for an alarm clock, then it must be standalone and we will
            // use it to wake early from idle if needed.
            if (alarmClock != null) {
                flags |= AlarmManager.FLAG_WAKE_FROM_IDLE | AlarmManager.FLAG_STANDALONE;
            }

            setImpl(type, triggerAtTime, windowLength, interval, operation,
                    flags, workSource, alarmClock, callingUid);
        }

        @Override
        public boolean setTime(long millis) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME",
                    "setTime");

            if (mNativeData == 0) {
                Slog.w(TAG, "Not setting time since no alarm driver is available.");
                return false;
            }

            synchronized (mLock) {
                return setKernelTime(mNativeData, millis) == 0;
            }
        }

        @Override
        public void setTimeZone(String tz) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME_ZONE",
                    "setTimeZone");

            final long oldId = Binder.clearCallingIdentity();
            try {
                setTimeZoneImpl(tz);
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
        }

        @Override
        public void remove(PendingIntent operation) {
            removeImpl(operation);

        }

        @Override
        public long getNextWakeFromIdleTime() {
            return getNextWakeFromIdleTimeImpl();
        }

        @Override
        public AlarmManager.AlarmClockInfo getNextAlarmClock(int userId) {
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false /* allowAll */, false /* requireFull */,
                    "getNextAlarmClock", null);

            return getNextAlarmClockImpl(userId);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump AlarmManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(pw);
        }
    };

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current Alarm Manager state:");
            mConstants.dump(pw);
            pw.println();

            final long nowRTC = System.currentTimeMillis();
            final long nowELAPSED = SystemClock.elapsedRealtime();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            pw.print("  nowRTC="); pw.print(nowRTC);
            pw.print("="); pw.print(sdf.format(new Date(nowRTC)));
            pw.print(" nowELAPSED="); TimeUtils.formatDuration(nowELAPSED, pw);
            pw.println();
            pw.print("  mLastTimeChangeClockTime="); pw.print(mLastTimeChangeClockTime);
            pw.print("="); pw.println(sdf.format(new Date(mLastTimeChangeClockTime)));
            pw.print("  mLastTimeChangeRealtime=");
            TimeUtils.formatDuration(mLastTimeChangeRealtime, pw);
            pw.println();
            if (!mInteractive) {
                pw.print("  Time since non-interactive: ");
                TimeUtils.formatDuration(nowELAPSED - mNonInteractiveStartTime, pw);
                pw.println();
                pw.print("  Max wakeup delay: ");
                TimeUtils.formatDuration(currentNonWakeupFuzzLocked(nowELAPSED), pw);
                pw.println();
                pw.print("  Time since last dispatch: ");
                TimeUtils.formatDuration(nowELAPSED - mLastAlarmDeliveryTime, pw);
                pw.println();
                pw.print("  Next non-wakeup delivery time: ");
                TimeUtils.formatDuration(nowELAPSED - mNextNonWakeupDeliveryTime, pw);
                pw.println();
            }

            long nextWakeupRTC = mNextWakeup + (nowRTC - nowELAPSED);
            long nextNonWakeupRTC = mNextNonWakeup + (nowRTC - nowELAPSED);
            pw.print("  Next non-wakeup alarm: ");
                    TimeUtils.formatDuration(mNextNonWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.println(sdf.format(new Date(nextNonWakeupRTC)));
            pw.print("  Next wakeup: "); TimeUtils.formatDuration(mNextWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.println(sdf.format(new Date(nextWakeupRTC)));
            pw.print("  Num time change events: "); pw.println(mNumTimeChanged);

            pw.println();
            pw.println("  Next alarm clock information: ");
            final TreeSet<Integer> users = new TreeSet<>();
            for (int i = 0; i < mNextAlarmClockForUser.size(); i++) {
                users.add(mNextAlarmClockForUser.keyAt(i));
            }
            for (int i = 0; i < mPendingSendNextAlarmClockChangedForUser.size(); i++) {
                users.add(mPendingSendNextAlarmClockChangedForUser.keyAt(i));
            }
            for (int user : users) {
                final AlarmManager.AlarmClockInfo next = mNextAlarmClockForUser.get(user);
                final long time = next != null ? next.getTriggerTime() : 0;
                final boolean pendingSend = mPendingSendNextAlarmClockChangedForUser.get(user);
                pw.print("    user:"); pw.print(user);
                pw.print(" pendingSend:"); pw.print(pendingSend);
                pw.print(" time:"); pw.print(time);
                if (time > 0) {
                    pw.print(" = "); pw.print(sdf.format(new Date(time)));
                    pw.print(" = "); TimeUtils.formatDuration(time, nowRTC, pw);
                }
                pw.println();
            }
            if (mAlarmBatches.size() > 0) {
                pw.println();
                pw.print("  Pending alarm batches: ");
                pw.println(mAlarmBatches.size());
                for (Batch b : mAlarmBatches) {
                    pw.print(b); pw.println(':');
                    dumpAlarmList(pw, b.alarms, "    ", nowELAPSED, nowRTC, sdf);
                }
            }
            if (mPendingIdleUntil != null || mPendingWhileIdleAlarms.size() > 0) {
                pw.println();
                pw.println("    Idle mode state:");
                pw.print("      Idling until: ");
                if (mPendingIdleUntil != null) {
                    pw.println(mPendingIdleUntil);
                    mPendingIdleUntil.dump(pw, "    ", nowRTC, nowELAPSED, sdf);
                } else {
                    pw.println("null");
                }
                pw.println("      Pending alarms:");
                dumpAlarmList(pw, mPendingWhileIdleAlarms, "      ", nowELAPSED, nowRTC, sdf);
            }
            if (mNextWakeFromIdle != null) {
                pw.println();
                pw.print("  Next wake from idle: "); pw.println(mNextWakeFromIdle);
                mNextWakeFromIdle.dump(pw, "    ", nowRTC, nowELAPSED, sdf);
            }

            pw.println();
            pw.print("  Past-due non-wakeup alarms: ");
            if (mPendingNonWakeupAlarms.size() > 0) {
                pw.println(mPendingNonWakeupAlarms.size());
                dumpAlarmList(pw, mPendingNonWakeupAlarms, "    ", nowELAPSED, nowRTC, sdf);
            } else {
                pw.println("(none)");
            }
            pw.print("    Number of delayed alarms: "); pw.print(mNumDelayedAlarms);
            pw.print(", total delay time: "); TimeUtils.formatDuration(mTotalDelayTime, pw);
            pw.println();
            pw.print("    Max delay time: "); TimeUtils.formatDuration(mMaxDelayTime, pw);
            pw.print(", max non-interactive time: ");
            TimeUtils.formatDuration(mNonInteractiveTime, pw);
            pw.println();

            pw.println();
            pw.print("  Broadcast ref count: "); pw.println(mBroadcastRefCount);
            pw.println();

            pw.print("  mAllowWhileIdleMinTime=");
            TimeUtils.formatDuration(mAllowWhileIdleMinTime, pw);
            pw.println();
            if (mLastAllowWhileIdleDispatch.size() > 0) {
                pw.println("  Last allow while idle dispatch times:");
                for (int i=0; i<mLastAllowWhileIdleDispatch.size(); i++) {
                    pw.print("  UID ");
                    UserHandle.formatUid(pw, mLastAllowWhileIdleDispatch.keyAt(i));
                    pw.print(": ");
                    TimeUtils.formatDuration(mLastAllowWhileIdleDispatch.valueAt(i),
                            nowELAPSED, pw);
                    pw.println();
                }
            }
            pw.println();

            if (mLog.dump(pw, "  Recent problems", "    ")) {
                pw.println();
            }

            final FilterStats[] topFilters = new FilterStats[10];
            final Comparator<FilterStats> comparator = new Comparator<FilterStats>() {
                @Override
                public int compare(FilterStats lhs, FilterStats rhs) {
                    if (lhs.aggregateTime < rhs.aggregateTime) {
                        return 1;
                    } else if (lhs.aggregateTime > rhs.aggregateTime) {
                        return -1;
                    }
                    return 0;
                }
            };
            int len = 0;
            for (int iu=0; iu<mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip=0; ip<uidStats.size(); ip++) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    for (int is=0; is<bs.filterStats.size(); is++) {
                        FilterStats fs = bs.filterStats.valueAt(is);
                        int pos = len > 0
                                ? Arrays.binarySearch(topFilters, 0, len, fs, comparator) : 0;
                        if (pos < 0) {
                            pos = -pos - 1;
                        }
                        if (pos < topFilters.length) {
                            int copylen = topFilters.length - pos - 1;
                            if (copylen > 0) {
                                System.arraycopy(topFilters, pos, topFilters, pos+1, copylen);
                            }
                            topFilters[pos] = fs;
                            if (len < topFilters.length) {
                                len++;
                            }
                        }
                    }
                }
            }
            if (len > 0) {
                pw.println("  Top Alarms:");
                for (int i=0; i<len; i++) {
                    FilterStats fs = topFilters[i];
                    pw.print("    ");
                    if (fs.nesting > 0) pw.print("*ACTIVE* ");
                    TimeUtils.formatDuration(fs.aggregateTime, pw);
                    pw.print(" running, "); pw.print(fs.numWakeup);
                    pw.print(" wakeups, "); pw.print(fs.count);
                    pw.print(" alarms: "); UserHandle.formatUid(pw, fs.mBroadcastStats.mUid);
                    pw.print(":"); pw.print(fs.mBroadcastStats.mPackageName);
                    pw.println();
                    pw.print("      "); pw.print(fs.mTag);
                    pw.println();
                }
            }

            pw.println(" ");
            pw.println("  Alarm Stats:");
            final ArrayList<FilterStats> tmpFilters = new ArrayList<FilterStats>();
            for (int iu=0; iu<mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip=0; ip<uidStats.size(); ip++) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    pw.print("  ");
                    if (bs.nesting > 0) pw.print("*ACTIVE* ");
                    UserHandle.formatUid(pw, bs.mUid);
                    pw.print(":");
                    pw.print(bs.mPackageName);
                    pw.print(" "); TimeUtils.formatDuration(bs.aggregateTime, pw);
                            pw.print(" running, "); pw.print(bs.numWakeup);
                            pw.println(" wakeups:");
                    tmpFilters.clear();
                    for (int is=0; is<bs.filterStats.size(); is++) {
                        tmpFilters.add(bs.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    for (int i=0; i<tmpFilters.size(); i++) {
                        FilterStats fs = tmpFilters.get(i);
                        pw.print("    ");
                                if (fs.nesting > 0) pw.print("*ACTIVE* ");
                                TimeUtils.formatDuration(fs.aggregateTime, pw);
                                pw.print(" "); pw.print(fs.numWakeup);
                                pw.print(" wakes " ); pw.print(fs.count);
                                pw.print(" alarms, last ");
                                TimeUtils.formatDuration(fs.lastTime, nowELAPSED, pw);
                                pw.println(":");
                        pw.print("      ");
                                pw.print(fs.mTag);
                                pw.println();
                    }
                }
            }

            if (WAKEUP_STATS) {
                pw.println();
                pw.println("  Recent Wakeup History:");
                long last = -1;
                for (WakeupEvent event : mRecentWakeups) {
                    pw.print("    "); pw.print(sdf.format(new Date(event.when)));
                    pw.print('|');
                    if (last < 0) {
                        pw.print('0');
                    } else {
                        pw.print(event.when - last);
                    }
                    last = event.when;
                    pw.print('|'); pw.print(event.uid);
                    pw.print('|'); pw.print(event.action);
                    pw.println();
                }
                pw.println();
            }
        }
    }

    private void logBatchesLocked(SimpleDateFormat sdf) {
        ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
        PrintWriter pw = new PrintWriter(bs);
        final long nowRTC = System.currentTimeMillis();
        final long nowELAPSED = SystemClock.elapsedRealtime();
        final int NZ = mAlarmBatches.size();
        for (int iz = 0; iz < NZ; iz++) {
            Batch bz = mAlarmBatches.get(iz);
            pw.append("Batch "); pw.print(iz); pw.append(": "); pw.println(bz);
            dumpAlarmList(pw, bz.alarms, "  ", nowELAPSED, nowRTC, sdf);
            pw.flush();
            Slog.v(TAG, bs.toString());
            bs.reset();
        }
    }

    private boolean validateConsistencyLocked() {
        if (DEBUG_VALIDATE) {
            long lastTime = Long.MIN_VALUE;
            final int N = mAlarmBatches.size();
            for (int i = 0; i < N; i++) {
                Batch b = mAlarmBatches.get(i);
                if (b.start >= lastTime) {
                    // duplicate start times are okay because of standalone batches
                    lastTime = b.start;
                } else {
                    Slog.e(TAG, "CONSISTENCY FAILURE: Batch " + i + " is out of order");
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    logBatchesLocked(sdf);
                    return false;
                }
            }
        }
        return true;
    }

    private Batch findFirstWakeupBatchLocked() {
        final int N = mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            Batch b = mAlarmBatches.get(i);
            if (b.hasWakeups()) {
                return b;
            }
        }
        return null;
    }

    long getNextWakeFromIdleTimeImpl() {
        synchronized (mLock) {
            return mNextWakeFromIdle != null ? mNextWakeFromIdle.whenElapsed : Long.MAX_VALUE;
        }
    }

    AlarmManager.AlarmClockInfo getNextAlarmClockImpl(int userId) {
        synchronized (mLock) {
            return mNextAlarmClockForUser.get(userId);
        }
    }

    /**
     * Recomputes the next alarm clock for all users.
     */
    private void updateNextAlarmClockLocked() {
        if (!mNextAlarmClockMayChange) {
            return;
        }
        mNextAlarmClockMayChange = false;

        SparseArray<AlarmManager.AlarmClockInfo> nextForUser = mTmpSparseAlarmClockArray;
        nextForUser.clear();

        final int N = mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            ArrayList<Alarm> alarms = mAlarmBatches.get(i).alarms;
            final int M = alarms.size();

            for (int j = 0; j < M; j++) {
                Alarm a = alarms.get(j);
                if (a.alarmClock != null) {
                    final int userId = UserHandle.getUserId(a.uid);

                    if (DEBUG_ALARM_CLOCK) {
                        Log.v(TAG, "Found AlarmClockInfo at " +
                                formatNextAlarm(getContext(), a.alarmClock, userId) +
                                " for user " + userId);
                    }

                    // Alarms and batches are sorted by time, no need to compare times here.
                    if (nextForUser.get(userId) == null) {
                        nextForUser.put(userId, a.alarmClock);
                    }
                }
            }
        }

        // Update mNextAlarmForUser with new values.
        final int NN = nextForUser.size();
        for (int i = 0; i < NN; i++) {
            AlarmManager.AlarmClockInfo newAlarm = nextForUser.valueAt(i);
            int userId = nextForUser.keyAt(i);
            AlarmManager.AlarmClockInfo currentAlarm = mNextAlarmClockForUser.get(userId);
            if (!newAlarm.equals(currentAlarm)) {
                updateNextAlarmInfoForUserLocked(userId, newAlarm);
            }
        }

        // Remove users without any alarm clocks scheduled.
        final int NNN = mNextAlarmClockForUser.size();
        for (int i = NNN - 1; i >= 0; i--) {
            int userId = mNextAlarmClockForUser.keyAt(i);
            if (nextForUser.get(userId) == null) {
                updateNextAlarmInfoForUserLocked(userId, null);
            }
        }
    }

    private void updateNextAlarmInfoForUserLocked(int userId,
            AlarmManager.AlarmClockInfo alarmClock) {
        if (alarmClock != null) {
            if (DEBUG_ALARM_CLOCK) {
                Log.v(TAG, "Next AlarmClockInfoForUser(" + userId + "): " +
                        formatNextAlarm(getContext(), alarmClock, userId));
            }
            mNextAlarmClockForUser.put(userId, alarmClock);
        } else {
            if (DEBUG_ALARM_CLOCK) {
                Log.v(TAG, "Next AlarmClockInfoForUser(" + userId + "): None");
            }
            mNextAlarmClockForUser.remove(userId);
        }

        mPendingSendNextAlarmClockChangedForUser.put(userId, true);
        mHandler.removeMessages(AlarmHandler.SEND_NEXT_ALARM_CLOCK_CHANGED);
        mHandler.sendEmptyMessage(AlarmHandler.SEND_NEXT_ALARM_CLOCK_CHANGED);
    }

    /**
     * Updates NEXT_ALARM_FORMATTED and sends NEXT_ALARM_CLOCK_CHANGED_INTENT for all users
     * for which alarm clocks have changed since the last call to this.
     *
     * Do not call with a lock held. Only call from mHandler's thread.
     *
     * @see AlarmHandler#SEND_NEXT_ALARM_CLOCK_CHANGED
     */
    private void sendNextAlarmClockChanged() {
        SparseArray<AlarmManager.AlarmClockInfo> pendingUsers = mHandlerSparseAlarmClockArray;
        pendingUsers.clear();

        synchronized (mLock) {
            final int N  = mPendingSendNextAlarmClockChangedForUser.size();
            for (int i = 0; i < N; i++) {
                int userId = mPendingSendNextAlarmClockChangedForUser.keyAt(i);
                pendingUsers.append(userId, mNextAlarmClockForUser.get(userId));
            }
            mPendingSendNextAlarmClockChangedForUser.clear();
        }

        final int N = pendingUsers.size();
        for (int i = 0; i < N; i++) {
            int userId = pendingUsers.keyAt(i);
            AlarmManager.AlarmClockInfo alarmClock = pendingUsers.valueAt(i);
            Settings.System.putStringForUser(getContext().getContentResolver(),
                    Settings.System.NEXT_ALARM_FORMATTED,
                    formatNextAlarm(getContext(), alarmClock, userId),
                    userId);

            getContext().sendBroadcastAsUser(NEXT_ALARM_CLOCK_CHANGED_INTENT,
                    new UserHandle(userId));
        }
    }

    /**
     * Formats an alarm like platform/packages/apps/DeskClock used to.
     */
    private static String formatNextAlarm(final Context context, AlarmManager.AlarmClockInfo info,
            int userId) {
        String skeleton = DateFormat.is24HourFormat(context, userId) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return (info == null) ? "" :
                DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    void rescheduleKernelAlarmsLocked() {
        // Schedule the next upcoming wakeup alarm.  If there is a deliverable batch
        // prior to that which contains no wakeups, we schedule that as well.
        long nextNonWakeup = 0;
        if (mAlarmBatches.size() > 0) {
            final Batch firstWakeup = findFirstWakeupBatchLocked();
            final Batch firstBatch = mAlarmBatches.get(0);
            if (firstWakeup != null && mNextWakeup != firstWakeup.start) {
                mNextWakeup = firstWakeup.start;
                setLocked(ELAPSED_REALTIME_WAKEUP, firstWakeup.start);
            }
            if (firstBatch != firstWakeup) {
                nextNonWakeup = firstBatch.start;
            }
        }
        if (mPendingNonWakeupAlarms.size() > 0) {
            if (nextNonWakeup == 0 || mNextNonWakeupDeliveryTime < nextNonWakeup) {
                nextNonWakeup = mNextNonWakeupDeliveryTime;
            }
        }
        if (nextNonWakeup != 0 && mNextNonWakeup != nextNonWakeup) {
            mNextNonWakeup = nextNonWakeup;
            setLocked(ELAPSED_REALTIME, nextNonWakeup);
        }
    }

    private void removeLocked(PendingIntent operation) {
        boolean didRemove = false;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(operation);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            if (mPendingWhileIdleAlarms.get(i).operation.equals(operation)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }

        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(operation) changed bounds; rebatching");
            }
            boolean restorePending = false;
            if (mPendingIdleUntil != null && mPendingIdleUntil.operation.equals(operation)) {
                mPendingIdleUntil = null;
                restorePending = true;
            }
            if (mNextWakeFromIdle != null && mNextWakeFromIdle.operation.equals(operation)) {
                mNextWakeFromIdle = null;
            }
            rebatchAllAlarmsLocked(true);
            if (restorePending) {
                restorePendingWhileIdleAlarmsLocked();
            }
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(String packageName) {
        boolean didRemove = false;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(packageName);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            if (mPendingWhileIdleAlarms.get(i).operation.getTargetPackage().equals(packageName)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }

        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(package) changed bounds; rebatching");
            }
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeUserLocked(int userHandle) {
        boolean didRemove = false;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(userHandle);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mPendingWhileIdleAlarms.get(i).operation.getCreatorUid())
                    == userHandle) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }
        for (int i = mLastAllowWhileIdleDispatch.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mLastAllowWhileIdleDispatch.keyAt(i)) == userHandle) {
                mLastAllowWhileIdleDispatch.removeAt(i);
            }
        }

        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(user) changed bounds; rebatching");
            }
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void interactiveStateChangedLocked(boolean interactive) {
        if (mInteractive != interactive) {
            mInteractive = interactive;
            final long nowELAPSED = SystemClock.elapsedRealtime();
            if (interactive) {
                if (mPendingNonWakeupAlarms.size() > 0) {
                    final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                    mTotalDelayTime += thisDelayTime;
                    if (mMaxDelayTime < thisDelayTime) {
                        mMaxDelayTime = thisDelayTime;
                    }
                    deliverAlarmsLocked(mPendingNonWakeupAlarms, nowELAPSED);
                    mPendingNonWakeupAlarms.clear();
                }
                if (mNonInteractiveStartTime > 0) {
                    long dur = nowELAPSED - mNonInteractiveStartTime;
                    if (dur > mNonInteractiveTime) {
                        mNonInteractiveTime = dur;
                    }
                }
            } else {
                mNonInteractiveStartTime = nowELAPSED;
            }
        }
    }

    boolean lookForPackageLocked(String packageName) {
        for (int i = 0; i < mAlarmBatches.size(); i++) {
            Batch b = mAlarmBatches.get(i);
            if (b.hasPackage(packageName)) {
                return true;
            }
        }
        for (int i = 0; i < mPendingWhileIdleAlarms.size(); i++) {
            if (mPendingWhileIdleAlarms.get(i).operation.getTargetPackage().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void setLocked(int type, long when) {
        if (mNativeData != 0) {
            // The kernel never triggers alarms with negative wakeup times
            // so we ensure they are positive.
            long alarmSeconds, alarmNanoseconds;
            if (when < 0) {
                alarmSeconds = 0;
                alarmNanoseconds = 0;
            } else {
                alarmSeconds = when / 1000;
                alarmNanoseconds = (when % 1000) * 1000 * 1000;
            }
            
            set(mNativeData, type, alarmSeconds, alarmNanoseconds);
        } else {
            Message msg = Message.obtain();
            msg.what = ALARM_EVENT;
            
            mHandler.removeMessages(ALARM_EVENT);
            mHandler.sendMessageAtTime(msg, when);
        }
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
            String prefix, String label, long nowRTC, long nowELAPSED, SimpleDateFormat sdf) {
        for (int i=list.size()-1; i>=0; i--) {
            Alarm a = list.get(i);
            pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
                    pw.print(": "); pw.println(a);
            a.dump(pw, prefix + "  ", nowRTC, nowELAPSED, sdf);
        }
    }

    private static final String labelForType(int type) {
        switch (type) {
        case RTC: return "RTC";
        case RTC_WAKEUP : return "RTC_WAKEUP";
        case ELAPSED_REALTIME : return "ELAPSED";
        case ELAPSED_REALTIME_WAKEUP: return "ELAPSED_WAKEUP";
        default:
            break;
        }
        return "--unknown--";
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
            String prefix, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
        for (int i=list.size()-1; i>=0; i--) {
            Alarm a = list.get(i);
            final String label = labelForType(a.type);
            pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
                    pw.print(": "); pw.println(a);
            a.dump(pw, prefix + "  ", nowRTC, nowELAPSED, sdf);
        }
    }

    private native long init();
    private native void close(long nativeData);
    private native void set(long nativeData, int type, long seconds, long nanoseconds);
    private native int waitForAlarm(long nativeData);
    private native int setKernelTime(long nativeData, long millis);
    private native int setKernelTimezone(long nativeData, int minuteswest);

    boolean triggerAlarmsLocked(ArrayList<Alarm> triggerList, final long nowELAPSED,
            final long nowRTC) {
        boolean hasWakeup = false;
        // batches are temporally sorted, so we need only pull from the
        // start of the list until we either empty it or hit a batch
        // that is not yet deliverable
        while (mAlarmBatches.size() > 0) {
            Batch batch = mAlarmBatches.get(0);
            if (batch.start > nowELAPSED) {
                // Everything else is scheduled for the future
                break;
            }

            // We will (re)schedule some alarms now; don't let that interfere
            // with delivery of this current batch
            mAlarmBatches.remove(0);

            final int N = batch.size();
            for (int i = 0; i < N; i++) {
                Alarm alarm = batch.get(i);

                if ((alarm.flags&AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0) {
                    // If this is an ALLOW_WHILE_IDLE alarm, we constrain how frequently the app can
                    // schedule such alarms.
                    long lastTime = mLastAllowWhileIdleDispatch.get(alarm.uid, 0);
                    long minTime = lastTime + mAllowWhileIdleMinTime;
                    if (nowELAPSED < minTime) {
                        // Whoops, it hasn't been long enough since the last ALLOW_WHILE_IDLE
                        // alarm went off for this app.  Reschedule the alarm to be in the
                        // correct time period.
                        alarm.whenElapsed = minTime;
                        if (alarm.maxWhenElapsed < minTime) {
                            alarm.maxWhenElapsed = minTime;
                        }
                        setImplLocked(alarm, true, false);
                        continue;
                    }
                }

                alarm.count = 1;
                triggerList.add(alarm);
                if ((alarm.flags&AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
                    EventLogTags.writeDeviceIdleWakeFromIdle(mPendingIdleUntil != null ? 1 : 0,
                            alarm.tag);
                }
                if (mPendingIdleUntil == alarm) {
                    mPendingIdleUntil = null;
                    rebatchAllAlarmsLocked(false);
                    restorePendingWhileIdleAlarmsLocked();
                }
                if (mNextWakeFromIdle == alarm) {
                    mNextWakeFromIdle = null;
                    rebatchAllAlarmsLocked(false);
                }

                // Recurring alarms may have passed several alarm intervals while the
                // phone was asleep or off, so pass a trigger count when sending them.
                if (alarm.repeatInterval > 0) {
                    // this adjustment will be zero if we're late by
                    // less than one full repeat interval
                    alarm.count += (nowELAPSED - alarm.whenElapsed) / alarm.repeatInterval;

                    // Also schedule its next recurrence
                    final long delta = alarm.count * alarm.repeatInterval;
                    final long nextElapsed = alarm.whenElapsed + delta;
                    setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
                            maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
                            alarm.repeatInterval, alarm.operation, alarm.flags, true,
                            alarm.workSource, alarm.alarmClock, alarm.uid);
                }

                if (alarm.wakeup) {
                    hasWakeup = true;
                }

                // We removed an alarm clock. Let the caller recompute the next alarm clock.
                if (alarm.alarmClock != null) {
                    mNextAlarmClockMayChange = true;
                }
            }
        }

        // This is a new alarm delivery set; bump the sequence number to indicate that
        // all apps' alarm delivery classes should be recalculated.
        mCurrentSeq++;
        calculateDeliveryPriorities(triggerList);
        Collections.sort(triggerList, mAlarmDispatchComparator);

        if (localLOGV) {
            for (int i=0; i<triggerList.size(); i++) {
                Slog.v(TAG, "Triggering alarm #" + i + ": " + triggerList.get(i));
            }
        }

        return hasWakeup;
    }

    /**
     * This Comparator sorts Alarms into increasing time order.
     */
    public static class IncreasingTimeOrder implements Comparator<Alarm> {
        public int compare(Alarm a1, Alarm a2) {
            long when1 = a1.whenElapsed;
            long when2 = a2.whenElapsed;
            if (when1 - when2 > 0) {
                return 1;
            }
            if (when1 - when2 < 0) {
                return -1;
            }
            return 0;
        }
    }
    
    private static class Alarm {
        public final int type;
        public final long origWhen;
        public final boolean wakeup;
        public final PendingIntent operation;
        public final String tag;
        public final WorkSource workSource;
        public final int flags;
        public final AlarmManager.AlarmClockInfo alarmClock;
        public final int uid;
        public int count;
        public long when;
        public long windowLength;
        public long whenElapsed;    // 'when' in the elapsed time base
        public long maxWhenElapsed; // also in the elapsed time base
        public long repeatInterval;
        public PriorityClass priorityClass;

        public Alarm(int _type, long _when, long _whenElapsed, long _windowLength, long _maxWhen,
                long _interval, PendingIntent _op, WorkSource _ws, int _flags,
                AlarmManager.AlarmClockInfo _info, int _uid) {
            type = _type;
            origWhen = _when;
            wakeup = _type == AlarmManager.ELAPSED_REALTIME_WAKEUP
                    || _type == AlarmManager.RTC_WAKEUP;
            when = _when;
            whenElapsed = _whenElapsed;
            windowLength = _windowLength;
            maxWhenElapsed = _maxWhen;
            repeatInterval = _interval;
            operation = _op;
            tag = makeTag(_op, _type);
            workSource = _ws;
            flags = _flags;
            alarmClock = _info;
            uid = _uid;
        }

        public static String makeTag(PendingIntent pi, int type) {
            return pi.getTag(type == ELAPSED_REALTIME_WAKEUP || type == RTC_WAKEUP
                    ? "*walarm*:" : "*alarm*:");
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Alarm{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" type ");
            sb.append(type);
            sb.append(" when ");
            sb.append(when);
            sb.append(" ");
            sb.append(operation.getTargetPackage());
            sb.append('}');
            return sb.toString();
        }

        public void dump(PrintWriter pw, String prefix, long nowRTC, long nowELAPSED,
                SimpleDateFormat sdf) {
            final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
            pw.print(prefix); pw.print("tag="); pw.println(tag);
            pw.print(prefix); pw.print("type="); pw.print(type);
                    pw.print(" whenElapsed="); TimeUtils.formatDuration(whenElapsed,
                            nowELAPSED, pw);
                    if (isRtc) {
                        pw.print(" when="); pw.print(sdf.format(new Date(when)));
                    } else {
                        pw.print(" when="); TimeUtils.formatDuration(when, nowELAPSED, pw);
                    }
                    pw.println();
            pw.print(prefix); pw.print("window="); TimeUtils.formatDuration(windowLength, pw);
                    pw.print(" repeatInterval="); pw.print(repeatInterval);
                    pw.print(" count="); pw.print(count);
                    pw.print(" flags=0x"); pw.println(Integer.toHexString(flags));
            if (alarmClock != null) {
                pw.print(prefix); pw.println("Alarm clock:");
                pw.print(prefix); pw.print("  triggerTime=");
                pw.println(sdf.format(new Date(alarmClock.getTriggerTime())));
                pw.print(prefix); pw.print("  showIntent="); pw.println(alarmClock.getShowIntent());
            }
            pw.print(prefix); pw.print("operation="); pw.println(operation);
        }
    }

    void recordWakeupAlarms(ArrayList<Batch> batches, long nowELAPSED, long nowRTC) {
        final int numBatches = batches.size();
        for (int nextBatch = 0; nextBatch < numBatches; nextBatch++) {
            Batch b = batches.get(nextBatch);
            if (b.start > nowELAPSED) {
                break;
            }

            final int numAlarms = b.alarms.size();
            for (int nextAlarm = 0; nextAlarm < numAlarms; nextAlarm++) {
                Alarm a = b.alarms.get(nextAlarm);
                WakeupEvent e = new WakeupEvent(nowRTC,
                        a.operation.getCreatorUid(),
                        a.operation.getIntent().getAction());
                mRecentWakeups.add(e);
            }
        }
    }

    long currentNonWakeupFuzzLocked(long nowELAPSED) {
        long timeSinceOn = nowELAPSED - mNonInteractiveStartTime;
        if (timeSinceOn < 5*60*1000) {
            // If the screen has been off for 5 minutes, only delay by at most two minutes.
            return 2*60*1000;
        } else if (timeSinceOn < 30*60*1000) {
            // If the screen has been off for 30 minutes, only delay by at most 15 minutes.
            return 15*60*1000;
        } else {
            // Otherwise, we will delay by at most an hour.
            return 60*60*1000;
        }
    }

    static int fuzzForDuration(long duration) {
        if (duration < 15*60*1000) {
            // If the duration until the time is less than 15 minutes, the maximum fuzz
            // is the duration.
            return (int)duration;
        } else if (duration < 90*60*1000) {
            // If duration is less than 1 1/2 hours, the maximum fuzz is 15 minutes,
            return 15*60*1000;
        } else {
            // Otherwise, we will fuzz by at most half an hour.
            return 30*60*1000;
        }
    }

    boolean checkAllowNonWakeupDelayLocked(long nowELAPSED) {
        if (mInteractive) {
            return false;
        }
        if (mLastAlarmDeliveryTime <= 0) {
            return false;
        }
        if (mPendingNonWakeupAlarms.size() > 0 && mNextNonWakeupDeliveryTime < nowELAPSED) {
            // This is just a little paranoia, if somehow we have pending non-wakeup alarms
            // and the next delivery time is in the past, then just deliver them all.  This
            // avoids bugs where we get stuck in a loop trying to poll for alarms.
            return false;
        }
        long timeSinceLast = nowELAPSED - mLastAlarmDeliveryTime;
        return timeSinceLast <= currentNonWakeupFuzzLocked(nowELAPSED);
    }

    void deliverAlarmsLocked(ArrayList<Alarm> triggerList, long nowELAPSED) {
        mLastAlarmDeliveryTime = nowELAPSED;
        for (int i=0; i<triggerList.size(); i++) {
            Alarm alarm = triggerList.get(i);
            final boolean allowWhileIdle = (alarm.flags&AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0;
            try {
                if (localLOGV) {
                    Slog.v(TAG, "sending alarm " + alarm);
                }
                if (RECORD_ALARMS_IN_HISTORY) {
                    if (alarm.workSource != null && alarm.workSource.size() > 0) {
                        for (int wi=0; wi<alarm.workSource.size(); wi++) {
                            ActivityManagerNative.noteAlarmStart(
                                    alarm.operation, alarm.workSource.get(wi), alarm.tag);
                        }
                    } else {
                        ActivityManagerNative.noteAlarmStart(
                                alarm.operation, -1, alarm.tag);
                    }
                }
                alarm.operation.send(getContext(), 0,
                        mBackgroundIntent.putExtra(
                                Intent.EXTRA_ALARM_COUNT, alarm.count),
                        mResultReceiver, mHandler, null, allowWhileIdle ? mIdleOptions : null);

                // we have an active broadcast so stay awake.
                if (mBroadcastRefCount == 0) {
                    setWakelockWorkSource(alarm.operation, alarm.workSource,
                            alarm.type, alarm.tag, true);
                    mWakeLock.acquire();
                }
                final InFlight inflight = new InFlight(AlarmManagerService.this,
                        alarm.operation, alarm.workSource, alarm.type, alarm.tag, nowELAPSED);
                mInFlight.add(inflight);
                mBroadcastRefCount++;

                if (allowWhileIdle) {
                    // Record the last time this uid handled an ALLOW_WHILE_IDLE alarm.
                    mLastAllowWhileIdleDispatch.put(alarm.uid, nowELAPSED);
                }

                final BroadcastStats bs = inflight.mBroadcastStats;
                bs.count++;
                if (bs.nesting == 0) {
                    bs.nesting = 1;
                    bs.startTime = nowELAPSED;
                } else {
                    bs.nesting++;
                }
                final FilterStats fs = inflight.mFilterStats;
                fs.count++;
                if (fs.nesting == 0) {
                    fs.nesting = 1;
                    fs.startTime = nowELAPSED;
                } else {
                    fs.nesting++;
                }
                if (alarm.type == ELAPSED_REALTIME_WAKEUP
                        || alarm.type == RTC_WAKEUP) {
                    bs.numWakeup++;
                    fs.numWakeup++;
                    if (alarm.workSource != null && alarm.workSource.size() > 0) {
                        for (int wi=0; wi<alarm.workSource.size(); wi++) {
                            ActivityManagerNative.noteWakeupAlarm(
                                    alarm.operation, alarm.workSource.get(wi),
                                    alarm.workSource.getName(wi), alarm.tag);
                        }
                    } else {
                        ActivityManagerNative.noteWakeupAlarm(
                                alarm.operation, -1, null, alarm.tag);
                    }
                }
            } catch (PendingIntent.CanceledException e) {
                if (alarm.repeatInterval > 0) {
                    // This IntentSender is no longer valid, but this
                    // is a repeating alarm, so toss the hoser.
                    removeImpl(alarm.operation);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure sending alarm.", e);
            }
        }
    }

    private class AlarmThread extends Thread
    {
        public AlarmThread()
        {
            super("AlarmManager");
        }
        
        public void run()
        {
            ArrayList<Alarm> triggerList = new ArrayList<Alarm>();

            while (true)
            {
                int result = waitForAlarm(mNativeData);

                triggerList.clear();

                final long nowRTC = System.currentTimeMillis();
                final long nowELAPSED = SystemClock.elapsedRealtime();

                if ((result & TIME_CHANGED_MASK) != 0) {
                    // The kernel can give us spurious time change notifications due to
                    // small adjustments it makes internally; we want to filter those out.
                    final long lastTimeChangeClockTime;
                    final long expectedClockTime;
                    synchronized (mLock) {
                        lastTimeChangeClockTime = mLastTimeChangeClockTime;
                        expectedClockTime = lastTimeChangeClockTime
                                + (nowELAPSED - mLastTimeChangeRealtime);
                    }
                    if (lastTimeChangeClockTime == 0 || nowRTC < (expectedClockTime-500)
                            || nowRTC > (expectedClockTime+500)) {
                        // The change is by at least +/- 500 ms (or this is the first change),
                        // let's do it!
                        if (DEBUG_BATCH) {
                            Slog.v(TAG, "Time changed notification from kernel; rebatching");
                        }
                        removeImpl(mTimeTickSender);
                        rebatchAllAlarms();
                        mClockReceiver.scheduleTimeTickEvent();
                        synchronized (mLock) {
                            mNumTimeChanged++;
                            mLastTimeChangeClockTime = nowRTC;
                            mLastTimeChangeRealtime = nowELAPSED;
                        }
                        Intent intent = new Intent(Intent.ACTION_TIME_CHANGED);
                        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                                | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        getContext().sendBroadcastAsUser(intent, UserHandle.ALL);

                        // The world has changed on us, so we need to re-evaluate alarms
                        // regardless of whether the kernel has told us one went off.
                        result |= IS_WAKEUP_MASK;
                    }
                }

                if (result != TIME_CHANGED_MASK) {
                    // If this was anything besides just a time change, then figure what if
                    // anything to do about alarms.
                    synchronized (mLock) {
                        if (localLOGV) Slog.v(
                            TAG, "Checking for alarms... rtc=" + nowRTC
                            + ", elapsed=" + nowELAPSED);

                        if (WAKEUP_STATS) {
                            if ((result & IS_WAKEUP_MASK) != 0) {
                                long newEarliest = nowRTC - RECENT_WAKEUP_PERIOD;
                                int n = 0;
                                for (WakeupEvent event : mRecentWakeups) {
                                    if (event.when > newEarliest) break;
                                    n++; // number of now-stale entries at the list head
                                }
                                for (int i = 0; i < n; i++) {
                                    mRecentWakeups.remove();
                                }

                                recordWakeupAlarms(mAlarmBatches, nowELAPSED, nowRTC);
                            }
                        }

                        boolean hasWakeup = triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
                        if (!hasWakeup && checkAllowNonWakeupDelayLocked(nowELAPSED)) {
                            // if there are no wakeup alarms and the screen is off, we can
                            // delay what we have so far until the future.
                            if (mPendingNonWakeupAlarms.size() == 0) {
                                mStartCurrentDelayTime = nowELAPSED;
                                mNextNonWakeupDeliveryTime = nowELAPSED
                                        + ((currentNonWakeupFuzzLocked(nowELAPSED)*3)/2);
                            }
                            mPendingNonWakeupAlarms.addAll(triggerList);
                            mNumDelayedAlarms += triggerList.size();
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        } else {
                            // now deliver the alarm intents; if there are pending non-wakeup
                            // alarms, we need to merge them in to the list.  note we don't
                            // just deliver them first because we generally want non-wakeup
                            // alarms delivered after wakeup alarms.
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                            if (mPendingNonWakeupAlarms.size() > 0) {
                                calculateDeliveryPriorities(mPendingNonWakeupAlarms);
                                triggerList.addAll(mPendingNonWakeupAlarms);
                                Collections.sort(triggerList, mAlarmDispatchComparator);
                                final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                                mTotalDelayTime += thisDelayTime;
                                if (mMaxDelayTime < thisDelayTime) {
                                    mMaxDelayTime = thisDelayTime;
                                }
                                mPendingNonWakeupAlarms.clear();
                            }
                            deliverAlarmsLocked(triggerList, nowELAPSED);
                        }
                    }
                }
            }
        }
    }

    /**
     * Attribute blame for a WakeLock.
     * @param pi PendingIntent to attribute blame to if ws is null.
     * @param ws WorkSource to attribute blame.
     */
    void setWakelockWorkSource(PendingIntent pi, WorkSource ws, int type, String tag,
            boolean first) {
        try {
            final boolean unimportant = pi == mTimeTickSender;
            mWakeLock.setUnimportantForLogging(unimportant);
            if (first || mLastWakeLockUnimportantForLogging) {
                mWakeLock.setHistoryTag(tag);
            } else {
                mWakeLock.setHistoryTag(null);
            }
            mLastWakeLockUnimportantForLogging = unimportant;
            if (ws != null) {
                mWakeLock.setWorkSource(ws);
                return;
            }

            final int uid = ActivityManagerNative.getDefault()
                    .getUidForIntentSender(pi.getTarget());
            if (uid >= 0) {
                mWakeLock.setWorkSource(new WorkSource(uid));
                return;
            }
        } catch (Exception e) {
        }

        // Something went wrong; fall back to attributing the lock to the OS
        mWakeLock.setWorkSource(null);
    }

    private class AlarmHandler extends Handler {
        public static final int ALARM_EVENT = 1;
        public static final int MINUTE_CHANGE_EVENT = 2;
        public static final int DATE_CHANGE_EVENT = 3;
        public static final int SEND_NEXT_ALARM_CLOCK_CHANGED = 4;
        
        public AlarmHandler() {
        }
        
        public void handleMessage(Message msg) {
            if (msg.what == ALARM_EVENT) {
                ArrayList<Alarm> triggerList = new ArrayList<Alarm>();
                synchronized (mLock) {
                    final long nowRTC = System.currentTimeMillis();
                    final long nowELAPSED = SystemClock.elapsedRealtime();
                    triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
                    updateNextAlarmClockLocked();
                }

                // now trigger the alarms without the lock held
                for (int i=0; i<triggerList.size(); i++) {
                    Alarm alarm = triggerList.get(i);
                    try {
                        alarm.operation.send();
                    } catch (PendingIntent.CanceledException e) {
                        if (alarm.repeatInterval > 0) {
                            // This IntentSender is no longer valid, but this
                            // is a repeating alarm, so toss the hoser.
                            removeImpl(alarm.operation);
                        }
                    }
                }
            } else if (msg.what == SEND_NEXT_ALARM_CLOCK_CHANGED) {
                sendNextAlarmClockChanged();
            }
        }
    }
    
    class ClockReceiver extends BroadcastReceiver {
        public ClockReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            getContext().registerReceiver(this, filter);
        }
        
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIME_TICK)) {
                if (DEBUG_BATCH) {
                    Slog.v(TAG, "Received TIME_TICK alarm; rescheduling");
                }
                scheduleTimeTickEvent();
            } else if (intent.getAction().equals(Intent.ACTION_DATE_CHANGED)) {
                // Since the kernel does not keep track of DST, we need to
                // reset the TZ information at the beginning of each day
                // based off of the current Zone gmt offset + userspace tracked
                // daylight savings information.
                TimeZone zone = TimeZone.getTimeZone(SystemProperties.get(TIMEZONE_PROPERTY));
                int gmtOffset = zone.getOffset(System.currentTimeMillis());
                setKernelTimezone(mNativeData, -(gmtOffset / 60000));
                scheduleDateChangedEvent();
            }
        }
        
        public void scheduleTimeTickEvent() {
            final long currentTime = System.currentTimeMillis();
            final long nextTime = 60000 * ((currentTime / 60000) + 1);

            // Schedule this event for the amount of time that it would take to get to
            // the top of the next minute.
            final long tickEventDelay = nextTime - currentTime;

            final WorkSource workSource = null; // Let system take blame for time tick events.
            setImpl(ELAPSED_REALTIME, SystemClock.elapsedRealtime() + tickEventDelay, 0,
                    0, mTimeTickSender, AlarmManager.FLAG_STANDALONE, workSource, null,
                    Process.myUid());
        }

        public void scheduleDateChangedEvent() {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.DAY_OF_MONTH, 1);

            final WorkSource workSource = null; // Let system take blame for date change events.
            setImpl(RTC, calendar.getTimeInMillis(), 0, 0, mDateChangeSender,
                    AlarmManager.FLAG_STANDALONE, workSource, null, Process.myUid());
        }
    }
    
    class InteractiveStateReceiver extends BroadcastReceiver {
        public InteractiveStateReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                interactiveStateChangedLocked(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
            }
        }
    }

    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme("package");
            getContext().registerReceiver(this, filter);
             // Register for events related to sdcard installation.
            IntentFilter sdFilter = new IntentFilter();
            sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            sdFilter.addAction(Intent.ACTION_USER_STOPPED);
            sdFilter.addAction(Intent.ACTION_UID_REMOVED);
            getContext().registerReceiver(this, sdFilter);
        }
        
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                String action = intent.getAction();
                String pkgList[] = null;
                if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                    for (String packageName : pkgList) {
                        if (lookForPackageLocked(packageName)) {
                            setResultCode(Activity.RESULT_OK);
                            return;
                        }
                    }
                    return;
                } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                    int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (userHandle >= 0) {
                        removeUserLocked(userHandle);
                    }
                } else if (Intent.ACTION_UID_REMOVED.equals(action)) {
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid >= 0) {
                        mLastAllowWhileIdleDispatch.delete(uid);
                    }
                } else {
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                            && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        // This package is being updated; don't kill its alarms.
                        return;
                    }
                    Uri data = intent.getData();
                    if (data != null) {
                        String pkg = data.getSchemeSpecificPart();
                        if (pkg != null) {
                            pkgList = new String[]{pkg};
                        }
                    }
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkg : pkgList) {
                        removeLocked(pkg);
                        mPriorities.remove(pkg);
                        for (int i=mBroadcastStats.size()-1; i>=0; i--) {
                            ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(i);
                            if (uidStats.remove(pkg) != null) {
                                if (uidStats.size() <= 0) {
                                    mBroadcastStats.removeAt(i);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private final BroadcastStats getStatsLocked(PendingIntent pi) {
        String pkg = pi.getCreatorPackage();
        int uid = pi.getCreatorUid();
        ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.get(uid);
        if (uidStats == null) {
            uidStats = new ArrayMap<String, BroadcastStats>();
            mBroadcastStats.put(uid, uidStats);
        }
        BroadcastStats bs = uidStats.get(pkg);
        if (bs == null) {
            bs = new BroadcastStats(uid, pkg);
            uidStats.put(pkg, bs);
        }
        return bs;
    }

    class ResultReceiver implements PendingIntent.OnFinished {
        public void onSendFinished(PendingIntent pi, Intent intent, int resultCode,
                String resultData, Bundle resultExtras) {
            synchronized (mLock) {
                InFlight inflight = null;
                for (int i=0; i<mInFlight.size(); i++) {
                    if (mInFlight.get(i).mPendingIntent == pi) {
                        inflight = mInFlight.remove(i);
                        break;
                    }
                }
                if (inflight != null) {
                    final long nowELAPSED = SystemClock.elapsedRealtime();
                    BroadcastStats bs = inflight.mBroadcastStats;
                    bs.nesting--;
                    if (bs.nesting <= 0) {
                        bs.nesting = 0;
                        bs.aggregateTime += nowELAPSED - bs.startTime;
                    }
                    FilterStats fs = inflight.mFilterStats;
                    fs.nesting--;
                    if (fs.nesting <= 0) {
                        fs.nesting = 0;
                        fs.aggregateTime += nowELAPSED - fs.startTime;
                    }
                    if (RECORD_ALARMS_IN_HISTORY) {
                        if (inflight.mWorkSource != null && inflight.mWorkSource.size() > 0) {
                            for (int wi=0; wi<inflight.mWorkSource.size(); wi++) {
                                ActivityManagerNative.noteAlarmFinish(
                                        pi, inflight.mWorkSource.get(wi), inflight.mTag);
                            }
                        } else {
                            ActivityManagerNative.noteAlarmFinish(
                                    pi, -1, inflight.mTag);
                        }
                    }
                } else {
                    mLog.w("No in-flight alarm for " + pi + " " + intent);
                }
                mBroadcastRefCount--;
                if (mBroadcastRefCount == 0) {
                    mWakeLock.release();
                    if (mInFlight.size() > 0) {
                        mLog.w("Finished all broadcasts with " + mInFlight.size()
                                + " remaining inflights");
                        for (int i=0; i<mInFlight.size(); i++) {
                            mLog.w("  Remaining #" + i + ": " + mInFlight.get(i));
                        }
                        mInFlight.clear();
                    }
                } else {
                    // the next of our alarms is now in flight.  reattribute the wakelock.
                    if (mInFlight.size() > 0) {
                        InFlight inFlight = mInFlight.get(0);
                        setWakelockWorkSource(inFlight.mPendingIntent, inFlight.mWorkSource,
                                inFlight.mAlarmType, inFlight.mTag, false);
                    } else {
                        // should never happen
                        mLog.w("Alarm wakelock still held but sent queue empty");
                        mWakeLock.setWorkSource(null);
                    }
                }
            }
        }
    }
}
