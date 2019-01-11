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
package com.android.server.power.batterysaver;

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
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.power.PowerManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to decide whether to turn on battery saver mode for specific services.
 *
 * IMPORTANT: This class shares the power manager lock, which is very low in the lock hierarchy.
 * Do not call out with the lock held, such as AccessibilityManager. (Settings provider is okay.)
 *
 * Test: atest com.android.server.power.batterysaver.BatterySaverPolicyTest.java
 */
public class BatterySaverPolicy extends ContentObserver {
    private static final String TAG = "BatterySaverPolicy";

    public static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE.

    private static final String KEY_GPS_MODE = "gps_mode";
    private static final String KEY_VIBRATION_DISABLED = "vibration_disabled";
    private static final String KEY_ANIMATION_DISABLED = "animation_disabled";
    private static final String KEY_SOUNDTRIGGER_DISABLED = "soundtrigger_disabled";

    /**
     * Disable turning on the network firewall when Battery Saver is turned on.
     * If set to false, the firewall WILL be turned on when Battery Saver is turned on.
     * If set to true, the firewall WILL NOT be turned on when Battery Saver is turned on.
     */
    private static final String KEY_ACTIVATE_FIREWALL_DISABLED = "firewall_disabled";

    /**
     * Disable turning on the special low power screen brightness dimming when Battery Saver is
     * turned on.
     * If set to false, the screen brightness dimming WILL be turned on by Battery Saver.
     * If set to true, the screen brightness WILL NOT be turned on by Battery Saver.
     */
    private static final String KEY_ADJUST_BRIGHTNESS_DISABLED = "adjust_brightness_disabled";

    /**
     * Disable turning on Data Saver when Battery Saver is turned on.
     * If set to false, Data Saver WILL be turned on when Battery Saver is turned on.
     * If set to true, Data Saver WILL NOT be turned on when Battery Saver is turned on.
     */
    private static final String KEY_ACTIVATE_DATASAVER_DISABLED = "datasaver_disabled";
    private static final String KEY_LAUNCH_BOOST_DISABLED = "launch_boost_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_FACTOR = "adjust_brightness_factor";
    private static final String KEY_FULLBACKUP_DEFERRED = "fullbackup_deferred";
    private static final String KEY_KEYVALUE_DEFERRED = "keyvaluebackup_deferred";
    private static final String KEY_FORCE_ALL_APPS_STANDBY = "force_all_apps_standby";
    private static final String KEY_FORCE_BACKGROUND_CHECK = "force_background_check";
    private static final String KEY_OPTIONAL_SENSORS_DISABLED = "optional_sensors_disabled";
    private static final String KEY_AOD_DISABLED = "aod_disabled";
    // Go into deep Doze as soon as the screen turns off.
    private static final String KEY_QUICK_DOZE_ENABLED = "quick_doze_enabled";
    private static final String KEY_SEND_TRON_LOG = "send_tron_log";

    private static final String KEY_CPU_FREQ_INTERACTIVE = "cpufreq-i";
    private static final String KEY_CPU_FREQ_NONINTERACTIVE = "cpufreq-n";

    private static final Policy sDefaultPolicy = new Policy(
            0.5f,  /* adjustBrightnessFactor */
            true,  /* deferFullBackup */
            true,  /* deferKeyValueBackup */
            false, /* disableAnimation */
            true,  /* disableAod */
            true,  /* disableLaunchBoost */
            true,  /* disableOptionalSensors */
            true,  /* disableSoundTrigger */
            true,  /* disableVibration */
            false, /* enableAdjustBrightness */
            false, /* enableDataSaver */
            true,  /* enableFirewall */
            true, /* enableQuickDoze */
            new ArrayMap<>(), /* filesForInteractive */
            new ArrayMap<>(), /* filesForNoninteractive */
            true, /* forceAllAppsStandby */
            true, /* forceBackgroundCheck */
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF, /* gpsMode */
            false /* sendTronLog */
    );

    private final Object mLock;
    private final Handler mHandler;

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
     * Whether vibration should *really* be disabled -- i.e. {@link Policy#disableVibration}
     * is true *and* {@link #mAccessibilityEnabled} is false.
     */
    @GuardedBy("mLock")
    private boolean mDisableVibrationEffective;

    /**
     * Whether accessibility is currently enabled or not.
     */
    @GuardedBy("mLock")
    private boolean mAccessibilityEnabled;

    @GuardedBy("mLock")
    private Policy mCurrPolicy = sDefaultPolicy;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final BatterySavingStats mBatterySavingStats;

    @GuardedBy("mLock")
    private final List<BatterySaverPolicyListener> mListeners = new ArrayList<>();

    public interface BatterySaverPolicyListener {
        void onBatterySaverPolicyChanged(BatterySaverPolicy policy);
    }

    public BatterySaverPolicy(Object lock, Context context, BatterySavingStats batterySavingStats) {
        super(BackgroundThread.getHandler());
        mLock = lock;
        mHandler = BackgroundThread.getHandler();
        mContext = context;
        mContentResolver = context.getContentResolver();
        mBatterySavingStats = batterySavingStats;
    }

    /**
     * Called by {@link PowerManagerService#systemReady}, *with no lock held.*
     */
    public void systemReady() {
        ConcurrentUtils.wtfIfLockHeld(TAG, mLock);

        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.BATTERY_SAVER_CONSTANTS), false, this);
        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS), false, this);

        final AccessibilityManager acm = mContext.getSystemService(AccessibilityManager.class);

        acm.addAccessibilityStateChangeListener((enabled) -> {
            synchronized (mLock) {
                mAccessibilityEnabled = enabled;
            }
            refreshSettings();
        });
        final boolean enabled = acm.isEnabled();
        synchronized (mLock) {
            mAccessibilityEnabled = enabled;
        }
        onChange(true, null);
    }

    @VisibleForTesting
    public void addListener(BatterySaverPolicyListener listener) {
        synchronized (mLock) {
            // TODO: set this in the constructor instead
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

            listeners = mListeners.toArray(new BatterySaverPolicyListener[0]);
        }

        // Notify the listeners.
        mHandler.post(() -> {
            for (BatterySaverPolicyListener listener : listeners) {
                listener.onBatterySaverPolicyChanged(this);
            }
        });
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

        // Device-specific parameters.
        try {
            parser.setString(deviceSpecificSetting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad device specific battery saver constants: "
                    + deviceSpecificSetting);
        }

        final String cpuFreqInteractive = parser.getString(KEY_CPU_FREQ_INTERACTIVE, "");
        final String cpuFreqNoninteractive = parser.getString(KEY_CPU_FREQ_NONINTERACTIVE, "");

        // Non-device-specific parameters.
        try {
            parser.setString(setting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad battery saver constants: " + setting);
        }

        float adjustBrightnessFactor = parser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR,
                sDefaultPolicy.adjustBrightnessFactor);
        boolean deferFullBackup = parser.getBoolean(KEY_FULLBACKUP_DEFERRED,
                sDefaultPolicy.deferFullBackup);
        boolean deferKeyValueBackup = parser.getBoolean(KEY_KEYVALUE_DEFERRED,
                sDefaultPolicy.deferKeyValueBackup);
        boolean disableAnimation = parser.getBoolean(KEY_ANIMATION_DISABLED,
                sDefaultPolicy.disableAnimation);
        boolean disableAod = parser.getBoolean(KEY_AOD_DISABLED, sDefaultPolicy.disableAod);
        boolean disableLaunchBoost = parser.getBoolean(KEY_LAUNCH_BOOST_DISABLED,
                sDefaultPolicy.disableLaunchBoost);
        boolean disableOptionalSensors = parser.getBoolean(KEY_OPTIONAL_SENSORS_DISABLED,
                sDefaultPolicy.disableOptionalSensors);
        boolean disableSoundTrigger = parser.getBoolean(KEY_SOUNDTRIGGER_DISABLED,
                sDefaultPolicy.disableSoundTrigger);
        boolean disableVibrationConfig = parser.getBoolean(KEY_VIBRATION_DISABLED,
                sDefaultPolicy.disableVibration);
        boolean enableAdjustBrightness = !parser.getBoolean(KEY_ADJUST_BRIGHTNESS_DISABLED,
                !sDefaultPolicy.enableAdjustBrightness);
        boolean enableDataSaver = !parser.getBoolean(KEY_ACTIVATE_DATASAVER_DISABLED,
                !sDefaultPolicy.enableDataSaver);
        boolean enableFirewall = !parser.getBoolean(KEY_ACTIVATE_FIREWALL_DISABLED,
                !sDefaultPolicy.enableFirewall);
        boolean enableQuickDoze = parser.getBoolean(KEY_QUICK_DOZE_ENABLED,
                sDefaultPolicy.enableQuickDoze);
        boolean forceAllAppsStandby = parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY,
                sDefaultPolicy.forceAllAppsStandby);
        boolean forceBackgroundCheck = parser.getBoolean(KEY_FORCE_BACKGROUND_CHECK,
                sDefaultPolicy.forceBackgroundCheck);
        int gpsMode = parser.getInt(KEY_GPS_MODE, sDefaultPolicy.gpsMode);
        boolean sendTronLog = parser.getBoolean(KEY_SEND_TRON_LOG, sDefaultPolicy.sendTronLog);

        mCurrPolicy = new Policy(
                adjustBrightnessFactor,
                deferFullBackup,
                deferKeyValueBackup,
                disableAnimation,
                disableAod,
                disableLaunchBoost,
                disableOptionalSensors,
                disableSoundTrigger,
                /* disableVibration */
                disableVibrationConfig,
                enableAdjustBrightness,
                enableDataSaver,
                enableFirewall,
                enableQuickDoze,
                /* filesForInteractive */
                (new CpuFrequencies()).parseString(cpuFreqInteractive).toSysFileMap(),
                /* filesForNoninteractive */
                (new CpuFrequencies()).parseString(cpuFreqNoninteractive).toSysFileMap(),
                forceAllAppsStandby,
                forceBackgroundCheck,
                gpsMode,
                sendTronLog
        );

        // Update the effective policy.
        mDisableVibrationEffective = mCurrPolicy.disableVibration
                && !mAccessibilityEnabled; // Don't disable vibration when accessibility is on.

        final StringBuilder sb = new StringBuilder();

        if (mCurrPolicy.forceAllAppsStandby) sb.append("A");
        if (mCurrPolicy.forceBackgroundCheck) sb.append("B");

        if (mDisableVibrationEffective) sb.append("v");
        if (mCurrPolicy.disableAnimation) sb.append("a");
        if (mCurrPolicy.disableSoundTrigger) sb.append("s");
        if (mCurrPolicy.deferFullBackup) sb.append("F");
        if (mCurrPolicy.deferKeyValueBackup) sb.append("K");
        if (mCurrPolicy.enableFirewall) sb.append("f");
        if (mCurrPolicy.enableDataSaver) sb.append("d");
        if (mCurrPolicy.enableAdjustBrightness) sb.append("b");

        if (mCurrPolicy.disableLaunchBoost) sb.append("l");
        if (mCurrPolicy.disableOptionalSensors) sb.append("S");
        if (mCurrPolicy.disableAod) sb.append("o");
        if (mCurrPolicy.enableQuickDoze) sb.append("q");
        if (mCurrPolicy.sendTronLog) sb.append("t");

        sb.append(mCurrPolicy.gpsMode);

        mEventLogKeys = sb.toString();

        mBatterySavingStats.setSendTronLog(mCurrPolicy.sendTronLog);
    }

    private static class Policy {
        /**
         * This is the flag to decide the how much to adjust the screen brightness. This is
         * the float value from 0 to 1 where 1 means don't change brightness.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ADJUST_BRIGHTNESS_FACTOR
         */
        public final float adjustBrightnessFactor;

        /**
         * {@code true} if full backup is deferred in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_FULLBACKUP_DEFERRED
         */
        public final boolean deferFullBackup;

        /**
         * {@code true} if key value backup is deferred in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_KEYVALUE_DEFERRED
         */
        public final boolean deferKeyValueBackup;

        /**
         * {@code true} if animation is disabled in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ANIMATION_DISABLED
         */
        public final boolean disableAnimation;

        /**
         * {@code true} if AOD is disabled in battery saver mode.
         */
        public final boolean disableAod;

        /**
         * {@code true} if launch boost should be disabled on battery saver.
         */
        public final boolean disableLaunchBoost;

        /**
         * Whether to show non-essential sensors (e.g. edge sensors) or not.
         */
        public final boolean disableOptionalSensors;

        /**
         * {@code true} if sound trigger is disabled in battery saver mode
         * in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_SOUNDTRIGGER_DISABLED
         */
        public final boolean disableSoundTrigger;

        /**
         * {@code true} if vibration is disabled in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_VIBRATION_DISABLED
         */
        public final boolean disableVibration;

        /**
         * {@code true} if low power mode brightness adjustment should be turned on in battery saver
         * mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ADJUST_BRIGHTNESS_DISABLED
         */
        public final boolean enableAdjustBrightness;

        /**
         * {@code true} if data saver should be turned on in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ACTIVATE_DATASAVER_DISABLED
         */
        public final boolean enableDataSaver;

        /**
         * {@code true} if network policy firewall should be turned on in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ACTIVATE_FIREWALL_DISABLED
         */
        public final boolean enableFirewall;

        /**
         * Whether Quick Doze is enabled or not.
         */
        public final boolean enableQuickDoze;

        /**
         * List of [Filename -> content] that should be written when battery saver is activated
         * and the device is interactive.
         *
         * We use this to change the max CPU frequencies.
         */
        public final ArrayMap<String, String> filesForInteractive;

        /**
         * List of [Filename -> content] that should be written when battery saver is activated
         * and the device is non-interactive.
         *
         * We use this to change the max CPU frequencies.
         */
        public final ArrayMap<String, String> filesForNoninteractive;

        /**
         * Whether to put all apps in the stand-by mode.
         */
        public final boolean forceAllAppsStandby;

        /**
         * Whether to force background check.
         */
        public final boolean forceBackgroundCheck;

        /**
         * This is the flag to decide the gps mode in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_GPS_MODE
         */
        public final int gpsMode;

        /**
         * Whether BatterySavingStats should send tron events.
         */
        public final boolean sendTronLog;

        Policy(
                float adjustBrightnessFactor,
                boolean deferFullBackup,
                boolean deferKeyValueBackup,
                boolean disableAnimation,
                boolean disableAod,
                boolean disableLaunchBoost,
                boolean disableOptionalSensors,
                boolean disableSoundTrigger,
                boolean disableVibration,
                boolean enableAdjustBrightness,
                boolean enableDataSaver,
                boolean enableFirewall,
                boolean enableQuickDoze,
                ArrayMap<String, String> filesForInteractive,
                ArrayMap<String, String> filesForNoninteractive,
                boolean forceAllAppsStandby,
                boolean forceBackgroundCheck,
                int gpsMode,
                boolean sendTronLog) {

            this.adjustBrightnessFactor = adjustBrightnessFactor;
            this.deferFullBackup = deferFullBackup;
            this.deferKeyValueBackup = deferKeyValueBackup;
            this.disableAnimation = disableAnimation;
            this.disableAod = disableAod;
            this.disableLaunchBoost = disableLaunchBoost;
            this.disableOptionalSensors = disableOptionalSensors;
            this.disableSoundTrigger = disableSoundTrigger;
            this.disableVibration = disableVibration;
            this.enableAdjustBrightness = enableAdjustBrightness;
            this.enableDataSaver = enableDataSaver;
            this.enableFirewall = enableFirewall;
            this.enableQuickDoze = enableQuickDoze;
            this.filesForInteractive = filesForInteractive;
            this.filesForNoninteractive = filesForNoninteractive;
            this.forceAllAppsStandby = forceAllAppsStandby;
            this.forceBackgroundCheck = forceBackgroundCheck;
            this.gpsMode = gpsMode;
            this.sendTronLog = sendTronLog;
        }
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
                            .setGpsMode(mCurrPolicy.gpsMode)
                            .build();
                case ServiceType.ANIMATION:
                    return builder.setBatterySaverEnabled(mCurrPolicy.disableAnimation)
                            .build();
                case ServiceType.FULL_BACKUP:
                    return builder.setBatterySaverEnabled(mCurrPolicy.deferFullBackup)
                            .build();
                case ServiceType.KEYVALUE_BACKUP:
                    return builder.setBatterySaverEnabled(mCurrPolicy.deferKeyValueBackup)
                            .build();
                case ServiceType.NETWORK_FIREWALL:
                    return builder.setBatterySaverEnabled(mCurrPolicy.enableFirewall)
                            .build();
                case ServiceType.SCREEN_BRIGHTNESS:
                    return builder.setBatterySaverEnabled(mCurrPolicy.enableAdjustBrightness)
                            .setBrightnessFactor(mCurrPolicy.adjustBrightnessFactor)
                            .build();
                case ServiceType.DATA_SAVER:
                    return builder.setBatterySaverEnabled(mCurrPolicy.enableDataSaver)
                            .build();
                case ServiceType.SOUND:
                    return builder.setBatterySaverEnabled(mCurrPolicy.disableSoundTrigger)
                            .build();
                case ServiceType.VIBRATION:
                    return builder.setBatterySaverEnabled(mDisableVibrationEffective)
                            .build();
                case ServiceType.FORCE_ALL_APPS_STANDBY:
                    return builder.setBatterySaverEnabled(mCurrPolicy.forceAllAppsStandby)
                            .build();
                case ServiceType.FORCE_BACKGROUND_CHECK:
                    return builder.setBatterySaverEnabled(mCurrPolicy.forceBackgroundCheck)
                            .build();
                case ServiceType.OPTIONAL_SENSORS:
                    return builder.setBatterySaverEnabled(mCurrPolicy.disableOptionalSensors)
                            .build();
                case ServiceType.AOD:
                    return builder.setBatterySaverEnabled(mCurrPolicy.disableAod)
                            .build();
                case ServiceType.QUICK_DOZE:
                    return builder.setBatterySaverEnabled(mCurrPolicy.enableQuickDoze)
                            .build();
                default:
                    return builder.setBatterySaverEnabled(realMode)
                            .build();
            }
        }
    }

    public int getGpsMode() {
        synchronized (mLock) {
            return mCurrPolicy.gpsMode;
        }
    }

    public ArrayMap<String, String> getFileValues(boolean interactive) {
        synchronized (mLock) {
            return interactive ? mCurrPolicy.filesForInteractive
                    : mCurrPolicy.filesForNoninteractive;
        }
    }

    public boolean isLaunchBoostDisabled() {
        synchronized (mLock) {
            return mCurrPolicy.disableLaunchBoost;
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
            mBatterySavingStats.dump(pw, "");

            pw.println();
            pw.println("Battery saver policy (*NOTE* they only apply when battery saver is ON):");
            pw.println("  Settings: " + Settings.Global.BATTERY_SAVER_CONSTANTS);
            pw.println("    value: " + mSettings);
            pw.println("  Settings: " + mDeviceSpecificSettingsSource);
            pw.println("    value: " + mDeviceSpecificSettings);

            pw.println();
            pw.println("  mAccessibilityEnabled=" + mAccessibilityEnabled);
            pw.println("  " + KEY_VIBRATION_DISABLED + ":config=" + mCurrPolicy.disableVibration);
            pw.println("  " + KEY_VIBRATION_DISABLED + ":effective=" + mDisableVibrationEffective);
            pw.println("  " + KEY_ANIMATION_DISABLED + "=" + mCurrPolicy.disableAnimation);
            pw.println("  " + KEY_FULLBACKUP_DEFERRED + "=" + mCurrPolicy.deferFullBackup);
            pw.println("  " + KEY_KEYVALUE_DEFERRED + "=" + mCurrPolicy.deferKeyValueBackup);
            pw.println("  " + KEY_ACTIVATE_FIREWALL_DISABLED + "=" + !mCurrPolicy.enableFirewall);
            pw.println("  " + KEY_ACTIVATE_DATASAVER_DISABLED + "=" + !mCurrPolicy.enableDataSaver);
            pw.println("  " + KEY_LAUNCH_BOOST_DISABLED + "=" + mCurrPolicy.disableLaunchBoost);
            pw.println("  " + KEY_ADJUST_BRIGHTNESS_DISABLED + "="
                    + !mCurrPolicy.enableAdjustBrightness);
            pw.println(
                    "  " + KEY_ADJUST_BRIGHTNESS_FACTOR + "=" + mCurrPolicy.adjustBrightnessFactor);
            pw.println("  " + KEY_GPS_MODE + "=" + mCurrPolicy.gpsMode);
            pw.println("  " + KEY_FORCE_ALL_APPS_STANDBY + "=" + mCurrPolicy.forceAllAppsStandby);
            pw.println("  " + KEY_FORCE_BACKGROUND_CHECK + "=" + mCurrPolicy.forceBackgroundCheck);
            pw.println("  " + KEY_OPTIONAL_SENSORS_DISABLED + "="
                    + mCurrPolicy.disableOptionalSensors);
            pw.println("  " + KEY_AOD_DISABLED + "=" + mCurrPolicy.disableAod);
            pw.println("  " + KEY_SOUNDTRIGGER_DISABLED + "=" + mCurrPolicy.disableSoundTrigger);
            pw.println("  " + KEY_QUICK_DOZE_ENABLED + "=" + mCurrPolicy.enableQuickDoze);
            pw.println("  " + KEY_SEND_TRON_LOG + "=" + mCurrPolicy.sendTronLog);
            pw.println();

            pw.print("  Interactive File values:\n");
            dumpMap(pw, "    ", mCurrPolicy.filesForInteractive);
            pw.println();

            pw.print("  Noninteractive File values:\n");
            dumpMap(pw, "    ", mCurrPolicy.filesForNoninteractive);
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

    @VisibleForTesting
    public void setAccessibilityEnabledForTest(boolean enabled) {
        synchronized (mLock) {
            mAccessibilityEnabled = enabled;
        }
    }
}
