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
import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatterySaverPolicyConfig;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
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
import java.util.Set;

/**
 * Class to decide whether to turn on battery saver mode for specific services.
 *
 * IMPORTANT: This class shares the power manager lock, which is very low in the lock hierarchy.
 * Do not call out with the lock held, such as AccessibilityManager. (Settings provider is okay.)
 *
 * Test: atest com.android.server.power.batterysaver.BatterySaverPolicyTest
 */
public class BatterySaverPolicy extends ContentObserver implements
        DeviceConfig.OnPropertiesChangedListener {
    private static final String TAG = "BatterySaverPolicy";

    static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE.

    @VisibleForTesting
    static final String KEY_LOCATION_MODE = "location_mode";
    @VisibleForTesting
    static final String KEY_DISABLE_VIBRATION = "disable_vibration";
    @VisibleForTesting
    static final String KEY_DISABLE_ANIMATION = "disable_animation";
    @VisibleForTesting
    static final String KEY_SOUNDTRIGGER_MODE = "soundtrigger_mode";

    /**
     * Turn on the network firewall when Battery Saver is turned on.
     * If set to false, the firewall WILL NOT be turned on when Battery Saver is turned on.
     * If set to true, the firewall WILL be turned on when Battery Saver is turned on.
     */
    @VisibleForTesting
    static final String KEY_ENABLE_FIREWALL = "enable_firewall";

    /**
     * Turn on the special low power screen brightness dimming when Battery Saver is
     * turned on.
     * If set to false, the screen brightness dimming WILL NOT be turned on by Battery Saver.
     * If set to true, the screen brightness WILL be turned on by Battery Saver.
     */
    @VisibleForTesting
    static final String KEY_ENABLE_BRIGHTNESS_ADJUSTMENT = "enable_brightness_adjustment";

    /**
     * Turn on Data Saver when Battery Saver is turned on.
     * If set to false, Data Saver WILL NOT be turned on when Battery Saver is turned on.
     * If set to true, Data Saver WILL be turned on when Battery Saver is turned on.
     */
    @VisibleForTesting
    static final String KEY_ENABLE_DATASAVER = "enable_datasaver";

    /**
     * {@code true} if the Policy should advertise to the rest of the system that battery saver
     * is enabled. This advertising could cause other system components to change their
     * behavior. This will not affect other policy flags and what they change.
     */
    @VisibleForTesting
    static final String KEY_ADVERTISE_IS_ENABLED = "advertise_is_enabled";

    @VisibleForTesting
    static final String KEY_DISABLE_LAUNCH_BOOST = "disable_launch_boost";
    @VisibleForTesting
    static final String KEY_ADJUST_BRIGHTNESS_FACTOR = "adjust_brightness_factor";
    @VisibleForTesting
    static final String KEY_DEFER_FULL_BACKUP = "defer_full_backup";
    @VisibleForTesting
    static final String KEY_DEFER_KEYVALUE_BACKUP = "defer_keyvalue_backup";
    @VisibleForTesting
    static final String KEY_FORCE_ALL_APPS_STANDBY = "force_all_apps_standby";
    @VisibleForTesting
    static final String KEY_FORCE_BACKGROUND_CHECK = "force_background_check";
    @VisibleForTesting
    static final String KEY_DISABLE_OPTIONAL_SENSORS = "disable_optional_sensors";
    @VisibleForTesting
    static final String KEY_DISABLE_AOD = "disable_aod";
    // Go into deep Doze as soon as the screen turns off.
    @VisibleForTesting
    static final String KEY_ENABLE_QUICK_DOZE = "enable_quick_doze";
    @VisibleForTesting
    static final String KEY_ENABLE_NIGHT_MODE = "enable_night_mode";

    private static final String KEY_CPU_FREQ_INTERACTIVE = "cpufreq-i";
    private static final String KEY_CPU_FREQ_NONINTERACTIVE = "cpufreq-n";

    private static final String KEY_SUFFIX_ADAPTIVE = "_adaptive";

    @VisibleForTesting
    static final Policy OFF_POLICY = new Policy(
            1f,    /* adjustBrightnessFactor */
            false, /* advertiseIsEnabled */
            new CpuFrequencies(), /* cpuFrequenciesForInteractive */
            new CpuFrequencies(), /* cpuFrequenciesForNoninteractive */
            false, /* deferFullBackup */
            false, /* deferKeyValueBackup */
            false, /* disableAnimation */
            false, /* disableAod */
            false, /* disableLaunchBoost */
            false, /* disableOptionalSensors */
            false, /* disableVibration */
            false, /* enableAdjustBrightness */
            false, /* enableDataSaver */
            false, /* enableFireWall */
            false, /* enableNightMode */
            false, /* enableQuickDoze */
            false, /* forceAllAppsStandby */
            false, /* forceBackgroundCheck */
            PowerManager.LOCATION_MODE_NO_CHANGE, /* locationMode */
            PowerManager.SOUND_TRIGGER_MODE_ALL_ENABLED /* soundTriggerMode */
    );

    private static final Policy DEFAULT_ADAPTIVE_POLICY = OFF_POLICY;

    private static final Policy DEFAULT_FULL_POLICY = new Policy(
            0.5f,  /* adjustBrightnessFactor */
            true,  /* advertiseIsEnabled */
            new CpuFrequencies(), /* cpuFrequenciesForInteractive */
            new CpuFrequencies(), /* cpuFrequenciesForNoninteractive */
            true,  /* deferFullBackup */
            true,  /* deferKeyValueBackup */
            false, /* disableAnimation */
            true,  /* disableAod */
            true,  /* disableLaunchBoost */
            true,  /* disableOptionalSensors */
            true,  /* disableVibration */
            false, /* enableAdjustBrightness */
            false, /* enableDataSaver */
            true,  /* enableFirewall */
            true, /* enableNightMode */
            true, /* enableQuickDoze */
            true, /* forceAllAppsStandby */
            true, /* forceBackgroundCheck */
            PowerManager.LOCATION_MODE_FOREGROUND_ONLY, /* locationMode */
            PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY /* soundTriggerMode */
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
    private DeviceConfig.Properties mLastDeviceConfigProperties;

    /**
     * A short string describing which battery saver is now enabled, which we dump in the eventlog.
     */
    @GuardedBy("mLock")
    private String mEventLogKeys;

    /**
     * Whether accessibility is currently enabled or not.
     */
    @VisibleForTesting
    final PolicyBoolean mAccessibilityEnabled = new PolicyBoolean("accessibility");

    /** Whether the phone has set automotive projection or not. */
    @VisibleForTesting
    final PolicyBoolean mAutomotiveProjectionActive = new PolicyBoolean("automotiveProjection");

    /** The current default adaptive policy. */
    @GuardedBy("mLock")
    private Policy mDefaultAdaptivePolicy = DEFAULT_ADAPTIVE_POLICY;

    /** The policy that will be used for adaptive battery saver. */
    @GuardedBy("mLock")
    private Policy mAdaptivePolicy = DEFAULT_ADAPTIVE_POLICY;

    /** The current default full policy. */
    @GuardedBy("mLock")
    private Policy mDefaultFullPolicy = DEFAULT_FULL_POLICY;

    /** The policy to be used for full battery saver. */
    @GuardedBy("mLock")
    private Policy mFullPolicy = DEFAULT_FULL_POLICY;

    /**
     * The current effective policy. This is based on the current policy level's policy, with any
     * required adjustments.
     */
    @GuardedBy("mLock")
    private Policy mEffectivePolicyRaw = OFF_POLICY;

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

    private final UiModeManager.OnProjectionStateChangedListener mOnProjectionStateChangedListener =
            (t, pkgs) -> mAutomotiveProjectionActive.update(!pkgs.isEmpty());

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

        final AccessibilityManager acm = mContext.getSystemService(AccessibilityManager.class);

        acm.addAccessibilityStateChangeListener(enabled -> mAccessibilityEnabled.update(enabled));
        mAccessibilityEnabled.initialize(acm.isEnabled());

        UiModeManager uiModeManager = mContext.getSystemService(UiModeManager.class);
        uiModeManager.addOnProjectionStateChangedListener(UiModeManager.PROJECTION_TYPE_AUTOMOTIVE,
                mContext.getMainExecutor(), mOnProjectionStateChangedListener);
        mAutomotiveProjectionActive.initialize(
                uiModeManager.getActiveProjectionTypes() != UiModeManager.PROJECTION_TYPE_NONE);

        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_BATTERY_SAVER,
                mContext.getMainExecutor(), this);
        mLastDeviceConfigProperties =
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_BATTERY_SAVER);
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

    @VisibleForTesting
    void invalidatePowerSaveModeCaches() {
        PowerManager.invalidatePowerSaveModeCaches();
    }

    /**
     * Notifies listeners of a policy change on the handler thread only if the current policy level
     * is not {@link #POLICY_LEVEL_OFF}.
     */
    private void maybeNotifyListenersOfPolicyChange() {
        final BatterySaverPolicyListener[] listeners;
        synchronized (mLock) {
            if (mPolicyLevel == POLICY_LEVEL_OFF) {
                // Current policy is OFF, so there's no change to notify listeners of.
                return;
            }
            // Don't call out to listeners with the lock held.
            listeners = mListeners.toArray(new BatterySaverPolicyListener[mListeners.size()]);
        }

        mHandler.post(() -> {
            for (BatterySaverPolicyListener listener : listeners) {
                listener.onBatterySaverPolicyChanged(this);
            }
        });
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        refreshSettings();
    }

    @Override
    public void onPropertiesChanged(DeviceConfig.Properties properties) {
        // Need to get all of the flags atomically.
        mLastDeviceConfigProperties =
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_BATTERY_SAVER);
        Policy newAdaptivePolicy = null;
        Policy newFullPolicy = null;

        boolean changed = false;

        synchronized (mLock) {
            for (String name : properties.getKeyset()) {
                if (name == null) {
                    continue;
                }
                if (name.endsWith(KEY_SUFFIX_ADAPTIVE)) {
                    if (newAdaptivePolicy == null) {
                        newAdaptivePolicy = Policy.fromSettings("", "",
                                mLastDeviceConfigProperties, KEY_SUFFIX_ADAPTIVE,
                                DEFAULT_ADAPTIVE_POLICY);
                    }
                } else if (newFullPolicy == null) {
                    newFullPolicy = Policy.fromSettings(mSettings, mDeviceSpecificSettings,
                            mLastDeviceConfigProperties, null, DEFAULT_FULL_POLICY);
                }
            }

            if (newFullPolicy != null) {
                changed |= maybeUpdateDefaultFullPolicy(newFullPolicy);
            }

            if (newAdaptivePolicy != null && !mAdaptivePolicy.equals(newAdaptivePolicy)) {
                mDefaultAdaptivePolicy = newAdaptivePolicy;
                // This will override any config set by an external source. This should be fine
                // for now.
                // TODO(119261320): make sure it doesn't override what's set externally
                mAdaptivePolicy = mDefaultAdaptivePolicy;
                changed |= (mPolicyLevel == POLICY_LEVEL_ADAPTIVE);
            }

            updatePolicyDependenciesLocked();
        }

        if (changed) {
            maybeNotifyListenersOfPolicyChange();
        }
    }

    private void refreshSettings() {
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

            if (!updateConstantsLocked(setting, deviceSpecificSetting)) {
                // Nothing of note changed.
                return;
            }
        }

        maybeNotifyListenersOfPolicyChange();
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    /** @return true if the currently active policy changed. */
    boolean updateConstantsLocked(String setting, String deviceSpecificSetting) {
        setting = TextUtils.emptyIfNull(setting);
        deviceSpecificSetting = TextUtils.emptyIfNull(deviceSpecificSetting);

        if (setting.equals(mSettings)
                && deviceSpecificSetting.equals(mDeviceSpecificSettings)) {
            return false;
        }

        mSettings = setting;
        mDeviceSpecificSettings = deviceSpecificSetting;

        if (DEBUG) {
            Slog.i(TAG, "mSettings=" + mSettings);
            Slog.i(TAG, "mDeviceSpecificSettings=" + mDeviceSpecificSettings);
        }

        boolean changed = maybeUpdateDefaultFullPolicy(
                Policy.fromSettings(setting, deviceSpecificSetting,
                        mLastDeviceConfigProperties, null, DEFAULT_FULL_POLICY));

        mDefaultAdaptivePolicy = Policy.fromSettings("", "",
                mLastDeviceConfigProperties, KEY_SUFFIX_ADAPTIVE, DEFAULT_ADAPTIVE_POLICY);
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
        final Policy rawPolicy = getCurrentRawPolicyLocked();
        final int locationMode;

        invalidatePowerSaveModeCaches();
        if (mAutomotiveProjectionActive.get()
                && rawPolicy.locationMode != PowerManager.LOCATION_MODE_NO_CHANGE
                && rawPolicy.locationMode != PowerManager.LOCATION_MODE_FOREGROUND_ONLY) {
            // If car projection is enabled, ensure that navigation works.
            locationMode = PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
        } else {
            locationMode = rawPolicy.locationMode;
        }

        mEffectivePolicyRaw = new Policy(
                rawPolicy.adjustBrightnessFactor,
                rawPolicy.advertiseIsEnabled,
                rawPolicy.cpuFrequenciesForInteractive,
                rawPolicy.cpuFrequenciesForNoninteractive,
                rawPolicy.deferFullBackup,
                rawPolicy.deferKeyValueBackup,
                rawPolicy.disableAnimation,
                rawPolicy.disableAod,
                rawPolicy.disableLaunchBoost,
                rawPolicy.disableOptionalSensors,
                // Don't disable vibration when accessibility is on.
                rawPolicy.disableVibration && !mAccessibilityEnabled.get(),
                rawPolicy.enableAdjustBrightness,
                rawPolicy.enableDataSaver,
                rawPolicy.enableFirewall,
                // Don't force night mode when car projection is enabled.
                rawPolicy.enableNightMode && !mAutomotiveProjectionActive.get(),
                rawPolicy.enableQuickDoze,
                rawPolicy.forceAllAppsStandby,
                rawPolicy.forceBackgroundCheck,
                locationMode,
                rawPolicy.soundTriggerMode
        );


        final StringBuilder sb = new StringBuilder();

        if (mEffectivePolicyRaw.forceAllAppsStandby) sb.append("A");
        if (mEffectivePolicyRaw.forceBackgroundCheck) sb.append("B");

        if (mEffectivePolicyRaw.disableVibration) sb.append("v");
        if (mEffectivePolicyRaw.disableAnimation) sb.append("a");

        sb.append(mEffectivePolicyRaw.soundTriggerMode);

        if (mEffectivePolicyRaw.deferFullBackup) sb.append("F");
        if (mEffectivePolicyRaw.deferKeyValueBackup) sb.append("K");
        if (mEffectivePolicyRaw.enableFirewall) sb.append("f");
        if (mEffectivePolicyRaw.enableDataSaver) sb.append("d");
        if (mEffectivePolicyRaw.enableAdjustBrightness) sb.append("b");

        if (mEffectivePolicyRaw.disableLaunchBoost) sb.append("l");
        if (mEffectivePolicyRaw.disableOptionalSensors) sb.append("S");
        if (mEffectivePolicyRaw.disableAod) sb.append("o");
        if (mEffectivePolicyRaw.enableQuickDoze) sb.append("q");

        sb.append(mEffectivePolicyRaw.locationMode);

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
         * @see #KEY_DEFER_FULL_BACKUP
         */
        public final boolean deferFullBackup;

        /**
         * {@code true} if key value backup is deferred in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_DEFER_KEYVALUE_BACKUP
         */
        public final boolean deferKeyValueBackup;

        /**
         * {@code true} if animation is disabled in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_DISABLE_ANIMATION
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
         * @see #KEY_SOUNDTRIGGER_MODE
         */
        public final int soundTriggerMode;

        /**
         * {@code true} if vibration is disabled in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_DISABLE_VIBRATION
         */
        public final boolean disableVibration;

        /**
         * {@code true} if low power mode brightness adjustment should be turned on in battery saver
         * mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ENABLE_BRIGHTNESS_ADJUSTMENT
         */
        public final boolean enableAdjustBrightness;

        /**
         * {@code true} if data saver should be turned on in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ENABLE_DATASAVER
         */
        public final boolean enableDataSaver;

        /**
         * {@code true} if network policy firewall should be turned on in battery saver mode.
         *
         * @see Settings.Global#BATTERY_SAVER_CONSTANTS
         * @see #KEY_ENABLE_FIREWALL
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
         * List of CPU frequencies that should be written when battery saver is activated
         * and the device is interactive.
         *
         * We use this to change the max CPU frequencies.
         */
        public final CpuFrequencies cpuFrequenciesForInteractive;

        /**
         * List of CPU frequencies that should be written when battery saver is activated
         * and the device is non-interactive.
         *
         * We use this to change the max CPU frequencies.
         */
        public final CpuFrequencies cpuFrequenciesForNoninteractive;

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
         * @see #KEY_LOCATION_MODE
         */
        public final int locationMode;

        private final int mHashCode;

        Policy(
                float adjustBrightnessFactor,
                boolean advertiseIsEnabled,
                CpuFrequencies cpuFrequenciesForInteractive,
                CpuFrequencies cpuFrequenciesForNoninteractive,
                boolean deferFullBackup,
                boolean deferKeyValueBackup,
                boolean disableAnimation,
                boolean disableAod,
                boolean disableLaunchBoost,
                boolean disableOptionalSensors,
                boolean disableVibration,
                boolean enableAdjustBrightness,
                boolean enableDataSaver,
                boolean enableFirewall,
                boolean enableNightMode,
                boolean enableQuickDoze,
                boolean forceAllAppsStandby,
                boolean forceBackgroundCheck,
                int locationMode,
                int soundTriggerMode) {

            this.adjustBrightnessFactor = Math.min(1, Math.max(0, adjustBrightnessFactor));
            this.advertiseIsEnabled = advertiseIsEnabled;
            this.cpuFrequenciesForInteractive = cpuFrequenciesForInteractive;
            this.cpuFrequenciesForNoninteractive = cpuFrequenciesForNoninteractive;
            this.deferFullBackup = deferFullBackup;
            this.deferKeyValueBackup = deferKeyValueBackup;
            this.disableAnimation = disableAnimation;
            this.disableAod = disableAod;
            this.disableLaunchBoost = disableLaunchBoost;
            this.disableOptionalSensors = disableOptionalSensors;
            this.disableVibration = disableVibration;
            this.enableAdjustBrightness = enableAdjustBrightness;
            this.enableDataSaver = enableDataSaver;
            this.enableFirewall = enableFirewall;
            this.enableNightMode = enableNightMode;
            this.enableQuickDoze = enableQuickDoze;
            this.forceAllAppsStandby = forceAllAppsStandby;
            this.forceBackgroundCheck = forceBackgroundCheck;

            if (locationMode < PowerManager.MIN_LOCATION_MODE
                    || PowerManager.MAX_LOCATION_MODE < locationMode) {
                Slog.e(TAG, "Invalid location mode: " + locationMode);
                this.locationMode = PowerManager.LOCATION_MODE_NO_CHANGE;
            } else {
                this.locationMode = locationMode;
            }

            if (soundTriggerMode < PowerManager.MIN_SOUND_TRIGGER_MODE
                    || soundTriggerMode > PowerManager.MAX_SOUND_TRIGGER_MODE) {
                Slog.e(TAG, "Invalid SoundTrigger mode: " + soundTriggerMode);
                this.soundTriggerMode = PowerManager.SOUND_TRIGGER_MODE_ALL_ENABLED;
            } else {
                this.soundTriggerMode = soundTriggerMode;
            }

            mHashCode = Objects.hash(
                    adjustBrightnessFactor,
                    advertiseIsEnabled,
                    cpuFrequenciesForInteractive,
                    cpuFrequenciesForNoninteractive,
                    deferFullBackup,
                    deferKeyValueBackup,
                    disableAnimation,
                    disableAod,
                    disableLaunchBoost,
                    disableOptionalSensors,
                    disableVibration,
                    enableAdjustBrightness,
                    enableDataSaver,
                    enableFirewall,
                    enableNightMode,
                    enableQuickDoze,
                    forceAllAppsStandby,
                    forceBackgroundCheck,
                    locationMode,
                    soundTriggerMode);
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
                    (new CpuFrequencies()).parseString(cpuFreqInteractive),
                    (new CpuFrequencies()).parseString(cpuFreqNoninteractive),
                    config.getDeferFullBackup(),
                    config.getDeferKeyValueBackup(),
                    config.getDisableAnimation(),
                    config.getDisableAod(),
                    config.getDisableLaunchBoost(),
                    config.getDisableOptionalSensors(),
                    config.getDisableVibration(),
                    config.getEnableAdjustBrightness(),
                    config.getEnableDataSaver(),
                    config.getEnableFirewall(),
                    config.getEnableNightMode(),
                    config.getEnableQuickDoze(),
                    config.getForceAllAppsStandby(),
                    config.getForceBackgroundCheck(),
                    config.getLocationMode(),
                    config.getSoundTriggerMode()
            );
        }

        BatterySaverPolicyConfig toConfig() {
            return new BatterySaverPolicyConfig.Builder()
                    .addDeviceSpecificSetting(KEY_CPU_FREQ_INTERACTIVE,
                            cpuFrequenciesForInteractive.toString())
                    .addDeviceSpecificSetting(KEY_CPU_FREQ_NONINTERACTIVE,
                            cpuFrequenciesForNoninteractive.toString())
                    .setAdjustBrightnessFactor(adjustBrightnessFactor)
                    .setAdvertiseIsEnabled(advertiseIsEnabled)
                    .setDeferFullBackup(deferFullBackup)
                    .setDeferKeyValueBackup(deferKeyValueBackup)
                    .setDisableAnimation(disableAnimation)
                    .setDisableAod(disableAod)
                    .setDisableLaunchBoost(disableLaunchBoost)
                    .setDisableOptionalSensors(disableOptionalSensors)
                    .setDisableVibration(disableVibration)
                    .setEnableAdjustBrightness(enableAdjustBrightness)
                    .setEnableDataSaver(enableDataSaver)
                    .setEnableFirewall(enableFirewall)
                    .setEnableNightMode(enableNightMode)
                    .setEnableQuickDoze(enableQuickDoze)
                    .setForceAllAppsStandby(forceAllAppsStandby)
                    .setForceBackgroundCheck(forceBackgroundCheck)
                    .setLocationMode(locationMode)
                    .setSoundTriggerMode(soundTriggerMode)
                    .build();
        }

        @VisibleForTesting
        static Policy fromSettings(String settings, String deviceSpecificSettings,
                DeviceConfig.Properties properties, String configSuffix) {
            return fromSettings(settings, deviceSpecificSettings, properties, configSuffix,
                    OFF_POLICY);
        }

        private static Policy fromSettings(String settings, String deviceSpecificSettings,
                DeviceConfig.Properties properties, String configSuffix, Policy defaultPolicy) {
            final KeyValueListParser parser = new KeyValueListParser(',');
            configSuffix = TextUtils.emptyIfNull(configSuffix);

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

            // The Settings value overrides everything, since that will be set by the user.
            // The DeviceConfig value takes second place, with the default as the last choice.
            final float adjustBrightnessFactor = parser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR,
                    properties.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR + configSuffix,
                            defaultPolicy.adjustBrightnessFactor));
            final boolean advertiseIsEnabled = parser.getBoolean(KEY_ADVERTISE_IS_ENABLED,
                    properties.getBoolean(KEY_ADVERTISE_IS_ENABLED + configSuffix,
                            defaultPolicy.advertiseIsEnabled));
            final boolean deferFullBackup = parser.getBoolean(KEY_DEFER_FULL_BACKUP,
                    properties.getBoolean(KEY_DEFER_FULL_BACKUP + configSuffix,
                            defaultPolicy.deferFullBackup));
            final boolean deferKeyValueBackup = parser.getBoolean(KEY_DEFER_KEYVALUE_BACKUP,
                    properties.getBoolean(KEY_DEFER_KEYVALUE_BACKUP + configSuffix,
                            defaultPolicy.deferKeyValueBackup));
            final boolean disableAnimation = parser.getBoolean(KEY_DISABLE_ANIMATION,
                    properties.getBoolean(KEY_DISABLE_ANIMATION + configSuffix,
                            defaultPolicy.disableAnimation));
            final boolean disableAod = parser.getBoolean(KEY_DISABLE_AOD,
                    properties.getBoolean(KEY_DISABLE_AOD + configSuffix,
                            defaultPolicy.disableAod));
            final boolean disableLaunchBoost = parser.getBoolean(KEY_DISABLE_LAUNCH_BOOST,
                    properties.getBoolean(KEY_DISABLE_LAUNCH_BOOST + configSuffix,
                            defaultPolicy.disableLaunchBoost));
            final boolean disableOptionalSensors = parser.getBoolean(KEY_DISABLE_OPTIONAL_SENSORS,
                    properties.getBoolean(KEY_DISABLE_OPTIONAL_SENSORS + configSuffix,
                            defaultPolicy.disableOptionalSensors));
            final boolean disableVibrationConfig = parser.getBoolean(KEY_DISABLE_VIBRATION,
                    properties.getBoolean(KEY_DISABLE_VIBRATION + configSuffix,
                            defaultPolicy.disableVibration));
            final boolean enableBrightnessAdjustment = parser.getBoolean(
                    KEY_ENABLE_BRIGHTNESS_ADJUSTMENT,
                    properties.getBoolean(KEY_ENABLE_BRIGHTNESS_ADJUSTMENT + configSuffix,
                            defaultPolicy.enableAdjustBrightness));
            final boolean enableDataSaver = parser.getBoolean(KEY_ENABLE_DATASAVER,
                    properties.getBoolean(KEY_ENABLE_DATASAVER + configSuffix,
                            defaultPolicy.enableDataSaver));
            final boolean enableFirewall = parser.getBoolean(KEY_ENABLE_FIREWALL,
                    properties.getBoolean(KEY_ENABLE_FIREWALL + configSuffix,
                            defaultPolicy.enableFirewall));
            final boolean enableNightMode = parser.getBoolean(KEY_ENABLE_NIGHT_MODE,
                    properties.getBoolean(KEY_ENABLE_NIGHT_MODE + configSuffix,
                            defaultPolicy.enableNightMode));
            final boolean enableQuickDoze = parser.getBoolean(KEY_ENABLE_QUICK_DOZE,
                    properties.getBoolean(KEY_ENABLE_QUICK_DOZE + configSuffix,
                            defaultPolicy.enableQuickDoze));
            final boolean forceAllAppsStandby = parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY,
                    properties.getBoolean(KEY_FORCE_ALL_APPS_STANDBY + configSuffix,
                            defaultPolicy.forceAllAppsStandby));
            final boolean forceBackgroundCheck = parser.getBoolean(KEY_FORCE_BACKGROUND_CHECK,
                    properties.getBoolean(KEY_FORCE_BACKGROUND_CHECK + configSuffix,
                            defaultPolicy.forceBackgroundCheck));
            final int locationMode = parser.getInt(KEY_LOCATION_MODE,
                    properties.getInt(KEY_LOCATION_MODE + configSuffix,
                            defaultPolicy.locationMode));
            final int soundTriggerMode = parser.getInt(KEY_SOUNDTRIGGER_MODE,
                    properties.getInt(KEY_SOUNDTRIGGER_MODE + configSuffix,
                            defaultPolicy.soundTriggerMode));
            return new Policy(
                    adjustBrightnessFactor,
                    advertiseIsEnabled,
                    (new CpuFrequencies()).parseString(cpuFreqInteractive),
                    (new CpuFrequencies()).parseString(cpuFreqNoninteractive),
                    deferFullBackup,
                    deferKeyValueBackup,
                    disableAnimation,
                    disableAod,
                    disableLaunchBoost,
                    disableOptionalSensors,
                    /* disableVibration */
                    disableVibrationConfig,
                    enableBrightnessAdjustment,
                    enableDataSaver,
                    enableFirewall,
                    enableNightMode,
                    enableQuickDoze,
                    forceAllAppsStandby,
                    forceBackgroundCheck,
                    locationMode,
                    soundTriggerMode
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
                    && disableVibration == other.disableVibration
                    && enableAdjustBrightness == other.enableAdjustBrightness
                    && enableDataSaver == other.enableDataSaver
                    && enableFirewall == other.enableFirewall
                    && enableNightMode == other.enableNightMode
                    && enableQuickDoze == other.enableQuickDoze
                    && forceAllAppsStandby == other.forceAllAppsStandby
                    && forceBackgroundCheck == other.forceBackgroundCheck
                    && locationMode == other.locationMode
                    && soundTriggerMode == other.soundTriggerMode
                    && cpuFrequenciesForInteractive.equals(other.cpuFrequenciesForInteractive)
                    && cpuFrequenciesForNoninteractive.equals(
                            other.cpuFrequenciesForNoninteractive);
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
                    boolean soundTriggerBatterySaverEnabled = currPolicy.advertiseIsEnabled
                            || currPolicy.soundTriggerMode
                            != PowerManager.SOUND_TRIGGER_MODE_ALL_ENABLED;
                    return builder.setBatterySaverEnabled(soundTriggerBatterySaverEnabled)
                            .setSoundTriggerMode(currPolicy.soundTriggerMode)
                            .build();
                case ServiceType.VIBRATION:
                    return builder.setBatterySaverEnabled(currPolicy.disableVibration)
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
            // If we are leaving the full policy level, then any overrides to the full policy set
            // through #setFullPolicyLocked should be cleared.
            if (mPolicyLevel == POLICY_LEVEL_FULL) {
                mFullPolicy = mDefaultFullPolicy;
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

    /**
     * Get the current policy for the provided policy level.
     */
    Policy getPolicyLocked(@PolicyLevel int policyLevel) {
        switch (policyLevel) {
            case POLICY_LEVEL_OFF:
                return OFF_POLICY;
            case POLICY_LEVEL_ADAPTIVE:
                return mAdaptivePolicy;
            case POLICY_LEVEL_FULL:
                return mFullPolicy;
        }

        throw new IllegalArgumentException(
                "getPolicyLocked: incorrect policy level provided - " + policyLevel);
    }

    /**
     * Updates the default policy with the passed in policy.
     * If the full policy is not overridden with runtime settings, then the full policy will be
     * updated.
     *
     * @return True if the active policy requires an update, false if not.
     */
    private boolean maybeUpdateDefaultFullPolicy(Policy p) {
        boolean fullPolicyChanged = false;
        if (!mDefaultFullPolicy.equals(p)) {
            // default policy can be overridden by #setFullPolicyLocked
            boolean isDefaultFullPolicyOverridden = !mDefaultFullPolicy.equals(mFullPolicy);
            if (!isDefaultFullPolicyOverridden) {
                mFullPolicy = p;
                fullPolicyChanged = (mPolicyLevel == POLICY_LEVEL_FULL);
            }
            mDefaultFullPolicy = p;
        }
        return fullPolicyChanged;
    }

    /** @return true if the current policy changed and the policy level is FULL. */
    boolean setFullPolicyLocked(Policy p) {
        if (p == null) {
            Slog.wtf(TAG, "setFullPolicy given null policy");
            return false;
        }
        if (mFullPolicy.equals(p)) {
            return false;
        }

        mFullPolicy = p;
        if (mPolicyLevel == POLICY_LEVEL_FULL) {
            updatePolicyDependenciesLocked();
            return true;
        }
        return false;
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
        return mEffectivePolicyRaw;
    }

    private Policy getCurrentRawPolicyLocked() {
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
            return interactive
                    ? getCurrentPolicyLocked().cpuFrequenciesForInteractive.toSysFileMap()
                    : getCurrentPolicyLocked().cpuFrequenciesForNoninteractive.toSysFileMap();
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
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        synchronized (mLock) {
            ipw.println();
            mBatterySavingStats.dump(ipw);

            ipw.println();
            ipw.println("Battery saver policy (*NOTE* they only apply when battery saver is ON):");
            ipw.increaseIndent();
            ipw.println("Settings: " + Settings.Global.BATTERY_SAVER_CONSTANTS);
            ipw.increaseIndent();
            ipw.println("value: " + mSettings);
            ipw.decreaseIndent();
            ipw.println("Settings: " + mDeviceSpecificSettingsSource);
            ipw.increaseIndent();
            ipw.println("value: " + mDeviceSpecificSettings);
            ipw.decreaseIndent();
            ipw.println("DeviceConfig: " + DeviceConfig.NAMESPACE_BATTERY_SAVER);
            ipw.increaseIndent();
            final Set<String> keys = mLastDeviceConfigProperties.getKeyset();
            if (keys.size() == 0) {
                ipw.println("N/A");
            } else {
                for (final String key : keys) {
                    ipw.print(key);
                    ipw.print(": ");
                    ipw.println(mLastDeviceConfigProperties.getString(key, null));
                }
            }
            ipw.decreaseIndent();

            ipw.println("mAccessibilityEnabled=" + mAccessibilityEnabled.get());
            ipw.println("mAutomotiveProjectionActive=" + mAutomotiveProjectionActive.get());
            ipw.println("mPolicyLevel=" + mPolicyLevel);

            dumpPolicyLocked(ipw, "default full", mDefaultFullPolicy);
            dumpPolicyLocked(ipw, "current full", mFullPolicy);
            dumpPolicyLocked(ipw, "default adaptive", mDefaultAdaptivePolicy);
            dumpPolicyLocked(ipw, "current adaptive", mAdaptivePolicy);
            dumpPolicyLocked(ipw, "effective", mEffectivePolicyRaw);

            ipw.decreaseIndent();
        }
    }

    private void dumpPolicyLocked(IndentingPrintWriter pw, String label, Policy p) {
        pw.println();
        pw.println("Policy '" + label + "'");
        pw.increaseIndent();
        pw.println(KEY_ADVERTISE_IS_ENABLED + "=" + p.advertiseIsEnabled);
        pw.println(KEY_DISABLE_VIBRATION + "=" + p.disableVibration);
        pw.println(KEY_DISABLE_ANIMATION + "=" + p.disableAnimation);
        pw.println(KEY_DEFER_FULL_BACKUP + "=" + p.deferFullBackup);
        pw.println(KEY_DEFER_KEYVALUE_BACKUP + "=" + p.deferKeyValueBackup);
        pw.println(KEY_ENABLE_FIREWALL + "=" + p.enableFirewall);
        pw.println(KEY_ENABLE_DATASAVER + "=" + p.enableDataSaver);
        pw.println(KEY_DISABLE_LAUNCH_BOOST + "=" + p.disableLaunchBoost);
        pw.println(KEY_ENABLE_BRIGHTNESS_ADJUSTMENT + "=" + p.enableAdjustBrightness);
        pw.println(KEY_ADJUST_BRIGHTNESS_FACTOR + "=" + p.adjustBrightnessFactor);
        pw.println(KEY_LOCATION_MODE + "=" + p.locationMode);
        pw.println(KEY_FORCE_ALL_APPS_STANDBY + "=" + p.forceAllAppsStandby);
        pw.println(KEY_FORCE_BACKGROUND_CHECK + "=" + p.forceBackgroundCheck);
        pw.println(KEY_DISABLE_OPTIONAL_SENSORS + "=" + p.disableOptionalSensors);
        pw.println(KEY_DISABLE_AOD + "=" + p.disableAod);
        pw.println(KEY_SOUNDTRIGGER_MODE + "=" + p.soundTriggerMode);
        pw.println(KEY_ENABLE_QUICK_DOZE + "=" + p.enableQuickDoze);
        pw.println(KEY_ENABLE_NIGHT_MODE + "=" + p.enableNightMode);

        pw.println("Interactive File values:");
        pw.increaseIndent();
        dumpMap(pw, p.cpuFrequenciesForInteractive.toSysFileMap());
        pw.decreaseIndent();
        pw.println();

        pw.println("Noninteractive File values:");
        pw.increaseIndent();
        dumpMap(pw, p.cpuFrequenciesForNoninteractive.toSysFileMap());
        pw.decreaseIndent();

        // Decrease from indent right after "Policy" line
        pw.decreaseIndent();
    }

    private void dumpMap(PrintWriter pw, ArrayMap<String, String> map) {
        if (map == null || map.size() == 0) {
            pw.println("N/A");
            return;
        }
        final int size = map.size();
        for (int i = 0; i < size; i++) {
            pw.print(map.keyAt(i));
            pw.print(": '");
            pw.print(map.valueAt(i));
            pw.println("'");
        }
    }

    /**
     * A boolean value which should trigger a policy update when it changes.
     */
    @VisibleForTesting
    class PolicyBoolean {
        private final String mDebugName;
        @GuardedBy("mLock")
        private boolean mValue;

        private PolicyBoolean(String debugName) {
            mDebugName = debugName;
        }

        /** Sets the initial value without triggering a policy update. */
        private void initialize(boolean initialValue) {
            synchronized (mLock) {
                mValue = initialValue;
            }
        }

        private boolean get() {
            synchronized (mLock) {
                return mValue;
            }
        }

        /** Sets a value, which if different from the current value, triggers a policy update. */
        @VisibleForTesting
        void update(boolean newValue) {
            synchronized (mLock) {
                if (mValue != newValue) {
                    Slog.d(TAG, mDebugName + " changed to " + newValue + ", updating policy.");
                    mValue = newValue;
                    updatePolicyDependenciesLocked();
                    maybeNotifyListenersOfPolicyChange();
                }
            }
        }
    }
}
