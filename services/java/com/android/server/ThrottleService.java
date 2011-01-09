/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.INetworkManagementEventObserver;
import android.net.IThrottleManager;
import android.net.SntpClient;
import android.net.ThrottleManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.telephony.TelephonyProperties;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.GregorianCalendar;
import java.util.Properties;
import java.util.Random;

// TODO - add comments - reference the ThrottleManager for public API
public class ThrottleService extends IThrottleManager.Stub {

    private static final String TESTING_ENABLED_PROPERTY = "persist.throttle.testing";

    private static final String TAG = "ThrottleService";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private Handler mHandler;
    private HandlerThread mThread;

    private Context mContext;

    private static final int INITIAL_POLL_DELAY_SEC = 90;
    private static final int TESTING_POLLING_PERIOD_SEC = 60 * 1;
    private static final int TESTING_RESET_PERIOD_SEC = 60 * 10;
    private static final long TESTING_THRESHOLD = 1 * 1024 * 1024;

    private int mPolicyPollPeriodSec;
    private AtomicLong mPolicyThreshold;
    private AtomicInteger mPolicyThrottleValue;
    private int mPolicyResetDay; // 1-28
    private int mPolicyNotificationsAllowedMask;

    private long mLastRead; // read byte count from last poll
    private long mLastWrite; // write byte count from last poll

    private static final String ACTION_POLL = "com.android.server.ThrottleManager.action.POLL";
    private static int POLL_REQUEST = 0;
    private PendingIntent mPendingPollIntent;
    private static final String ACTION_RESET = "com.android.server.ThorottleManager.action.RESET";
    private static int RESET_REQUEST = 1;
    private PendingIntent mPendingResetIntent;

    private INetworkManagementService mNMService;
    private AlarmManager mAlarmManager;
    private NotificationManager mNotificationManager;

    private DataRecorder mRecorder;

    private String mIface;

    private static final int NOTIFICATION_WARNING   = 2;

    private Notification mThrottlingNotification;
    private boolean mWarningNotificationSent = false;

    private InterfaceObserver mInterfaceObserver;
    private SettingsObserver mSettingsObserver;

    private AtomicInteger mThrottleIndex; // 0 for none, 1 for first throttle val, 2 for next, etc
    private static final int THROTTLE_INDEX_UNINITIALIZED = -1;
    private static final int THROTTLE_INDEX_UNTHROTTLED   =  0;

    private static final String PROPERTIES_FILE = "/etc/gps.conf";
    private String mNtpServer;
    private boolean mNtpActive;

    public ThrottleService(Context context) {
        if (VDBG) Slog.v(TAG, "Starting ThrottleService");
        mContext = context;

        mPolicyThreshold = new AtomicLong();
        mPolicyThrottleValue = new AtomicInteger();
        mThrottleIndex = new AtomicInteger();

        mNtpActive = false;

        mIface = mContext.getResources().getString(R.string.config_datause_iface);
        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent pollIntent = new Intent(ACTION_POLL, null);
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST, pollIntent, 0);
        Intent resetIntent = new Intent(ACTION_RESET, null);
        mPendingResetIntent = PendingIntent.getBroadcast(mContext, RESET_REQUEST, resetIntent, 0);

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);

        mNotificationManager = (NotificationManager)mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    private static class InterfaceObserver extends INetworkManagementEventObserver.Stub {
        private int mMsg;
        private Handler mHandler;
        private String mIface;

        InterfaceObserver(Handler handler, int msg, String iface) {
            super();
            mHandler = handler;
            mMsg = msg;
            mIface = iface;
        }

        public void interfaceLinkStatusChanged(String iface, boolean link) {
            if (link) {
                if (TextUtils.equals(iface, mIface)) {
                    mHandler.obtainMessage(mMsg).sendToTarget();
                }
            }
        }

        public void interfaceAdded(String iface) {
            // TODO - an interface added in the UP state should also trigger a StatusChanged
            // notification..
            if (TextUtils.equals(iface, mIface)) {
                mHandler.obtainMessage(mMsg).sendToTarget();
            }
        }

        public void interfaceRemoved(String iface) {}
    }


    private static class SettingsObserver extends ContentObserver {
        private int mMsg;
        private Handler mHandler;
        SettingsObserver(Handler handler, int msg) {
            super(handler);
            mHandler = handler;
            mMsg = msg;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_POLLING_SEC), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_THRESHOLD_BYTES), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_VALUE_KBITSPS), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_RESET_DAY), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_NOTIFICATION_TYPE), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_HELP_URI), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.THROTTLE_MAX_NTP_CACHE_AGE_SEC), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mMsg).sendToTarget();
        }
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ThrottleService");
    }

    private long ntpToWallTime(long ntpTime) {
        long bestNow = getBestTime(true); // do it quickly
        long localNow = System.currentTimeMillis();
        return localNow + (ntpTime - bestNow);
    }

    // TODO - fetch for the iface
    // return time in the local, system wall time, correcting for the use of ntp

    public long getResetTime(String iface) {
        enforceAccessPermission();
        long resetTime = 0;
        if (mRecorder != null) {
            resetTime = mRecorder.getPeriodEnd();
        }
        resetTime = ntpToWallTime(resetTime);
        return resetTime;
    }

    // TODO - fetch for the iface
    // return time in the local, system wall time, correcting for the use of ntp
    public long getPeriodStartTime(String iface) {
        long startTime = 0;
        enforceAccessPermission();
        if (mRecorder != null) {
            startTime = mRecorder.getPeriodStart();
        }
        startTime = ntpToWallTime(startTime);
        return startTime;
    }
    //TODO - a better name?  getCliffByteCountThreshold?
    // TODO - fetch for the iface
    public long getCliffThreshold(String iface, int cliff) {
        enforceAccessPermission();
        if (cliff == 1) {
           return mPolicyThreshold.get();
        }
        return 0;
    }
    // TODO - a better name? getThrottleRate?
    // TODO - fetch for the iface
    public int getCliffLevel(String iface, int cliff) {
        enforceAccessPermission();
        if (cliff == 1) {
            return mPolicyThrottleValue.get();
        }
        return 0;
    }

    public String getHelpUri() {
        enforceAccessPermission();
        return Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_HELP_URI);
    }

    // TODO - fetch for the iface
    public long getByteCount(String iface, int dir, int period, int ago) {
        enforceAccessPermission();
        if ((period == ThrottleManager.PERIOD_CYCLE) && (mRecorder != null)) {
            if (dir == ThrottleManager.DIRECTION_TX) return mRecorder.getPeriodTx(ago);
            if (dir == ThrottleManager.DIRECTION_RX) return mRecorder.getPeriodRx(ago);
        }
        return 0;
    }

    // TODO - a better name - getCurrentThrottleRate?
    // TODO - fetch for the iface
    public int getThrottle(String iface) {
        enforceAccessPermission();
        if (mThrottleIndex.get() == 1) {
            return mPolicyThrottleValue.get();
        }
        return 0;
    }

    void systemReady() {
        if (VDBG) Slog.v(TAG, "systemReady");
        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mHandler.obtainMessage(EVENT_POLL_ALARM).sendToTarget();
                }
            }, new IntentFilter(ACTION_POLL));

        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mHandler.obtainMessage(EVENT_RESET_ALARM).sendToTarget();
                }
            }, new IntentFilter(ACTION_RESET));

        FileInputStream stream = null;
        try {
            Properties properties = new Properties();
            File file = new File(PROPERTIES_FILE);
            stream = new FileInputStream(file);
            properties.load(stream);
            mNtpServer = properties.getProperty("NTP_SERVER", null);
        } catch (IOException e) {
            Slog.e(TAG, "Could not open GPS configuration file " + PROPERTIES_FILE);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception e) {}
            }
        }

        // use a new thread as we don't want to stall the system for file writes
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new MyHandler(mThread.getLooper());
        mHandler.obtainMessage(EVENT_REBOOT_RECOVERY).sendToTarget();

        mInterfaceObserver = new InterfaceObserver(mHandler, EVENT_IFACE_UP, mIface);
        try {
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register InterfaceObserver " + e);
        }

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_POLICY_CHANGED);
        mSettingsObserver.observe(mContext);
    }


    private static final int EVENT_REBOOT_RECOVERY = 0;
    private static final int EVENT_POLICY_CHANGED  = 1;
    private static final int EVENT_POLL_ALARM      = 2;
    private static final int EVENT_RESET_ALARM     = 3;
    private static final int EVENT_IFACE_UP        = 4;
    private class MyHandler extends Handler {
        public MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_REBOOT_RECOVERY:
                onRebootRecovery();
                break;
            case EVENT_POLICY_CHANGED:
                onPolicyChanged();
                break;
            case EVENT_POLL_ALARM:
                onPollAlarm();
                break;
            case EVENT_RESET_ALARM:
                onResetAlarm();
                break;
            case EVENT_IFACE_UP:
                onIfaceUp();
            }
        }

        private void onRebootRecovery() {
            if (VDBG) Slog.v(TAG, "onRebootRecovery");
            // check for sim change TODO
            // reregister for notification of policy change

            mThrottleIndex.set(THROTTLE_INDEX_UNINITIALIZED);

            mRecorder = new DataRecorder(mContext, ThrottleService.this);

            // get policy
            mHandler.obtainMessage(EVENT_POLICY_CHANGED).sendToTarget();

            // if we poll now we won't have network connectivity or even imsi access
            // queue up a poll to happen in a little while - after ntp and imsi are avail
            // TODO - make this callback based (ie, listen for notificaitons)
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_POLL_ALARM),
                    INITIAL_POLL_DELAY_SEC * 1000);
        }

        // check for new policy info (threshold limit/value/etc)
        private void onPolicyChanged() {
            boolean testing = SystemProperties.get(TESTING_ENABLED_PROPERTY).equals("true");

            int pollingPeriod = mContext.getResources().getInteger(
                    R.integer.config_datause_polling_period_sec);
            mPolicyPollPeriodSec = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_POLLING_SEC, pollingPeriod);

            // TODO - remove testing stuff?
            long defaultThreshold = mContext.getResources().getInteger(
                    R.integer.config_datause_threshold_bytes);
            int defaultValue = mContext.getResources().getInteger(
                    R.integer.config_datause_throttle_kbitsps);
            long threshold = Settings.Secure.getLong(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_THRESHOLD_BYTES, defaultThreshold);
            int value = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_VALUE_KBITSPS, defaultValue);

            mPolicyThreshold.set(threshold);
            mPolicyThrottleValue.set(value);
            if (testing) {
                mPolicyPollPeriodSec = TESTING_POLLING_PERIOD_SEC;
                mPolicyThreshold.set(TESTING_THRESHOLD);
            }

            mPolicyResetDay = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_RESET_DAY, -1);
            if (mPolicyResetDay == -1 ||
                    ((mPolicyResetDay < 1) || (mPolicyResetDay > 28))) {
                Random g = new Random();
                mPolicyResetDay = 1 + g.nextInt(28); // 1-28
                Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.THROTTLE_RESET_DAY, mPolicyResetDay);
            }
            if (mIface == null) {
                mPolicyThreshold.set(0);
            }

            int defaultNotificationType = mContext.getResources().getInteger(
                    R.integer.config_datause_notification_type);
            mPolicyNotificationsAllowedMask = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_NOTIFICATION_TYPE, defaultNotificationType);

            mMaxNtpCacheAgeSec = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_MAX_NTP_CACHE_AGE_SEC, MAX_NTP_CACHE_AGE_SEC);

            if (VDBG || (mPolicyThreshold.get() != 0)) {
                Slog.d(TAG, "onPolicyChanged testing=" + testing +", period=" +
                        mPolicyPollPeriodSec + ", threshold=" + mPolicyThreshold.get() +
                        ", value=" + mPolicyThrottleValue.get() + ", resetDay=" + mPolicyResetDay +
                        ", noteType=" + mPolicyNotificationsAllowedMask + ", maxNtpCacheAge=" +
                        mMaxNtpCacheAgeSec);
            }

            // force updates
            mThrottleIndex.set(THROTTLE_INDEX_UNINITIALIZED);

            onResetAlarm();

            onPollAlarm();

            Intent broadcast = new Intent(ThrottleManager.POLICY_CHANGED_ACTION);
            mContext.sendBroadcast(broadcast);
        }

        private void onPollAlarm() {
            long now = SystemClock.elapsedRealtime();
            long next = now + mPolicyPollPeriodSec*1000;

            checkForAuthoritativeTime();

            long incRead = 0;
            long incWrite = 0;
            try {
                incRead = mNMService.getInterfaceRxCounter(mIface) - mLastRead;
                incWrite = mNMService.getInterfaceTxCounter(mIface) - mLastWrite;
                // handle iface resets - on some device the 3g iface comes and goes and gets
                // totals reset to 0.  Deal with it
                if ((incRead < 0) || (incWrite < 0)) {
                    incRead += mLastRead;
                    incWrite += mLastWrite;
                    mLastRead = 0;
                    mLastWrite = 0;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "got remoteException in onPollAlarm:" + e);
            }
            // don't count this data if we're roaming.
            boolean roaming = "true".equals(
                    SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING));
            if (!roaming) {
                mRecorder.addData(incRead, incWrite);
            }

            long periodRx = mRecorder.getPeriodRx(0);
            long periodTx = mRecorder.getPeriodTx(0);
            long total = periodRx + periodTx;
            if (VDBG || (mPolicyThreshold.get() != 0)) {
                Slog.d(TAG, "onPollAlarm - roaming =" + roaming +
                        ", read =" + incRead + ", written =" + incWrite + ", new total =" + total);
            }
            mLastRead += incRead;
            mLastWrite += incWrite;

            checkThrottleAndPostNotification(total);

            Intent broadcast = new Intent(ThrottleManager.THROTTLE_POLL_ACTION);
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_READ, periodRx);
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_WRITE, periodTx);
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_START, getPeriodStartTime(mIface));
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_END, getResetTime(mIface));
            mContext.sendStickyBroadcast(broadcast);

            mAlarmManager.cancel(mPendingPollIntent);
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, next, mPendingPollIntent);
        }

        private void onIfaceUp() {
            // if we were throttled before, be sure and set it again - the iface went down
            // (and may have disappeared all together) and these settings were lost
            if (mThrottleIndex.get() == 1) {
                try {
                    mNMService.setInterfaceThrottle(mIface, -1, -1);
                    mNMService.setInterfaceThrottle(mIface,
                            mPolicyThrottleValue.get(), mPolicyThrottleValue.get());
                } catch (Exception e) {
                    Slog.e(TAG, "error setting Throttle: " + e);
                }
            }
        }

        private void checkThrottleAndPostNotification(long currentTotal) {
            // is throttling enabled?
            long threshold = mPolicyThreshold.get();
            if (threshold == 0) {
                clearThrottleAndNotification();
                return;
            }

            // have we spoken with an ntp server yet?
            // this is controversial, but we'd rather err towards not throttling
            if ((mNtpServer != null) && !mNtpActive) {
                return;
            }

            // check if we need to throttle
            if (currentTotal > threshold) {
                if (mThrottleIndex.get() != 1) {
                    mThrottleIndex.set(1);
                    if (DBG) Slog.d(TAG, "Threshold " + threshold + " exceeded!");
                    try {
                        mNMService.setInterfaceThrottle(mIface,
                                mPolicyThrottleValue.get(), mPolicyThrottleValue.get());
                    } catch (Exception e) {
                        Slog.e(TAG, "error setting Throttle: " + e);
                    }

                    mNotificationManager.cancel(R.drawable.stat_sys_throttled);

                    postNotification(R.string.throttled_notification_title,
                            R.string.throttled_notification_message,
                            R.drawable.stat_sys_throttled,
                            Notification.FLAG_ONGOING_EVENT);

                    Intent broadcast = new Intent(ThrottleManager.THROTTLE_ACTION);
                    broadcast.putExtra(ThrottleManager.EXTRA_THROTTLE_LEVEL,
                            mPolicyThrottleValue.get());
                    mContext.sendStickyBroadcast(broadcast);

                } // else already up!
            } else {
                clearThrottleAndNotification();
                if ((mPolicyNotificationsAllowedMask & NOTIFICATION_WARNING) != 0) {
                    // check if we should warn about throttle
                    // pretend we only have 1/2 the time remaining that we actually do
                    // if our burn rate in the period so far would have us exceed the limit
                    // in that 1/2 window, warn the user.
                    // this gets more generous in the early to middle period and converges back
                    // to the limit as we move toward the period end.

                    // adding another factor - it must be greater than the total cap/4
                    // else we may get false alarms very early in the period..  in the first
                    // tenth of a percent of the period if we used more than a tenth of a percent
                    // of the cap we'd get a warning and that's not desired.
                    long start = mRecorder.getPeriodStart();
                    long end = mRecorder.getPeriodEnd();
                    long periodLength = end - start;
                    long now = System.currentTimeMillis();
                    long timeUsed = now - start;
                    long warningThreshold = 2*threshold*timeUsed/(timeUsed+periodLength);
                    if ((currentTotal > warningThreshold) && (currentTotal > threshold/4)) {
                        if (mWarningNotificationSent == false) {
                            mWarningNotificationSent = true;
                            mNotificationManager.cancel(R.drawable.stat_sys_throttled);
                            postNotification(R.string.throttle_warning_notification_title,
                                    R.string.throttle_warning_notification_message,
                                    R.drawable.stat_sys_throttled,
                                    0);
                        }
                    } else {
                        if (mWarningNotificationSent == true) {
                            mNotificationManager.cancel(R.drawable.stat_sys_throttled);
                            mWarningNotificationSent =false;
                        }
                    }
                }
            }
        }

        private void postNotification(int titleInt, int messageInt, int icon, int flags) {
            Intent intent = new Intent();
            // TODO - fix up intent
            intent.setClassName("com.android.phone", "com.android.phone.DataUsage");
            intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

            Resources r = Resources.getSystem();
            CharSequence title = r.getText(titleInt);
            CharSequence message = r.getText(messageInt);
            if (mThrottlingNotification == null) {
                mThrottlingNotification = new Notification();
                mThrottlingNotification.when = 0;
                // TODO -  fixup icon
                mThrottlingNotification.icon = icon;
                mThrottlingNotification.defaults &= ~Notification.DEFAULT_SOUND;
            }
            mThrottlingNotification.flags = flags;
            mThrottlingNotification.tickerText = title;
            mThrottlingNotification.setLatestEventInfo(mContext, title, message, pi);

            mNotificationManager.notify(mThrottlingNotification.icon, mThrottlingNotification);
        }


        private void clearThrottleAndNotification() {
            if (mThrottleIndex.get() != THROTTLE_INDEX_UNTHROTTLED) {
                mThrottleIndex.set(THROTTLE_INDEX_UNTHROTTLED);
                try {
                    mNMService.setInterfaceThrottle(mIface, -1, -1);
                } catch (Exception e) {
                    Slog.e(TAG, "error clearing Throttle: " + e);
                }
                Intent broadcast = new Intent(ThrottleManager.THROTTLE_ACTION);
                broadcast.putExtra(ThrottleManager.EXTRA_THROTTLE_LEVEL, -1);
                mContext.sendStickyBroadcast(broadcast);
                mNotificationManager.cancel(R.drawable.stat_sys_throttled);
                mWarningNotificationSent = false;
            }
        }

        private Calendar calculatePeriodEnd(long now) {
            Calendar end = GregorianCalendar.getInstance();
            end.setTimeInMillis(now);
            int day = end.get(Calendar.DAY_OF_MONTH);
            end.set(Calendar.DAY_OF_MONTH, mPolicyResetDay);
            end.set(Calendar.HOUR_OF_DAY, 0);
            end.set(Calendar.MINUTE, 0);
            end.set(Calendar.SECOND, 0);
            end.set(Calendar.MILLISECOND, 0);
            if (day >= mPolicyResetDay) {
                int month = end.get(Calendar.MONTH);
                if (month == Calendar.DECEMBER) {
                    end.set(Calendar.YEAR, end.get(Calendar.YEAR) + 1);
                    month = Calendar.JANUARY - 1;
                }
                end.set(Calendar.MONTH, month + 1);
            }

            // TODO - remove!
            if (SystemProperties.get(TESTING_ENABLED_PROPERTY).equals("true")) {
                end = GregorianCalendar.getInstance();
                end.setTimeInMillis(now);
                end.add(Calendar.SECOND, TESTING_RESET_PERIOD_SEC);
            }
            return end;
        }
        private Calendar calculatePeriodStart(Calendar end) {
            Calendar start = (Calendar)end.clone();
            int month = end.get(Calendar.MONTH);
            if (end.get(Calendar.MONTH) == Calendar.JANUARY) {
                month = Calendar.DECEMBER + 1;
                start.set(Calendar.YEAR, start.get(Calendar.YEAR) - 1);
            }
            start.set(Calendar.MONTH, month - 1);

            // TODO - remove!!
            if (SystemProperties.get(TESTING_ENABLED_PROPERTY).equals("true")) {
                start = (Calendar)end.clone();
                start.add(Calendar.SECOND, -TESTING_RESET_PERIOD_SEC);
            }
            return start;
        }

        private void onResetAlarm() {
            if (VDBG || (mPolicyThreshold.get() != 0)) {
                Slog.d(TAG, "onResetAlarm - last period had " + mRecorder.getPeriodRx(0) +
                        " bytes read and " + mRecorder.getPeriodTx(0) + " written");
            }

            long now = getBestTime(false);

            if (mNtpActive || (mNtpServer == null)) {
                Calendar end = calculatePeriodEnd(now);
                Calendar start = calculatePeriodStart(end);

                if (mRecorder.setNextPeriod(start, end)) {
                    onPollAlarm();
                }

                mAlarmManager.cancel(mPendingResetIntent);
                long offset = end.getTimeInMillis() - now;
                // use Elapsed realtime so clock changes don't fool us.
                mAlarmManager.set(AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + offset,
                        mPendingResetIntent);
            } else {
                if (VDBG) Slog.d(TAG, "no authoritative time - not resetting period");
            }
        }
    }

    private void checkForAuthoritativeTime() {
        if (mNtpActive || (mNtpServer == null)) return;

        // will try to get the ntp time and switch to it if found.
        // will also cache the time so we don't fetch it repeatedly.
        getBestTime(false);
    }

    private static final int MAX_NTP_CACHE_AGE_SEC = 60 * 60 * 24; // 1 day
    private static final int MAX_NTP_FETCH_WAIT = 20 * 1000;
    private int mMaxNtpCacheAgeSec = MAX_NTP_CACHE_AGE_SEC;
    private long cachedNtp;
    private long cachedNtpTimestamp;

    // if the request is tied to UI and ANR's are a danger, request a fast result
    // the regular polling should have updated the cached time recently using the
    // slower method (!fast)
    private long getBestTime(boolean fast) {
        if (mNtpServer != null) {
            if (mNtpActive) {
                long ntpAge = SystemClock.elapsedRealtime() - cachedNtpTimestamp;
                if (ntpAge < mMaxNtpCacheAgeSec * 1000 || fast) {
                    if (VDBG) Slog.v(TAG, "using cached time");
                    return cachedNtp + ntpAge;
                }
            }
            SntpClient client = new SntpClient();
            if (client.requestTime(mNtpServer, MAX_NTP_FETCH_WAIT)) {
                cachedNtp = client.getNtpTime();
                cachedNtpTimestamp = SystemClock.elapsedRealtime();
                if (!mNtpActive) {
                    mNtpActive = true;
                    if (VDBG) Slog.d(TAG, "found Authoritative time - reseting alarm");
                    mHandler.obtainMessage(EVENT_RESET_ALARM).sendToTarget();
                }
                if (VDBG) Slog.v(TAG, "using Authoritative time: " + cachedNtp);
                return cachedNtp;
            }
        }
        long time = System.currentTimeMillis();
        if (VDBG) Slog.v(TAG, "using User time: " + time);
        mNtpActive = false;
        return time;
    }

    // records bytecount data for a given time and accumulates it into larger time windows
    // for logging and other purposes
    //
    // since time can be changed (user or network action) we will have to track the time of the
    // last recording and deal with it.
    private static class DataRecorder {
        long[] mPeriodRxData;
        long[] mPeriodTxData;
        int mCurrentPeriod;
        int mPeriodCount;

        Calendar mPeriodStart;
        Calendar mPeriodEnd;

        ThrottleService mParent;
        Context mContext;
        String mImsi = null;

        TelephonyManager mTelephonyManager;

        DataRecorder(Context context, ThrottleService parent) {
            mContext = context;
            mParent = parent;

            mTelephonyManager = (TelephonyManager)mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);

            synchronized (mParent) {
                mPeriodCount = 6;
                mPeriodRxData = new long[mPeriodCount];
                mPeriodTxData = new long[mPeriodCount];

                mPeriodStart = Calendar.getInstance();
                mPeriodEnd = Calendar.getInstance();

                retrieve();
            }
        }

        boolean setNextPeriod(Calendar start, Calendar end) {
            // TODO - how would we deal with a dual-IMSI device?
            checkForSubscriberId();
            boolean startNewPeriod = true;

            if (start.equals(mPeriodStart) && end.equals(mPeriodEnd)) {
                // same endpoints - keep collecting
                if (VDBG) {
                    Slog.d(TAG, "same period (" + start.getTimeInMillis() + "," +
                            end.getTimeInMillis() +") - ammending data");
                }
                startNewPeriod = false;
            } else {
                if (VDBG) {
                    if(start.equals(mPeriodEnd) || start.after(mPeriodEnd)) {
                        Slog.d(TAG, "next period (" + start.getTimeInMillis() + "," +
                                end.getTimeInMillis() + ") - old end was " +
                                mPeriodEnd.getTimeInMillis() + ", following");
                    } else {
                        Slog.d(TAG, "new period (" + start.getTimeInMillis() + "," +
                                end.getTimeInMillis() + ") replacing old (" +
                                mPeriodStart.getTimeInMillis() + "," +
                                mPeriodEnd.getTimeInMillis() + ")");
                    }
                }
                synchronized (mParent) {
                    ++mCurrentPeriod;
                    if (mCurrentPeriod >= mPeriodCount) mCurrentPeriod = 0;
                    mPeriodRxData[mCurrentPeriod] = 0;
                    mPeriodTxData[mCurrentPeriod] = 0;
                }
            }
            setPeriodStart(start);
            setPeriodEnd(end);
            record();
            return startNewPeriod;
        }

        public long getPeriodEnd() {
            synchronized (mParent) {
                return mPeriodEnd.getTimeInMillis();
            }
        }

        private void setPeriodEnd(Calendar end) {
            synchronized (mParent) {
                mPeriodEnd = end;
            }
        }

        public long getPeriodStart() {
            synchronized (mParent) {
                return mPeriodStart.getTimeInMillis();
            }
        }

        private void setPeriodStart(Calendar start) {
            synchronized (mParent) {
                mPeriodStart = start;
            }
        }

        public int getPeriodCount() {
            synchronized (mParent) {
                return mPeriodCount;
            }
        }

        private void zeroData(int field) {
            synchronized (mParent) {
                for(int period = 0; period<mPeriodCount; period++) {
                    mPeriodRxData[period] = 0;
                    mPeriodTxData[period] = 0;
                }
                mCurrentPeriod = 0;
            }

        }

        // if time moves backward accumulate all read/write that's lost into the now
        // otherwise time moved forward.
        void addData(long bytesRead, long bytesWritten) {
            checkForSubscriberId();

            synchronized (mParent) {
                mPeriodRxData[mCurrentPeriod] += bytesRead;
                mPeriodTxData[mCurrentPeriod] += bytesWritten;
            }
            record();
        }

        private File getDataFile() {
            File dataDir = Environment.getDataDirectory();
            File throttleDir = new File(dataDir, "system/throttle");
            throttleDir.mkdirs();
            String mImsi = mTelephonyManager.getSubscriberId();
            File dataFile;
            if (mImsi == null) {
                dataFile = useMRUFile(throttleDir);
                if (VDBG) Slog.v(TAG, "imsi not available yet, using " + dataFile);
            } else {
                String imsiHash = Integer.toString(mImsi.hashCode());
                dataFile = new File(throttleDir, imsiHash);
            }
            // touch the file so it's not LRU
            dataFile.setLastModified(System.currentTimeMillis());
            checkAndDeleteLRUDataFile(throttleDir);
            return dataFile;
        }

        // TODO - get broadcast (TelephonyIntents.ACTION_SIM_STATE_CHANGED) instead of polling
        private void checkForSubscriberId() {
            if (mImsi != null) return;

            mImsi = mTelephonyManager.getSubscriberId();
            if (mImsi == null) return;

            if (VDBG) Slog.d(TAG, "finally have imsi - retreiving data");
            retrieve();
        }

        private final static int MAX_SIMS_SUPPORTED = 3;

        private void checkAndDeleteLRUDataFile(File dir) {
            File[] files = dir.listFiles();

            if (files.length <= MAX_SIMS_SUPPORTED) return;
            if (DBG) Slog.d(TAG, "Too many data files");
            do {
                File oldest = null;
                for (File f : files) {
                    if ((oldest == null) || (oldest.lastModified() > f.lastModified())) {
                        oldest = f;
                    }
                }
                if (oldest == null) return;
                if (DBG) Slog.d(TAG, " deleting " + oldest);
                oldest.delete();
                files = dir.listFiles();
            } while (files.length > MAX_SIMS_SUPPORTED);
        }

        private File useMRUFile(File dir) {
            File newest = null;
            File[] files = dir.listFiles();

            for (File f : files) {
                if ((newest == null) || (newest.lastModified() < f.lastModified())) {
                    newest = f;
                }
            }
            if (newest == null) {
                newest = new File(dir, "temp");
            }
            return newest;
        }


        private static final int DATA_FILE_VERSION = 1;

        private void record() {
            // 1 int version
            // 1 int mPeriodCount
            // 13*6 long[PERIOD_COUNT] mPeriodRxData
            // 13*6 long[PERIOD_COUNT] mPeriodTxData
            // 1  int mCurrentPeriod
            // 13 long periodStartMS
            // 13 long periodEndMS
            // 200 chars max
            StringBuilder builder = new StringBuilder();
            builder.append(DATA_FILE_VERSION);
            builder.append(":");
            builder.append(mPeriodCount);
            builder.append(":");
            for(int i = 0; i < mPeriodCount; i++) {
                builder.append(mPeriodRxData[i]);
                builder.append(":");
            }
            for(int i = 0; i < mPeriodCount; i++) {
                builder.append(mPeriodTxData[i]);
                builder.append(":");
            }
            builder.append(mCurrentPeriod);
            builder.append(":");
            builder.append(mPeriodStart.getTimeInMillis());
            builder.append(":");
            builder.append(mPeriodEnd.getTimeInMillis());

            BufferedWriter out = null;
            try {
                out = new BufferedWriter(new FileWriter(getDataFile()), 256);
                out.write(builder.toString());
            } catch (IOException e) {
                Slog.e(TAG, "Error writing data file");
                return;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception e) {}
                }
            }
        }

        private void retrieve() {
            // clean out any old data first.  If we fail to read we don't want old stuff
            zeroData(0);

            File f = getDataFile();
            byte[] buffer;
            FileInputStream s = null;
            try {
                buffer = new byte[(int)f.length()];
                s = new FileInputStream(f);
                s.read(buffer);
            } catch (IOException e) {
                Slog.e(TAG, "Error reading data file");
                return;
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception e) {}
                }
            }
            String data = new String(buffer);
            if (data == null || data.length() == 0) {
                if (DBG) Slog.d(TAG, "data file empty");
                return;
            }
            String[] parsed = data.split(":");
            int parsedUsed = 0;
            if (parsed.length < 6) {
                Slog.e(TAG, "reading data file with insufficient length - ignoring");
                return;
            }

            int periodCount;
            long[] periodRxData;
            long[] periodTxData;
            int currentPeriod;
            Calendar periodStart;
            Calendar periodEnd;
            try {
                if (Integer.parseInt(parsed[parsedUsed++]) != DATA_FILE_VERSION) {
                    Slog.e(TAG, "reading data file with bad version - ignoring");
                    return;
                }

                periodCount = Integer.parseInt(parsed[parsedUsed++]);
                if (parsed.length != 5 + (2 * periodCount)) {
                    Slog.e(TAG, "reading data file with bad length (" + parsed.length +
                            " != " + (5 + (2 * periodCount)) + ") - ignoring");
                    return;
                }
                periodRxData = new long[periodCount];
                for (int i = 0; i < periodCount; i++) {
                    periodRxData[i] = Long.parseLong(parsed[parsedUsed++]);
                }
                periodTxData = new long[periodCount];
                for (int i = 0; i < periodCount; i++) {
                    periodTxData[i] = Long.parseLong(parsed[parsedUsed++]);
                }

                currentPeriod = Integer.parseInt(parsed[parsedUsed++]);

                periodStart = new GregorianCalendar();
                periodStart.setTimeInMillis(Long.parseLong(parsed[parsedUsed++]));
                periodEnd = new GregorianCalendar();
                periodEnd.setTimeInMillis(Long.parseLong(parsed[parsedUsed++]));
            } catch (Exception e) {
                Slog.e(TAG, "Error parsing data file - ignoring");
                return;
            }
            synchronized (mParent) {
                mPeriodCount = periodCount;
                mPeriodRxData = periodRxData;
                mPeriodTxData = periodTxData;
                mCurrentPeriod = currentPeriod;
                mPeriodStart = periodStart;
                mPeriodEnd = periodEnd;
            }
        }

        long getPeriodRx(int which) {
            synchronized (mParent) {
                if (which > mPeriodCount) return 0;
                which = mCurrentPeriod - which;
                if (which < 0) which += mPeriodCount;
                return mPeriodRxData[which];
            }
        }
        long getPeriodTx(int which) {
            synchronized (mParent) {
                if (which > mPeriodCount) return 0;
                which = mCurrentPeriod - which;
                if (which < 0) which += mPeriodCount;
                return mPeriodTxData[which];
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ThrottleService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }
        pw.println();

        pw.println("The threshold is " + mPolicyThreshold.get() +
                ", after which you experince throttling to " +
                mPolicyThrottleValue.get() + "kbps");
        pw.println("Current period is " +
                (mRecorder.getPeriodEnd() - mRecorder.getPeriodStart())/1000 + " seconds long " +
                "and ends in " + (getResetTime(mIface) - System.currentTimeMillis()) / 1000 +
                " seconds.");
        pw.println("Polling every " + mPolicyPollPeriodSec + " seconds");
        pw.println("Current Throttle Index is " + mThrottleIndex.get());
        pw.println("Max NTP Cache Age is " + mMaxNtpCacheAgeSec);

        for (int i = 0; i < mRecorder.getPeriodCount(); i++) {
            pw.println(" Period[" + i + "] - read:" + mRecorder.getPeriodRx(i) + ", written:" +
                    mRecorder.getPeriodTx(i));
        }
    }
}
