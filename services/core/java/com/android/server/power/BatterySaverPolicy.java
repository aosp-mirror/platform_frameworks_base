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
package com.android.server.power;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.R;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Class to decide whether to turn on battery saver mode for specific service
 *
 * Test: atest BatterySaverPolicyTest
 */
public class BatterySaverPolicy extends ContentObserver {
    private static final String TAG = "BatterySaverPolicy";

    // Value of batterySaverGpsMode such that GPS isn't affected by battery saver mode.
    public static final int GPS_MODE_NO_CHANGE = 0;
    // Value of batterySaverGpsMode such that GPS is disabled when battery saver mode
    // is enabled and the screen is off.
    public static final int GPS_MODE_DISABLED_WHEN_SCREEN_OFF = 1;
    // Secure setting for GPS behavior when battery saver mode is on.
    public static final String SECURE_KEY_GPS_MODE = "batterySaverGpsMode";

    private static final String KEY_GPS_MODE = "gps_mode";
    private static final String KEY_VIBRATION_DISABLED = "vibration_disabled";
    private static final String KEY_ANIMATION_DISABLED = "animation_disabled";
    private static final String KEY_SOUNDTRIGGER_DISABLED = "soundtrigger_disabled";
    private static final String KEY_FIREWALL_DISABLED = "firewall_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_DISABLED = "adjust_brightness_disabled";
    private static final String KEY_DATASAVER_DISABLED = "datasaver_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_FACTOR = "adjust_brightness_factor";
    private static final String KEY_FULLBACKUP_DEFERRED = "fullbackup_deferred";
    private static final String KEY_KEYVALUE_DEFERRED = "keyvaluebackup_deferred";
    private static final String KEY_FORCE_ALL_APPS_STANDBY_JOBS = "force_all_apps_standby_jobs";
    private static final String KEY_FORCE_ALL_APPS_STANDBY_ALARMS = "force_all_apps_standby_alarms";
    private static final String KEY_OPTIONAL_SENSORS_DISABLED = "optional_sensors_disabled";

    private static final String KEY_SCREEN_ON_FILE_PREFIX = "file-on:";
    private static final String KEY_SCREEN_OFF_FILE_PREFIX = "file-off:";

    private static String mSettings;
    private static String mDeviceSpecificSettings;
    private static String mDeviceSpecificSettingsSource; // For dump() only.

    /**
     * {@code true} if vibration is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_VIBRATION_DISABLED
     */
    private boolean mVibrationDisabled;

    /**
     * {@code true} if animation is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ANIMATION_DISABLED
     */
    private boolean mAnimationDisabled;

    /**
     * {@code true} if sound trigger is disabled in battery saver mode
     * in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_SOUNDTRIGGER_DISABLED
     */
    private boolean mSoundTriggerDisabled;

    /**
     * {@code true} if full backup is deferred in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_FULLBACKUP_DEFERRED
     */
    private boolean mFullBackupDeferred;

    /**
     * {@code true} if key value backup is deferred in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_KEYVALUE_DEFERRED
     */
    private boolean mKeyValueBackupDeferred;

    /**
     * {@code true} if network policy firewall is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_FIREWALL_DISABLED
     */
    private boolean mFireWallDisabled;

    /**
     * {@code true} if adjust brightness is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ADJUST_BRIGHTNESS_DISABLED
     */
    private boolean mAdjustBrightnessDisabled;

    /**
     * {@code true} if data saver is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_DATASAVER_DISABLED
     */
    private boolean mDataSaverDisabled;

    /**
     * This is the flag to decide the gps mode in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_GPS_MODE
     */
    private int mGpsMode;

    /**
     * This is the flag to decide the how much to adjust the screen brightness. This is
     * the float value from 0 to 1 where 1 means don't change brightness.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ADJUST_BRIGHTNESS_FACTOR
     */
    private float mAdjustBrightnessFactor;

    /**
     * Whether to put all apps in the stand-by mode or not for job scheduler.
     */
    private boolean mForceAllAppsStandbyJobs;

    /**
     * Whether to put all apps in the stand-by mode or not for alarms.
     */
    private boolean mForceAllAppsStandbyAlarms;

    /**
     * Weather to show non-essential sensors (e.g. edge sensors) or not.
     */
    private boolean mOptionalSensorsDisabled;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private Context mContext;

    @GuardedBy("mLock")
    private ContentResolver mContentResolver;

    @GuardedBy("mLock")
    private final ArrayList<BatterySaverPolicyListener> mListeners = new ArrayList<>();

    /**
     * List of [Filename -> content] that should be written when battery saver is activated
     * and the screen is on.
     *
     * We use this to change the max CPU frequencies.
     */
    @GuardedBy("mLock")
    private ArrayMap<String, String> mScreenOnFiles;

    /**
     * List of [Filename -> content] that should be written when battery saver is activated
     * and the screen is off.
     *
     * We use this to change the max CPU frequencies.
     */
    @GuardedBy("mLock")
    private ArrayMap<String, String> mScreenOffFiles;

    public interface BatterySaverPolicyListener {
        void onBatterySaverPolicyChanged(BatterySaverPolicy policy);
    }

    public BatterySaverPolicy(Handler handler) {
        super(handler);
    }

    public void systemReady(Context context) {
        synchronized (mLock) {
            mContext = context;
            mContentResolver = context.getContentResolver();

            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_CONSTANTS), false, this);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS), false, this);
        }
        onChange(true, null);
    }

    public void addListener(BatterySaverPolicyListener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    @VisibleForTesting
    String getGlobalSetting(String key) {
        return Settings.Global.getString(mContentResolver, key);
    }

    @VisibleForTesting
    int getDeviceSpecificConfigResId() {
        return R.string.config_batterySaverDeviceSpecificConfig;
    }

    @VisibleForTesting
    void onChangeForTest() {
        onChange(true, null);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        final BatterySaverPolicyListener[] listeners;
        synchronized (mLock) {
            // Load the non-device-specific setting.
            final String setting = getGlobalSetting(Settings.Global.BATTERY_SAVER_CONSTANTS);

            // Load the device specific setting.
            // We first check the global setting, and if it's empty or the string "null" is set,
            // use the default value from config.xml.
            String deviceSpecificSetting = getGlobalSetting(
                    Settings.Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS);
            mDeviceSpecificSettingsSource =
                    Settings.Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS;

            if (TextUtils.isEmpty(deviceSpecificSetting) || "null".equals(deviceSpecificSetting)) {
                deviceSpecificSetting =
                        mContext.getString(getDeviceSpecificConfigResId());
                mDeviceSpecificSettingsSource = "(overlay)";
            }

            // Update.
            updateConstantsLocked(setting, deviceSpecificSetting);

            listeners = mListeners.toArray(new BatterySaverPolicyListener[mListeners.size()]);
        }

        // Notify the listeners.
        for (BatterySaverPolicyListener listener : listeners) {
            listener.onBatterySaverPolicyChanged(this);
        }
    }

    @VisibleForTesting
    void updateConstantsLocked(final String setting, final String deviceSpecificSetting) {
        mSettings = setting;
        mDeviceSpecificSettings = deviceSpecificSetting;

        final KeyValueListParser parser = new KeyValueListParser(',');

        // Non-device-specific parameters.
        try {
            parser.setString(setting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad battery saver constants: " + setting);
        }

        mVibrationDisabled = parser.getBoolean(KEY_VIBRATION_DISABLED, true);
        mAnimationDisabled = parser.getBoolean(KEY_ANIMATION_DISABLED, true);
        mSoundTriggerDisabled = parser.getBoolean(KEY_SOUNDTRIGGER_DISABLED, true);
        mFullBackupDeferred = parser.getBoolean(KEY_FULLBACKUP_DEFERRED, true);
        mKeyValueBackupDeferred = parser.getBoolean(KEY_KEYVALUE_DEFERRED, true);
        mFireWallDisabled = parser.getBoolean(KEY_FIREWALL_DISABLED, false);
        mAdjustBrightnessDisabled = parser.getBoolean(KEY_ADJUST_BRIGHTNESS_DISABLED, false);
        mAdjustBrightnessFactor = parser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR, 0.5f);
        mDataSaverDisabled = parser.getBoolean(KEY_DATASAVER_DISABLED, true);
        mForceAllAppsStandbyJobs = parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY_JOBS, true);
        mForceAllAppsStandbyAlarms =
                parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY_ALARMS, true);
        mOptionalSensorsDisabled = parser.getBoolean(KEY_OPTIONAL_SENSORS_DISABLED, true);

        // Get default value from Settings.Secure
        final int defaultGpsMode = Settings.Secure.getInt(mContentResolver, SECURE_KEY_GPS_MODE,
                GPS_MODE_NO_CHANGE);
        mGpsMode = parser.getInt(KEY_GPS_MODE, defaultGpsMode);

        // Non-device-specific parameters.
        try {
            parser.setString(deviceSpecificSetting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad device specific battery saver constants: "
                    + deviceSpecificSetting);
        }

        mScreenOnFiles = collectParams(parser, KEY_SCREEN_ON_FILE_PREFIX);
        mScreenOffFiles = collectParams(parser, KEY_SCREEN_OFF_FILE_PREFIX);
    }

    private static ArrayMap<String, String> collectParams(
            KeyValueListParser parser, String prefix) {
        final ArrayMap<String, String> ret = new ArrayMap<>();

        for (int i = parser.size() - 1; i >= 0; i--) {
            final String key = parser.keyAt(i);
            if (!key.startsWith(prefix)) {
                continue;
            }
            final String path = key.substring(prefix.length());

            if (!(path.startsWith("/sys/") || path.startsWith("/proc"))) {
                Slog.wtf(TAG, "Invalid path: " + path);
                continue;
            }

            ret.put(path, parser.getString(key, ""));
        }
        return ret;
    }

    /**
     * Get the {@link PowerSaveState} based on {@paramref type} and {@paramref realMode}.
     * The result will have {@link PowerSaveState#batterySaverEnabled} and some other
     * parameters when necessary.
     *
     * @param type     type of the service, one of {@link ServiceType}
     * @param realMode whether the battery saver is on by default
     * @return State data that contains battery saver data
     */
    public PowerSaveState getBatterySaverPolicy(@ServiceType int type, boolean realMode) {
        synchronized (mLock) {
            final PowerSaveState.Builder builder = new PowerSaveState.Builder()
                    .setGlobalBatterySaverEnabled(realMode);
            if (!realMode) {
                return builder.setBatterySaverEnabled(realMode)
                        .build();
            }
            switch (type) {
                case ServiceType.GPS:
                    return builder.setBatterySaverEnabled(realMode)
                            .setGpsMode(mGpsMode)
                            .build();
                case ServiceType.ANIMATION:
                    return builder.setBatterySaverEnabled(mAnimationDisabled)
                            .build();
                case ServiceType.FULL_BACKUP:
                    return builder.setBatterySaverEnabled(mFullBackupDeferred)
                            .build();
                case ServiceType.KEYVALUE_BACKUP:
                    return builder.setBatterySaverEnabled(mKeyValueBackupDeferred)
                            .build();
                case ServiceType.NETWORK_FIREWALL:
                    return builder.setBatterySaverEnabled(!mFireWallDisabled)
                            .build();
                case ServiceType.SCREEN_BRIGHTNESS:
                    return builder.setBatterySaverEnabled(!mAdjustBrightnessDisabled)
                            .setBrightnessFactor(mAdjustBrightnessFactor)
                            .build();
                case ServiceType.DATA_SAVER:
                    return builder.setBatterySaverEnabled(!mDataSaverDisabled)
                            .build();
                case ServiceType.SOUND:
                    return builder.setBatterySaverEnabled(mSoundTriggerDisabled)
                            .build();
                case ServiceType.VIBRATION:
                    return builder.setBatterySaverEnabled(mVibrationDisabled)
                            .build();
                case ServiceType.FORCE_ALL_APPS_STANDBY_JOBS:
                    return builder.setBatterySaverEnabled(mForceAllAppsStandbyJobs)
                            .build();
                case ServiceType.FORCE_ALL_APPS_STANDBY_ALARMS:
                    return builder.setBatterySaverEnabled(mForceAllAppsStandbyAlarms)
                            .build();
                case ServiceType.OPTIONAL_SENSORS:
                    return builder.setBatterySaverEnabled(mOptionalSensorsDisabled)
                            .build();
                default:
                    return builder.setBatterySaverEnabled(realMode)
                            .build();
            }
        }
    }

    public ArrayMap<String, String> getFileValues(boolean screenOn) {
        synchronized (mLock) {
            return screenOn ? mScreenOnFiles : mScreenOffFiles;
        }
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Battery saver policy");
            pw.println("  Settings " + Settings.Global.BATTERY_SAVER_CONSTANTS);
            pw.println("  value: " + mSettings);
            pw.println("  Settings " + mDeviceSpecificSettingsSource);
            pw.println("  value: " + mDeviceSpecificSettings);

            pw.println();
            pw.println("  " + KEY_VIBRATION_DISABLED + "=" + mVibrationDisabled);
            pw.println("  " + KEY_ANIMATION_DISABLED + "=" + mAnimationDisabled);
            pw.println("  " + KEY_FULLBACKUP_DEFERRED + "=" + mFullBackupDeferred);
            pw.println("  " + KEY_KEYVALUE_DEFERRED + "=" + mKeyValueBackupDeferred);
            pw.println("  " + KEY_FIREWALL_DISABLED + "=" + mFireWallDisabled);
            pw.println("  " + KEY_DATASAVER_DISABLED + "=" + mDataSaverDisabled);
            pw.println("  " + KEY_ADJUST_BRIGHTNESS_DISABLED + "=" + mAdjustBrightnessDisabled);
            pw.println("  " + KEY_ADJUST_BRIGHTNESS_FACTOR + "=" + mAdjustBrightnessFactor);
            pw.println("  " + KEY_GPS_MODE + "=" + mGpsMode);
            pw.println("  " + KEY_FORCE_ALL_APPS_STANDBY_JOBS + "=" + mForceAllAppsStandbyJobs);
            pw.println("  " + KEY_FORCE_ALL_APPS_STANDBY_ALARMS + "=" + mForceAllAppsStandbyAlarms);
            pw.println("  " + KEY_OPTIONAL_SENSORS_DISABLED + "=" + mOptionalSensorsDisabled);
            pw.println();

            pw.print("  Screen On Files:\n");
            dumpMap(pw, "    ", mScreenOnFiles);
            pw.println();

            pw.print("  Screen Off Files:\n");
            dumpMap(pw, "    ", mScreenOffFiles);
            pw.println();
        }
    }

    private void dumpMap(PrintWriter pw, String prefix, ArrayMap<String, String> map) {
        if (map == null) {
            return;
        }
        final int size = map.size();
        for (int i = 0; i < size; i++) {
            pw.print(prefix);
            pw.print(map.keyAt(i));
            pw.print(": '");
            pw.print(map.valueAt(i));
            pw.println("'");
        }
    }
}
