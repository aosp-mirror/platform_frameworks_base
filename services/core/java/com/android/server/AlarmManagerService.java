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

import static android.app.AlarmManager.ELAPSED_REALTIME;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;

import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IAlarmCompleteListener;
import android.app.IAlarmListener;
import android.app.IAlarmManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.ThreadLocalWorkSource;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.LongArrayQueue;
import android.util.NtpTrustedTime;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.LocalLog;
import com.android.internal.util.StatLogger;
import com.android.server.AppStateTracker.Listener;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
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
import java.util.function.Predicate;

/**
 * Alarm manager implementation.
 *
 * Unit test:
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/AlarmManagerServiceTest.java
 */
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
    static final boolean DEBUG_LISTENER_CALLBACK = localLOGV || false;
    static final boolean DEBUG_WAKELOCK = localLOGV || false;
    static final boolean DEBUG_BG_LIMIT = localLOGV || false;
    static final boolean DEBUG_STANDBY = localLOGV || false;
    static final boolean RECORD_ALARMS_IN_HISTORY = true;
    static final boolean RECORD_DEVICE_IDLE_ALARMS = false;
    static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    static final int TICK_HISTORY_DEPTH = 10;
    static final long MILLIS_IN_DAY = 24 * 60 * 60 * 1000;

    // Indices into the APP_STANDBY_MIN_DELAYS and KEYS_APP_STANDBY_DELAY arrays
    static final int ACTIVE_INDEX = 0;
    static final int WORKING_INDEX = 1;
    static final int FREQUENT_INDEX = 2;
    static final int RARE_INDEX = 3;
    static final int NEVER_INDEX = 4;

    private final Intent mBackgroundIntent
            = new Intent().addFlags(Intent.FLAG_FROM_BACKGROUND);
    static final IncreasingTimeOrder sIncreasingTimeOrder = new IncreasingTimeOrder();

    static final boolean WAKEUP_STATS = false;

    private static final Intent NEXT_ALARM_CLOCK_CHANGED_INTENT =
            new Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                    .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                            | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

    final LocalLog mLog = new LocalLog(TAG);

    AppOpsManager mAppOps;
    DeviceIdleController.LocalService mLocalDeviceIdleController;
    private UsageStatsManagerInternal mUsageStatsManagerInternal;

    final Object mLock = new Object();

    // List of alarms per uid deferred due to user applied background restrictions on the source app
    SparseArray<ArrayList<Alarm>> mPendingBackgroundAlarms = new SparseArray<>();
    private long mNextWakeup;
    private long mNextNonWakeup;
    private long mNextWakeUpSetAt;
    private long mNextNonWakeUpSetAt;
    private long mLastWakeup;
    private long mLastTrigger;

    private long mLastTickSet;
    private long mLastTickReceived;
    private long mLastTickAdded;
    private long mLastTickRemoved;
    // ring buffer of recent TIME_TICK issuance, in the elapsed timebase
    private final long[] mTickHistory = new long[TICK_HISTORY_DEPTH];
    private int mNextTickHistory;

    private final Injector mInjector;
    int mBroadcastRefCount = 0;
    PowerManager.WakeLock mWakeLock;
    ArrayList<Alarm> mPendingNonWakeupAlarms = new ArrayList<>();
    ArrayList<InFlight> mInFlight = new ArrayList<>();
    private final ArrayList<AlarmManagerInternal.InFlightListener> mInFlightListeners =
            new ArrayList<>();
    AlarmHandler mHandler;
    AppWakeupHistory mAppWakeupHistory;
    ClockReceiver mClockReceiver;
    final DeliveryTracker mDeliveryTracker = new DeliveryTracker();
    Intent mTimeTickIntent;
    IAlarmListener mTimeTickTrigger;
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
    int mNumTimeChanged;

    // Bookkeeping about the identity of the "System UI" package, determined at runtime.

    /**
     * This permission must be defined by the canonical System UI package,
     * with protection level "signature".
     */
    private static final String SYSTEM_UI_SELF_PERMISSION =
            "android.permission.systemui.IDENTITY";

    /**
     * At boot we use SYSTEM_UI_SELF_PERMISSION to look up the definer's uid.
     */
    int mSystemUiUid;

    /**
     * For each uid, this is the last time we dispatched an "allow while idle" alarm,
     * used to determine the earliest we can dispatch the next such alarm. Times are in the
     * 'elapsed' timebase.
     */
    final SparseLongArray mLastAllowWhileIdleDispatch = new SparseLongArray();

    /**
     * For each uid, we store whether the last allow-while-idle alarm was dispatched while
     * the uid was in foreground or not. We will use the allow_while_idle_short_time in such cases.
     */
    final SparseBooleanArray mUseAllowWhileIdleShortTime = new SparseBooleanArray();

    final static class IdleDispatchEntry {
        int uid;
        String pkg;
        String tag;
        String op;
        long elapsedRealtime;
        long argRealtime;
    }
    final ArrayList<IdleDispatchEntry> mAllowWhileIdleDispatches = new ArrayList();

    interface Stats {
        int REBATCH_ALL_ALARMS = 0;
        int REORDER_ALARMS_FOR_STANDBY = 1;
    }

    private final StatLogger mStatLogger = new StatLogger(new String[] {
            "REBATCH_ALL_ALARMS",
            "REORDER_ALARMS_FOR_STANDBY",
    });

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

    private AppStateTracker mAppStateTracker;
    private boolean mAppStandbyParole;

    /**
     * A rolling window history of previous times when an alarm was sent to a package.
     */
    private static class AppWakeupHistory {
        private ArrayMap<Pair<String, Integer>, LongArrayQueue> mPackageHistory =
                new ArrayMap<>();
        private long mWindowSize;

        AppWakeupHistory(long windowSize) {
            mWindowSize = windowSize;
        }

        void recordAlarmForPackage(String packageName, int userId, long nowElapsed) {
            final Pair<String, Integer> packageUser = Pair.create(packageName, userId);
            LongArrayQueue history = mPackageHistory.get(packageUser);
            if (history == null) {
                history = new LongArrayQueue();
                mPackageHistory.put(packageUser, history);
            }
            if (history.size() == 0 || history.peekLast() < nowElapsed) {
                history.addLast(nowElapsed);
            }
            snapToWindow(history);
        }

        void removeForUser(int userId) {
            for (int i = mPackageHistory.size() - 1; i >= 0; i--) {
                final Pair<String, Integer> packageUserKey = mPackageHistory.keyAt(i);
                if (packageUserKey.second == userId) {
                    mPackageHistory.removeAt(i);
                }
            }
        }

        void removeForPackage(String packageName, int userId) {
            final Pair<String, Integer> packageUser = Pair.create(packageName, userId);
            mPackageHistory.remove(packageUser);
        }

        private void snapToWindow(LongArrayQueue history) {
            while (history.peekFirst() + mWindowSize < history.peekLast()) {
                history.removeFirst();
            }
        }

        int getTotalWakeupsInWindow(String packageName, int userId) {
            final LongArrayQueue history = mPackageHistory.get(Pair.create(packageName, userId));
            return (history == null) ? 0 : history.size();
        }

        long getLastWakeupForPackage(String packageName, int userId, int positionFromEnd) {
            final LongArrayQueue history = mPackageHistory.get(Pair.create(packageName, userId));
            if (history == null) {
                return 0;
            }
            final int i = history.size() - positionFromEnd;
            return (i < 0) ? 0 : history.get(i);
        }

        void dump(PrintWriter pw, String prefix, long nowElapsed) {
            dump(new IndentingPrintWriter(pw, "  ").setIndent(prefix), nowElapsed);
        }

        void dump(IndentingPrintWriter pw, long nowElapsed) {
            pw.println("App Alarm history:");
            pw.increaseIndent();
            for (int i = 0; i < mPackageHistory.size(); i++) {
                final Pair<String, Integer> packageUser = mPackageHistory.keyAt(i);
                final LongArrayQueue timestamps = mPackageHistory.valueAt(i);
                pw.print(packageUser.first);
                pw.print(", u");
                pw.print(packageUser.second);
                pw.print(": ");
                // limit dumping to a max of 100 values
                final int lastIdx = Math.max(0, timestamps.size() - 100);
                for (int j = timestamps.size() - 1; j >= lastIdx; j--) {
                    TimeUtils.formatDuration(timestamps.get(j), nowElapsed, pw);
                    pw.print(", ");
                }
                pw.println();
            }
            pw.decreaseIndent();
        }
    }

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the AlarmManagerService.mLock lock.
     */
    @VisibleForTesting
    final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        @VisibleForTesting
        static final String KEY_MIN_FUTURITY = "min_futurity";
        @VisibleForTesting
        static final String KEY_MIN_INTERVAL = "min_interval";
        @VisibleForTesting
        static final String KEY_MAX_INTERVAL = "max_interval";
        @VisibleForTesting
        static final String KEY_ALLOW_WHILE_IDLE_SHORT_TIME = "allow_while_idle_short_time";
        @VisibleForTesting
        static final String KEY_ALLOW_WHILE_IDLE_LONG_TIME = "allow_while_idle_long_time";
        @VisibleForTesting
        static final String KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION
                = "allow_while_idle_whitelist_duration";
        @VisibleForTesting
        static final String KEY_LISTENER_TIMEOUT = "listener_timeout";
        @VisibleForTesting
        static final String KEY_APP_STANDBY_QUOTAS_ENABLED = "app_standby_quotas_enabled";
        private static final String KEY_APP_STANDBY_WINDOW = "app_standby_window";
        @VisibleForTesting
        final String[] KEYS_APP_STANDBY_QUOTAS = {
                "standby_active_quota",
                "standby_working_quota",
                "standby_frequent_quota",
                "standby_rare_quota",
                "standby_never_quota",
        };

        // Keys for specifying throttling delay based on app standby bucketing
        private final String[] KEYS_APP_STANDBY_DELAY = {
                "standby_active_delay",
                "standby_working_delay",
                "standby_frequent_delay",
                "standby_rare_delay",
                "standby_never_delay",
        };

        private static final long DEFAULT_MIN_FUTURITY = 5 * 1000;
        private static final long DEFAULT_MIN_INTERVAL = 60 * 1000;
        private static final long DEFAULT_MAX_INTERVAL = 365 * DateUtils.DAY_IN_MILLIS;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME = DEFAULT_MIN_FUTURITY;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME = 9*60*1000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION = 10*1000;
        private static final long DEFAULT_LISTENER_TIMEOUT = 5 * 1000;
        private static final boolean DEFAULT_APP_STANDBY_QUOTAS_ENABLED = true;
        private static final long DEFAULT_APP_STANDBY_WINDOW = 60 * 60 * 1000;  // 1 hr
        /**
         * Max number of times an app can receive alarms in {@link #APP_STANDBY_WINDOW}
         */
        private final int[] DEFAULT_APP_STANDBY_QUOTAS = {
                720,    // Active
                10,     // Working
                2,      // Frequent
                1,      // Rare
                0       // Never
        };
        private final long[] DEFAULT_APP_STANDBY_DELAYS = {
                0,                       // Active
                6 * 60_000,              // Working
                30 * 60_000,             // Frequent
                2 * 60 * 60_000,         // Rare
                10 * 24 * 60 * 60_000    // Never
        };

        // Minimum futurity of a new alarm
        public long MIN_FUTURITY = DEFAULT_MIN_FUTURITY;

        // Minimum alarm recurrence interval
        public long MIN_INTERVAL = DEFAULT_MIN_INTERVAL;

        // Maximum alarm recurrence interval
        public long MAX_INTERVAL = DEFAULT_MAX_INTERVAL;

        // Minimum time between ALLOW_WHILE_IDLE alarms when system is not idle.
        public long ALLOW_WHILE_IDLE_SHORT_TIME = DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME;

        // Minimum time between ALLOW_WHILE_IDLE alarms when system is idling.
        public long ALLOW_WHILE_IDLE_LONG_TIME = DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME;

        // BroadcastOptions.setTemporaryAppWhitelistDuration() to use for FLAG_ALLOW_WHILE_IDLE.
        public long ALLOW_WHILE_IDLE_WHITELIST_DURATION
                = DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION;

        // Direct alarm listener callback timeout
        public long LISTENER_TIMEOUT = DEFAULT_LISTENER_TIMEOUT;
        public boolean APP_STANDBY_QUOTAS_ENABLED = DEFAULT_APP_STANDBY_QUOTAS_ENABLED;

        public long APP_STANDBY_WINDOW = DEFAULT_APP_STANDBY_WINDOW;
        public long[] APP_STANDBY_MIN_DELAYS = new long[DEFAULT_APP_STANDBY_DELAYS.length];
        public int[] APP_STANDBY_QUOTAS = new int[DEFAULT_APP_STANDBY_QUOTAS.length];

        private ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private long mLastAllowWhileIdleWhitelistDuration = -1;

        public Constants(Handler handler) {
            super(handler);
            updateAllowWhileIdleWhitelistDurationLocked();
        }

        public void start(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.ALARM_MANAGER_CONSTANTS), false, this);
            updateConstants();
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
                    Slog.e(TAG, "Bad alarm manager settings", e);
                }

                MIN_FUTURITY = mParser.getLong(KEY_MIN_FUTURITY, DEFAULT_MIN_FUTURITY);
                MIN_INTERVAL = mParser.getLong(KEY_MIN_INTERVAL, DEFAULT_MIN_INTERVAL);
                MAX_INTERVAL = mParser.getLong(KEY_MAX_INTERVAL, DEFAULT_MAX_INTERVAL);
                ALLOW_WHILE_IDLE_SHORT_TIME = mParser.getLong(KEY_ALLOW_WHILE_IDLE_SHORT_TIME,
                        DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME);
                ALLOW_WHILE_IDLE_LONG_TIME = mParser.getLong(KEY_ALLOW_WHILE_IDLE_LONG_TIME,
                        DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME);
                ALLOW_WHILE_IDLE_WHITELIST_DURATION = mParser.getLong(
                        KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION,
                        DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION);
                LISTENER_TIMEOUT = mParser.getLong(KEY_LISTENER_TIMEOUT,
                        DEFAULT_LISTENER_TIMEOUT);
                APP_STANDBY_MIN_DELAYS[ACTIVE_INDEX] = mParser.getDurationMillis(
                        KEYS_APP_STANDBY_DELAY[ACTIVE_INDEX],
                        DEFAULT_APP_STANDBY_DELAYS[ACTIVE_INDEX]);
                for (int i = WORKING_INDEX; i < KEYS_APP_STANDBY_DELAY.length; i++) {
                    APP_STANDBY_MIN_DELAYS[i] = mParser.getDurationMillis(KEYS_APP_STANDBY_DELAY[i],
                            Math.max(APP_STANDBY_MIN_DELAYS[i - 1], DEFAULT_APP_STANDBY_DELAYS[i]));
                }

                APP_STANDBY_QUOTAS_ENABLED = mParser.getBoolean(KEY_APP_STANDBY_QUOTAS_ENABLED,
                        DEFAULT_APP_STANDBY_QUOTAS_ENABLED);

                APP_STANDBY_WINDOW = mParser.getLong(KEY_APP_STANDBY_WINDOW,
                        DEFAULT_APP_STANDBY_WINDOW);
                if (APP_STANDBY_WINDOW > DEFAULT_APP_STANDBY_WINDOW) {
                    Slog.w(TAG, "Cannot exceed the app_standby_window size of "
                            + DEFAULT_APP_STANDBY_WINDOW);
                    APP_STANDBY_WINDOW = DEFAULT_APP_STANDBY_WINDOW;
                } else if (APP_STANDBY_WINDOW < DEFAULT_APP_STANDBY_WINDOW) {
                    // Not recommended outside of testing.
                    Slog.w(TAG, "Using a non-default app_standby_window of " + APP_STANDBY_WINDOW);
                }

                APP_STANDBY_QUOTAS[ACTIVE_INDEX] = mParser.getInt(
                        KEYS_APP_STANDBY_QUOTAS[ACTIVE_INDEX],
                        DEFAULT_APP_STANDBY_QUOTAS[ACTIVE_INDEX]);
                for (int i = WORKING_INDEX; i < KEYS_APP_STANDBY_QUOTAS.length; i++) {
                    APP_STANDBY_QUOTAS[i] = mParser.getInt(KEYS_APP_STANDBY_QUOTAS[i],
                            Math.min(APP_STANDBY_QUOTAS[i - 1], DEFAULT_APP_STANDBY_QUOTAS[i]));
                }
                updateAllowWhileIdleWhitelistDurationLocked();
            }
        }

        void dump(PrintWriter pw, String prefix) {
            dump(new IndentingPrintWriter(pw, "  ").setIndent(prefix));
        }

        void dump(IndentingPrintWriter pw) {
            pw.println("Settings:");

            pw.increaseIndent();

            pw.print(KEY_MIN_FUTURITY); pw.print("=");
            TimeUtils.formatDuration(MIN_FUTURITY, pw);
            pw.println();

            pw.print(KEY_MIN_INTERVAL); pw.print("=");
            TimeUtils.formatDuration(MIN_INTERVAL, pw);
            pw.println();

            pw.print(KEY_MAX_INTERVAL); pw.print("=");
            TimeUtils.formatDuration(MAX_INTERVAL, pw);
            pw.println();

            pw.print(KEY_LISTENER_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LISTENER_TIMEOUT, pw);
            pw.println();

            pw.print(KEY_ALLOW_WHILE_IDLE_SHORT_TIME); pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_SHORT_TIME, pw);
            pw.println();

            pw.print(KEY_ALLOW_WHILE_IDLE_LONG_TIME); pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_LONG_TIME, pw);
            pw.println();

            pw.print(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION); pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_WHITELIST_DURATION, pw);
            pw.println();

            for (int i = 0; i < KEYS_APP_STANDBY_DELAY.length; i++) {
                pw.print(KEYS_APP_STANDBY_DELAY[i]); pw.print("=");
                TimeUtils.formatDuration(APP_STANDBY_MIN_DELAYS[i], pw);
                pw.println();
            }

            pw.print(KEY_APP_STANDBY_QUOTAS_ENABLED); pw.print("=");
            pw.println(APP_STANDBY_QUOTAS_ENABLED);

            pw.print(KEY_APP_STANDBY_WINDOW); pw.print("=");
            TimeUtils.formatDuration(APP_STANDBY_WINDOW, pw);
            pw.println();

            for (int i = 0; i < KEYS_APP_STANDBY_QUOTAS.length; i++) {
                pw.print(KEYS_APP_STANDBY_QUOTAS[i]); pw.print("=");
                pw.println(APP_STANDBY_QUOTAS[i]);
            }

            pw.decreaseIndent();
        }

        void dumpProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(ConstantsProto.MIN_FUTURITY_DURATION_MS, MIN_FUTURITY);
            proto.write(ConstantsProto.MIN_INTERVAL_DURATION_MS, MIN_INTERVAL);
            proto.write(ConstantsProto.MAX_INTERVAL_DURATION_MS, MAX_INTERVAL);
            proto.write(ConstantsProto.LISTENER_TIMEOUT_DURATION_MS, LISTENER_TIMEOUT);
            proto.write(ConstantsProto.ALLOW_WHILE_IDLE_SHORT_DURATION_MS,
                    ALLOW_WHILE_IDLE_SHORT_TIME);
            proto.write(ConstantsProto.ALLOW_WHILE_IDLE_LONG_DURATION_MS,
                    ALLOW_WHILE_IDLE_LONG_TIME);
            proto.write(ConstantsProto.ALLOW_WHILE_IDLE_WHITELIST_DURATION_MS,
                    ALLOW_WHILE_IDLE_WHITELIST_DURATION);

            proto.end(token);
        }
    }

    Constants mConstants;

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
            end = clampPositive(seed.maxWhenElapsed);
            flags = seed.flags;
            alarms.add(seed);
            if (seed.listener == mTimeTickTrigger) {
                mLastTickAdded = mInjector.getCurrentTimeMillis();
            }
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
            if (alarm.listener == mTimeTickTrigger) {
                mLastTickAdded = mInjector.getCurrentTimeMillis();
            }
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

        boolean remove(Alarm alarm) {
            return remove(a -> (a == alarm));
        }

        boolean remove(Predicate<Alarm> predicate) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            int newFlags = 0;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (predicate.test(alarm)) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                    if (alarm.listener == mTimeTickTrigger) {
                        mLastTickRemoved = mInjector.getCurrentTimeMillis();
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

        boolean hasPackage(final String packageName) {
            final int N = alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = alarms.get(i);
                if (a.matches(packageName)) {
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

        public void writeToProto(ProtoOutputStream proto, long fieldId, long nowElapsed,
                long nowRTC) {
            final long token = proto.start(fieldId);

            proto.write(BatchProto.START_REALTIME, start);
            proto.write(BatchProto.END_REALTIME, end);
            proto.write(BatchProto.FLAGS, flags);
            for (Alarm a : alarms) {
                a.writeToProto(proto, BatchProto.ALARMS, nowElapsed, nowRTC);
            }

            proto.end(token);
        }
    }

    static class BatchTimeOrder implements Comparator<Batch> {
        public int compare(Batch b1, Batch b2) {
            long when1 = b1.start;
            long when2 = b2.start;
            if (when1 > when2) {
                return 1;
            }
            if (when1 < when2) {
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
            if (a.listener == mTimeTickTrigger) {
                alarmPrio = PRIO_TICK;
            } else if (a.wakeup) {
                alarmPrio = PRIO_WAKEUP;
            } else {
                alarmPrio = PRIO_NORMAL;
            }

            PriorityClass packagePrio = a.priorityClass;
            String alarmPackage = a.sourcePackage;
            if (packagePrio == null) packagePrio = mPriorities.get(alarmPackage);
            if (packagePrio == null) {
                packagePrio = a.priorityClass = new PriorityClass(); // lowest prio & stale sequence
                mPriorities.put(alarmPackage, packagePrio);
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

    // set to non-null if in idle mode; while in this mode, any alarms we don't want
    // to run during this time are placed in mPendingWhileIdleAlarms
    Alarm mPendingIdleUntil = null;
    Alarm mNextWakeFromIdle = null;
    ArrayList<Alarm> mPendingWhileIdleAlarms = new ArrayList<>();

    @VisibleForTesting
    AlarmManagerService(Context context, Injector injector) {
        super(context);
        mInjector = injector;
    }

    AlarmManagerService(Context context) {
        this(context, new Injector(context));
    }

    private long convertToElapsed(long when, int type) {
        final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
        if (isRtc) {
            when -= mInjector.getCurrentTimeMillis() - mInjector.getElapsedRealtime();
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
        return clampPositive(triggerAtTime + (long)(.75 * futurity));
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

    private void insertAndBatchAlarmLocked(Alarm alarm) {
        final int whichBatch = ((alarm.flags & AlarmManager.FLAG_STANDALONE) != 0) ? -1
                : attemptCoalesceLocked(alarm.whenElapsed, alarm.maxWhenElapsed);

        if (whichBatch < 0) {
            addBatchLocked(mAlarmBatches, new Batch(alarm));
        } else {
            final Batch batch = mAlarmBatches.get(whichBatch);
            if (batch.add(alarm)) {
                // The start time of this batch advanced, so batch ordering may
                // have just been broken.  Move it to where it now belongs.
                mAlarmBatches.remove(whichBatch);
                addBatchLocked(mAlarmBatches, batch);
            }
        }
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
    /** @return total count of the alarms in a set of alarm batches. */
    static int getAlarmCount(ArrayList<Batch> batches) {
        int ret = 0;

        final int size = batches.size();
        for (int i = 0; i < size; i++) {
            ret += batches.get(i).size();
        }
        return ret;
    }

    boolean haveAlarmsTimeTickAlarm(ArrayList<Alarm> alarms) {
        if (alarms.size() == 0) {
            return false;
        }
        final int batchSize = alarms.size();
        for (int j = 0; j < batchSize; j++) {
            if (alarms.get(j).listener == mTimeTickTrigger) {
                return true;
            }
        }
        return false;
    }

    boolean haveBatchesTimeTickAlarm(ArrayList<Batch> batches) {
        final int numBatches = batches.size();
        for (int i = 0; i < numBatches; i++) {
            if (haveAlarmsTimeTickAlarm(batches.get(i).alarms)) {
                return true;
            }
        }
        return false;
    }

    // The RTC clock has moved arbitrarily, so we need to recalculate all the batching
    void rebatchAllAlarms() {
        synchronized (mLock) {
            rebatchAllAlarmsLocked(true);
        }
    }

    void rebatchAllAlarmsLocked(boolean doValidate) {
        final long start = mStatLogger.getTime();
        final int oldCount =
                getAlarmCount(mAlarmBatches) + ArrayUtils.size(mPendingWhileIdleAlarms);
        final boolean oldHasTick = haveBatchesTimeTickAlarm(mAlarmBatches)
                || haveAlarmsTimeTickAlarm(mPendingWhileIdleAlarms);

        ArrayList<Batch> oldSet = (ArrayList<Batch>) mAlarmBatches.clone();
        mAlarmBatches.clear();
        Alarm oldPendingIdleUntil = mPendingIdleUntil;
        final long nowElapsed = mInjector.getElapsedRealtime();
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
        final int newCount =
                getAlarmCount(mAlarmBatches) + ArrayUtils.size(mPendingWhileIdleAlarms);
        final boolean newHasTick = haveBatchesTimeTickAlarm(mAlarmBatches)
                || haveAlarmsTimeTickAlarm(mPendingWhileIdleAlarms);

        if (oldCount != newCount) {
            Slog.wtf(TAG, "Rebatching: total count changed from " + oldCount + " to " + newCount);
        }
        if (oldHasTick != newHasTick) {
            Slog.wtf(TAG, "Rebatching: hasTick changed from " + oldHasTick + " to " + newHasTick);
        }

        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();
        mStatLogger.logDurationStat(Stats.REBATCH_ALL_ALARMS, start);
    }

    /**
     * Re-orders the alarm batches based on newly evaluated send times based on the current
     * app-standby buckets
     * @param targetPackages [Package, User] pairs for which alarms need to be re-evaluated,
     *                       null indicates all
     * @return True if there was any reordering done to the current list.
     */
    boolean reorderAlarmsBasedOnStandbyBuckets(ArraySet<Pair<String, Integer>> targetPackages) {
        final long start = mStatLogger.getTime();
        final ArrayList<Alarm> rescheduledAlarms = new ArrayList<>();

        for (int batchIndex = mAlarmBatches.size() - 1; batchIndex >= 0; batchIndex--) {
            final Batch batch = mAlarmBatches.get(batchIndex);
            for (int alarmIndex = batch.size() - 1; alarmIndex >= 0; alarmIndex--) {
                final Alarm alarm = batch.get(alarmIndex);
                final Pair<String, Integer> packageUser =
                        Pair.create(alarm.sourcePackage, UserHandle.getUserId(alarm.creatorUid));
                if (targetPackages != null && !targetPackages.contains(packageUser)) {
                    continue;
                }
                if (adjustDeliveryTimeBasedOnBucketLocked(alarm)) {
                    batch.remove(alarm);
                    rescheduledAlarms.add(alarm);
                }
            }
            if (batch.size() == 0) {
                mAlarmBatches.remove(batchIndex);
            }
        }
        for (int i = 0; i < rescheduledAlarms.size(); i++) {
            final Alarm a = rescheduledAlarms.get(i);
            insertAndBatchAlarmLocked(a);
        }

        mStatLogger.logDurationStat(Stats.REORDER_ALARMS_FOR_STANDBY, start);
        return rescheduledAlarms.size() > 0;
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
                    ? clampPositive(whenElapsed + a.windowLength)
                    : maxTriggerTime(nowElapsed, whenElapsed, a.repeatInterval);
        }
        a.whenElapsed = whenElapsed;
        a.maxWhenElapsed = maxElapsed;
        setImplLocked(a, true, doValidate);
    }

    static long clampPositive(long val) {
        return (val >= 0) ? val : Long.MAX_VALUE;
    }

    /**
     * Sends alarms that were blocked due to user applied background restrictions - either because
     * the user lifted those or the uid came to foreground.
     *
     * @param uid uid to filter on
     * @param packageName package to filter on, or null for all packages in uid
     */
    void sendPendingBackgroundAlarmsLocked(int uid, String packageName) {
        final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.get(uid);
        if (alarmsForUid == null || alarmsForUid.size() == 0) {
            return;
        }
        final ArrayList<Alarm> alarmsToDeliver;
        if (packageName != null) {
            if (DEBUG_BG_LIMIT) {
                Slog.d(TAG, "Sending blocked alarms for uid " + uid + ", package " + packageName);
            }
            alarmsToDeliver = new ArrayList<>();
            for (int i = alarmsForUid.size() - 1; i >= 0; i--) {
                final Alarm a = alarmsForUid.get(i);
                if (a.matches(packageName)) {
                    alarmsToDeliver.add(alarmsForUid.remove(i));
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.remove(uid);
            }
        } else {
            if (DEBUG_BG_LIMIT) {
                Slog.d(TAG, "Sending blocked alarms for uid " + uid);
            }
            alarmsToDeliver = alarmsForUid;
            mPendingBackgroundAlarms.remove(uid);
        }
        deliverPendingBackgroundAlarmsLocked(alarmsToDeliver, mInjector.getElapsedRealtime());
    }

    /**
     * Check all alarms in {@link #mPendingBackgroundAlarms} and send the ones that are not
     * restricted.
     *
     * This is only called when the global "force all apps-standby" flag changes or when the
     * power save whitelist changes, so it's okay to be slow.
     */
    void sendAllUnrestrictedPendingBackgroundAlarmsLocked() {
        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();

        findAllUnrestrictedPendingBackgroundAlarmsLockedInner(
                mPendingBackgroundAlarms, alarmsToDeliver, this::isBackgroundRestricted);

        if (alarmsToDeliver.size() > 0) {
            deliverPendingBackgroundAlarmsLocked(alarmsToDeliver, mInjector.getElapsedRealtime());
        }
    }

    @VisibleForTesting
    static void findAllUnrestrictedPendingBackgroundAlarmsLockedInner(
            SparseArray<ArrayList<Alarm>> pendingAlarms, ArrayList<Alarm> unrestrictedAlarms,
            Predicate<Alarm> isBackgroundRestricted) {

        for (int uidIndex = pendingAlarms.size() - 1; uidIndex >= 0; uidIndex--) {
            final int uid = pendingAlarms.keyAt(uidIndex);
            final ArrayList<Alarm> alarmsForUid = pendingAlarms.valueAt(uidIndex);

            for (int alarmIndex = alarmsForUid.size() - 1; alarmIndex >= 0; alarmIndex--) {
                final Alarm alarm = alarmsForUid.get(alarmIndex);

                if (isBackgroundRestricted.test(alarm)) {
                    continue;
                }

                unrestrictedAlarms.add(alarm);
                alarmsForUid.remove(alarmIndex);
            }

            if (alarmsForUid.size() == 0) {
                pendingAlarms.removeAt(uidIndex);
            }
        }
    }

    private void deliverPendingBackgroundAlarmsLocked(ArrayList<Alarm> alarms, long nowELAPSED) {
        final int N = alarms.size();
        boolean hasWakeup = false;
        for (int i = 0; i < N; i++) {
            final Alarm alarm = alarms.get(i);
            if (alarm.wakeup) {
                hasWakeup = true;
            }
            alarm.count = 1;
            // Recurring alarms may have passed several alarm intervals while the
            // alarm was kept pending. Send the appropriate trigger count.
            if (alarm.repeatInterval > 0) {
                alarm.count += (nowELAPSED - alarm.expectedWhenElapsed) / alarm.repeatInterval;
                // Also schedule its next recurrence
                final long delta = alarm.count * alarm.repeatInterval;
                final long nextElapsed = alarm.whenElapsed + delta;
                setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
                        maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
                        alarm.repeatInterval, alarm.operation, null, null, alarm.flags, true,
                        alarm.workSource, alarm.alarmClock, alarm.uid, alarm.packageName);
                // Kernel alarms will be rescheduled as needed in setImplLocked
            }
        }
        if (!hasWakeup && checkAllowNonWakeupDelayLocked(nowELAPSED)) {
            // No need to wakeup for non wakeup alarms
            if (mPendingNonWakeupAlarms.size() == 0) {
                mStartCurrentDelayTime = nowELAPSED;
                mNextNonWakeupDeliveryTime = nowELAPSED
                        + ((currentNonWakeupFuzzLocked(nowELAPSED)*3)/2);
            }
            mPendingNonWakeupAlarms.addAll(alarms);
            mNumDelayedAlarms += alarms.size();
        } else {
            if (DEBUG_BG_LIMIT) {
                Slog.d(TAG, "Waking up to deliver pending blocked alarms");
            }
            // Since we are waking up, also deliver any pending non wakeup alarms we have.
            if (mPendingNonWakeupAlarms.size() > 0) {
                alarms.addAll(mPendingNonWakeupAlarms);
                final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                mTotalDelayTime += thisDelayTime;
                if (mMaxDelayTime < thisDelayTime) {
                    mMaxDelayTime = thisDelayTime;
                }
                mPendingNonWakeupAlarms.clear();
            }
            calculateDeliveryPriorities(alarms);
            Collections.sort(alarms, mAlarmDispatchComparator);
            deliverAlarmsLocked(alarms, nowELAPSED);
        }
    }

    void restorePendingWhileIdleAlarmsLocked() {
        if (RECORD_DEVICE_IDLE_ALARMS) {
            IdleDispatchEntry ent = new IdleDispatchEntry();
            ent.uid = 0;
            ent.pkg = "FINISH IDLE";
            ent.elapsedRealtime = mInjector.getElapsedRealtime();
            mAllowWhileIdleDispatches.add(ent);
        }

        // Bring pending alarms back into the main list.
        if (mPendingWhileIdleAlarms.size() > 0) {
            ArrayList<Alarm> alarms = mPendingWhileIdleAlarms;
            mPendingWhileIdleAlarms = new ArrayList<>();
            final long nowElapsed = mInjector.getElapsedRealtime();
            for (int i=alarms.size() - 1; i >= 0; i--) {
                Alarm a = alarms.get(i);
                reAddAlarmLocked(a, nowElapsed, false);
            }
        }

        // Reschedule everything.
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();

    }

    static final class InFlight {
        final PendingIntent mPendingIntent;
        final long mWhenElapsed;
        final IBinder mListener;
        final WorkSource mWorkSource;
        final int mUid;
        final int mCreatorUid;
        final String mTag;
        final BroadcastStats mBroadcastStats;
        final FilterStats mFilterStats;
        final int mAlarmType;

        InFlight(AlarmManagerService service, Alarm alarm, long nowELAPSED) {
            mPendingIntent = alarm.operation;
            mWhenElapsed = nowELAPSED;
            mListener = alarm.listener != null ? alarm.listener.asBinder() : null;
            mWorkSource = alarm.workSource;
            mUid = alarm.uid;
            mCreatorUid = alarm.creatorUid;
            mTag = alarm.statsTag;
            mBroadcastStats = (alarm.operation != null)
                    ? service.getStatsLocked(alarm.operation)
                    : service.getStatsLocked(alarm.uid, alarm.packageName);
            FilterStats fs = mBroadcastStats.filterStats.get(mTag);
            if (fs == null) {
                fs = new FilterStats(mBroadcastStats, mTag);
                mBroadcastStats.filterStats.put(mTag, fs);
            }
            fs.lastTime = nowELAPSED;
            mFilterStats = fs;
            mAlarmType = alarm.type;
        }

        boolean isBroadcast() {
            return mPendingIntent != null && mPendingIntent.isBroadcast();
        }

        @Override
        public String toString() {
            return "InFlight{"
                    + "pendingIntent=" + mPendingIntent
                    + ", when=" + mWhenElapsed
                    + ", workSource=" + mWorkSource
                    + ", uid=" + mUid
                    + ", creatorUid=" + mCreatorUid
                    + ", tag=" + mTag
                    + ", broadcastStats=" + mBroadcastStats
                    + ", filterStats=" + mFilterStats
                    + ", alarmType=" + mAlarmType
                    + "}";
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(InFlightProto.UID, mUid);
            proto.write(InFlightProto.TAG, mTag);
            proto.write(InFlightProto.WHEN_ELAPSED_MS, mWhenElapsed);
            proto.write(InFlightProto.ALARM_TYPE, mAlarmType);
            if (mPendingIntent != null) {
                mPendingIntent.writeToProto(proto, InFlightProto.PENDING_INTENT);
            }
            if (mBroadcastStats != null) {
                mBroadcastStats.writeToProto(proto, InFlightProto.BROADCAST_STATS);
            }
            if (mFilterStats != null) {
                mFilterStats.writeToProto(proto, InFlightProto.FILTER_STATS);
            }
            if (mWorkSource != null) {
                mWorkSource.writeToProto(proto, InFlightProto.WORK_SOURCE);
            }

            proto.end(token);
        }
    }

    private void notifyBroadcastAlarmPendingLocked(int uid) {
        final int numListeners = mInFlightListeners.size();
        for (int i = 0; i < numListeners; i++) {
            mInFlightListeners.get(i).broadcastAlarmPending(uid);
        }
    }

    private void notifyBroadcastAlarmCompleteLocked(int uid) {
        final int numListeners = mInFlightListeners.size();
        for (int i = 0; i < numListeners; i++) {
            mInFlightListeners.get(i).broadcastAlarmComplete(uid);
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

        @Override
        public String toString() {
            return "FilterStats{"
                    + "tag=" + mTag
                    + ", lastTime=" + lastTime
                    + ", aggregateTime=" + aggregateTime
                    + ", count=" + count
                    + ", numWakeup=" + numWakeup
                    + ", startTime=" + startTime
                    + ", nesting=" + nesting
                    + "}";
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(FilterStatsProto.TAG, mTag);
            proto.write(FilterStatsProto.LAST_FLIGHT_TIME_REALTIME, lastTime);
            proto.write(FilterStatsProto.TOTAL_FLIGHT_DURATION_MS, aggregateTime);
            proto.write(FilterStatsProto.COUNT, count);
            proto.write(FilterStatsProto.WAKEUP_COUNT, numWakeup);
            proto.write(FilterStatsProto.START_TIME_REALTIME, startTime);
            proto.write(FilterStatsProto.NESTING, nesting);

            proto.end(token);
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

        @Override
        public String toString() {
            return "BroadcastStats{"
                    + "uid=" + mUid
                    + ", packageName=" + mPackageName
                    + ", aggregateTime=" + aggregateTime
                    + ", count=" + count
                    + ", numWakeup=" + numWakeup
                    + ", startTime=" + startTime
                    + ", nesting=" + nesting
                    + "}";
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(BroadcastStatsProto.UID, mUid);
            proto.write(BroadcastStatsProto.PACKAGE_NAME, mPackageName);
            proto.write(BroadcastStatsProto.TOTAL_FLIGHT_DURATION_MS, aggregateTime);
            proto.write(BroadcastStatsProto.COUNT, count);
            proto.write(BroadcastStatsProto.WAKEUP_COUNT, numWakeup);
            proto.write(BroadcastStatsProto.START_TIME_REALTIME, startTime);
            proto.write(BroadcastStatsProto.NESTING, nesting);

            proto.end(token);
        }
    }

    final SparseArray<ArrayMap<String, BroadcastStats>> mBroadcastStats
            = new SparseArray<ArrayMap<String, BroadcastStats>>();

    int mNumDelayedAlarms = 0;
    long mTotalDelayTime = 0;
    long mMaxDelayTime = 0;

    @Override
    public void onStart() {
        mInjector.init();

        synchronized (mLock) {
            mHandler = new AlarmHandler();
            mConstants = new Constants(mHandler);
            mAppWakeupHistory = new AppWakeupHistory(Constants.DEFAULT_APP_STANDBY_WINDOW);

            mNextWakeup = mNextNonWakeup = 0;

            // We have to set current TimeZone info to kernel
            // because kernel doesn't keep this after reboot
            setTimeZoneImpl(SystemProperties.get(TIMEZONE_PROPERTY));

            // Ensure that we're booting with a halfway sensible current time.  Use the
            // most recent of Build.TIME, the root file system's timestamp, and the
            // value of the ro.build.date.utc system property (which is in seconds).
            final long systemBuildTime =  Long.max(
                    1000L * SystemProperties.getLong("ro.build.date.utc", -1L),
                    Long.max(Environment.getRootDirectory().lastModified(), Build.TIME));
            if (mInjector.getCurrentTimeMillis() < systemBuildTime) {
                Slog.i(TAG, "Current time only " + mInjector.getCurrentTimeMillis()
                        + ", advancing to build time " + systemBuildTime);
                mInjector.setKernelTime(systemBuildTime);
            }

            // Determine SysUI's uid
            mSystemUiUid = mInjector.getSystemUiUid();
            if (mSystemUiUid <= 0) {
                Slog.wtf(TAG, "SysUI package not found!");
            }
            mWakeLock = mInjector.getAlarmWakeLock();

            mTimeTickIntent = new Intent(Intent.ACTION_TIME_TICK).addFlags(
                    Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND
                    | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);

            mTimeTickTrigger = new IAlarmListener.Stub() {
                @Override
                public void doAlarm(final IAlarmCompleteListener callback) throws RemoteException {
                    if (DEBUG_BATCH) {
                        Slog.v(TAG, "Received TIME_TICK alarm; rescheduling");
                    }

                    // Via handler because dispatch invokes this within its lock.  OnAlarmListener
                    // takes care of this automatically, but we're using the direct internal
                    // interface here rather than that client-side wrapper infrastructure.
                    mHandler.post(() -> {
                        getContext().sendBroadcastAsUser(mTimeTickIntent, UserHandle.ALL);

                        try {
                            callback.alarmComplete(this);
                        } catch (RemoteException e) { /* local method call */ }
                    });

                    synchronized (mLock) {
                        mLastTickReceived = mInjector.getCurrentTimeMillis();
                    }
                    mClockReceiver.scheduleTimeTickEvent();
                }
            };

            Intent intent = new Intent(Intent.ACTION_DATE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
            mDateChangeSender = PendingIntent.getBroadcastAsUser(getContext(), 0, intent,
                    Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT, UserHandle.ALL);

            mClockReceiver = mInjector.getClockReceiver(this);
            new InteractiveStateReceiver();
            new UninstallReceiver();

            if (mInjector.isAlarmDriverPresent()) {
                AlarmThread waitThread = new AlarmThread();
                waitThread.start();
            } else {
                Slog.w(TAG, "Failed to open alarm driver. Falling back to a handler.");
            }

            try {
                ActivityManager.getService().registerUidObserver(new UidObserver(),
                        ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_IDLE
                                | ActivityManager.UID_OBSERVER_ACTIVE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }
        }
        publishLocalService(AlarmManagerInternal.class, new LocalService());
        publishBinderService(Context.ALARM_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                mConstants.start(getContext().getContentResolver());
                mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
                mLocalDeviceIdleController =
                        LocalServices.getService(DeviceIdleController.LocalService.class);
                mUsageStatsManagerInternal =
                        LocalServices.getService(UsageStatsManagerInternal.class);
                mUsageStatsManagerInternal.addAppIdleStateChangeListener(new AppStandbyTracker());

                mAppStateTracker = LocalServices.getService(AppStateTracker.class);
                mAppStateTracker.addListener(mForceAppStandbyListener);

                mClockReceiver.scheduleTimeTickEvent();
                mClockReceiver.scheduleDateChangedEvent();
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mInjector.close();
        } finally {
            super.finalize();
        }
    }

    boolean setTimeImpl(long millis) {
        if (!mInjector.isAlarmDriverPresent()) {
            Slog.w(TAG, "Not setting time since no alarm driver is available.");
            return false;
        }

        synchronized (mLock) {
            final long currentTimeMillis = mInjector.getCurrentTimeMillis();
            mInjector.setKernelTime(millis);
            final TimeZone timeZone = TimeZone.getDefault();
            final int currentTzOffset = timeZone.getOffset(currentTimeMillis);
            final int newTzOffset = timeZone.getOffset(millis);
            if (currentTzOffset != newTzOffset) {
                Slog.i(TAG, "Timezone offset has changed, updating kernel timezone");
                mInjector.setKernelTimezone(-(newTzOffset / 60000));
            }
            // The native implementation of setKernelTime can return -1 even when the kernel
            // time was set correctly, so assume setting kernel time was successful and always
            // return true.
            return true;
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
            int gmtOffset = zone.getOffset(mInjector.getCurrentTimeMillis());
            mInjector.setKernelTimezone(-(gmtOffset / 60000));
        }

        TimeZone.setDefault(null);

        if (timeZoneWasChanged) {
            // Don't wait for broadcasts to update our midnight alarm
            mClockReceiver.scheduleDateChangedEvent();

            // And now let everyone else know
            Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                    | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
            intent.putExtra("time-zone", zone.getID());
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    void removeImpl(PendingIntent operation, IAlarmListener listener) {
        synchronized (mLock) {
            removeLocked(operation, listener);
        }
    }

    void setImpl(int type, long triggerAtTime, long windowLength, long interval,
            PendingIntent operation, IAlarmListener directReceiver, String listenerTag,
            int flags, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock,
            int callingUid, String callingPackage) {
        // must be *either* PendingIntent or AlarmReceiver, but not both
        if ((operation == null && directReceiver == null)
                || (operation != null && directReceiver != null)) {
            Slog.w(TAG, "Alarms must either supply a PendingIntent or an AlarmReceiver");
            // NB: previous releases failed silently here, so we are continuing to do the same
            // rather than throw an IllegalArgumentException.
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
        // seconds when the API expects milliseconds, or apps trying shenanigans
        // around intentional period overflow, etc.
        final long minInterval = mConstants.MIN_INTERVAL;
        if (interval > 0 && interval < minInterval) {
            Slog.w(TAG, "Suspiciously short interval " + interval
                    + " millis; expanding to " + (minInterval/1000)
                    + " seconds");
            interval = minInterval;
        } else if (interval > mConstants.MAX_INTERVAL) {
            Slog.w(TAG, "Suspiciously long interval " + interval
                    + " millis; clamping");
            interval = mConstants.MAX_INTERVAL;
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

        final long nowElapsed = mInjector.getElapsedRealtime();
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
                    interval, operation, directReceiver, listenerTag, flags, true, workSource,
                    alarmClock, callingUid, callingPackage);
        }
    }

    private void setImplLocked(int type, long when, long whenElapsed, long windowLength,
            long maxWhen, long interval, PendingIntent operation, IAlarmListener directReceiver,
            String listenerTag, int flags, boolean doValidate, WorkSource workSource,
            AlarmManager.AlarmClockInfo alarmClock, int callingUid, String callingPackage) {
        Alarm a = new Alarm(type, when, whenElapsed, windowLength, maxWhen, interval,
                operation, directReceiver, listenerTag, workSource, flags, alarmClock,
                callingUid, callingPackage);
        try {
            if (ActivityManager.getService().isAppStartModeDisabled(callingUid, callingPackage)) {
                Slog.w(TAG, "Not setting alarm from " + callingUid + ":" + a
                        + " -- package not allowed to start");
                return;
            }
        } catch (RemoteException e) {
        }
        removeLocked(operation, directReceiver);
        setImplLocked(a, false, doValidate);
    }

    /**
     * Returns the maximum alarms that an app in the specified bucket can receive in a rolling time
     * window given by {@link Constants#APP_STANDBY_WINDOW}
     */
    @VisibleForTesting
    int getQuotaForBucketLocked(int bucket) {
        final int index;
        if (bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE) {
            index = ACTIVE_INDEX;
        } else if (bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
            index = WORKING_INDEX;
        } else if (bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT) {
            index = FREQUENT_INDEX;
        } else if (bucket < UsageStatsManager.STANDBY_BUCKET_NEVER) {
            index = RARE_INDEX;
        } else {
            index = NEVER_INDEX;
        }
        return mConstants.APP_STANDBY_QUOTAS[index];
    }

    /**
     * Return the minimum time that should elapse before an app in the specified bucket
     * can receive alarms again
     */
    @VisibleForTesting
    long getMinDelayForBucketLocked(int bucket) {
        // UsageStats bucket values are treated as floors of their behavioral range.
        // In other words, a bucket value between WORKING and ACTIVE is treated as
        // WORKING, not as ACTIVE.  The ACTIVE and NEVER bucket apply only at specific
        // values.
        final int index;

        if (bucket == UsageStatsManager.STANDBY_BUCKET_NEVER) index = NEVER_INDEX;
        else if (bucket > UsageStatsManager.STANDBY_BUCKET_FREQUENT) index = RARE_INDEX;
        else if (bucket > UsageStatsManager.STANDBY_BUCKET_WORKING_SET) index = FREQUENT_INDEX;
        else if (bucket > UsageStatsManager.STANDBY_BUCKET_ACTIVE) index = WORKING_INDEX;
        else index = ACTIVE_INDEX;

        return mConstants.APP_STANDBY_MIN_DELAYS[index];
    }

    /**
     * Adjusts the alarm delivery time based on the current app standby bucket.
     * @param alarm The alarm to adjust
     * @return true if the alarm delivery time was updated.
     */
    private boolean adjustDeliveryTimeBasedOnBucketLocked(Alarm alarm) {
        if (isExemptFromAppStandby(alarm)) {
            return false;
        }
        if (mAppStandbyParole) {
            if (alarm.whenElapsed > alarm.expectedWhenElapsed) {
                // We did defer this alarm earlier, restore original requirements
                alarm.whenElapsed = alarm.expectedWhenElapsed;
                alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
                return true;
            }
            return false;
        }
        final long oldWhenElapsed = alarm.whenElapsed;
        final long oldMaxWhenElapsed = alarm.maxWhenElapsed;

        final String sourcePackage = alarm.sourcePackage;
        final int sourceUserId = UserHandle.getUserId(alarm.creatorUid);
        final int standbyBucket = mUsageStatsManagerInternal.getAppStandbyBucket(
                sourcePackage, sourceUserId, mInjector.getElapsedRealtime());

        if (mConstants.APP_STANDBY_QUOTAS_ENABLED) {
            // Quota deferring implementation:
            final int wakeupsInWindow = mAppWakeupHistory.getTotalWakeupsInWindow(sourcePackage,
                    sourceUserId);
            final int quotaForBucket = getQuotaForBucketLocked(standbyBucket);
            boolean deferred = false;
            if (wakeupsInWindow >= quotaForBucket) {
                final long minElapsed;
                if (quotaForBucket <= 0) {
                    // Just keep deferring for a day till the quota changes
                    minElapsed = mInjector.getElapsedRealtime() + MILLIS_IN_DAY;
                } else {
                    // Suppose the quota for window was q, and the qth last delivery time for this
                    // package was t(q) then the next delivery must be after t(q) + <window_size>
                    final long t = mAppWakeupHistory.getLastWakeupForPackage(sourcePackage,
                            sourceUserId, quotaForBucket);
                    minElapsed = t + 1 + mConstants.APP_STANDBY_WINDOW;
                }
                if (alarm.expectedWhenElapsed < minElapsed) {
                    alarm.whenElapsed = alarm.maxWhenElapsed = minElapsed;
                    deferred = true;
                }
            }
            if (!deferred) {
                // Restore original requirements in case they were changed earlier.
                alarm.whenElapsed = alarm.expectedWhenElapsed;
                alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
            }
        } else {
            // Minimum delay deferring implementation:
            final long lastElapsed = mAppWakeupHistory.getLastWakeupForPackage(sourcePackage,
                    sourceUserId, 1);
            if (lastElapsed > 0) {
                final long minElapsed = lastElapsed + getMinDelayForBucketLocked(standbyBucket);
                if (alarm.expectedWhenElapsed < minElapsed) {
                    alarm.whenElapsed = alarm.maxWhenElapsed = minElapsed;
                } else {
                    // app is now eligible to run alarms at the originally requested window.
                    // Restore original requirements in case they were changed earlier.
                    alarm.whenElapsed = alarm.expectedWhenElapsed;
                    alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
                }
            }
        }
        return (oldWhenElapsed != alarm.whenElapsed || oldMaxWhenElapsed != alarm.maxWhenElapsed);
    }

    private void setImplLocked(Alarm a, boolean rebatching, boolean doValidate) {
        if ((a.flags&AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            // This is a special alarm that will put the system into idle until it goes off.
            // The caller has given the time they want this to happen at, however we need
            // to pull that earlier if there are existing alarms that have requested to
            // bring us out of idle at an earlier time.
            if (mNextWakeFromIdle != null && a.whenElapsed > mNextWakeFromIdle.whenElapsed) {
                a.when = a.whenElapsed = a.maxWhenElapsed = mNextWakeFromIdle.whenElapsed;
            }
            // Add fuzz to make the alarm go off some time before the actual desired time.
            final long nowElapsed = mInjector.getElapsedRealtime();
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
        if (RECORD_DEVICE_IDLE_ALARMS) {
            if ((a.flags & AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0) {
                IdleDispatchEntry ent = new IdleDispatchEntry();
                ent.uid = a.uid;
                ent.pkg = a.operation.getCreatorPackage();
                ent.tag = a.operation.getTag("");
                ent.op = "SET";
                ent.elapsedRealtime = mInjector.getElapsedRealtime();
                ent.argRealtime = a.whenElapsed;
                mAllowWhileIdleDispatches.add(ent);
            }
        }
        adjustDeliveryTimeBasedOnBucketLocked(a);
        insertAndBatchAlarmLocked(a);

        if (a.alarmClock != null) {
            mNextAlarmClockMayChange = true;
        }

        boolean needRebatch = false;

        if ((a.flags&AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            if (RECORD_DEVICE_IDLE_ALARMS) {
                if (mPendingIdleUntil == null) {
                    IdleDispatchEntry ent = new IdleDispatchEntry();
                    ent.uid = 0;
                    ent.pkg = "START IDLE";
                    ent.elapsedRealtime = mInjector.getElapsedRealtime();
                    mAllowWhileIdleDispatches.add(ent);
                }
            }
            if ((mPendingIdleUntil != a) && (mPendingIdleUntil != null)) {
                Slog.wtfStack(TAG, "setImplLocked: idle until changed from " + mPendingIdleUntil
                        + " to " + a);
            }

            mPendingIdleUntil = a;
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

    /**
     * System-process internal API
     */
    private final class LocalService implements AlarmManagerInternal {
        @Override
        public boolean isIdling() {
            return isIdlingImpl();
        }

        @Override
        public void removeAlarmsForUid(int uid) {
            synchronized (mLock) {
                removeLocked(uid);
            }
        }

        @Override
        public void registerInFlightListener(InFlightListener callback) {
            synchronized (mLock) {
                mInFlightListeners.add(callback);
            }
        }
    }

    /**
     * Public-facing binder interface
     */
    private final IBinder mService = new IAlarmManager.Stub() {
        @Override
        public void set(String callingPackage,
                int type, long triggerAtTime, long windowLength, long interval, int flags,
                PendingIntent operation, IAlarmListener directReceiver, String listenerTag,
                WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock) {
            final int callingUid = Binder.getCallingUid();

            // make sure the caller is not lying about which package should be blamed for
            // wakelock time spent in alarm delivery
            mAppOps.checkPackage(callingUid, callingPackage);

            // Repeating alarms must use PendingIntent, not direct listener
            if (interval != 0) {
                if (directReceiver != null) {
                    throw new IllegalArgumentException("Repeating alarms cannot use AlarmReceivers");
                }
            }

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

            // If this is an exact time alarm, then it can't be batched with other alarms.
            if (windowLength == AlarmManager.WINDOW_EXACT) {
                flags |= AlarmManager.FLAG_STANDALONE;
            }

            // If this alarm is for an alarm clock, then it must be standalone and we will
            // use it to wake early from idle if needed.
            if (alarmClock != null) {
                flags |= AlarmManager.FLAG_WAKE_FROM_IDLE | AlarmManager.FLAG_STANDALONE;

            // If the caller is a core system component or on the user's whitelist, and not calling
            // to do work on behalf of someone else, then always set ALLOW_WHILE_IDLE_UNRESTRICTED.
            // This means we will allow these alarms to go off as normal even while idle, with no
            // timing restrictions.
            } else if (workSource == null && (callingUid < Process.FIRST_APPLICATION_UID
                    || UserHandle.isSameApp(callingUid, mSystemUiUid)
                    || ((mAppStateTracker != null)
                        && mAppStateTracker.isUidPowerSaveUserWhitelisted(callingUid)))) {
                flags |= AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
                flags &= ~AlarmManager.FLAG_ALLOW_WHILE_IDLE;
            }

            setImpl(type, triggerAtTime, windowLength, interval, operation, directReceiver,
                    listenerTag, flags, workSource, alarmClock, callingUid, callingPackage);
        }

        @Override
        public boolean setTime(long millis) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME",
                    "setTime");

            return setTimeImpl(millis);
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
        public void remove(PendingIntent operation, IAlarmListener listener) {
            if (operation == null && listener == null) {
                Slog.w(TAG, "remove() with no intent or listener");
                return;
            }

            synchronized (mLock) {
                removeLocked(operation, listener);
            }
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
        public long currentNetworkTimeMillis() {
            final NtpTrustedTime time = NtpTrustedTime.getInstance(getContext());
            if (time.hasCache()) {
                return time.currentTimeMillis();
            } else {
                throw new ParcelableException(new DateTimeException("Missing NTP fix"));
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

            if (args.length > 0 && "--proto".equals(args[0])) {
                dumpProto(fd);
            } else {
                dumpImpl(pw);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new ShellCmd()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current Alarm Manager state:");
            mConstants.dump(pw, "  ");
            pw.println();

            if (mAppStateTracker != null) {
                mAppStateTracker.dump(pw, "  ");
                pw.println();
            }

            pw.println("  App Standby Parole: " + mAppStandbyParole);
            pw.println();

            final long nowELAPSED = mInjector.getElapsedRealtime();
            final long nowUPTIME = SystemClock.uptimeMillis();
            final long nowRTC = mInjector.getCurrentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

            pw.print("  nowRTC="); pw.print(nowRTC);
            pw.print("="); pw.print(sdf.format(new Date(nowRTC)));
            pw.print(" nowELAPSED="); pw.print(nowELAPSED);
            pw.println();
            pw.print("  mLastTimeChangeClockTime="); pw.print(mLastTimeChangeClockTime);
            pw.print("="); pw.println(sdf.format(new Date(mLastTimeChangeClockTime)));
            pw.print("  mLastTimeChangeRealtime="); pw.println(mLastTimeChangeRealtime);
            pw.print("  mLastTickReceived="); pw.println(sdf.format(new Date(mLastTickReceived)));
            pw.print("  mLastTickSet="); pw.println(sdf.format(new Date(mLastTickSet)));
            pw.print("  mLastTickAdded="); pw.println(sdf.format(new Date(mLastTickAdded)));
            pw.print("  mLastTickRemoved="); pw.println(sdf.format(new Date(mLastTickRemoved)));

            if (RECORD_ALARMS_IN_HISTORY) {
                pw.println();
                pw.println("  Recent TIME_TICK history:");
                int i = mNextTickHistory;
                do {
                    i--;
                    if (i < 0) i = TICK_HISTORY_DEPTH - 1;
                    final long time = mTickHistory[i];
                    pw.print("    ");
                    pw.println((time > 0)
                            ? sdf.format(new Date(nowRTC - (nowELAPSED - time)))
                            : "-");
                } while (i != mNextTickHistory);
                pw.println();
            }

            SystemServiceManager ssm = LocalServices.getService(SystemServiceManager.class);
            if (ssm != null) {
                pw.println();
                pw.print("  RuntimeStarted=");
                pw.print(sdf.format(
                        new Date(nowRTC - nowELAPSED + ssm.getRuntimeStartElapsedTime())));
                if (ssm.isRuntimeRestarted()) {
                    pw.print("  (Runtime restarted)");
                }
                pw.println();
                pw.print("  Runtime uptime (elapsed): ");
                TimeUtils.formatDuration(nowELAPSED, ssm.getRuntimeStartElapsedTime(), pw);
                pw.println();
                pw.print("  Runtime uptime (uptime): ");
                TimeUtils.formatDuration(nowUPTIME, ssm.getRuntimeStartUptime(), pw);
                pw.println();
            }

            pw.println();
            if (!mInteractive) {
                pw.print("  Time since non-interactive: ");
                TimeUtils.formatDuration(nowELAPSED - mNonInteractiveStartTime, pw);
                pw.println();
            }
            pw.print("  Max wakeup delay: ");
            TimeUtils.formatDuration(currentNonWakeupFuzzLocked(nowELAPSED), pw);
            pw.println();
            pw.print("  Time since last dispatch: ");
            TimeUtils.formatDuration(nowELAPSED - mLastAlarmDeliveryTime, pw);
            pw.println();
            pw.print("  Next non-wakeup delivery time: ");
            TimeUtils.formatDuration(mNextNonWakeupDeliveryTime, nowELAPSED, pw);
            pw.println();

            long nextWakeupRTC = mNextWakeup + (nowRTC - nowELAPSED);
            long nextNonWakeupRTC = mNextNonWakeup + (nowRTC - nowELAPSED);
            pw.print("  Next non-wakeup alarm: ");
                    TimeUtils.formatDuration(mNextNonWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.print(mNextNonWakeup);
                    pw.print(" = "); pw.println(sdf.format(new Date(nextNonWakeupRTC)));
            pw.print("    set at "); TimeUtils.formatDuration(mNextNonWakeUpSetAt, nowELAPSED, pw);
            pw.println();
            pw.print("  Next wakeup alarm: "); TimeUtils.formatDuration(mNextWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.print(mNextWakeup);
                    pw.print(" = "); pw.println(sdf.format(new Date(nextWakeupRTC)));
            pw.print("    set at "); TimeUtils.formatDuration(mNextWakeUpSetAt, nowELAPSED, pw);
                    pw.println();

            pw.print("  Next kernel non-wakeup alarm: ");
            TimeUtils.formatDuration(mInjector.getNextAlarm(ELAPSED_REALTIME), pw);
            pw.println();
            pw.print("  Next kernel wakeup alarm: ");
            TimeUtils.formatDuration(mInjector.getNextAlarm(ELAPSED_REALTIME_WAKEUP), pw);
            pw.println();

            pw.print("  Last wakeup: "); TimeUtils.formatDuration(mLastWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.println(mLastWakeup);
            pw.print("  Last trigger: "); TimeUtils.formatDuration(mLastTrigger, nowELAPSED, pw);
                    pw.print(" = "); pw.println(mLastTrigger);
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
            pw.println();
            pw.println("  Pending user blocked background alarms: ");
            boolean blocked = false;
            for (int i = 0; i < mPendingBackgroundAlarms.size(); i++) {
                final ArrayList<Alarm> blockedAlarms = mPendingBackgroundAlarms.valueAt(i);
                if (blockedAlarms != null && blockedAlarms.size() > 0) {
                    blocked = true;
                    dumpAlarmList(pw, blockedAlarms, "    ", nowELAPSED, nowRTC, sdf);
                }
            }
            if (!blocked) {
                pw.println("    none");
            }

            mAppWakeupHistory.dump(pw, "  ", nowELAPSED);

            if (mPendingIdleUntil != null || mPendingWhileIdleAlarms.size() > 0) {
                pw.println();
                pw.println("    Idle mode state:");
                pw.print("      Idling until: ");
                if (mPendingIdleUntil != null) {
                    pw.println(mPendingIdleUntil);
                    mPendingIdleUntil.dump(pw, "        ", nowELAPSED, nowRTC, sdf);
                } else {
                    pw.println("null");
                }
                pw.println("      Pending alarms:");
                dumpAlarmList(pw, mPendingWhileIdleAlarms, "      ", nowELAPSED, nowRTC, sdf);
            }
            if (mNextWakeFromIdle != null) {
                pw.println();
                pw.print("  Next wake from idle: "); pw.println(mNextWakeFromIdle);
                mNextWakeFromIdle.dump(pw, "    ", nowELAPSED, nowRTC, sdf);
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
            pw.print("  PendingIntent send count: "); pw.println(mSendCount);
            pw.print("  PendingIntent finish count: "); pw.println(mSendFinishCount);
            pw.print("  Listener send count: "); pw.println(mListenerCount);
            pw.print("  Listener finish count: "); pw.println(mListenerFinishCount);
            pw.println();

            if (mInFlight.size() > 0) {
                pw.println("Outstanding deliveries:");
                for (int i = 0; i < mInFlight.size(); i++) {
                    pw.print("   #"); pw.print(i); pw.print(": ");
                    pw.println(mInFlight.get(i));
                }
                pw.println();
            }

            if (mLastAllowWhileIdleDispatch.size() > 0) {
                pw.println("  Last allow while idle dispatch times:");
                for (int i=0; i<mLastAllowWhileIdleDispatch.size(); i++) {
                    pw.print("    UID ");
                    final int uid = mLastAllowWhileIdleDispatch.keyAt(i);
                    UserHandle.formatUid(pw, uid);
                    pw.print(": ");
                    final long lastTime = mLastAllowWhileIdleDispatch.valueAt(i);
                    TimeUtils.formatDuration(lastTime, nowELAPSED, pw);

                    final long minInterval = getWhileIdleMinIntervalLocked(uid);
                    pw.print("  Next allowed:");
                    TimeUtils.formatDuration(lastTime + minInterval, nowELAPSED, pw);
                    pw.print(" (");
                    TimeUtils.formatDuration(minInterval, 0, pw);
                    pw.print(")");

                    pw.println();
                }
            }

            pw.print("  mUseAllowWhileIdleShortTime: [");
            for (int i = 0; i < mUseAllowWhileIdleShortTime.size(); i++) {
                if (mUseAllowWhileIdleShortTime.valueAt(i)) {
                    UserHandle.formatUid(pw, mUseAllowWhileIdleShortTime.keyAt(i));
                    pw.print(" ");
                }
            }
            pw.println("]");
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
            // Get the top 10 FilterStats, ordered by aggregateTime.
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
            pw.println();
            mStatLogger.dump(pw, "  ");

            if (RECORD_DEVICE_IDLE_ALARMS) {
                pw.println();
                pw.println("  Allow while idle dispatches:");
                for (int i = 0; i < mAllowWhileIdleDispatches.size(); i++) {
                    IdleDispatchEntry ent = mAllowWhileIdleDispatches.get(i);
                    pw.print("    ");
                    TimeUtils.formatDuration(ent.elapsedRealtime, nowELAPSED, pw);
                    pw.print(": ");
                    UserHandle.formatUid(pw, ent.uid);
                    pw.print(":");
                    pw.println(ent.pkg);
                    if (ent.op != null) {
                        pw.print("      ");
                        pw.print(ent.op);
                        pw.print(" / ");
                        pw.print(ent.tag);
                        if (ent.argRealtime != 0) {
                            pw.print(" (");
                            TimeUtils.formatDuration(ent.argRealtime, nowELAPSED, pw);
                            pw.print(")");
                        }
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

    void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            final long nowRTC = mInjector.getCurrentTimeMillis();
            final long nowElapsed = mInjector.getElapsedRealtime();
            proto.write(AlarmManagerServiceDumpProto.CURRENT_TIME, nowRTC);
            proto.write(AlarmManagerServiceDumpProto.ELAPSED_REALTIME, nowElapsed);
            proto.write(AlarmManagerServiceDumpProto.LAST_TIME_CHANGE_CLOCK_TIME,
                    mLastTimeChangeClockTime);
            proto.write(AlarmManagerServiceDumpProto.LAST_TIME_CHANGE_REALTIME,
                    mLastTimeChangeRealtime);

            mConstants.dumpProto(proto, AlarmManagerServiceDumpProto.SETTINGS);

            if (mAppStateTracker != null) {
                mAppStateTracker.dumpProto(proto,
                        AlarmManagerServiceDumpProto.FORCE_APP_STANDBY_TRACKER);
            }

            proto.write(AlarmManagerServiceDumpProto.IS_INTERACTIVE, mInteractive);
            if (!mInteractive) {
                // Durations
                proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_NON_INTERACTIVE_MS,
                        nowElapsed - mNonInteractiveStartTime);
                proto.write(AlarmManagerServiceDumpProto.MAX_WAKEUP_DELAY_MS,
                        currentNonWakeupFuzzLocked(nowElapsed));
                proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_LAST_DISPATCH_MS,
                        nowElapsed - mLastAlarmDeliveryTime);
                proto.write(AlarmManagerServiceDumpProto.TIME_UNTIL_NEXT_NON_WAKEUP_DELIVERY_MS,
                        nowElapsed - mNextNonWakeupDeliveryTime);
            }

            proto.write(AlarmManagerServiceDumpProto.TIME_UNTIL_NEXT_NON_WAKEUP_ALARM_MS,
                    mNextNonWakeup - nowElapsed);
            proto.write(AlarmManagerServiceDumpProto.TIME_UNTIL_NEXT_WAKEUP_MS,
                    mNextWakeup - nowElapsed);
            proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_LAST_WAKEUP_MS,
                    nowElapsed - mLastWakeup);
            proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_LAST_WAKEUP_SET_MS,
                    nowElapsed - mNextWakeUpSetAt);
            proto.write(AlarmManagerServiceDumpProto.TIME_CHANGE_EVENT_COUNT, mNumTimeChanged);

            final TreeSet<Integer> users = new TreeSet<>();
            final int nextAlarmClockForUserSize = mNextAlarmClockForUser.size();
            for (int i = 0; i < nextAlarmClockForUserSize; i++) {
                users.add(mNextAlarmClockForUser.keyAt(i));
            }
            final int pendingSendNextAlarmClockChangedForUserSize =
                    mPendingSendNextAlarmClockChangedForUser.size();
            for (int i = 0; i < pendingSendNextAlarmClockChangedForUserSize; i++) {
                users.add(mPendingSendNextAlarmClockChangedForUser.keyAt(i));
            }
            for (int user : users) {
                final AlarmManager.AlarmClockInfo next = mNextAlarmClockForUser.get(user);
                final long time = next != null ? next.getTriggerTime() : 0;
                final boolean pendingSend = mPendingSendNextAlarmClockChangedForUser.get(user);
                final long aToken = proto.start(AlarmManagerServiceDumpProto.NEXT_ALARM_CLOCK_METADATA);
                proto.write(AlarmClockMetadataProto.USER, user);
                proto.write(AlarmClockMetadataProto.IS_PENDING_SEND, pendingSend);
                proto.write(AlarmClockMetadataProto.TRIGGER_TIME_MS, time);
                proto.end(aToken);
            }
            for (Batch b : mAlarmBatches) {
                b.writeToProto(proto, AlarmManagerServiceDumpProto.PENDING_ALARM_BATCHES,
                        nowElapsed, nowRTC);
            }
            for (int i = 0; i < mPendingBackgroundAlarms.size(); i++) {
                final ArrayList<Alarm> blockedAlarms = mPendingBackgroundAlarms.valueAt(i);
                if (blockedAlarms != null) {
                    for (Alarm a : blockedAlarms) {
                        a.writeToProto(proto,
                                AlarmManagerServiceDumpProto.PENDING_USER_BLOCKED_BACKGROUND_ALARMS,
                                nowElapsed, nowRTC);
                    }
                }
            }
            if (mPendingIdleUntil != null) {
                mPendingIdleUntil.writeToProto(
                        proto, AlarmManagerServiceDumpProto.PENDING_IDLE_UNTIL, nowElapsed, nowRTC);
            }
            for (Alarm a : mPendingWhileIdleAlarms) {
                a.writeToProto(proto, AlarmManagerServiceDumpProto.PENDING_WHILE_IDLE_ALARMS,
                        nowElapsed, nowRTC);
            }
            if (mNextWakeFromIdle != null) {
                mNextWakeFromIdle.writeToProto(proto, AlarmManagerServiceDumpProto.NEXT_WAKE_FROM_IDLE,
                        nowElapsed, nowRTC);
            }

            for (Alarm a : mPendingNonWakeupAlarms) {
                a.writeToProto(proto, AlarmManagerServiceDumpProto.PAST_DUE_NON_WAKEUP_ALARMS,
                        nowElapsed, nowRTC);
            }

            proto.write(AlarmManagerServiceDumpProto.DELAYED_ALARM_COUNT, mNumDelayedAlarms);
            proto.write(AlarmManagerServiceDumpProto.TOTAL_DELAY_TIME_MS, mTotalDelayTime);
            proto.write(AlarmManagerServiceDumpProto.MAX_DELAY_DURATION_MS, mMaxDelayTime);
            proto.write(AlarmManagerServiceDumpProto.MAX_NON_INTERACTIVE_DURATION_MS,
                    mNonInteractiveTime);

            proto.write(AlarmManagerServiceDumpProto.BROADCAST_REF_COUNT, mBroadcastRefCount);
            proto.write(AlarmManagerServiceDumpProto.PENDING_INTENT_SEND_COUNT, mSendCount);
            proto.write(AlarmManagerServiceDumpProto.PENDING_INTENT_FINISH_COUNT, mSendFinishCount);
            proto.write(AlarmManagerServiceDumpProto.LISTENER_SEND_COUNT, mListenerCount);
            proto.write(AlarmManagerServiceDumpProto.LISTENER_FINISH_COUNT, mListenerFinishCount);

            for (InFlight f : mInFlight) {
                f.writeToProto(proto, AlarmManagerServiceDumpProto.OUTSTANDING_DELIVERIES);
            }

            for (int i = 0; i < mLastAllowWhileIdleDispatch.size(); ++i) {
                final long token = proto.start(
                        AlarmManagerServiceDumpProto.LAST_ALLOW_WHILE_IDLE_DISPATCH_TIMES);
                final int uid = mLastAllowWhileIdleDispatch.keyAt(i);
                final long lastTime = mLastAllowWhileIdleDispatch.valueAt(i);

                proto.write(AlarmManagerServiceDumpProto.LastAllowWhileIdleDispatch.UID, uid);
                proto.write(AlarmManagerServiceDumpProto.LastAllowWhileIdleDispatch.TIME_MS, lastTime);
                proto.write(AlarmManagerServiceDumpProto.LastAllowWhileIdleDispatch.NEXT_ALLOWED_MS,
                        lastTime + getWhileIdleMinIntervalLocked(uid));
                proto.end(token);
            }

            for (int i = 0; i < mUseAllowWhileIdleShortTime.size(); i++) {
                if (mUseAllowWhileIdleShortTime.valueAt(i)) {
                    proto.write(AlarmManagerServiceDumpProto.USE_ALLOW_WHILE_IDLE_SHORT_TIME,
                            mUseAllowWhileIdleShortTime.keyAt(i));
                }
            }

            mLog.writeToProto(proto, AlarmManagerServiceDumpProto.RECENT_PROBLEMS);

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
            // Get the top 10 FilterStats, ordered by aggregateTime.
            for (int iu = 0; iu < mBroadcastStats.size(); ++iu) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip = 0; ip < uidStats.size(); ++ip) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    for (int is = 0; is < bs.filterStats.size(); ++is) {
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
            for (int i = 0; i < len; ++i) {
                final long token = proto.start(AlarmManagerServiceDumpProto.TOP_ALARMS);
                FilterStats fs = topFilters[i];

                proto.write(AlarmManagerServiceDumpProto.TopAlarm.UID, fs.mBroadcastStats.mUid);
                proto.write(AlarmManagerServiceDumpProto.TopAlarm.PACKAGE_NAME,
                        fs.mBroadcastStats.mPackageName);
                fs.writeToProto(proto, AlarmManagerServiceDumpProto.TopAlarm.FILTER);

                proto.end(token);
            }

            final ArrayList<FilterStats> tmpFilters = new ArrayList<FilterStats>();
            for (int iu = 0; iu < mBroadcastStats.size(); ++iu) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip = 0; ip < uidStats.size(); ++ip) {
                    final long token = proto.start(AlarmManagerServiceDumpProto.ALARM_STATS);

                    BroadcastStats bs = uidStats.valueAt(ip);
                    bs.writeToProto(proto, AlarmManagerServiceDumpProto.AlarmStat.BROADCAST);

                    // uidStats is an ArrayMap, which we can't sort.
                    tmpFilters.clear();
                    for (int is = 0; is < bs.filterStats.size(); ++is) {
                        tmpFilters.add(bs.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    for (FilterStats fs : tmpFilters) {
                        fs.writeToProto(proto, AlarmManagerServiceDumpProto.AlarmStat.FILTERS);
                    }

                    proto.end(token);
                }
            }

            if (RECORD_DEVICE_IDLE_ALARMS) {
                for (int i = 0; i < mAllowWhileIdleDispatches.size(); i++) {
                    IdleDispatchEntry ent = mAllowWhileIdleDispatches.get(i);
                    final long token = proto.start(
                            AlarmManagerServiceDumpProto.ALLOW_WHILE_IDLE_DISPATCHES);

                    proto.write(IdleDispatchEntryProto.UID, ent.uid);
                    proto.write(IdleDispatchEntryProto.PKG, ent.pkg);
                    proto.write(IdleDispatchEntryProto.TAG, ent.tag);
                    proto.write(IdleDispatchEntryProto.OP, ent.op);
                    proto.write(IdleDispatchEntryProto.ENTRY_CREATION_REALTIME,
                            ent.elapsedRealtime);
                    proto.write(IdleDispatchEntryProto.ARG_REALTIME, ent.argRealtime);

                    proto.end(token);
                }
            }

            if (WAKEUP_STATS) {
                for (WakeupEvent event : mRecentWakeups) {
                    final long token = proto.start(AlarmManagerServiceDumpProto.RECENT_WAKEUP_HISTORY);
                    proto.write(WakeupEventProto.UID, event.uid);
                    proto.write(WakeupEventProto.ACTION, event.action);
                    proto.write(WakeupEventProto.WHEN, event.when);
                    proto.end(token);
                }
            }
        }

        proto.flush();
    }

    private void logBatchesLocked(SimpleDateFormat sdf) {
        ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
        PrintWriter pw = new PrintWriter(bs);
        final long nowRTC = mInjector.getCurrentTimeMillis();
        final long nowELAPSED = mInjector.getElapsedRealtime();
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

    private boolean isIdlingImpl() {
        synchronized (mLock) {
            return mPendingIdleUntil != null;
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
                    AlarmManager.AlarmClockInfo current = mNextAlarmClockForUser.get(userId);

                    if (DEBUG_ALARM_CLOCK) {
                        Log.v(TAG, "Found AlarmClockInfo " + a.alarmClock + " at " +
                                formatNextAlarm(getContext(), a.alarmClock, userId) +
                                " for user " + userId);
                    }

                    // Alarms and batches are sorted by time, no need to compare times here.
                    if (nextForUser.get(userId) == null) {
                        nextForUser.put(userId, a.alarmClock);
                    } else if (a.alarmClock.equals(current)
                            && current.getTriggerTime() <= nextForUser.get(userId).getTriggerTime()) {
                        // same/earlier time and it's the one we cited before, so stick with it
                        nextForUser.put(userId, current);
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

    /**
     * If the last time AlarmThread woke up precedes any due wakeup or non-wakeup alarm that we set
     * by more than half a minute, log a wtf.
     */
    private void validateLastAlarmExpiredLocked(long nowElapsed) {
        final StringBuilder errorMsg = new StringBuilder();
        boolean stuck = false;
        if (mNextNonWakeup < (nowElapsed - 10_000) && mLastWakeup < mNextNonWakeup) {
            stuck = true;
            errorMsg.append("[mNextNonWakeup=");
            TimeUtils.formatDuration(mNextNonWakeup - nowElapsed, errorMsg);
            errorMsg.append(" set at ");
            TimeUtils.formatDuration(mNextNonWakeUpSetAt - nowElapsed, errorMsg);
            errorMsg.append(", mLastWakeup=");
            TimeUtils.formatDuration(mLastWakeup - nowElapsed, errorMsg);
            errorMsg.append(", timerfd_gettime=" + mInjector.getNextAlarm(ELAPSED_REALTIME));
            errorMsg.append("];");
        }
        if (mNextWakeup < (nowElapsed - 10_000) && mLastWakeup < mNextWakeup) {
            stuck = true;
            errorMsg.append("[mNextWakeup=");
            TimeUtils.formatDuration(mNextWakeup - nowElapsed, errorMsg);
            errorMsg.append(" set at ");
            TimeUtils.formatDuration(mNextWakeUpSetAt - nowElapsed, errorMsg);
            errorMsg.append(", mLastWakeup=");
            TimeUtils.formatDuration(mLastWakeup - nowElapsed, errorMsg);
            errorMsg.append(", timerfd_gettime="
                    + mInjector.getNextAlarm(ELAPSED_REALTIME_WAKEUP));
            errorMsg.append("];");
        }
        if (stuck) {
            Slog.wtf(TAG, "Alarm delivery stuck: " + errorMsg.toString());
        }
    }

    void rescheduleKernelAlarmsLocked() {
        // Schedule the next upcoming wakeup alarm.  If there is a deliverable batch
        // prior to that which contains no wakeups, we schedule that as well.
        final long nowElapsed = mInjector.getElapsedRealtime();
        validateLastAlarmExpiredLocked(nowElapsed);
        long nextNonWakeup = 0;
        if (mAlarmBatches.size() > 0) {
            final Batch firstWakeup = findFirstWakeupBatchLocked();
            final Batch firstBatch = mAlarmBatches.get(0);
            if (firstWakeup != null) {
                mNextWakeup = firstWakeup.start;
                mNextWakeUpSetAt = nowElapsed;
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
        if (nextNonWakeup != 0) {
            mNextNonWakeup = nextNonWakeup;
            mNextNonWakeUpSetAt = nowElapsed;
            setLocked(ELAPSED_REALTIME, nextNonWakeup);
        }
    }

    void removeLocked(PendingIntent operation, IAlarmListener directReceiver) {
        if (operation == null && directReceiver == null) {
            if (localLOGV) {
                Slog.w(TAG, "requested remove() of null operation",
                        new RuntimeException("here"));
            }
            return;
        }

        boolean didRemove = false;
        final Predicate<Alarm> whichAlarms = (Alarm a) -> a.matches(operation, directReceiver);
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            if (mPendingWhileIdleAlarms.get(i).matches(operation, directReceiver)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.valueAt(i);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).matches(operation, directReceiver)) {
                    // Don't set didRemove, since this doesn't impact the scheduled alarms.
                    alarmsForUid.remove(j);
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(operation) changed bounds; rebatching");
            }
            boolean restorePending = false;
            if (mPendingIdleUntil != null && mPendingIdleUntil.matches(operation, directReceiver)) {
                mPendingIdleUntil = null;
                restorePending = true;
            }
            if (mNextWakeFromIdle != null && mNextWakeFromIdle.matches(operation, directReceiver)) {
                mNextWakeFromIdle = null;
            }
            rebatchAllAlarmsLocked(true);
            if (restorePending) {
                restorePendingWhileIdleAlarmsLocked();
            }
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(final int uid) {
        if (uid == Process.SYSTEM_UID) {
            Slog.wtf(TAG, "removeLocked: Shouldn't for UID=" + uid);
            return;
        }
        boolean didRemove = false;
        final Predicate<Alarm> whichAlarms = (Alarm a) -> a.uid == uid;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.uid == uid) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i --) {
            final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.valueAt(i);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).uid == uid) {
                    alarmsForUid.remove(j);
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(uid) changed bounds; rebatching");
            }
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(final String packageName) {
        if (packageName == null) {
            if (localLOGV) {
                Slog.w(TAG, "requested remove() of null packageName",
                        new RuntimeException("here"));
            }
            return;
        }

        boolean didRemove = false;
        final Predicate<Alarm> whichAlarms = (Alarm a) -> a.matches(packageName);
        final boolean oldHasTick = haveBatchesTimeTickAlarm(mAlarmBatches);
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        final boolean newHasTick = haveBatchesTimeTickAlarm(mAlarmBatches);
        if (oldHasTick != newHasTick) {
            Slog.wtf(TAG, "removeLocked: hasTick changed from " + oldHasTick + " to " + newHasTick);
        }

        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.matches(packageName)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i --) {
            final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.valueAt(i);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).matches(packageName)) {
                    alarmsForUid.remove(j);
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.removeAt(i);
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

    void removeForStoppedLocked(final int uid) {
        if (uid == Process.SYSTEM_UID) {
            Slog.wtf(TAG, "removeForStoppedLocked: Shouldn't for UID=" + uid);
            return;
        }
        boolean didRemove = false;
        final Predicate<Alarm> whichAlarms = (Alarm a) -> {
            try {
                if (a.uid == uid && ActivityManager.getService().isAppStartModeDisabled(
                        uid, a.packageName)) {
                    return true;
                }
            } catch (RemoteException e) { /* fall through */}
            return false;
        };
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.uid == uid) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            if (mPendingBackgroundAlarms.keyAt(i) == uid) {
                mPendingBackgroundAlarms.removeAt(i);
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
        if (userHandle == UserHandle.USER_SYSTEM) {
            Slog.wtf(TAG, "removeForStoppedLocked: Shouldn't for user=" + userHandle);
            return;
        }
        boolean didRemove = false;
        final Predicate<Alarm> whichAlarms =
                (Alarm a) -> UserHandle.getUserId(a.creatorUid) == userHandle;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mPendingWhileIdleAlarms.get(i).creatorUid)
                    == userHandle) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mPendingBackgroundAlarms.keyAt(i)) == userHandle) {
                mPendingBackgroundAlarms.removeAt(i);
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
            final long nowELAPSED = mInjector.getElapsedRealtime();
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
                // And send a TIME_TICK right now, since it is important to get the UI updated.
                mHandler.post(() ->
                        getContext().sendBroadcastAsUser(mTimeTickIntent, UserHandle.ALL));
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
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.matches(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void setLocked(int type, long when) {
        if (mInjector.isAlarmDriverPresent()) {
            mInjector.setAlarm(type, when);
        } else {
            Message msg = Message.obtain();
            msg.what = AlarmHandler.ALARM_EVENT;

            mHandler.removeMessages(msg.what);
            mHandler.sendMessageAtTime(msg, when);
        }
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
            String prefix, String label, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
        for (int i=list.size()-1; i>=0; i--) {
            Alarm a = list.get(i);
            pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
                    pw.print(": "); pw.println(a);
            a.dump(pw, prefix + "  ", nowELAPSED, nowRTC, sdf);
        }
    }

    private static final String labelForType(int type) {
        switch (type) {
        case RTC: return "RTC";
        case RTC_WAKEUP : return "RTC_WAKEUP";
        case ELAPSED_REALTIME : return "ELAPSED";
        case ELAPSED_REALTIME_WAKEUP: return "ELAPSED_WAKEUP";
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
            a.dump(pw, prefix + "  ", nowELAPSED, nowRTC, sdf);
        }
    }

    private boolean isBackgroundRestricted(Alarm alarm) {
        boolean exemptOnBatterySaver = (alarm.flags & FLAG_ALLOW_WHILE_IDLE) != 0;
        if (alarm.alarmClock != null) {
            // Don't defer alarm clocks
            return false;
        }
        if (alarm.operation != null) {
            if (alarm.operation.isActivity()) {
                // Don't defer starting actual UI
                return false;
            }
            if (alarm.operation.isForegroundService()) {
                // FG service alarms are nearly as important; consult AST policy
                exemptOnBatterySaver = true;
            }
        }
        final String sourcePackage = alarm.sourcePackage;
        final int sourceUid = alarm.creatorUid;
        return (mAppStateTracker != null) &&
                mAppStateTracker.areAlarmsRestricted(sourceUid, sourcePackage,
                        exemptOnBatterySaver);
    }

    private static native long init();
    private static native void close(long nativeData);
    private static native int set(long nativeData, int type, long seconds, long nanoseconds);
    private static native int waitForAlarm(long nativeData);
    private static native int setKernelTime(long nativeData, long millis);
    private static native int setKernelTimezone(long nativeData, int minuteswest);
    private static native long getNextAlarm(long nativeData, int type);

    private long getWhileIdleMinIntervalLocked(int uid) {
        final boolean dozing = mPendingIdleUntil != null;
        final boolean ebs = (mAppStateTracker != null)
                && mAppStateTracker.isForceAllAppsStandbyEnabled();
        if (!dozing && !ebs) {
            return mConstants.ALLOW_WHILE_IDLE_SHORT_TIME;
        }
        if (dozing) {
            return mConstants.ALLOW_WHILE_IDLE_LONG_TIME;
        }
        if (mUseAllowWhileIdleShortTime.get(uid)) {
            // if the last allow-while-idle went off while uid was fg, or the uid
            // recently came into fg, don't block the alarm for long.
            return mConstants.ALLOW_WHILE_IDLE_SHORT_TIME;
        }
        return mConstants.ALLOW_WHILE_IDLE_LONG_TIME;
    }

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
                    // schedule such alarms.  The first such alarm from an app is always delivered.
                    final long lastTime = mLastAllowWhileIdleDispatch.get(alarm.creatorUid, -1);
                    final long minTime = lastTime + getWhileIdleMinIntervalLocked(alarm.creatorUid);
                    if (lastTime >= 0 && nowELAPSED < minTime) {
                        // Whoops, it hasn't been long enough since the last ALLOW_WHILE_IDLE
                        // alarm went off for this app.  Reschedule the alarm to be in the
                        // correct time period.
                        alarm.expectedWhenElapsed = alarm.whenElapsed = minTime;
                        if (alarm.maxWhenElapsed < minTime) {
                            alarm.maxWhenElapsed = minTime;
                        }
                        alarm.expectedMaxWhenElapsed = alarm.maxWhenElapsed;
                        if (RECORD_DEVICE_IDLE_ALARMS) {
                            IdleDispatchEntry ent = new IdleDispatchEntry();
                            ent.uid = alarm.uid;
                            ent.pkg = alarm.operation.getCreatorPackage();
                            ent.tag = alarm.operation.getTag("");
                            ent.op = "RESCHEDULE";
                            ent.elapsedRealtime = nowELAPSED;
                            ent.argRealtime = lastTime;
                            mAllowWhileIdleDispatches.add(ent);
                        }
                        setImplLocked(alarm, true, false);
                        continue;
                    }
                }
                if (isBackgroundRestricted(alarm)) {
                    // Alarms with FLAG_WAKE_FROM_IDLE or mPendingIdleUntil alarm are not deferred
                    if (DEBUG_BG_LIMIT) {
                        Slog.d(TAG, "Deferring alarm " + alarm + " due to user forced app standby");
                    }
                    ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.get(alarm.creatorUid);
                    if (alarmsForUid == null) {
                        alarmsForUid = new ArrayList<>();
                        mPendingBackgroundAlarms.put(alarm.creatorUid, alarmsForUid);
                    }
                    alarmsForUid.add(alarm);
                    continue;
                }

                alarm.count = 1;
                triggerList.add(alarm);
                if ((alarm.flags&AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
                    EventLogTags.writeDeviceIdleWakeFromIdle(mPendingIdleUntil != null ? 1 : 0,
                            alarm.statsTag);
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
                    alarm.count += (nowELAPSED - alarm.expectedWhenElapsed) / alarm.repeatInterval;

                    // Also schedule its next recurrence
                    final long delta = alarm.count * alarm.repeatInterval;
                    final long nextElapsed = alarm.whenElapsed + delta;
                    setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
                            maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
                            alarm.repeatInterval, alarm.operation, null, null, alarm.flags, true,
                            alarm.workSource, alarm.alarmClock, alarm.uid, alarm.packageName);
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
            if (when1 > when2) {
                return 1;
            }
            if (when1 < when2) {
                return -1;
            }
            return 0;
        }
    }

    @VisibleForTesting
    static class Alarm {
        public final int type;
        public final long origWhen;
        public final boolean wakeup;
        public final PendingIntent operation;
        public final IAlarmListener listener;
        public final String listenerTag;
        public final String statsTag;
        public final WorkSource workSource;
        public final int flags;
        public final AlarmManager.AlarmClockInfo alarmClock;
        public final int uid;
        public final int creatorUid;
        public final String packageName;
        public final String sourcePackage;
        public int count;
        public long when;
        public long windowLength;
        public long whenElapsed;    // 'when' in the elapsed time base
        public long maxWhenElapsed; // also in the elapsed time base
        // Expected alarm expiry time before app standby deferring is applied.
        public long expectedWhenElapsed;
        public long expectedMaxWhenElapsed;
        public long repeatInterval;
        public PriorityClass priorityClass;

        public Alarm(int _type, long _when, long _whenElapsed, long _windowLength, long _maxWhen,
                long _interval, PendingIntent _op, IAlarmListener _rec, String _listenerTag,
                WorkSource _ws, int _flags, AlarmManager.AlarmClockInfo _info,
                int _uid, String _pkgName) {
            type = _type;
            origWhen = _when;
            wakeup = _type == AlarmManager.ELAPSED_REALTIME_WAKEUP
                    || _type == AlarmManager.RTC_WAKEUP;
            when = _when;
            whenElapsed = _whenElapsed;
            expectedWhenElapsed = _whenElapsed;
            windowLength = _windowLength;
            maxWhenElapsed = expectedMaxWhenElapsed = clampPositive(_maxWhen);
            repeatInterval = _interval;
            operation = _op;
            listener = _rec;
            listenerTag = _listenerTag;
            statsTag = makeTag(_op, _listenerTag, _type);
            workSource = _ws;
            flags = _flags;
            alarmClock = _info;
            uid = _uid;
            packageName = _pkgName;
            sourcePackage = (operation != null) ? operation.getCreatorPackage() : packageName;
            creatorUid = (operation != null) ? operation.getCreatorUid() : uid;
        }

        public static String makeTag(PendingIntent pi, String tag, int type) {
            final String alarmString = type == ELAPSED_REALTIME_WAKEUP || type == RTC_WAKEUP
                    ? "*walarm*:" : "*alarm*:";
            return (pi != null) ? pi.getTag(alarmString) : (alarmString + tag);
        }

        public WakeupEvent makeWakeupEvent(long nowRTC) {
            return new WakeupEvent(nowRTC, creatorUid,
                    (operation != null)
                        ? operation.getIntent().getAction()
                        : ("<listener>:" + listenerTag));
        }

        // Returns true if either matches
        public boolean matches(PendingIntent pi, IAlarmListener rec) {
            return (operation != null)
                    ? operation.equals(pi)
                    : rec != null && listener.asBinder().equals(rec.asBinder());
        }

        public boolean matches(String packageName) {
            return packageName.equals(sourcePackage);
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
            sb.append(sourcePackage);
            sb.append('}');
            return sb.toString();
        }

        public void dump(PrintWriter pw, String prefix, long nowELAPSED, long nowRTC,
                SimpleDateFormat sdf) {
            final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
            pw.print(prefix); pw.print("tag="); pw.println(statsTag);
            pw.print(prefix); pw.print("type="); pw.print(type);
                    pw.print(" expectedWhenElapsed="); TimeUtils.formatDuration(
                    expectedWhenElapsed, nowELAPSED, pw);
                    pw.print(" expectedMaxWhenElapsed="); TimeUtils.formatDuration(
                    expectedMaxWhenElapsed, nowELAPSED, pw);
                    pw.print(" whenElapsed="); TimeUtils.formatDuration(whenElapsed,
                            nowELAPSED, pw);
                    pw.print(" maxWhenElapsed="); TimeUtils.formatDuration(maxWhenElapsed,
                            nowELAPSED, pw);
                    pw.print(" when=");
                    if (isRtc) {
                        pw.print(sdf.format(new Date(when)));
                    } else {
                        TimeUtils.formatDuration(when, nowELAPSED, pw);
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
            if (listener != null) {
                pw.print(prefix); pw.print("listener="); pw.println(listener.asBinder());
            }
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId, long nowElapsed,
                long nowRTC) {
            final long token = proto.start(fieldId);

            proto.write(AlarmProto.TAG, statsTag);
            proto.write(AlarmProto.TYPE, type);
            proto.write(AlarmProto.TIME_UNTIL_WHEN_ELAPSED_MS, whenElapsed - nowElapsed);
            proto.write(AlarmProto.WINDOW_LENGTH_MS, windowLength);
            proto.write(AlarmProto.REPEAT_INTERVAL_MS, repeatInterval);
            proto.write(AlarmProto.COUNT, count);
            proto.write(AlarmProto.FLAGS, flags);
            if (alarmClock != null) {
                alarmClock.writeToProto(proto, AlarmProto.ALARM_CLOCK);
            }
            if (operation != null) {
                operation.writeToProto(proto, AlarmProto.OPERATION);
            }
            if (listener != null) {
                proto.write(AlarmProto.LISTENER, listener.asBinder().toString());
            }

            proto.end(token);
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
                mRecentWakeups.add(a.makeWakeupEvent(nowRTC));
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
            if (alarm.wakeup) {
              Trace.traceBegin(Trace.TRACE_TAG_POWER, "Dispatch wakeup alarm to " + alarm.packageName);
            } else {
              Trace.traceBegin(Trace.TRACE_TAG_POWER, "Dispatch non-wakeup alarm to " + alarm.packageName);
            }
            try {
                if (localLOGV) {
                    Slog.v(TAG, "sending alarm " + alarm);
                }
                if (RECORD_ALARMS_IN_HISTORY) {
                    ActivityManager.noteAlarmStart(alarm.operation, alarm.workSource, alarm.uid,
                            alarm.statsTag);
                }
                mDeliveryTracker.deliverLocked(alarm, nowELAPSED, allowWhileIdle);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure sending alarm.", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    private boolean isExemptFromAppStandby(Alarm a) {
        return a.alarmClock != null || UserHandle.isCore(a.creatorUid)
                || (a.flags & FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED) != 0;
    }

    @VisibleForTesting
    static class Injector {
        private long mNativeData;
        private Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        void init() {
            mNativeData = AlarmManagerService.init();
        }

        int waitForAlarm() {
            return AlarmManagerService.waitForAlarm(mNativeData);
        }

        boolean isAlarmDriverPresent() {
            return mNativeData != 0;
        }

        void setAlarm(int type, long millis) {
            // The kernel never triggers alarms with negative wakeup times
            // so we ensure they are positive.
            final long alarmSeconds, alarmNanoseconds;
            if (millis < 0) {
                alarmSeconds = 0;
                alarmNanoseconds = 0;
            } else {
                alarmSeconds = millis / 1000;
                alarmNanoseconds = (millis % 1000) * 1000 * 1000;
            }

            final int result = AlarmManagerService.set(mNativeData, type, alarmSeconds,
                    alarmNanoseconds);
            if (result != 0) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                Slog.wtf(TAG, "Unable to set kernel alarm, now=" + nowElapsed
                        + " type=" + type + " @ (" + alarmSeconds + "," + alarmNanoseconds
                        + "), ret = " + result + " = " + Os.strerror(result));
            }
        }

        long getNextAlarm(int type) {
            return AlarmManagerService.getNextAlarm(mNativeData, type);
        }

        void setKernelTimezone(int minutesWest) {
            AlarmManagerService.setKernelTimezone(mNativeData, minutesWest);
        }

        void setKernelTime(long millis) {
            if (mNativeData != 0) {
                AlarmManagerService.setKernelTime(mNativeData, millis);
            }
        }

        void close() {
            AlarmManagerService.close(mNativeData);
        }

        long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        long getCurrentTimeMillis() {
            return System.currentTimeMillis();
        }

        PowerManager.WakeLock getAlarmWakeLock() {
            final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*alarm*");
        }

        int getSystemUiUid() {
            int sysUiUid = -1;
            final PackageManager pm = mContext.getPackageManager();
            try {
                PermissionInfo sysUiPerm = pm.getPermissionInfo(SYSTEM_UI_SELF_PERMISSION, 0);
                ApplicationInfo sysUi = pm.getApplicationInfo(sysUiPerm.packageName, 0);
                if ((sysUi.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                    sysUiUid = sysUi.uid;
                } else {
                    Slog.e(TAG, "SysUI permission " + SYSTEM_UI_SELF_PERMISSION
                            + " defined by non-privileged app " + sysUi.packageName
                            + " - ignoring");
                }
            } catch (NameNotFoundException e) {
            }
            return sysUiUid;
        }

        ClockReceiver getClockReceiver(AlarmManagerService service) {
            return service.new ClockReceiver();
        }
    }

    private class AlarmThread extends Thread
    {
        private int mFalseWakeups;
        private int mWtfThreshold;
        public AlarmThread()
        {
            super("AlarmManager");
            mFalseWakeups = 0;
            mWtfThreshold = 100;
        }

        public void run()
        {
            ArrayList<Alarm> triggerList = new ArrayList<Alarm>();

            while (true)
            {
                int result = mInjector.waitForAlarm();
                final long nowRTC = mInjector.getCurrentTimeMillis();
                final long nowELAPSED = mInjector.getElapsedRealtime();
                synchronized (mLock) {
                    mLastWakeup = nowELAPSED;
                }
                if (result == 0) {
                    Slog.wtf(TAG, "waitForAlarm returned 0, nowRTC = " + nowRTC
                            + ", nowElapsed = " + nowELAPSED);
                }
                triggerList.clear();

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
                    if (lastTimeChangeClockTime == 0 || nowRTC < (expectedClockTime-1000)
                            || nowRTC > (expectedClockTime+1000)) {
                        // The change is by at least +/- 1000 ms (or this is the first change),
                        // let's do it!
                        if (DEBUG_BATCH) {
                            Slog.v(TAG, "Time changed notification from kernel; rebatching");
                        }
                        // StatsLog requires currentTimeMillis(), which == nowRTC to within usecs.
                        StatsLog.write(StatsLog.WALL_CLOCK_TIME_SHIFTED, nowRTC);
                        removeImpl(null, mTimeTickTrigger);
                        removeImpl(mDateChangeSender, null);
                        rebatchAllAlarms();
                        mClockReceiver.scheduleTimeTickEvent();
                        mClockReceiver.scheduleDateChangedEvent();
                        synchronized (mLock) {
                            mNumTimeChanged++;
                            mLastTimeChangeClockTime = nowRTC;
                            mLastTimeChangeRealtime = nowELAPSED;
                        }
                        Intent intent = new Intent(Intent.ACTION_TIME_CHANGED);
                        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                                | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
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

                        mLastTrigger = nowELAPSED;
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
                            if (mLastTimeChangeRealtime != nowELAPSED && triggerList.isEmpty()) {
                                if (++mFalseWakeups >= mWtfThreshold) {
                                    Slog.wtf(TAG, "Too many (" + mFalseWakeups
                                            + ") false wakeups, nowElapsed=" + nowELAPSED);
                                    if (mWtfThreshold < 100_000) {
                                        mWtfThreshold *= 10;
                                    } else {
                                        mFalseWakeups = 0;
                                    }
                                }
                            }
                            final ArraySet<Pair<String, Integer>> triggerPackages =
                                    new ArraySet<>();
                            for (int i = 0; i < triggerList.size(); i++) {
                                final Alarm a = triggerList.get(i);
                                if (!isExemptFromAppStandby(a)) {
                                    triggerPackages.add(Pair.create(
                                            a.sourcePackage, UserHandle.getUserId(a.creatorUid)));
                                }
                            }
                            deliverAlarmsLocked(triggerList, nowELAPSED);
                            reorderAlarmsBasedOnStandbyBuckets(triggerPackages);
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        }
                    }

                } else {
                    // Just in case -- even though no wakeup flag was set, make sure
                    // we have updated the kernel to the next alarm time.
                    synchronized (mLock) {
                        rescheduleKernelAlarmsLocked();
                    }
                }
            }
        }
    }

    /**
     * Attribute blame for a WakeLock.
     * @param ws WorkSource to attribute blame.
     * @param knownUid attribution uid; < 0 values are ignored.
     */
    void setWakelockWorkSource(WorkSource ws, int knownUid, String tag, boolean first) {
        try {
            mWakeLock.setHistoryTag(first ? tag : null);

            if (ws != null) {
                mWakeLock.setWorkSource(ws);
                return;
            }

            if (knownUid >= 0) {
                mWakeLock.setWorkSource(new WorkSource(knownUid));
                return;
            }
        } catch (Exception e) {
        }

        // Something went wrong; fall back to attributing the lock to the OS
        mWakeLock.setWorkSource(null);
    }

    private static int getAlarmAttributionUid(Alarm alarm) {
        if (alarm.workSource != null && !alarm.workSource.isEmpty()) {
            return alarm.workSource.getAttributionUid();
        }

        return alarm.creatorUid;
    }

    @VisibleForTesting
    class AlarmHandler extends Handler {
        public static final int ALARM_EVENT = 1;
        public static final int SEND_NEXT_ALARM_CLOCK_CHANGED = 2;
        public static final int LISTENER_TIMEOUT = 3;
        public static final int REPORT_ALARMS_ACTIVE = 4;
        public static final int APP_STANDBY_BUCKET_CHANGED = 5;
        public static final int APP_STANDBY_PAROLE_CHANGED = 6;
        public static final int REMOVE_FOR_STOPPED = 7;

        AlarmHandler() {
            super(Looper.myLooper());
        }

        public void postRemoveForStopped(int uid) {
            obtainMessage(REMOVE_FOR_STOPPED, uid, 0).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ALARM_EVENT: {
                    ArrayList<Alarm> triggerList = new ArrayList<Alarm>();
                    synchronized (mLock) {
                        final long nowRTC = mInjector.getCurrentTimeMillis();
                        final long nowELAPSED = mInjector.getElapsedRealtime();
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
                                removeImpl(alarm.operation, null);
                            }
                        }
                    }
                    break;
                }

                case SEND_NEXT_ALARM_CLOCK_CHANGED:
                    sendNextAlarmClockChanged();
                    break;

                case LISTENER_TIMEOUT:
                    mDeliveryTracker.alarmTimedOut((IBinder) msg.obj);
                    break;

                case REPORT_ALARMS_ACTIVE:
                    if (mLocalDeviceIdleController != null) {
                        mLocalDeviceIdleController.setAlarmsActive(msg.arg1 != 0);
                    }
                    break;

                case APP_STANDBY_PAROLE_CHANGED:
                    synchronized (mLock) {
                        mAppStandbyParole = (Boolean) msg.obj;
                        if (reorderAlarmsBasedOnStandbyBuckets(null)) {
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        }
                    }
                    break;

                case APP_STANDBY_BUCKET_CHANGED:
                    synchronized (mLock) {
                        final ArraySet<Pair<String, Integer>> filterPackages = new ArraySet<>();
                        filterPackages.add(Pair.create((String) msg.obj, msg.arg1));
                        if (reorderAlarmsBasedOnStandbyBuckets(filterPackages)) {
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        }
                    }
                    break;

                case REMOVE_FOR_STOPPED:
                    synchronized (mLock) {
                        removeForStoppedLocked(msg.arg1);
                    }
                    break;

                default:
                    // nope, just ignore it
                    break;
            }
        }
    }

    class ClockReceiver extends BroadcastReceiver {
        public ClockReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_DATE_CHANGED)) {
                // Since the kernel does not keep track of DST, we need to
                // reset the TZ information at the beginning of each day
                // based off of the current Zone gmt offset + userspace tracked
                // daylight savings information.
                TimeZone zone = TimeZone.getTimeZone(SystemProperties.get(TIMEZONE_PROPERTY));
                int gmtOffset = zone.getOffset(mInjector.getCurrentTimeMillis());
                mInjector.setKernelTimezone(-(gmtOffset / 60000));
                scheduleDateChangedEvent();
            }
        }

        public void scheduleTimeTickEvent() {
            final long currentTime = mInjector.getCurrentTimeMillis();
            final long nextTime = 60000 * ((currentTime / 60000) + 1);

            // Schedule this event for the amount of time that it would take to get to
            // the top of the next minute.
            final long tickEventDelay = nextTime - currentTime;

            final WorkSource workSource = null; // Let system take blame for time tick events.
            setImpl(ELAPSED_REALTIME, mInjector.getElapsedRealtime() + tickEventDelay, 0,
                    0, null, mTimeTickTrigger, "TIME_TICK", AlarmManager.FLAG_STANDALONE,
                    workSource, null, Process.myUid(), "android");

            // Finally, remember when we set the tick alarm
            synchronized (mLock) {
                mLastTickSet = currentTime;
            }
        }

        public void scheduleDateChangedEvent() {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(mInjector.getCurrentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.DAY_OF_MONTH, 1);

            final WorkSource workSource = null; // Let system take blame for date change events.
            setImpl(RTC, calendar.getTimeInMillis(), 0, 0, mDateChangeSender, null, null,
                    AlarmManager.FLAG_STANDALONE, workSource, null,
                    Process.myUid(), "android");
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
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            synchronized (mLock) {
                String pkgList[] = null;
                switch (intent.getAction()) {
                    case Intent.ACTION_QUERY_PACKAGE_RESTART:
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                        for (String packageName : pkgList) {
                            if (lookForPackageLocked(packageName)) {
                                setResultCode(Activity.RESULT_OK);
                                return;
                            }
                        }
                        return;
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        break;
                    case Intent.ACTION_USER_STOPPED:
                        final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userHandle >= 0) {
                            removeUserLocked(userHandle);
                            mAppWakeupHistory.removeForUser(userHandle);
                        }
                        return;
                    case Intent.ACTION_UID_REMOVED:
                        if (uid >= 0) {
                            mLastAllowWhileIdleDispatch.delete(uid);
                            mUseAllowWhileIdleShortTime.delete(uid);
                        }
                        return;
                    case Intent.ACTION_PACKAGE_REMOVED:
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            // This package is being updated; don't kill its alarms.
                            return;
                        }
                        // Intentional fall-through.
                    case Intent.ACTION_PACKAGE_RESTARTED:
                        final Uri data = intent.getData();
                        if (data != null) {
                            final String pkg = data.getSchemeSpecificPart();
                            if (pkg != null) {
                                pkgList = new String[]{pkg};
                            }
                        }
                        break;
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkg : pkgList) {
                        if (uid >= 0) {
                            // package-removed and package-restarted case
                            mAppWakeupHistory.removeForPackage(pkg, UserHandle.getUserId(uid));
                            removeLocked(uid);
                        } else {
                            // external-applications-unavailable case
                            removeLocked(pkg);
                        }
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

    final class UidObserver extends IUidObserver.Stub {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq) {
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            if (disabled) {
                mHandler.postRemoveForStopped(uid);
            }
        }

        @Override public void onUidActive(int uid) {
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
            if (disabled) {
                mHandler.postRemoveForStopped(uid);
            }
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    /**
     * Tracking of app assignments to standby buckets
     */
    private final class AppStandbyTracker extends
            UsageStatsManagerInternal.AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,
                boolean idle, int bucket, int reason) {
            if (DEBUG_STANDBY) {
                Slog.d(TAG, "Package " + packageName + " for user " + userId + " now in bucket " +
                        bucket);
            }
            mHandler.removeMessages(AlarmHandler.APP_STANDBY_BUCKET_CHANGED);
            mHandler.obtainMessage(AlarmHandler.APP_STANDBY_BUCKET_CHANGED, userId, -1, packageName)
                    .sendToTarget();
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            if (DEBUG_STANDBY) {
                Slog.d(TAG, "Global parole state now " + (isParoleOn ? "ON" : "OFF"));
            }
            mHandler.removeMessages(AlarmHandler.APP_STANDBY_BUCKET_CHANGED);
            mHandler.removeMessages(AlarmHandler.APP_STANDBY_PAROLE_CHANGED);
            mHandler.obtainMessage(AlarmHandler.APP_STANDBY_PAROLE_CHANGED,
                    Boolean.valueOf(isParoleOn)).sendToTarget();
        }
    };

    private final Listener mForceAppStandbyListener = new Listener() {
        @Override
        public void unblockAllUnrestrictedAlarms() {
            synchronized (mLock) {
                sendAllUnrestrictedPendingBackgroundAlarmsLocked();
            }
        }

        @Override
        public void unblockAlarmsForUid(int uid) {
            synchronized (mLock) {
                sendPendingBackgroundAlarmsLocked(uid, null);
            }
        }

        @Override
        public void unblockAlarmsForUidPackage(int uid, String packageName) {
            synchronized (mLock) {
                sendPendingBackgroundAlarmsLocked(uid, packageName);
            }
        }

        @Override
        public void onUidForeground(int uid, boolean foreground) {
            synchronized (mLock) {
                if (foreground) {
                    mUseAllowWhileIdleShortTime.put(uid, true);

                    // Note we don't have to drain the pending while-idle alarms here, because
                    // this event should coincide with unblockAlarmsForUid().
                }
            }
        }
    };

    private final BroadcastStats getStatsLocked(PendingIntent pi) {
        String pkg = pi.getCreatorPackage();
        int uid = pi.getCreatorUid();
        return getStatsLocked(uid, pkg);
    }

    private final BroadcastStats getStatsLocked(int uid, String pkgName) {
        ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.get(uid);
        if (uidStats == null) {
            uidStats = new ArrayMap<String, BroadcastStats>();
            mBroadcastStats.put(uid, uidStats);
        }
        BroadcastStats bs = uidStats.get(pkgName);
        if (bs == null) {
            bs = new BroadcastStats(uid, pkgName);
            uidStats.put(pkgName, bs);
        }
        return bs;
    }

    /**
     * Canonical count of (operation.send() - onSendFinished()) and
     * listener send/complete/timeout invocations.
     * Guarded by the usual lock.
     */
    @GuardedBy("mLock")
    private int mSendCount = 0;
    @GuardedBy("mLock")
    private int mSendFinishCount = 0;
    @GuardedBy("mLock")
    private int mListenerCount = 0;
    @GuardedBy("mLock")
    private int mListenerFinishCount = 0;

    class DeliveryTracker extends IAlarmCompleteListener.Stub implements PendingIntent.OnFinished {

        private InFlight removeLocked(PendingIntent pi, Intent intent) {
            for (int i = 0; i < mInFlight.size(); i++) {
                final InFlight inflight = mInFlight.get(i);
                if (inflight.mPendingIntent == pi) {
                    if (pi.isBroadcast()) {
                        notifyBroadcastAlarmCompleteLocked(inflight.mUid);
                    }
                    return mInFlight.remove(i);
                }
            }
            mLog.w("No in-flight alarm for " + pi + " " + intent);
            return null;
        }

        private InFlight removeLocked(IBinder listener) {
            for (int i = 0; i < mInFlight.size(); i++) {
                if (mInFlight.get(i).mListener == listener) {
                    return mInFlight.remove(i);
                }
            }
            mLog.w("No in-flight alarm for listener " + listener);
            return null;
        }

        private void updateStatsLocked(InFlight inflight) {
            final long nowELAPSED = mInjector.getElapsedRealtime();
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
                ActivityManager.noteAlarmFinish(inflight.mPendingIntent, inflight.mWorkSource,
                        inflight.mUid, inflight.mTag);
            }
        }

        private void updateTrackingLocked(InFlight inflight) {
            if (inflight != null) {
                updateStatsLocked(inflight);
            }
            mBroadcastRefCount--;
            if (DEBUG_WAKELOCK) {
                Slog.d(TAG, "mBroadcastRefCount -> " + mBroadcastRefCount);
            }
            if (mBroadcastRefCount == 0) {
                mHandler.obtainMessage(AlarmHandler.REPORT_ALARMS_ACTIVE, 0).sendToTarget();
                mWakeLock.release();
                if (mInFlight.size() > 0) {
                    mLog.w("Finished all dispatches with " + mInFlight.size()
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
                    setWakelockWorkSource(inFlight.mWorkSource, inFlight.mCreatorUid, inFlight.mTag,
                            false);
                } else {
                    // should never happen
                    mLog.w("Alarm wakelock still held but sent queue empty");
                    mWakeLock.setWorkSource(null);
                }
            }
        }

        /**
         * Callback that arrives when a direct-call alarm reports that delivery has finished
         */
        @Override
        public void alarmComplete(IBinder who) {
            if (who == null) {
                mLog.w("Invalid alarmComplete: uid=" + Binder.getCallingUid()
                        + " pid=" + Binder.getCallingPid());
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mHandler.removeMessages(AlarmHandler.LISTENER_TIMEOUT, who);
                    InFlight inflight = removeLocked(who);
                    if (inflight != null) {
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.i(TAG, "alarmComplete() from " + who);
                        }
                        updateTrackingLocked(inflight);
                        mListenerFinishCount++;
                    } else {
                        // Delivery timed out, and the timeout handling already took care of
                        // updating our tracking here, so we needn't do anything further.
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.i(TAG, "Late alarmComplete() from " + who);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Callback that arrives when a PendingIntent alarm has finished delivery
         */
        @Override
        public void onSendFinished(PendingIntent pi, Intent intent, int resultCode,
                String resultData, Bundle resultExtras) {
            synchronized (mLock) {
                mSendFinishCount++;
                updateTrackingLocked(removeLocked(pi, intent));
            }
        }

        /**
         * Timeout of a direct-call alarm delivery
         */
        public void alarmTimedOut(IBinder who) {
            synchronized (mLock) {
                InFlight inflight = removeLocked(who);
                if (inflight != null) {
                    // TODO: implement ANR policy for the target
                    if (DEBUG_LISTENER_CALLBACK) {
                        Slog.i(TAG, "Alarm listener " + who + " timed out in delivery");
                    }
                    updateTrackingLocked(inflight);
                    mListenerFinishCount++;
                } else {
                    if (DEBUG_LISTENER_CALLBACK) {
                        Slog.i(TAG, "Spurious timeout of listener " + who);
                    }
                    mLog.w("Spurious timeout of listener " + who);
                }
            }
        }

        /**
         * Deliver an alarm and set up the post-delivery handling appropriately
         */
        @GuardedBy("mLock")
        public void deliverLocked(Alarm alarm, long nowELAPSED, boolean allowWhileIdle) {
            final long workSourceToken = ThreadLocalWorkSource.setUid(
                    getAlarmAttributionUid(alarm));
            try {
                if (alarm.operation != null) {
                    // PendingIntent alarm
                    mSendCount++;

                    try {
                        alarm.operation.send(getContext(), 0,
                                mBackgroundIntent.putExtra(
                                        Intent.EXTRA_ALARM_COUNT, alarm.count),
                                mDeliveryTracker, mHandler, null,
                                allowWhileIdle ? mIdleOptions : null);
                    } catch (PendingIntent.CanceledException e) {
                        if (alarm.repeatInterval > 0) {
                            // This IntentSender is no longer valid, but this
                            // is a repeating alarm, so toss it
                            removeImpl(alarm.operation, null);
                        }
                        // No actual delivery was possible, so the delivery tracker's
                        // 'finished' callback won't be invoked.  We also don't need
                        // to do any wakelock or stats tracking, so we have nothing
                        // left to do here but go on to the next thing.
                        mSendFinishCount++;
                        return;
                    }
                } else {
                    // Direct listener callback alarm
                    mListenerCount++;

                    if (RECORD_ALARMS_IN_HISTORY) {
                        if (alarm.listener == mTimeTickTrigger) {
                            mTickHistory[mNextTickHistory++] = nowELAPSED;
                            if (mNextTickHistory >= TICK_HISTORY_DEPTH) {
                                mNextTickHistory = 0;
                            }
                        }
                    }

                    try {
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.v(TAG, "Alarm to uid=" + alarm.uid
                                    + " listener=" + alarm.listener.asBinder());
                        }
                        alarm.listener.doAlarm(this);
                        mHandler.sendMessageDelayed(
                                mHandler.obtainMessage(AlarmHandler.LISTENER_TIMEOUT,
                                        alarm.listener.asBinder()),
                                mConstants.LISTENER_TIMEOUT);
                    } catch (Exception e) {
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.i(TAG, "Alarm undeliverable to listener "
                                    + alarm.listener.asBinder(), e);
                        }
                        // As in the PendingIntent.CanceledException case, delivery of the
                        // alarm was not possible, so we have no wakelock or timeout or
                        // stats management to do.  It threw before we posted the delayed
                        // timeout message, so we're done here.
                        mListenerFinishCount++;
                        return;
                    }
                }
            } finally {
                ThreadLocalWorkSource.restore(workSourceToken);
            }

            // The alarm is now in flight; now arrange wakelock and stats tracking
            if (DEBUG_WAKELOCK) {
                Slog.d(TAG, "mBroadcastRefCount -> " + (mBroadcastRefCount + 1));
            }
            if (mBroadcastRefCount == 0) {
                setWakelockWorkSource(alarm.workSource, alarm.creatorUid, alarm.statsTag, true);
                mWakeLock.acquire();
                mHandler.obtainMessage(AlarmHandler.REPORT_ALARMS_ACTIVE, 1).sendToTarget();
            }
            final InFlight inflight = new InFlight(AlarmManagerService.this, alarm, nowELAPSED);
            mInFlight.add(inflight);
            mBroadcastRefCount++;
            if (inflight.isBroadcast()) {
                notifyBroadcastAlarmPendingLocked(alarm.uid);
            }
            if (allowWhileIdle) {
                // Record the last time this uid handled an ALLOW_WHILE_IDLE alarm.
                mLastAllowWhileIdleDispatch.put(alarm.creatorUid, nowELAPSED);
                if ((mAppStateTracker == null)
                        || mAppStateTracker.isUidInForeground(alarm.creatorUid)) {
                    mUseAllowWhileIdleShortTime.put(alarm.creatorUid, true);
                } else {
                    mUseAllowWhileIdleShortTime.put(alarm.creatorUid, false);
                }
                if (RECORD_DEVICE_IDLE_ALARMS) {
                    IdleDispatchEntry ent = new IdleDispatchEntry();
                    ent.uid = alarm.uid;
                    ent.pkg = alarm.packageName;
                    ent.tag = alarm.statsTag;
                    ent.op = "DELIVER";
                    ent.elapsedRealtime = nowELAPSED;
                    mAllowWhileIdleDispatches.add(ent);
                }
            }
            if (!isExemptFromAppStandby(alarm)) {
                final Pair<String, Integer> packageUser = Pair.create(alarm.sourcePackage,
                        UserHandle.getUserId(alarm.creatorUid));
                mAppWakeupHistory.recordAlarmForPackage(alarm.sourcePackage,
                        UserHandle.getUserId(alarm.creatorUid), nowELAPSED);
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
                ActivityManager.noteWakeupAlarm(
                        alarm.operation, alarm.workSource, alarm.uid, alarm.packageName,
                        alarm.statsTag);
            }
        }
    }

    private class ShellCmd extends ShellCommand {

        IAlarmManager getBinderService() {
            return IAlarmManager.Stub.asInterface(mService);
        }

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }

            final PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd) {
                    case "set-time":
                        final long millis = Long.parseLong(getNextArgRequired());
                        return (getBinderService().setTime(millis)) ? 0 : -1;
                    case "set-timezone":
                        final String tz = getNextArgRequired();
                        getBinderService().setTimeZone(tz);
                        return 0;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (Exception e) {
                pw.println(e);
            }
            return -1;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Alarm manager service (alarm) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  set-time TIME");
            pw.println("    Set the system clock time to TIME where TIME is milliseconds");
            pw.println("    since the Epoch.");
            pw.println("  set-timezone TZ");
            pw.println("    Set the system timezone to TZ where TZ is an Olson id.");
        }
    }
}
