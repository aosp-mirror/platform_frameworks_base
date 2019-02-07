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

import android.annotation.IntDef;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatterySaverPolicyConfig;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.provider.Settings;
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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Class to decide whether to turn on battery saver mode for specific services.
 *
 * IMPORTANT: This class shares the power manager lock, which is very low in the lock hierarchy.
 * Do not call out with the lock held, such as AccessibilityManager. (Settings provider is okay.)
 *
 * Test: atest com.android.server.power.batterysaver.BatterySaverPolicyTest
 */
public class BatterySaverPolicy extends ContentObserver {
    private static final String TAG = "BatterySaverPolicy";

    static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE.

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

    /**
     * {@code true} if the Policy should advertise to the rest of the system that battery saver
     * is enabled. This advertising could cause other system components to change their
     * behavior. This will not affect other policy flags and what they change.
     */
    private static final String KEY_ADVERTISE_IS_ENABLED = "advertise_is_enabled";

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
    private static final String KEY_ENABLE_NIGHT_MODE = "enable_night_mode";

    private static final String KEY_CPU_FREQ_INTERACTIVE = "cpufreq-i";
    private static final String KEY_CPU_FREQ_NONINTERACTIVE = "cpufreq-n";

    @VisibleForTesting
    static final Policy OFF_POLICY = new Policy(
            1f,    /* adjustBrightnessFactor */
            false, /* advertiseIsEnabled */
            false, /* deferFullBackup */
            false, /* deferKeyValueBackup */
            false, /* disableAnimation */
            false, /* disableAod */
            false, /* disableLaunchBoost */
            false, /* disableOptionalSensors */
            false, /* disableSoundTrigger */
            false, /* disableVibration */
            false, /* enableAdjustBrightness */
            false, /* enableDataSaver */
            false, /* enableFireWall */
            false, /* enableNightMode */
            false, /* enableQuickDoze */
            new ArrayMap<>(), /* filesForInteractive */
            new ArrayMap<>(), /* filesForNoninteractive */
            false, /* forceAllAppsStandby */
            false, /* forceBackgroundCheck */
            PowerManager.LOCATION_MODE_NO_CHANGE /* locationMode */
    );

    private static final Policy DEFAULT_ADAPTIVE_POLICY = OFF_POLICY;

    private static final Policy DEFAULT_FULL_POLICY = new Policy(
            0.5f,  /* adjustBrightnessFactor */
            true,  /* advertiseIsEnabled */
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
            true, /* enableNightMode */
            true, /* enableQuickDoze */
            new ArrayMap<>(), /* filesForInteractive */
            new ArrayMap<>(), /* filesForNoninteractive */
            true, /* forceAllAppsStandby */
            true, /* forceBackgroundCheck */
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF /* locationMode */
    );

    private final Object mLock;
    private final Handler mHandler;

    @GuardedBy("mLock")
    private String mSettings;

    @GuardedBy("mLock")
    private String mDeviceSpecificSettings;

    @GuardedBy("mLock")
    private String mDeviceSpecificSettingsSource; // For dump() only.

    @GuardedBy("mLock")
    private String mAdaptiveSettings;

    @GuardedBy("mLock")
    private String mAdaptiveDeviceSpecificSettings;

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

    /** The current default adaptive policy. */
    @GuardedBy("mLock")
    private Policy mDefaultAdaptivePolicy = DEFAULT_ADAPTIVE_POLICY;

    /** The policy that will be used for adaptive battery saver. */
    @GuardedBy("mLock")
    private Policy mAdaptivePolicy = DEFAULT_ADAPTIVE_POLICY;

    /** The policy to be used for full battery saver. */
    @GuardedBy("mLock")
    private Policy mFullPolicy = DEFAULT_FULL_POLICY;

    @IntDef(prefix = {"POLICY_LEVEL_"}, value = {
            POLICY_LEVEL_OFF,
            POLICY_LEVEL_ADAPTIVE,
            POLICY_LEVEL_FULL,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PolicyLevel {}

    static final int POLICY_LEVEL_OFF = 0;
    static final int POLICY_LEVEL_ADAPTIVE = 1;
    static final int POLICY_LEVEL_FULL = 2;

    @GuardedBy("mLock")
    private int mPolicyLevel = POLICY_LEVEL_OFF;

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
                Settings.Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS), false, this);
        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.BATTERY_SAVER_ADAPTIVE_CONSTANTS), false, this);
        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.BATTERY_SAVER_ADAPTIVE_DEVICE_SPECIFIC_CONSTANTS), false, this);

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

            final String adaptiveSetting =
                    getGlobalSetting(Settings.Global.BATTERY_SAVER_ADAPTIVE_CONSTANTS);
            final String adaptiveDeviceSpecificSetting = getGlobalSetting(
                    Settings.Global.BATTERY_SAVER_ADAPTIVE_DEVICE_SPECIFIC_CONSTANTS);

            if (!updateConstantsLocked(setting, deviceSpecificSetting,
                    adaptiveSetting, adaptiveDeviceSpecificSetting)) {
                // Nothing of note changed.
                return;
            }

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
        updateConstantsLocked(setting, deviceSpecificSetting, "", "");
    }

    /** @return true if the currently active policy changed. */
    private boolean updateConstantsLocked(String setting, String deviceSpecificSetting,
            String adaptiveSetting, String adaptiveDeviceSpecificSetting) {
        setting = TextUtils.emptyIfNull(setting);
        deviceSpecificSetting = TextUtils.emptyIfNull(deviceSpecificSetting);
        adaptiveSetting = TextUtils.emptyIfNull(adaptiveSetting);
        adaptiveDeviceSpecificSetting = TextUtils.emptyIfNull(adaptiveDeviceSpecificSetting);

        if (setting.equals(mSettings)
                && deviceSpecificSetting.equals(mDeviceSpecificSettings)
                && adaptiveSetting.equals(mAdaptiveSettings)
                && adaptiveDeviceSpecificSetting.equals(mAdaptiveDeviceSpecificSettings)) {
            return false;
        }

        mSettings = setting;
        mDeviceSpecificSettings = deviceSpecificSetting;
        mAdaptiveSettings = adaptiveSetting;
        mAdaptiveDeviceSpecificSettings = adaptiveDeviceSpecificSetting;

        if (DEBUG) {
            Slog.i(TAG, "mSettings=" + mSettings);
            Slog.i(TAG, "mDeviceSpecificSettings=" + mDeviceSpecificSettings);
            Slog.i(TAG, "mAdaptiveSettings=" + mAdaptiveSettings);
            Slog.i(TAG, "mAdaptiveDeviceSpecificSettings=" + mAdaptiveDeviceSpecificSettings);
        }

        boolean changed = false;
        Policy newFullPolicy = Policy.fromSettings(setting, deviceSpecificSetting,
                DEFAULT_FULL_POLICY);
        if (mPolicyLevel == POLICY_LEVEL_FULL && !mFullPolicy.equals(newFullPolicy)) {
            changed = true;
        }
        mFullPolicy = newFullPolicy;

        mDefaultAdaptivePolicy = Policy.fromSettings(adaptiveSetting, adaptiveDeviceSpecificSetting,
                DEFAULT_ADAPTIVE_POLICY);
        if (mPolicyLevel == POLICY_LEVEL_ADAPTIVE
                && !mAdaptivePolicy.equals(mDefaultAdaptivePolicy)) {
            changed = true;
        }
        // This will override any config set by an external source. This should be fine for now.
        // TODO: make sure it doesn't override what's set externally
        mAdaptivePolicy = mDefaultAdaptivePolicy;

        updatePolicyDependenciesLocked();

        return changed;
    }

    @GuardedBy("mLock")
    private void updatePolicyDependenciesLocked() {
        final Policy currPolicy = getCurrentPolicyLocked();
        // Update the effective vibration policy.
        mDisableVibrationEffective = currPolicy.disableVibration
                && !mAccessibilityEnabled; // Don't disable vibration when accessibility is on.

        final StringBuilder sb = new StringBuilder();

        if (currPolicy.forceAllAppsStandby) sb.append("A");
        if (currPolicy.forceBackgroundCheck) sb.append("B");

        if (mDisableVibrationEffective) sb.append("v");
        if (currPolicy.disableAnimation) sb.append("a");
        if (currPolicy.disableSoundTrigger) sb.append("s");
        if (currPolicy.deferFullBackup) sb.append("F");
        if (currPolicy.deferKeyValueBackup) sb.append("K");
        if (currPolicy.enableFirewall) sb.append("f");
        if (currPolicy.enableDataSaver) sb.append("d");
        if (currPolicy.enableAdjustBrightness) sb.append("b");

        if (currPolicy.disableLaunchBoost) sb.append("l");
        if (currPolicy.disableOptionalSensors) sb.append("S");
        if (currPolicy.disableAod) sb.append("o");
        if (currPolicy.enableQuickDoze) sb.append("q");

        sb.append(currPolicy.locationMode);

        mEventLogKeys = sb.toString();
    }

    static class Policy {
        /**
         * This is the flag to decide the how much to adjust the screen brightness. This is
         * the float value from 0 to 1 where 1 means don't change brightness.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ADJUST_BRIGHTNESS_FACTOR
         */
        public final float adjustBrightnessFactor;

        /**
         * {@code true} if the Policy should advertise to the rest of the system that battery saver
         * is enabled. This advertising could cause other system components to change their
         * behavior. This will not affect other policy flags and what they change.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ADVERTISE_IS_ENABLED
         */
        public final boolean advertiseIsEnabled;

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
         * Whether to enable night mode or not.
         */
        public final boolean enableNightMode;

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
         * This is the flag to decide the location mode in battery saver mode. This was
         * previously called gpsMode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_GPS_MODE
         */
        public final int locationMode;

        private final int mHashCode;

        Policy(
                float adjustBrightnessFactor,
                boolean advertiseIsEnabled,
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
                boolean enableNightMode,
                boolean enableQuickDoze,
                ArrayMap<String, String> filesForInteractive,
                ArrayMap<String, String> filesForNoninteractive,
                boolean forceAllAppsStandby,
                boolean forceBackgroundCheck,
                int locationMode) {

            this.adjustBrightnessFactor = adjustBrightnessFactor;
            this.advertiseIsEnabled = advertiseIsEnabled;
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
            this.enableNightMode = enableNightMode;
            this.enableQuickDoze = enableQuickDoze;
            this.filesForInteractive = filesForInteractive;
            this.filesForNoninteractive = filesForNoninteractive;
            this.forceAllAppsStandby = forceAllAppsStandby;
            this.forceBackgroundCheck = forceBackgroundCheck;
            this.locationMode = locationMode;

            mHashCode = Objects.hash(
                    adjustBrightnessFactor,
                    advertiseIsEnabled,
                    deferFullBackup,
                    deferKeyValueBackup,
                    disableAnimation,
                    disableAod,
                    disableLaunchBoost,
                    disableOptionalSensors,
                    disableSoundTrigger,
                    disableVibration,
                    enableAdjustBrightness,
                    enableDataSaver,
                    enableFirewall,
                    enableNightMode,
                    enableQuickDoze,
                    filesForInteractive,
                    filesForNoninteractive,
                    forceAllAppsStandby,
                    forceBackgroundCheck,
                    locationMode);
        }

        static Policy fromConfig(BatterySaverPolicyConfig config) {
            if (config == null) {
                Slog.e(TAG, "Null config passed down to BatterySaverPolicy");
                return OFF_POLICY;
            }

            // Device-specific parameters.
            Map<String, String> deviceSpecificSettings = config.getDeviceSpecificSettings();
            final String cpuFreqInteractive =
                    deviceSpecificSettings.getOrDefault(KEY_CPU_FREQ_INTERACTIVE, "");
            final String cpuFreqNoninteractive =
                    deviceSpecificSettings.getOrDefault(KEY_CPU_FREQ_NONINTERACTIVE, "");

            return new Policy(
                    config.getAdjustBrightnessFactor(),
                    config.getAdvertiseIsEnabled(),
                    config.getDeferFullBackup(),
                    config.getDeferKeyValueBackup(),
                    config.getDisableAnimation(),
                    config.getDisableAod(),
                    config.getDisableLaunchBoost(),
                    config.getDisableOptionalSensors(),
                    config.getDisableSoundTrigger(),
                    config.getDisableVibration(),
                    config.getEnableAdjustBrightness(),
                    config.getEnableDataSaver(),
                    config.getEnableFirewall(),
                    config.getEnableNightMode(),
                    config.getEnableQuickDoze(),
                    /* filesForInteractive */
                    (new CpuFrequencies()).parseString(cpuFreqInteractive).toSysFileMap(),
                    /* filesForNoninteractive */
                    (new CpuFrequencies()).parseString(cpuFreqNoninteractive).toSysFileMap(),
                    config.getForceAllAppsStandby(),
                    config.getForceBackgroundCheck(),
                    config.getLocationMode()
            );
        }

        static Policy fromSettings(String settings, String deviceSpecificSettings) {
            return fromSettings(settings, deviceSpecificSettings, OFF_POLICY);
        }

        static Policy fromSettings(String settings, String deviceSpecificSettings,
                Policy defaultPolicy) {
            final KeyValueListParser parser = new KeyValueListParser(',');

            // Device-specific parameters.
            try {
                parser.setString(deviceSpecificSettings == null ? "" : deviceSpecificSettings);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad device specific battery saver constants: "
                        + deviceSpecificSettings);
            }

            final String cpuFreqInteractive = parser.getString(KEY_CPU_FREQ_INTERACTIVE, "");
            final String cpuFreqNoninteractive = parser.getString(KEY_CPU_FREQ_NONINTERACTIVE, "");

            // Non-device-specific parameters.
            try {
                parser.setString(settings == null ? "" : settings);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad battery saver constants: " + settings);
            }

            float adjustBrightnessFactor = parser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR,
                    defaultPolicy.adjustBrightnessFactor);
            boolean advertiseIsEnabled = parser.getBoolean(KEY_ADVERTISE_IS_ENABLED,
                    defaultPolicy.advertiseIsEnabled);
            boolean deferFullBackup = parser.getBoolean(KEY_FULLBACKUP_DEFERRED,
                    defaultPolicy.deferFullBackup);
            boolean deferKeyValueBackup = parser.getBoolean(KEY_KEYVALUE_DEFERRED,
                    defaultPolicy.deferKeyValueBackup);
            boolean disableAnimation = parser.getBoolean(KEY_ANIMATION_DISABLED,
                    defaultPolicy.disableAnimation);
            boolean disableAod = parser.getBoolean(KEY_AOD_DISABLED, defaultPolicy.disableAod);
            boolean disableLaunchBoost = parser.getBoolean(KEY_LAUNCH_BOOST_DISABLED,
                    defaultPolicy.disableLaunchBoost);
            boolean disableOptionalSensors = parser.getBoolean(KEY_OPTIONAL_SENSORS_DISABLED,
                    defaultPolicy.disableOptionalSensors);
            boolean disableSoundTrigger = parser.getBoolean(KEY_SOUNDTRIGGER_DISABLED,
                    defaultPolicy.disableSoundTrigger);
            boolean disableVibrationConfig = parser.getBoolean(KEY_VIBRATION_DISABLED,
                    defaultPolicy.disableVibration);
            boolean enableAdjustBrightness = !parser.getBoolean(KEY_ADJUST_BRIGHTNESS_DISABLED,
                    !defaultPolicy.enableAdjustBrightness);
            boolean enableDataSaver = !parser.getBoolean(KEY_ACTIVATE_DATASAVER_DISABLED,
                    !defaultPolicy.enableDataSaver);
            boolean enableFirewall = !parser.getBoolean(KEY_ACTIVATE_FIREWALL_DISABLED,
                    !defaultPolicy.enableFirewall);
            boolean enableNightMode = !parser.getBoolean(KEY_ENABLE_NIGHT_MODE,
                    !defaultPolicy.enableNightMode);
            boolean enableQuickDoze = parser.getBoolean(KEY_QUICK_DOZE_ENABLED,
                    defaultPolicy.enableQuickDoze);
            boolean forceAllAppsStandby = parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY,
                    defaultPolicy.forceAllAppsStandby);
            boolean forceBackgroundCheck = parser.getBoolean(KEY_FORCE_BACKGROUND_CHECK,
                    defaultPolicy.forceBackgroundCheck);
            int locationMode = parser.getInt(KEY_GPS_MODE, defaultPolicy.locationMode);

            return new Policy(
                    adjustBrightnessFactor,
                    advertiseIsEnabled,
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
                    enableNightMode,
                    enableQuickDoze,
                    /* filesForInteractive */
                    (new CpuFrequencies()).parseString(cpuFreqInteractive).toSysFileMap(),
                    /* filesForNoninteractive */
                    (new CpuFrequencies()).parseString(cpuFreqNoninteractive).toSysFileMap(),
                    forceAllAppsStandby,
                    forceBackgroundCheck,
                    locationMode
            );
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Policy)) return false;
            Policy other = (Policy) obj;
            return Float.compare(other.adjustBrightnessFactor, adjustBrightnessFactor) == 0
                    && advertiseIsEnabled == other.advertiseIsEnabled
                    && deferFullBackup == other.deferFullBackup
                    && deferKeyValueBackup == other.deferKeyValueBackup
                    && disableAnimation == other.disableAnimation
                    && disableAod == other.disableAod
                    && disableLaunchBoost == other.disableLaunchBoost
                    && disableOptionalSensors == other.disableOptionalSensors
                    && disableSoundTrigger == other.disableSoundTrigger
                    && disableVibration == other.disableVibration
                    && enableAdjustBrightness == other.enableAdjustBrightness
                    && enableDataSaver == other.enableDataSaver
                    && enableFirewall == other.enableFirewall
                    && enableNightMode == other.enableNightMode
                    && enableQuickDoze == other.enableQuickDoze
                    && forceAllAppsStandby == other.forceAllAppsStandby
                    && forceBackgroundCheck == other.forceBackgroundCheck
                    && locationMode == other.locationMode
                    && filesForInteractive.equals(other.filesForInteractive)
                    && filesForNoninteractive.equals(other.filesForNoninteractive);
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }
    }

    /**
     * Get the {@link PowerSaveState} based on the current policy level.
     * The result will have {@link PowerSaveState#batterySaverEnabled} and some other
     * parameters when necessary.
     *
     * @param type   type of the service, one of {@link ServiceType}
     * @return State data that contains battery saver data
     */
    public PowerSaveState getBatterySaverPolicy(@ServiceType int type) {
        synchronized (mLock) {
            final Policy currPolicy = getCurrentPolicyLocked();
            final PowerSaveState.Builder builder = new PowerSaveState.Builder()
                    .setGlobalBatterySaverEnabled(currPolicy.advertiseIsEnabled);
            switch (type) {
                case ServiceType.LOCATION:
                    boolean isEnabled = currPolicy.advertiseIsEnabled
                            || currPolicy.locationMode != PowerManager.LOCATION_MODE_NO_CHANGE;
                    return builder.setBatterySaverEnabled(isEnabled)
                            .setLocationMode(currPolicy.locationMode)
                            .build();
                case ServiceType.ANIMATION:
                    return builder.setBatterySaverEnabled(currPolicy.disableAnimation)
                            .build();
                case ServiceType.FULL_BACKUP:
                    return builder.setBatterySaverEnabled(currPolicy.deferFullBackup)
                            .build();
                case ServiceType.KEYVALUE_BACKUP:
                    return builder.setBatterySaverEnabled(currPolicy.deferKeyValueBackup)
                            .build();
                case ServiceType.NETWORK_FIREWALL:
                    return builder.setBatterySaverEnabled(currPolicy.enableFirewall)
                            .build();
                case ServiceType.SCREEN_BRIGHTNESS:
                    return builder.setBatterySaverEnabled(currPolicy.enableAdjustBrightness)
                            .setBrightnessFactor(currPolicy.adjustBrightnessFactor)
                            .build();
                case ServiceType.DATA_SAVER:
                    return builder.setBatterySaverEnabled(currPolicy.enableDataSaver)
                            .build();
                case ServiceType.SOUND:
                    return builder.setBatterySaverEnabled(currPolicy.disableSoundTrigger)
                            .build();
                case ServiceType.VIBRATION:
                    return builder.setBatterySaverEnabled(mDisableVibrationEffective)
                            .build();
                case ServiceType.FORCE_ALL_APPS_STANDBY:
                    return builder.setBatterySaverEnabled(currPolicy.forceAllAppsStandby)
                            .build();
                case ServiceType.FORCE_BACKGROUND_CHECK:
                    return builder.setBatterySaverEnabled(currPolicy.forceBackgroundCheck)
                            .build();
                case ServiceType.NIGHT_MODE:
                    return builder.setBatterySaverEnabled(currPolicy.enableNightMode)
                            .build();
                case ServiceType.OPTIONAL_SENSORS:
                    return builder.setBatterySaverEnabled(currPolicy.disableOptionalSensors)
                            .build();
                case ServiceType.AOD:
                    return builder.setBatterySaverEnabled(currPolicy.disableAod)
                            .build();
                case ServiceType.QUICK_DOZE:
                    return builder.setBatterySaverEnabled(currPolicy.enableQuickDoze)
                            .build();
                default:
                    return builder.setBatterySaverEnabled(currPolicy.advertiseIsEnabled)
                            .build();
            }
        }
    }

    /**
     * Sets the current policy.
     *
     * @return true if the policy level was changed.
     */
    boolean setPolicyLevel(@PolicyLevel int level) {
        synchronized (mLock) {
            if (mPolicyLevel == level) {
                return false;
            }
            switch (level) {
                case POLICY_LEVEL_FULL:
                case POLICY_LEVEL_ADAPTIVE:
                case POLICY_LEVEL_OFF:
                    mPolicyLevel = level;
                    break;
                default:
                    Slog.wtf(TAG, "setPolicyLevel invalid level given: " + level);
                    return false;
            }
            updatePolicyDependenciesLocked();
            return true;
        }
    }

    /** @return true if the current policy changed and the policy level is ADAPTIVE. */
    boolean setAdaptivePolicyLocked(Policy p) {
        if (p == null) {
            Slog.wtf(TAG, "setAdaptivePolicy given null policy");
            return false;
        }
        if (mAdaptivePolicy.equals(p)) {
            return false;
        }

        mAdaptivePolicy = p;
        if (mPolicyLevel == POLICY_LEVEL_ADAPTIVE) {
            updatePolicyDependenciesLocked();
            return true;
        }
        return false;
    }

    /** @return true if the current policy changed and the policy level is ADAPTIVE. */
    boolean resetAdaptivePolicyLocked() {
        return setAdaptivePolicyLocked(mDefaultAdaptivePolicy);
    }

    private Policy getCurrentPolicyLocked() {
        switch (mPolicyLevel) {
            case POLICY_LEVEL_FULL:
                return mFullPolicy;
            case POLICY_LEVEL_ADAPTIVE:
                return mAdaptivePolicy;
            case POLICY_LEVEL_OFF:
            default:
                return OFF_POLICY;
        }
    }

    public int getGpsMode() {
        synchronized (mLock) {
            return getCurrentPolicyLocked().locationMode;
        }
    }

    public ArrayMap<String, String> getFileValues(boolean interactive) {
        synchronized (mLock) {
            return interactive ? getCurrentPolicyLocked().filesForInteractive
                    : getCurrentPolicyLocked().filesForNoninteractive;
        }
    }

    public boolean isLaunchBoostDisabled() {
        synchronized (mLock) {
            return getCurrentPolicyLocked().disableLaunchBoost;
        }
    }

    boolean shouldAdvertiseIsEnabled() {
        synchronized (mLock) {
            return getCurrentPolicyLocked().advertiseIsEnabled;
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

            pw.println("  Adaptive Settings: " + Settings.Global.BATTERY_SAVER_ADAPTIVE_CONSTANTS);
            pw.println("    value: " + mAdaptiveSettings);
            pw.println("  Adaptive Device Specific Settings: "
                    + Settings.Global.BATTERY_SAVER_ADAPTIVE_DEVICE_SPECIFIC_CONSTANTS);
            pw.println("    value: " + mAdaptiveDeviceSpecificSettings);

            pw.println("  mAccessibilityEnabled=" + mAccessibilityEnabled);
            pw.println("  mPolicyLevel=" + mPolicyLevel);

            dumpPolicyLocked(pw, "  ", "full", mFullPolicy);
            dumpPolicyLocked(pw, "  ", "default adaptive", mDefaultAdaptivePolicy);
            dumpPolicyLocked(pw, "  ", "current adaptive", mAdaptivePolicy);
        }
    }

    private void dumpPolicyLocked(PrintWriter pw, String indent, String label, Policy p) {
        pw.println();
        pw.print(indent);
        pw.println("Policy '" + label + "'");
        pw.print(indent);
        pw.println("  " + KEY_ADVERTISE_IS_ENABLED + "=" + p.advertiseIsEnabled);
        pw.print(indent);
        pw.println("  " + KEY_VIBRATION_DISABLED + ":config=" + p.disableVibration);
        // mDisableVibrationEffective is based on the currently selected policy
        pw.print(indent);
        pw.println("  " + KEY_VIBRATION_DISABLED + ":effective=" + (p.disableVibration
                && !mAccessibilityEnabled));
        pw.print(indent);
        pw.println("  " + KEY_ANIMATION_DISABLED + "=" + p.disableAnimation);
        pw.print(indent);
        pw.println("  " + KEY_FULLBACKUP_DEFERRED + "=" + p.deferFullBackup);
        pw.print(indent);
        pw.println("  " + KEY_KEYVALUE_DEFERRED + "=" + p.deferKeyValueBackup);
        pw.print(indent);
        pw.println("  " + KEY_ACTIVATE_FIREWALL_DISABLED + "=" + !p.enableFirewall);
        pw.print(indent);
        pw.println("  " + KEY_ACTIVATE_DATASAVER_DISABLED + "=" + !p.enableDataSaver);
        pw.print(indent);
        pw.println("  " + KEY_LAUNCH_BOOST_DISABLED + "=" + p.disableLaunchBoost);
        pw.println(
                "    " + KEY_ADJUST_BRIGHTNESS_DISABLED + "=" + !p.enableAdjustBrightness);
        pw.print(indent);
        pw.println("  " + KEY_ADJUST_BRIGHTNESS_FACTOR + "=" + p.adjustBrightnessFactor);
        pw.print(indent);
        pw.println("  " + KEY_GPS_MODE + "=" + p.locationMode);
        pw.print(indent);
        pw.println("  " + KEY_FORCE_ALL_APPS_STANDBY + "=" + p.forceAllAppsStandby);
        pw.print(indent);
        pw.println("  " + KEY_FORCE_BACKGROUND_CHECK + "=" + p.forceBackgroundCheck);
        pw.println(
                "    " + KEY_OPTIONAL_SENSORS_DISABLED + "=" + p.disableOptionalSensors);
        pw.print(indent);
        pw.println("  " + KEY_AOD_DISABLED + "=" + p.disableAod);
        pw.print(indent);
        pw.println("  " + KEY_SOUNDTRIGGER_DISABLED + "=" + p.disableSoundTrigger);
        pw.print(indent);
        pw.println("  " + KEY_QUICK_DOZE_ENABLED + "=" + p.enableQuickDoze);
        pw.print(indent);
        pw.println("  " + KEY_ENABLE_NIGHT_MODE + "=" + p.enableNightMode);

        pw.print("    Interactive File values:\n");
        dumpMap(pw, "      ", p.filesForInteractive);
        pw.println();

        pw.print("    Noninteractive File values:\n");
        dumpMap(pw, "      ", p.filesForNoninteractive);
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
            updatePolicyDependenciesLocked();
        }
    }
}
