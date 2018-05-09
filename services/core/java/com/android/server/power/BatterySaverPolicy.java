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
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.power.batterysaver.BatterySavingStats;
import com.android.server.power.batterysaver.CpuFrequencies;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to decide whether to turn on battery saver mode for specific service
 *
 * Test:
 atest ${ANDROID_BUILD_TOP}/frameworks/base/services/tests/servicestests/src/com/android/server/power/BatterySaverPolicyTest.java
 */
public class BatterySaverPolicy extends ContentObserver {
    private static final String TAG = "BatterySaverPolicy";

    public static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE.

    // Secure setting for GPS behavior when battery saver mode is on.
    public static final String SECURE_KEY_GPS_MODE = "batterySaverGpsMode";

    private static final String KEY_GPS_MODE = "gps_mode";
    private static final String KEY_VIBRATION_DISABLED = "vibration_disabled";
    private static final String KEY_ANIMATION_DISABLED = "animation_disabled";
    private static final String KEY_SOUNDTRIGGER_DISABLED = "soundtrigger_disabled";
    private static final String KEY_FIREWALL_DISABLED = "firewall_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_DISABLED = "adjust_brightness_disabled";
    private static final String KEY_DATASAVER_DISABLED = "datasaver_disabled";
    private static final String KEY_LAUNCH_BOOST_DISABLED = "launch_boost_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_FACTOR = "adjust_brightness_factor";
    private static final String KEY_FULLBACKUP_DEFERRED = "fullbackup_deferred";
    private static final String KEY_KEYVALUE_DEFERRED = "keyvaluebackup_deferred";
    private static final String KEY_FORCE_ALL_APPS_STANDBY = "force_all_apps_standby";
    private static final String KEY_FORCE_BACKGROUND_CHECK = "force_background_check";
    private static final String KEY_OPTIONAL_SENSORS_DISABLED = "optional_sensors_disabled";
    private static final String KEY_AOD_DISABLED = "aod_disabled";
    private static final String KEY_SEND_TRON_LOG = "send_tron_log";

    private static final String KEY_CPU_FREQ_INTERACTIVE = "cpufreq-i";
    private static final String KEY_CPU_FREQ_NONINTERACTIVE = "cpufreq-n";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private String mSettings;

    @GuardedBy("mLock")
    private String mDeviceSpecificSettings;

    @GuardedBy("mLock")
    private String mDeviceSpecificSettingsSource; // For dump() only.

    /**
     * A short string describing which battery saver is now enabled, which we dump in the eventlog.
     */
    @GuardedBy("mLock")
    private String mEventLogKeys;

    /**
     * {@code true} if vibration is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_VIBRATION_DISABLED
     */
    @GuardedBy("mLock")
    private boolean mVibrationDisabledConfig;

    /**
     * Whether vibration should *really* be disabled -- i.e. {@link #mVibrationDisabledConfig}
     * is true *and* {@link #mAccessibilityEnabled} is false.
     */
    @GuardedBy("mLock")
    private boolean mVibrationDisabledEffective;

    /**
     * {@code true} if animation is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ANIMATION_DISABLED
     */
    @GuardedBy("mLock")
    private boolean mAnimationDisabled;

    /**
     * {@code true} if sound trigger is disabled in battery saver mode
     * in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_SOUNDTRIGGER_DISABLED
     */
    @GuardedBy("mLock")
    private boolean mSoundTriggerDisabled;

    /**
     * {@code true} if full backup is deferred in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_FULLBACKUP_DEFERRED
     */
    @GuardedBy("mLock")
    private boolean mFullBackupDeferred;

    /**
     * {@code true} if key value backup is deferred in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_KEYVALUE_DEFERRED
     */
    @GuardedBy("mLock")
    private boolean mKeyValueBackupDeferred;

    /**
     * {@code true} if network policy firewall is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_FIREWALL_DISABLED
     */
    @GuardedBy("mLock")
    private boolean mFireWallDisabled;

    /**
     * {@code true} if adjust brightness is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ADJUST_BRIGHTNESS_DISABLED
     */
    @GuardedBy("mLock")
    private boolean mAdjustBrightnessDisabled;

    /**
     * {@code true} if data saver is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_DATASAVER_DISABLED
     */
    @GuardedBy("mLock")
    private boolean mDataSaverDisabled;

    /**
     * {@code true} if launch boost should be disabled on battery saver.
     */
    @GuardedBy("mLock")
    private boolean mLaunchBoostDisabled;

    /**
     * This is the flag to decide the gps mode in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_GPS_MODE
     */
    @GuardedBy("mLock")
    private int mGpsMode;

    /**
     * This is the flag to decide the how much to adjust the screen brightness. This is
     * the float value from 0 to 1 where 1 means don't change brightness.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ADJUST_BRIGHTNESS_FACTOR
     */
    @GuardedBy("mLock")
    private float mAdjustBrightnessFactor;

    /**
     * Whether to put all apps in the stand-by mode.
     */
    @GuardedBy("mLock")
    private boolean mForceAllAppsStandby;

    /**
     * Whether to put all apps in the stand-by mode.
     */
    @GuardedBy("mLock")
    private boolean mForceBackgroundCheck;

    /**
     * Whether to show non-essential sensors (e.g. edge sensors) or not.
     */
    @GuardedBy("mLock")
    private boolean mOptionalSensorsDisabled;

    /**
     * Whether AOD is enabled or not.
     */
    @GuardedBy("mLock")
    private boolean mAodDisabled;

    /**
     * Whether BatterySavingStats should send tron events.
     */
    @GuardedBy("mLock")
    private boolean mSendTronLog;

    @GuardedBy("mLock")
    private Context mContext;

    @GuardedBy("mLock")
    private ContentResolver mContentResolver;

    @GuardedBy("mLock")
    private AccessibilityManager mAccessibilityManager;

    @GuardedBy("mLock")
    private final List<BatterySaverPolicyListener> mListeners = new ArrayList<>();

    /**
     * List of [Filename -> content] that should be written when battery saver is activated
     * and the device is interactive.
     *
     * We use this to change the max CPU frequencies.
     */
    @GuardedBy("mLock")
    private ArrayMap<String, String> mFilesForInteractive;

    /**
     * List of [Filename -> content] that should be written when battery saver is activated
     * and the device is non-interactive.
     *
     * We use this to change the max CPU frequencies.
     */
    @GuardedBy("mLock")
    private ArrayMap<String, String> mFilesForNoninteractive;

    /**
     * Whether accessibility is enabled or not.
     */
    @GuardedBy("mLock")
    private boolean mAccessibilityEnabled;

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

            mAccessibilityManager = context.getSystemService(AccessibilityManager.class);

            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_CONSTANTS), false, this);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS), false, this);

            mAccessibilityManager.addAccessibilityStateChangeListener((enabled) -> {
                refreshSettings();
            });
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
        final ContentResolver cr;
        synchronized (mLock) {
            cr = mContentResolver;
        }
        return Settings.Global.getString(cr, key);
    }

    @VisibleForTesting
    int getDeviceSpecificConfigResId() {
        return R.string.config_batterySaverDeviceSpecificConfig;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        refreshSettings();
    }

    private void refreshSettings() {
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

    @GuardedBy("mLock")
    @VisibleForTesting
    void updateConstantsLocked(final String setting, final String deviceSpecificSetting) {
        mSettings = setting;
        mDeviceSpecificSettings = deviceSpecificSetting;

        if (DEBUG) {
            Slog.i(TAG, "mSettings=" + mSettings);
            Slog.i(TAG, "mDeviceSpecificSettings=" + mDeviceSpecificSettings);
        }

        final KeyValueListParser parser = new KeyValueListParser(',');

        // Non-device-specific parameters.
        try {
            parser.setString(setting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad battery saver constants: " + setting);
        }

        mVibrationDisabledConfig = parser.getBoolean(KEY_VIBRATION_DISABLED, true);
        mAnimationDisabled = parser.getBoolean(KEY_ANIMATION_DISABLED, false);
        mSoundTriggerDisabled = parser.getBoolean(KEY_SOUNDTRIGGER_DISABLED, true);
        mFullBackupDeferred = parser.getBoolean(KEY_FULLBACKUP_DEFERRED, true);
        mKeyValueBackupDeferred = parser.getBoolean(KEY_KEYVALUE_DEFERRED, true);
        mFireWallDisabled = parser.getBoolean(KEY_FIREWALL_DISABLED, false);
        mAdjustBrightnessDisabled = parser.getBoolean(KEY_ADJUST_BRIGHTNESS_DISABLED, true);
        mAdjustBrightnessFactor = parser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR, 0.5f);
        mDataSaverDisabled = parser.getBoolean(KEY_DATASAVER_DISABLED, true);
        mLaunchBoostDisabled = parser.getBoolean(KEY_LAUNCH_BOOST_DISABLED, true);
        mForceAllAppsStandby = parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY, true);
        mForceBackgroundCheck = parser.getBoolean(KEY_FORCE_BACKGROUND_CHECK, true);
        mOptionalSensorsDisabled = parser.getBoolean(KEY_OPTIONAL_SENSORS_DISABLED, true);
        mAodDisabled = parser.getBoolean(KEY_AOD_DISABLED, true);
        mSendTronLog = parser.getBoolean(KEY_SEND_TRON_LOG, true);

        // Get default value from Settings.Secure
        final int defaultGpsMode = Settings.Secure.getInt(mContentResolver, SECURE_KEY_GPS_MODE,
                PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        mGpsMode = parser.getInt(KEY_GPS_MODE, defaultGpsMode);

        // Non-device-specific parameters.
        try {
            parser.setString(deviceSpecificSetting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad device specific battery saver constants: "
                    + deviceSpecificSetting);
        }

        mFilesForInteractive = (new CpuFrequencies()).parseString(
                parser.getString(KEY_CPU_FREQ_INTERACTIVE, "")).toSysFileMap();

        mFilesForNoninteractive = (new CpuFrequencies()).parseString(
                parser.getString(KEY_CPU_FREQ_NONINTERACTIVE, "")).toSysFileMap();

        // Update the effective policy.
        mAccessibilityEnabled = mAccessibilityManager.isEnabled();

        mVibrationDisabledEffective = mVibrationDisabledConfig
                && !mAccessibilityEnabled; // Don't disable vibration when accessibility is on.

        final StringBuilder sb = new StringBuilder();

        if (mForceAllAppsStandby) sb.append("A");
        if (mForceBackgroundCheck) sb.append("B");

        if (mVibrationDisabledEffective) sb.append("v");
        if (mAnimationDisabled) sb.append("a");
        if (mSoundTriggerDisabled) sb.append("s");
        if (mFullBackupDeferred) sb.append("F");
        if (mKeyValueBackupDeferred) sb.append("K");
        if (!mFireWallDisabled) sb.append("f");
        if (!mDataSaverDisabled) sb.append("d");
        if (!mAdjustBrightnessDisabled) sb.append("b");

        if (mLaunchBoostDisabled) sb.append("l");
        if (mOptionalSensorsDisabled) sb.append("S");
        if (mAodDisabled) sb.append("o");
        if (mSendTronLog) sb.append("t");

        sb.append(mGpsMode);

        mEventLogKeys = sb.toString();

        BatterySavingStats.getInstance().setSendTronLog(mSendTronLog);
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
                    return builder.setBatterySaverEnabled(mVibrationDisabledEffective)
                            .build();
                case ServiceType.FORCE_ALL_APPS_STANDBY:
                    return builder.setBatterySaverEnabled(mForceAllAppsStandby)
                            .build();
                case ServiceType.FORCE_BACKGROUND_CHECK:
                    return builder.setBatterySaverEnabled(mForceBackgroundCheck)
                            .build();
                case ServiceType.OPTIONAL_SENSORS:
                    return builder.setBatterySaverEnabled(mOptionalSensorsDisabled)
                            .build();
                case ServiceType.AOD:
                    return builder.setBatterySaverEnabled(mAodDisabled)
                            .build();
                default:
                    return builder.setBatterySaverEnabled(realMode)
                            .build();
            }
        }
    }

    public int getGpsMode() {
        synchronized (mLock) {
            return mGpsMode;
        }
    }

    public ArrayMap<String, String> getFileValues(boolean interactive) {
        synchronized (mLock) {
            return interactive ? mFilesForInteractive : mFilesForNoninteractive;
        }
    }

    public boolean isLaunchBoostDisabled() {
        synchronized (mLock) {
            return mLaunchBoostDisabled;
        }
    }

    public String toEventLogString() {
        synchronized (mLock) {
            return mEventLogKeys;
        }
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            BatterySavingStats.getInstance().dump(pw, "");

            pw.println();
            pw.println("Battery saver policy (*NOTE* they only apply when battery saver is ON):");
            pw.println("  Settings: " + Settings.Global.BATTERY_SAVER_CONSTANTS);
            pw.println("    value: " + mSettings);
            pw.println("  Settings: " + mDeviceSpecificSettingsSource);
            pw.println("    value: " + mDeviceSpecificSettings);

            pw.println();
            pw.println("  mAccessibilityEnabled=" + mAccessibilityEnabled);
            pw.println("  " + KEY_VIBRATION_DISABLED + ":config=" + mVibrationDisabledConfig);
            pw.println("  " + KEY_VIBRATION_DISABLED + ":effective=" + mVibrationDisabledEffective);
            pw.println("  " + KEY_ANIMATION_DISABLED + "=" + mAnimationDisabled);
            pw.println("  " + KEY_FULLBACKUP_DEFERRED + "=" + mFullBackupDeferred);
            pw.println("  " + KEY_KEYVALUE_DEFERRED + "=" + mKeyValueBackupDeferred);
            pw.println("  " + KEY_FIREWALL_DISABLED + "=" + mFireWallDisabled);
            pw.println("  " + KEY_DATASAVER_DISABLED + "=" + mDataSaverDisabled);
            pw.println("  " + KEY_LAUNCH_BOOST_DISABLED + "=" + mLaunchBoostDisabled);
            pw.println("  " + KEY_ADJUST_BRIGHTNESS_DISABLED + "=" + mAdjustBrightnessDisabled);
            pw.println("  " + KEY_ADJUST_BRIGHTNESS_FACTOR + "=" + mAdjustBrightnessFactor);
            pw.println("  " + KEY_GPS_MODE + "=" + mGpsMode);
            pw.println("  " + KEY_FORCE_ALL_APPS_STANDBY + "=" + mForceAllAppsStandby);
            pw.println("  " + KEY_FORCE_BACKGROUND_CHECK + "=" + mForceBackgroundCheck);
            pw.println("  " + KEY_OPTIONAL_SENSORS_DISABLED + "=" + mOptionalSensorsDisabled);
            pw.println("  " + KEY_AOD_DISABLED + "=" + mAodDisabled);
            pw.println("  " + KEY_SEND_TRON_LOG + "=" + mSendTronLog);
            pw.println();

            pw.print("  Interactive File values:\n");
            dumpMap(pw, "    ", mFilesForInteractive);
            pw.println();

            pw.print("  Noninteractive File values:\n");
            dumpMap(pw, "    ", mFilesForNoninteractive);
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
