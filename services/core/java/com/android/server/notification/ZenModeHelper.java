/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.util.Slog;

import com.android.internal.R;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * NotificationManagerService helper for functionality related to zen mode.
 */
public class ZenModeHelper {
    private static final String TAG = "ZenModeHelper";

    private static final String ACTION_ENTER_ZEN = "enter_zen";
    private static final int REQUEST_CODE_ENTER = 100;
    private static final String ACTION_EXIT_ZEN = "exit_zen";
    private static final int REQUEST_CODE_EXIT = 101;
    private static final String EXTRA_TIME = "time";

    private final Context mContext;
    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final AppOpsManager mAppOps;
    private final ZenModeConfig mDefaultConfig;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private int mZenMode;
    private ZenModeConfig mConfig;

    // temporary, until we update apps to provide metadata
    private static final Set<String> CALL_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.example.notificationshowcase"
            ));
    private static final Set<String> MESSAGE_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.google.android.talk",
            "com.android.mms",
            "com.android.example.notificationshowcase"
            ));
    private static final Set<String> ALARM_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.google.android.deskclock"
            ));

    public ZenModeHelper(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mDefaultConfig = readDefaultConfig(context.getResources());
        mConfig = mDefaultConfig;
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ENTER_ZEN);
        filter.addAction(ACTION_EXIT_ZEN);
        mContext.registerReceiver(new ZenBroadcastReceiver(), filter);
    }

    public static ZenModeConfig readDefaultConfig(Resources resources) {
        XmlResourceParser parser = null;
        try {
            parser = resources.getXml(R.xml.default_zen_mode_config);
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                final ZenModeConfig config = ZenModeConfig.readXml(parser);
                if (config != null) return config;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error reading default zen mode config from resource", e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
        return new ZenModeConfig();
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public boolean shouldIntercept(NotificationRecord record, boolean previouslySeen) {
        if (mZenMode != Global.ZEN_MODE_OFF) {
            if (previouslySeen && !record.isIntercepted()) {
                // notifications never transition from not intercepted to intercepted
                return false;
            }
            if (isAlarm(record)) {
                return false;
            }
            // audience has veto power over all following rules
            if (!audienceMatches(record)) {
                return true;
            }
            if (isCall(record)) {
                return !mConfig.allowCalls;
            }
            if (isMessage(record)) {
                return !mConfig.allowMessages;
            }
            return true;
        }
        return false;
    }

    public int getZenMode() {
        return mZenMode;
    }

    public void setZenMode(int zenModeValue) {
        Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, zenModeValue);
    }

    public void updateZenMode() {
        final int mode = Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE, Global.ZEN_MODE_OFF);
        if (mode != mZenMode) {
            Slog.d(TAG, String.format("updateZenMode: %s -> %s",
                    Global.zenModeToString(mZenMode),
                    Global.zenModeToString(mode)));
        }
        mZenMode = mode;
        final boolean zen = mZenMode != Global.ZEN_MODE_OFF;
        final String[] exceptionPackages = null; // none (for now)

        // call restrictions
        final boolean muteCalls = zen && !mConfig.allowCalls;
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, AudioManager.STREAM_RING,
                muteCalls ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        mAppOps.setRestriction(AppOpsManager.OP_PLAY_AUDIO, AudioManager.STREAM_RING,
                muteCalls ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);

        // restrict vibrations with no hints
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, AudioManager.USE_DEFAULT_STREAM_TYPE,
                zen ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        dispatchOnZenModeChanged();
    }

    public boolean allowDisable(int what, IBinder token, String pkg) {
        // TODO(cwren): delete this API before the next release. Bug:15344099
        if (CALL_PACKAGES.contains(pkg)) {
            return mZenMode == Global.ZEN_MODE_OFF || mConfig.allowCalls;
        }
        return true;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mZenMode=");
        pw.println(Global.zenModeToString(mZenMode));
        pw.print(prefix); pw.print("mConfig="); pw.println(mConfig);
        pw.print(prefix); pw.print("mDefaultConfig="); pw.println(mDefaultConfig);
    }

    public void readXml(XmlPullParser parser) throws XmlPullParserException, IOException {
        final ZenModeConfig config = ZenModeConfig.readXml(parser);
        if (config != null) {
            setConfig(config);
        }
    }

    public void writeXml(XmlSerializer out) throws IOException {
        mConfig.writeXml(out);
    }

    public ZenModeConfig getConfig() {
        return mConfig;
    }

    public boolean setConfig(ZenModeConfig config) {
        if (config == null || !config.isValid()) return false;
        if (config.equals(mConfig)) return true;
        mConfig = config;
        Slog.d(TAG, "mConfig=" + mConfig);
        dispatchOnConfigChanged();
        final String val = Integer.toString(mConfig.hashCode());
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_CONFIG_ETAG, val);
        updateAlarms();
        updateZenMode();
        return true;
    }

    private void dispatchOnConfigChanged() {
        for (Callback callback : mCallbacks) {
            callback.onConfigChanged();
        }
    }

    private void dispatchOnZenModeChanged() {
        for (Callback callback : mCallbacks) {
            callback.onZenModeChanged();
        }
    }

    private boolean isAlarm(NotificationRecord record) {
        return ALARM_PACKAGES.contains(record.sbn.getPackageName());
    }

    private boolean isCall(NotificationRecord record) {
        return CALL_PACKAGES.contains(record.sbn.getPackageName());
    }

    private boolean isMessage(NotificationRecord record) {
        return MESSAGE_PACKAGES.contains(record.sbn.getPackageName());
    }

    private boolean audienceMatches(NotificationRecord record) {
        switch (mConfig.allowFrom) {
            case ZenModeConfig.SOURCE_ANYONE:
                return true;
            case ZenModeConfig.SOURCE_CONTACT:
                return record.getContactAffinity() >= ValidateNotificationPeople.VALID_CONTACT;
            case ZenModeConfig.SOURCE_STAR:
                return record.getContactAffinity() >= ValidateNotificationPeople.STARRED_CONTACT;
            default:
                Slog.w(TAG, "Encountered unknown source: " + mConfig.allowFrom);
                return true;
        }
    }

    private void updateAlarms() {
        updateAlarm(ACTION_ENTER_ZEN, REQUEST_CODE_ENTER,
                mConfig.sleepStartHour, mConfig.sleepStartMinute);
        updateAlarm(ACTION_EXIT_ZEN, REQUEST_CODE_EXIT,
                mConfig.sleepEndHour, mConfig.sleepEndMinute);
    }

    private void updateAlarm(String action, int requestCode, int hr, int min) {
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final long now = System.currentTimeMillis();
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hr);
        c.set(Calendar.MINUTE, min);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) {
            c.add(Calendar.DATE, 1);
        }
        final long time = c.getTimeInMillis();
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, requestCode,
                new Intent(action).putExtra(EXTRA_TIME, time), PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        if (mConfig.sleepMode != null) {
            Slog.d(TAG, String.format("Scheduling %s for %s, %s in the future, now=%s",
                    action, ts(time), time - now, ts(now)));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        }
    }

    private static String ts(long time) {
        return new Date(time) + " (" + time + ")";
    }

    public static boolean isWeekend(long time, int offsetDays) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        if (offsetDays != 0) {
            c.add(Calendar.DATE, offsetDays);
        }
        final int day = c.get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY;
    }

    private class SettingsObserver extends ContentObserver {
        private final Uri ZEN_MODE = Global.getUriFor(Global.ZEN_MODE);

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(ZEN_MODE, false /*notifyForDescendents*/, this);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (ZEN_MODE.equals(uri)) {
                updateZenMode();
            }
        }
    }

    private class ZenBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_ENTER_ZEN.equals(intent.getAction())) {
                setZenMode(intent, 1, Global.ZEN_MODE_ON);
            } else if (ACTION_EXIT_ZEN.equals(intent.getAction())) {
                setZenMode(intent, 0, Global.ZEN_MODE_OFF);
            }
        }

        private void setZenMode(Intent intent, int wkendOffsetDays, int zenModeValue) {
            final long schTime = intent.getLongExtra(EXTRA_TIME, 0);
            final long now = System.currentTimeMillis();
            Slog.d(TAG, String.format("%s scheduled for %s, fired at %s, delta=%s",
                    intent.getAction(), ts(schTime), ts(now), now - schTime));

            final boolean skip = ZenModeConfig.SLEEP_MODE_WEEKNIGHTS.equals(mConfig.sleepMode) &&
                    isWeekend(schTime, wkendOffsetDays);

            if (skip) {
                Slog.d(TAG, "Skipping zen mode update for the weekend");
            } else {
                ZenModeHelper.this.setZenMode(zenModeValue);
            }
            updateAlarms();
        }
    }

    public static class Callback {
        void onConfigChanged() {}
        void onZenModeChanged() {}
    }
}
