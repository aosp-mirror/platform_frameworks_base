/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.VibrationAttributes.USAGE_ACCESSIBILITY;
import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Vibrator.VibrationIntensity;
import android.os.vibrator.VibrationConfig;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Controls all the system settings related to vibration. */
final class VibrationSettings {
    private static final String TAG = "VibrationSettings";

    /**
     * Set of usages allowed for vibrations from background processes.
     *
     * <p>Some examples are notification, ringtone or alarm vibrations, that are allowed to vibrate
     * unexpectedly as they are meant to grab the user's attention. Hardware feedback and physical
     * emulation are also supported, as the trigger process might still be in the background when
     * the user interaction wakes the device.
     */
    private static final Set<Integer> BACKGROUND_PROCESS_USAGE_ALLOWLIST = new HashSet<>(
            Arrays.asList(
                    USAGE_RINGTONE,
                    USAGE_ALARM,
                    USAGE_NOTIFICATION,
                    USAGE_COMMUNICATION_REQUEST,
                    USAGE_HARDWARE_FEEDBACK,
                    USAGE_PHYSICAL_EMULATION));

    /**
     * Set of usages allowed for vibrations in battery saver mode (low power).
     *
     * <p>Some examples are ringtone or alarm vibrations, that have high priority and should vibrate
     * even when the device is saving battery.
     */
    private static final Set<Integer> BATTERY_SAVER_USAGE_ALLOWLIST = new HashSet<>(
            Arrays.asList(
                    USAGE_RINGTONE,
                    USAGE_ALARM,
                    USAGE_COMMUNICATION_REQUEST));

    /**
     * Usage allowed for vibrations when {@link Settings.System#VIBRATE_ON} is disabled.
     *
     * <p>The only allowed usage is accessibility, which is applied when the user enables talkback.
     * Other usages that must ignore this setting should use
     * {@link VibrationAttributes#FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF}.
     */
    private static final int VIBRATE_ON_DISABLED_USAGE_ALLOWED = USAGE_ACCESSIBILITY;

    /**
     * Set of usages allowed for vibrations from system packages when the screen goes off.
     *
     * <p>Some examples are touch and hardware feedback, and physical emulation. When the system is
     * playing one of these usages during the screen off event then the vibration will not be
     * cancelled by the service.
     */
    private static final Set<Integer> SYSTEM_VIBRATION_SCREEN_OFF_USAGE_ALLOWLIST = new HashSet<>(
            Arrays.asList(
                    USAGE_TOUCH,
                    USAGE_PHYSICAL_EMULATION,
                    USAGE_HARDWARE_FEEDBACK));

    /**
     * Set of reasons for {@link PowerManager} going to sleep events that allows vibrations to
     * continue running.
     *
     * <p>Some examples are timeout and inattentive, which indicates automatic screen off events.
     * When a vibration is playing during one of these screen off events then it will not be
     * cancelled by the service.
     */
    private static final Set<Integer> POWER_MANAGER_SLEEP_REASON_ALLOWLIST = new HashSet<>(
            Arrays.asList(
                    PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE,
                    PowerManager.GO_TO_SLEEP_REASON_TIMEOUT));

    private static final IntentFilter USER_SWITCHED_INTENT_FILTER =
            new IntentFilter(Intent.ACTION_USER_SWITCHED);
    private static final IntentFilter INTERNAL_RINGER_MODE_CHANGED_INTENT_FILTER =
            new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);

    /** Listener for changes on vibration settings. */
    interface OnVibratorSettingsChanged {
        /** Callback triggered when any of the vibrator settings change. */
        void onChange();
    }

    private final Object mLock = new Object();
    private final Context mContext;
    private final String mSystemUiPackage;
    @VisibleForTesting
    final SettingsContentObserver mSettingObserver;
    @VisibleForTesting
    final UidObserver mUidObserver;
    @VisibleForTesting
    final SettingsBroadcastReceiver mSettingChangeReceiver;

    @GuardedBy("mLock")
    private final List<OnVibratorSettingsChanged> mListeners = new ArrayList<>();
    private final SparseArray<VibrationEffect> mFallbackEffects;

    private final VibrationConfig mVibrationConfig;

    @GuardedBy("mLock")
    @Nullable
    private AudioManager mAudioManager;
    @GuardedBy("mLock")
    @Nullable
    private PowerManagerInternal mPowerManagerInternal;

    @GuardedBy("mLock")
    private boolean mVibrateInputDevices;
    @GuardedBy("mLock")
    private SparseIntArray mCurrentVibrationIntensities = new SparseIntArray();
    @GuardedBy("mLock")
    private boolean mBatterySaverMode;
    @GuardedBy("mLock")
    private boolean mVibrateOn;
    @GuardedBy("mLock")
    private int mRingerMode;

    VibrationSettings(Context context, Handler handler) {
        this(context, handler, new VibrationConfig(context.getResources()));
    }

    @VisibleForTesting
    VibrationSettings(Context context, Handler handler, VibrationConfig config) {
        mContext = context;
        mVibrationConfig = config;
        mSettingObserver = new SettingsContentObserver(handler);
        mUidObserver = new UidObserver();
        mSettingChangeReceiver = new SettingsBroadcastReceiver();

        mSystemUiPackage = LocalServices.getService(PackageManagerInternal.class)
                .getSystemUiServiceComponent().getPackageName();

        VibrationEffect clickEffect = createEffectFromResource(
                com.android.internal.R.array.config_virtualKeyVibePattern);
        VibrationEffect doubleClickEffect = createEffectFromResource(
                com.android.internal.R.array.config_doubleClickVibePattern);
        VibrationEffect heavyClickEffect = createEffectFromResource(
                com.android.internal.R.array.config_longPressVibePattern);
        VibrationEffect tickEffect = createEffectFromResource(
                com.android.internal.R.array.config_clockTickVibePattern);

        mFallbackEffects = new SparseArray<>();
        mFallbackEffects.put(VibrationEffect.EFFECT_CLICK, clickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_DOUBLE_CLICK, doubleClickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_TICK, tickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_HEAVY_CLICK, heavyClickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_TEXTURE_TICK,
                VibrationEffect.get(VibrationEffect.EFFECT_TICK, false));

        // Update with current values from settings.
        update();
    }

    public void onSystemReady() {
        PowerManagerInternal pm = LocalServices.getService(PowerManagerInternal.class);
        AudioManager am = mContext.getSystemService(AudioManager.class);
        int ringerMode = am.getRingerModeInternal();

        synchronized (mLock) {
            mPowerManagerInternal = pm;
            mAudioManager = am;
            mRingerMode = ringerMode;
        }

        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

        pm.registerLowPowerModeObserver(
                new PowerManagerInternal.LowPowerModeListener() {
                    @Override
                    public int getServiceType() {
                        return PowerManager.ServiceType.VIBRATION;
                    }

                    @Override
                    public void onLowPowerModeChanged(PowerSaveState result) {
                        boolean shouldNotifyListeners;
                        synchronized (mLock) {
                            shouldNotifyListeners = result.batterySaverEnabled != mBatterySaverMode;
                            mBatterySaverMode = result.batterySaverEnabled;
                        }
                        if (shouldNotifyListeners) {
                            notifyListeners();
                        }
                    }
                });

        registerSettingsChangeReceiver(USER_SWITCHED_INTENT_FILTER);
        registerSettingsChangeReceiver(INTERNAL_RINGER_MODE_CHANGED_INTENT_FILTER);

        // Listen to all settings that might affect the result of Vibrator.getVibrationIntensity.
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_INPUT_DEVICES));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_ON));
        registerSettingsObserver(Settings.System.getUriFor(
                Settings.System.HAPTIC_FEEDBACK_ENABLED));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.ALARM_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.MEDIA_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.NOTIFICATION_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.RING_VIBRATION_INTENSITY));

        // Update with newly loaded services.
        update();
    }

    /**
     * Add listener to vibrator settings changes. This will trigger the listener with current state
     * immediately and every time one of the settings change.
     */
    public void addListener(OnVibratorSettingsChanged listener) {
        synchronized (mLock) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    /** Remove listener to vibrator settings. */
    public void removeListener(OnVibratorSettingsChanged listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    /**
     * The duration, in milliseconds, that should be applied to convert vibration effect's
     * {@link android.os.vibrator.RampSegment} to a {@link android.os.vibrator.StepSegment} on
     * devices without PWLE support.
     */
    public int getRampStepDuration() {
        return mVibrationConfig.getRampStepDurationMs();
    }

    /**
     * The duration, in milliseconds, that should be applied to the ramp to turn off the vibrator
     * when a vibration is cancelled or finished at non-zero amplitude.
     */
    public int getRampDownDuration() {
        return mVibrationConfig.getRampDownDurationMs();
    }

    /**
     * Return default vibration intensity for given usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The vibration intensity, one of Vibrator.VIBRATION_INTENSITY_*
     */
    public int getDefaultIntensity(@VibrationAttributes.Usage int usageHint) {
        return mVibrationConfig.getDefaultVibrationIntensity(usageHint);
    }

    /**
     * Return the current vibration intensity set for given usage at the user settings.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The vibration intensity, one of Vibrator.VIBRATION_INTENSITY_*
     */
    public int getCurrentIntensity(@VibrationAttributes.Usage int usageHint) {
        int defaultIntensity = getDefaultIntensity(usageHint);
        synchronized (mLock) {
            return mCurrentVibrationIntensities.get(usageHint, defaultIntensity);
        }
    }

    /**
     * Return a {@link VibrationEffect} that should be played if the device do not support given
     * {@code effectId}.
     *
     * @param effectId one of VibrationEffect.EFFECT_*
     * @return The effect to be played as a fallback
     */
    public VibrationEffect getFallbackEffect(int effectId) {
        return mFallbackEffects.get(effectId);
    }

    /** Return {@code true} if input devices should vibrate instead of this device. */
    public boolean shouldVibrateInputDevices() {
        return mVibrateInputDevices;
    }

    /**
     * Check if given vibration should be ignored by the service.
     *
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored,
     * null otherwise.
     */
    @Nullable
    public Vibration.Status shouldIgnoreVibration(int uid, VibrationAttributes attrs) {
        final int usage = attrs.getUsage();
        synchronized (mLock) {
            if (!mUidObserver.isUidForeground(uid)
                    && !BACKGROUND_PROCESS_USAGE_ALLOWLIST.contains(usage)) {
                return Vibration.Status.IGNORED_BACKGROUND;
            }

            if (mBatterySaverMode && !BATTERY_SAVER_USAGE_ALLOWLIST.contains(usage)) {
                return Vibration.Status.IGNORED_FOR_POWER;
            }

            if (!attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)) {
                if (!mVibrateOn && (VIBRATE_ON_DISABLED_USAGE_ALLOWED != usage)) {
                    return Vibration.Status.IGNORED_FOR_SETTINGS;
                }

                if (getCurrentIntensity(usage) == Vibrator.VIBRATION_INTENSITY_OFF) {
                    return Vibration.Status.IGNORED_FOR_SETTINGS;
                }
            }

            if (!attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
                if (!shouldVibrateForRingerModeLocked(usage)) {
                    return Vibration.Status.IGNORED_FOR_RINGER_MODE;
                }
            }
        }
        return null;
    }

    /**
     * Check if given vibration should be cancelled by the service when the screen goes off.
     *
     * <p>When the system is entering a non-interactive state, we want to cancel vibrations in case
     * a misbehaving app has abandoned them. However, it may happen that the system is currently
     * playing haptic feedback as part of the transition. So we don't cancel system vibrations of
     * usages like touch and hardware feedback, and physical emulation.
     *
     * @return true if the vibration should be cancelled when the screen goes off, false otherwise.
     */
    public boolean shouldCancelVibrationOnScreenOff(int uid, String opPkg,
            @VibrationAttributes.Usage int usage, long vibrationStartUptimeMillis) {
        PowerManagerInternal pm;
        synchronized (mLock) {
            pm = mPowerManagerInternal;
        }
        if (pm != null) {
            // The SleepData from PowerManager may refer to a more recent sleep than the broadcast
            // that triggered this method call. That's ok because only automatic sleeps would be
            // ignored here and not cancel a vibration, and those are usually triggered by timeout
            // or inactivity, so it's unlikely that it will override a more active goToSleep reason.
            PowerManager.SleepData sleepData = pm.getLastGoToSleep();
            if ((sleepData.goToSleepUptimeMillis < vibrationStartUptimeMillis)
                    || POWER_MANAGER_SLEEP_REASON_ALLOWLIST.contains(sleepData.goToSleepReason)) {
                // Ignore screen off events triggered before the vibration started, and all
                // automatic "go to sleep" events from allowlist.
                Slog.d(TAG, "Ignoring screen off event triggered at uptime "
                        + sleepData.goToSleepUptimeMillis + " for reason "
                        + PowerManager.sleepReasonToString(sleepData.goToSleepReason));
                return false;
            }
        }
        if (!SYSTEM_VIBRATION_SCREEN_OFF_USAGE_ALLOWLIST.contains(usage)) {
            // Usages not allowed even for system vibrations should always be cancelled.
            return true;
        }
        // Only allow vibrations from System packages to continue vibrating when the screen goes off
        return uid != Process.SYSTEM_UID && uid != 0 && !mSystemUiPackage.equals(opPkg);
    }

    /**
     * Return {@code true} if the device should vibrate for current ringer mode.
     *
     * <p>This checks the current {@link AudioManager#getRingerModeInternal()} against user settings
     * for ringtone and notification usages. All other usages are allowed by this method.
     */
    @GuardedBy("mLock")
    private boolean shouldVibrateForRingerModeLocked(@VibrationAttributes.Usage int usageHint) {
        if ((usageHint != USAGE_RINGTONE) && (usageHint != USAGE_NOTIFICATION)) {
            // Only ringtone and notification vibrations are disabled when phone is on silent mode.
            return true;
        }
        return mRingerMode != AudioManager.RINGER_MODE_SILENT;
    }

    /** Update all cached settings and triggers registered listeners. */
    void update() {
        updateSettings();
        updateRingerMode();
        notifyListeners();
    }

    private void updateSettings() {
        synchronized (mLock) {
            mVibrateInputDevices = loadSystemSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0) > 0;
            mVibrateOn = loadSystemSetting(Settings.System.VIBRATE_ON, 1) > 0;

            int alarmIntensity = toIntensity(
                    loadSystemSetting(Settings.System.ALARM_VIBRATION_INTENSITY, -1),
                    getDefaultIntensity(USAGE_ALARM));
            int defaultHapticFeedbackIntensity = getDefaultIntensity(USAGE_TOUCH);
            int hapticFeedbackIntensity = toIntensity(
                    loadSystemSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, -1),
                    defaultHapticFeedbackIntensity);
            int positiveHapticFeedbackIntensity = toPositiveIntensity(
                    hapticFeedbackIntensity, defaultHapticFeedbackIntensity);
            int hardwareFeedbackIntensity = toIntensity(
                    loadSystemSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, -1),
                    positiveHapticFeedbackIntensity);
            int mediaIntensity = toIntensity(
                    loadSystemSetting(Settings.System.MEDIA_VIBRATION_INTENSITY, -1),
                    getDefaultIntensity(USAGE_MEDIA));
            int defaultNotificationIntensity = getDefaultIntensity(USAGE_NOTIFICATION);
            int notificationIntensity = toIntensity(
                    loadSystemSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, -1),
                    defaultNotificationIntensity);
            int positiveNotificationIntensity = toPositiveIntensity(
                    notificationIntensity, defaultNotificationIntensity);
            int ringIntensity = toIntensity(
                    loadSystemSetting(Settings.System.RING_VIBRATION_INTENSITY, -1),
                    getDefaultIntensity(USAGE_RINGTONE));

            mCurrentVibrationIntensities.clear();
            mCurrentVibrationIntensities.put(USAGE_ALARM, alarmIntensity);
            mCurrentVibrationIntensities.put(USAGE_NOTIFICATION, notificationIntensity);
            mCurrentVibrationIntensities.put(USAGE_MEDIA, mediaIntensity);
            mCurrentVibrationIntensities.put(USAGE_UNKNOWN, mediaIntensity);
            mCurrentVibrationIntensities.put(USAGE_RINGTONE, ringIntensity);

            // Communication request is not disabled by the notification setting.
            mCurrentVibrationIntensities.put(USAGE_COMMUNICATION_REQUEST,
                    positiveNotificationIntensity);

            // This should adapt the behavior preceding the introduction of this new setting
            // key, which is to apply HAPTIC_FEEDBACK_INTENSITY, unless it's disabled.
            mCurrentVibrationIntensities.put(USAGE_HARDWARE_FEEDBACK, hardwareFeedbackIntensity);
            mCurrentVibrationIntensities.put(USAGE_PHYSICAL_EMULATION, hardwareFeedbackIntensity);

            if (!loadBooleanSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED)) {
                // Make sure deprecated boolean setting still disables touch vibrations.
                mCurrentVibrationIntensities.put(USAGE_TOUCH, Vibrator.VIBRATION_INTENSITY_OFF);
            } else {
                mCurrentVibrationIntensities.put(USAGE_TOUCH, hapticFeedbackIntensity);
            }

            // A11y is not disabled by any haptic feedback setting.
            mCurrentVibrationIntensities.put(USAGE_ACCESSIBILITY, positiveHapticFeedbackIntensity);
        }
    }

    private void updateRingerMode() {
        synchronized (mLock) {
            // If audio manager was not loaded yet then assume most restrictive mode.
            // This will be loaded again as soon as the audio manager is loaded in onSystemReady.
            mRingerMode = (mAudioManager == null)
                    ? AudioManager.RINGER_MODE_SILENT
                    : mAudioManager.getRingerModeInternal();
        }
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            StringBuilder vibrationIntensitiesString = new StringBuilder("{");
            for (int i = 0; i < mCurrentVibrationIntensities.size(); i++) {
                int usage = mCurrentVibrationIntensities.keyAt(i);
                int intensity = mCurrentVibrationIntensities.valueAt(i);
                vibrationIntensitiesString.append(VibrationAttributes.usageToString(usage))
                        .append("=(").append(intensityToString(intensity))
                        .append(",default:").append(intensityToString(getDefaultIntensity(usage)))
                        .append("), ");
            }
            vibrationIntensitiesString.append('}');
            return "VibrationSettings{"
                    + "mVibratorConfig=" + mVibrationConfig
                    + ", mVibrateInputDevices=" + mVibrateInputDevices
                    + ", mBatterySaverMode=" + mBatterySaverMode
                    + ", mVibrateOn=" + mVibrateOn
                    + ", mVibrationIntensities=" + vibrationIntensitiesString
                    + ", mProcStatesCache=" + mUidObserver.mProcStatesCache
                    + '}';
        }
    }

    /** Write current settings into given {@link ProtoOutputStream}. */
    public void dumpProto(ProtoOutputStream proto) {
        synchronized (mLock) {
            proto.write(VibratorManagerServiceDumpProto.VIBRATE_ON, mVibrateOn);
            proto.write(VibratorManagerServiceDumpProto.LOW_POWER_MODE, mBatterySaverMode);
            proto.write(VibratorManagerServiceDumpProto.ALARM_INTENSITY,
                    getCurrentIntensity(USAGE_ALARM));
            proto.write(VibratorManagerServiceDumpProto.ALARM_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_ALARM));
            proto.write(VibratorManagerServiceDumpProto.HARDWARE_FEEDBACK_INTENSITY,
                    getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
            proto.write(VibratorManagerServiceDumpProto.HARDWARE_FEEDBACK_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_HARDWARE_FEEDBACK));
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_INTENSITY,
                    getCurrentIntensity(USAGE_TOUCH));
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_TOUCH));
            proto.write(VibratorManagerServiceDumpProto.MEDIA_INTENSITY,
                    getCurrentIntensity(USAGE_MEDIA));
            proto.write(VibratorManagerServiceDumpProto.MEDIA_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_MEDIA));
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_INTENSITY,
                    getCurrentIntensity(USAGE_NOTIFICATION));
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_NOTIFICATION));
            proto.write(VibratorManagerServiceDumpProto.RING_INTENSITY,
                    getCurrentIntensity(USAGE_RINGTONE));
            proto.write(VibratorManagerServiceDumpProto.RING_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_RINGTONE));
        }
    }

    private void notifyListeners() {
        List<OnVibratorSettingsChanged> currentListeners;
        synchronized (mLock) {
            currentListeners = new ArrayList<>(mListeners);
        }
        for (OnVibratorSettingsChanged listener : currentListeners) {
            listener.onChange();
        }
    }

    private static String intensityToString(int intensity) {
        switch (intensity) {
            case Vibrator.VIBRATION_INTENSITY_OFF:
                return "OFF";
            case Vibrator.VIBRATION_INTENSITY_LOW:
                return "LOW";
            case Vibrator.VIBRATION_INTENSITY_MEDIUM:
                return "MEDIUM";
            case Vibrator.VIBRATION_INTENSITY_HIGH:
                return "HIGH";
            default:
                return "UNKNOWN INTENSITY " + intensity;
        }
    }

    @VibrationIntensity
    private int toPositiveIntensity(int value, @VibrationIntensity int defaultValue) {
        if (value == Vibrator.VIBRATION_INTENSITY_OFF) {
            return defaultValue;
        }
        return toIntensity(value, defaultValue);
    }

    @VibrationIntensity
    private int toIntensity(int value, @VibrationIntensity int defaultValue) {
        if ((value < Vibrator.VIBRATION_INTENSITY_OFF)
                || (value > Vibrator.VIBRATION_INTENSITY_HIGH)) {
            return defaultValue;
        }
        return value;
    }

    private boolean loadBooleanSetting(String settingKey) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                settingKey, 0, UserHandle.USER_CURRENT) != 0;
    }

    private int loadSystemSetting(String settingName, int defaultValue) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                settingName, defaultValue, UserHandle.USER_CURRENT);
    }

    private void registerSettingsObserver(Uri settingUri) {
        mContext.getContentResolver().registerContentObserver(
                settingUri, /* notifyForDescendants= */ true, mSettingObserver,
                UserHandle.USER_ALL);
    }

    private void registerSettingsChangeReceiver(IntentFilter intentFilter) {
        mContext.registerReceiver(mSettingChangeReceiver, intentFilter);
    }

    @Nullable
    private VibrationEffect createEffectFromResource(int resId) {
        long[] timings = getLongIntArray(mContext.getResources(), resId);
        return createEffectFromTimings(timings);
    }

    @Nullable
    private static VibrationEffect createEffectFromTimings(@Nullable long[] timings) {
        if (timings == null || timings.length == 0) {
            return null;
        } else if (timings.length == 1) {
            return VibrationEffect.createOneShot(timings[0], VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            return VibrationEffect.createWaveform(timings, -1);
        }
    }

    private static long[] getLongIntArray(Resources r, int resid) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return null;
        }
        long[] out = new long[ar.length];
        for (int i = 0; i < ar.length; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    /** Implementation of {@link ContentObserver} to be registered to a setting {@link Uri}. */
    @VisibleForTesting
    final class SettingsContentObserver extends ContentObserver {
        SettingsContentObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            notifyListeners();
        }
    }

    /**
     * Implementation of {@link BroadcastReceiver} to update settings on current user or ringer
     * mode change.
     */
    @VisibleForTesting
    final class SettingsBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                // Reload all settings, as they are user-based.
                update();
            } else if (AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                updateRingerMode();
                notifyListeners();
            }
        }
    }

    /** Implementation of {@link ContentObserver} to be registered to a setting {@link Uri}. */
    @VisibleForTesting
    final class UidObserver extends IUidObserver.Stub {
        private final SparseArray<Integer> mProcStatesCache = new SparseArray<>();

        public boolean isUidForeground(int uid) {
            return mProcStatesCache.get(uid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                    <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mProcStatesCache.delete(uid);
        }

        @Override
        public void onUidActive(int uid) {
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mProcStatesCache.put(uid, procState);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
        }

        @Override
        public void onUidProcAdjChanged(int uid) {
        }
    }
}
