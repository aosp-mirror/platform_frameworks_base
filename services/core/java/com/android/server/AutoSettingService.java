/*
 * Copyright (C) 2023 Yet Another AOSP Project
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

import static com.android.internal.util.xtended.AutoSettingConsts.MODE_DISABLED;
import static com.android.internal.util.xtended.AutoSettingConsts.MODE_NIGHT;
import static com.android.internal.util.xtended.AutoSettingConsts.MODE_TIME;
import static com.android.internal.util.xtended.AutoSettingConsts.MODE_MIXED_SUNSET;
import static com.android.internal.util.xtended.AutoSettingConsts.MODE_MIXED_SUNRISE;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import android.util.Slog;

import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.io.File;
import java.lang.IllegalArgumentException;
import java.util.Calendar;
import java.util.List;

public abstract class AutoSettingService extends SystemService {
    private static final String PREF_DIR_NAME = "shared_prefs";

    private final String TAG;
    private final String PREF_FILE_NAME;
    private final String PREF_TIME_KEY;

    protected final Context mContext;
    private final AlarmManager mAlarmManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TwilightManager mTwilightManager;
    private TwilightState mTwilightState;
    private SharedPreferences mSharedPreferences;

    /**
     * Current operation mode
     * Can either be {@link #MODE_DISABLED}, {@link #MODE_NIGHT} or {@link #MODE_TIME}
     */
    private int mMode = MODE_DISABLED;
    /**
     * Whether setting is currently active
     */
    private boolean mActive = false;
    /**
     * Whether next alarm should enable or disable the setting
     */
    private boolean mIsNextActivate = false;

    private boolean mTwilightRegistered = false;
    private boolean mTimeRegistered = false;
    private boolean mSelfChange = false;
    private boolean mOverrideOnce = false;
    private long mLastSetTime = 0;

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            if (mMode != MODE_NIGHT && mMode < MODE_MIXED_SUNSET) {
                // just incase
                setTwilightListener(false);
                return;
            }
            Slog.v(TAG, "onTwilightStateChanged state: " + state);
            if (state == null) return;
            mTwilightState = state;
            if (mMode < MODE_MIXED_SUNSET) mHandler.post(() -> maybeActivateNight(false));
            else mHandler.post(() -> maybeActivateTime(false));
        }
    };

    private final BroadcastReceiver mTimeChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mMode != MODE_TIME && mMode < MODE_MIXED_SUNSET) {
                // just incase
                setTimeReceiver(false);
                return;
            }
            Slog.v(TAG, "mTimeChangedReceiver onReceive");
            mHandler.post(() -> maybeActivateTime(false));
        }
    };

    /**
     * A class to manage and handle alarms
     */
    private class Alarm implements AlarmManager.OnAlarmListener {
        @Override
        public void onAlarm() {
            Slog.v(TAG, "onAlarm");
            mHandler.post(() -> setActive(mIsNextActivate));
            if (mMode == MODE_TIME || mMode >= MODE_MIXED_SUNSET)
                mHandler.post(() -> maybeActivateTime(false));
            else
                maybeActivateNight(false);
        }

        /**
         * Set a new alarm using a Calendar
         * @param time time as Calendar
         */
        public void set(Calendar time) {
            set(time.getTimeInMillis());
        }

        /**
         * Set a new alarm using ms since epoch
         * @param time time as ms since epoch
         */
        public void set(long time) {
            cancel(); // making sure there is no more than 1
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                    time, TAG, this, mHandler);
            mLastSetTime = time;
            Slog.v(TAG, "new alarm set to " + time
                    + " mIsNextActivate=" + mIsNextActivate);
        }

        public void cancel() {
            mAlarmManager.cancel(this);
            mLastSetTime = 0;
            Slog.v(TAG, "alarm cancelled");
        }
    }

    private final Alarm mAlarm = new Alarm();

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            for (Uri uri : getObserveUris()) {
                resolver.registerContentObserver(uri, false, this);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.getLastPathSegment().equals(getMainSetting())) {
                if (mSelfChange) {
                    mSelfChange = false;
                    return;
                }

                mActive = getIsActive();

                if (mLastSetTime != 0 && mActive == mIsNextActivate) {
                    // we have a future alarm set and user left current state
                    // save the next alarm
                    Slog.v(TAG, "user abandoned state. active: " + mActive);
                    mSharedPreferences.edit()
                            .putLong(PREF_TIME_KEY, mLastSetTime).apply();
                    return;
                }
                Slog.v(TAG, "removing PREF_TIME_KEY. active: " + mActive);
                mSharedPreferences.edit().remove(PREF_TIME_KEY).apply();
                return;
            }
            mHandler.post(() -> initState());
        }
    }

    private final SettingsObserver mSettingsObserver;

    public AutoSettingService(Context context) {
        super(context);

        TAG = getTag();
        PREF_FILE_NAME = TAG + "_preferences.xml";
        PREF_TIME_KEY = TAG + "_last_time";

        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mSettingsObserver = new SettingsObserver(mHandler);
        mActive = getIsActive();
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting " + TAG);
        publish();
        mSettingsObserver.observe();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Slog.v(TAG, "onBootPhase PHASE_SYSTEM_SERVICES_READY");
            mTwilightManager = getLocalService(TwilightManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Slog.v(TAG, "onBootPhase PHASE_BOOT_COMPLETED");
            // we want to access our shared preferences before unlock
            // use device encrypted storage & context for that
            // also make sure to not store any sensitive data there
            final File prefsFile = new File(
                    new File(Environment.getDataSystemDeDirectory(
                        UserHandle.USER_SYSTEM), PREF_DIR_NAME), PREF_FILE_NAME);
            mSharedPreferences = mContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
            mHandler.post(() -> initState(true));
        }
    }

    /**
     * Registers or unregisters {@link #mTimeChangedReceiver}
     * @param register Register when true, unregister when false
     */
    private void setTimeReceiver(boolean register) {
        if (register) {
            Slog.v(TAG, "Registering mTimeChangedReceiver");
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mContext.registerReceiver(mTimeChangedReceiver, intentFilter);
            mTimeRegistered = true;
            return;
        }
        try {
            mContext.unregisterReceiver(mTimeChangedReceiver);
            Slog.v(TAG, "Unregistered mTimeChangedReceiver");
        } catch (IllegalArgumentException e) {
            // nothing to do. Already unregistered
        }
        mTimeRegistered = false;
    }

    /**
     * Registers or unregisters {@link #mTwilightListener}
     * @param register Register when true, unregister when false
     */
    private void setTwilightListener(boolean register) {
        if (register) {
            Slog.v(TAG, "Registering mTwilightListener");
            mTwilightManager.registerListener(mTwilightListener, mHandler);
            mTwilightState = mTwilightManager.getLastTwilightState();
            mTwilightRegistered = true;
            return;
        }
        try {
            mTwilightManager.unregisterListener(mTwilightListener);
            Slog.v(TAG, "Unregistered mTwilightListener");
        } catch (IllegalArgumentException e) {
            // nothing to do. Already unregistered
        }
        mTwilightRegistered = false;
    }

    /**
     * See {@link #initState(boolean)}
     */
    private void initState() {
        initState(false);
    }

    /**
     * Initiates the state according to user settings
     * Registers or unregisters listeners and calls {@link #maybeActivate()}
     * @param boot true if triggered by boot
     */
    private void initState(boolean boot) {
        if (boot && mSharedPreferences.contains(PREF_TIME_KEY)) {
            final long prefTime = mSharedPreferences.getLong(PREF_TIME_KEY, 0);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(prefTime);
            // skip setting AOD once if we left the state before a reboot
            if (cal.after(Calendar.getInstance()))
                mOverrideOnce = true;
        }

        final int mode = getModeValue();
        mMode = mode;
        mAlarm.cancel(); // cancelling set alarm
        // unregister all registered listeners
        if (mTimeRegistered) setTimeReceiver(false);
        if (mTwilightRegistered) setTwilightListener(false);
        // erase shared preferences
        if (!boot) mSharedPreferences.edit().remove(PREF_TIME_KEY).apply();
        switch (mMode) {
            default:
            case MODE_DISABLED:
                return;
            case MODE_TIME:
                setTimeReceiver(true);
                break;
            case MODE_NIGHT:
                setTwilightListener(true);
                break;
            case MODE_MIXED_SUNSET:
            case MODE_MIXED_SUNRISE:
                setTwilightListener(true);
                setTimeReceiver(true);
                break;
        }
        maybeActivate();
    }

    /**
     * Calls the correct function to set the next alarm according to {@link #mMode}
     */
    private void maybeActivate() {
        switch (mMode) {
            default:
            case MODE_DISABLED:
                break;
            case MODE_NIGHT:
                maybeActivateNight();
                break;
            case MODE_TIME:
            case MODE_MIXED_SUNSET:
            case MODE_MIXED_SUNRISE:
                maybeActivateTime();
                break;
        }
    }

    /**
     * See {@link #maybeActivateNight(boolean)}
     */
    private void maybeActivateNight() {
        maybeActivateNight(true);
    }

    /**
     * Sets the next alarm for {@link #MODE_NIGHT}
     * @param setActive Whether to set activation state.
     *                  When false only updates the alarm
     */
    private void maybeActivateNight(boolean setActive) {
        if (mTwilightState == null) {
            Slog.e(TAG, "aborting maybeActivateNight(). mTwilightState is null");
            return;
        }
        mIsNextActivate = !mTwilightState.isNight();
        mAlarm.set(mIsNextActivate ? mTwilightState.sunsetTimeMillis()
                : mTwilightState.sunriseTimeMillis());
        if (setActive) mHandler.post(() -> setAutoActive(!mIsNextActivate));
    }

    /**
     * See {@link #maybeActivateTime(boolean)}
     */
    private void maybeActivateTime() {
        maybeActivateTime(true);
    }

    /**
     * Sets the next alarm for {@link #MODE_TIME}, {@link #MODE_MIXED_SUNSET} and
     *                         {@link #MODE_MIXED_SUNRISE}
     * @param setActive Whether to set activation state
     *                  When false only updates the alarm
     */
    private void maybeActivateTime(boolean setActive) {
        Calendar currentTime = Calendar.getInstance();
        Calendar since = Calendar.getInstance();
        Calendar till = Calendar.getInstance();
        String value = getTimeValue();
        if (value == null || value.equals("")) value = "20:00,07:00";
        String[] times = value.split(",", 0);
        String[] sinceValues = times[0].split(":", 0);
        String[] tillValues = times[1].split(":", 0);
        since.set(Calendar.HOUR_OF_DAY, Integer.parseInt(sinceValues[0]));
        since.set(Calendar.MINUTE, Integer.parseInt(sinceValues[1]));
        since.set(Calendar.SECOND, 0);
        till.set(Calendar.HOUR_OF_DAY, Integer.parseInt(tillValues[0]));
        till.set(Calendar.MINUTE, Integer.parseInt(tillValues[1]));
        till.set(Calendar.SECOND, 0);

        // handle mixed modes
        if (mMode >= MODE_MIXED_SUNSET) {
            if (mTwilightState == null) {
                Slog.e(TAG, "aborting maybeActivateTime(). mTwilightState is null");
                return;
            }
            if (mMode == MODE_MIXED_SUNSET) {
                since.setTimeInMillis(mTwilightState.sunsetTimeMillis());
            } else { // MODE_MIXED_SUNRISE
                till.setTimeInMillis(mTwilightState.sunriseTimeMillis());
                if (!mTwilightState.isNight()) till.add(Calendar.DATE, 1);
            }
        }

        // roll to the next day if needed be
        if (since.after(till)) till.add(Calendar.DATE, 1);
        if (currentTime.after(since) && currentTime.compareTo(till) >= 0) {
            since.add(Calendar.DATE, 1);
            till.add(Calendar.DATE, 1);
        }
        // abort if the user was dumb enough to set the same time
        if (since.compareTo(till) == 0) {
            Slog.e(TAG, "Aborting maybeActivateTime(). Time diff is 0");
            return;
        }

        // update the next alarm
        mIsNextActivate = currentTime.before(since);
        mAlarm.set(mIsNextActivate ? since : till);

        // activate or disable according to current time
        if (setActive) setAutoActive(currentTime.compareTo(since) >= 0
                && currentTime.before(till));
    }

    /**
     * Activates or inactivates Setting
     * @param active Whether to enable or disable Setting
     */
    private void setAutoActive(boolean active) {
        if (mOverrideOnce) {
            Slog.v(TAG, "setAutoActive: user abandoned this session before, skipping");
            mOverrideOnce = false;
            return;
        }
        mSharedPreferences.edit().remove(PREF_TIME_KEY).apply();

        if (mActive == active) return;
        mActive = active;
        Slog.v(TAG, "setAutoActive: active=" + active);
        mSelfChange = true;
        setActive(active);
    }

    /**
     * Used to publish the local service
     */
    protected abstract void publish();

    /**
     * @return Uris to listen to for {@link mSettingsObserver}
     */
    protected abstract List<Uri> getObserveUris();

    /**
     * @return The main setting string
     */
    protected abstract String getMainSetting();

    /**
     * @return The time setting value
     */
    protected abstract String getTimeValue();

    /**
     * @return The mode setting value
     */
    protected abstract int getModeValue();

    /**
     * @return true if the setting is currently active
     */
    protected abstract boolean getIsActive();

    /**
     * Apply a setting state
     * @param active Whether to activate or deactivate
     */
    protected abstract void setActive(boolean active);

    /**
     * @return the tag for logs and prefs
     */
    protected abstract String getTag();
}

