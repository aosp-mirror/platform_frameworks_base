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

import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.SparseArray;
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

    /** Listener for changes on vibration settings. */
    interface OnVibratorSettingsChanged {
        /** Callback triggered when any of the vibrator settings change. */
        void onChange();
    }

    private final Object mLock = new Object();
    private final Context mContext;
    private final SettingsObserver mSettingObserver;
    @VisibleForTesting
    final UidObserver mUidObserver;
    @VisibleForTesting
    final UserObserver mUserReceiver;

    @GuardedBy("mLock")
    private final List<OnVibratorSettingsChanged> mListeners = new ArrayList<>();
    private final SparseArray<VibrationEffect> mFallbackEffects;

    private final int mRampStepDuration;
    private final int mRampDownDuration;

    @GuardedBy("mLock")
    @Nullable
    private Vibrator mVibrator;
    @GuardedBy("mLock")
    @Nullable
    private AudioManager mAudioManager;

    @GuardedBy("mLock")
    private boolean mVibrateInputDevices;
    @GuardedBy("mLock")
    private boolean mVibrateWhenRinging;
    @GuardedBy("mLock")
    private boolean mApplyRampingRinger;
    @GuardedBy("mLock")
    private int mHapticFeedbackIntensity;
    @GuardedBy("mLock")
    private int mHardwareFeedbackIntensity;
    @GuardedBy("mLock")
    private int mNotificationIntensity;
    @GuardedBy("mLock")
    private int mRingIntensity;
    @GuardedBy("mLock")
    private boolean mBatterySaverMode;

    VibrationSettings(Context context, Handler handler) {
        this(context, handler,
                context.getResources().getInteger(
                        com.android.internal.R.integer.config_vibrationWaveformRampDownDuration),
                context.getResources().getInteger(
                        com.android.internal.R.integer.config_vibrationWaveformRampStepDuration));
    }

    @VisibleForTesting
    VibrationSettings(Context context, Handler handler, int rampDownDuration,
            int rampStepDuration) {
        mContext = context;
        mSettingObserver = new SettingsObserver(handler);
        mUidObserver = new UidObserver();
        mUserReceiver = new UserObserver();

        // TODO(b/191150049): move these to vibrator static config file
        mRampDownDuration = rampDownDuration;
        mRampStepDuration = rampStepDuration;

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
        updateSettings();
    }

    public void onSystemReady() {
        synchronized (mLock) {
            mVibrator = mContext.getSystemService(Vibrator.class);
            mAudioManager = mContext.getSystemService(AudioManager.class);
        }
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

        PowerManagerInternal pm = LocalServices.getService(PowerManagerInternal.class);
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

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mUserReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_INPUT_DEVICES));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.APPLY_RAMPING_RINGER));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.NOTIFICATION_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.RING_VIBRATION_INTENSITY));

        // Update with newly loaded services.
        updateSettings();
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
        return mRampStepDuration;
    }

    /**
     * The duration, in milliseconds, that should be applied to the ramp to turn off the vibrator
     * when a vibration is cancelled or finished at non-zero amplitude.
     */
    public int getRampDownDuration() {
        return mRampDownDuration;
    }

    /**
     * Return default vibration intensity for given usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The vibration intensity, one of Vibrator.VIBRATION_INTENSITY_*
     */
    public int getDefaultIntensity(int usageHint) {
        if (usageHint == USAGE_ALARM) {
            return Vibrator.VIBRATION_INTENSITY_HIGH;
        }
        synchronized (mLock) {
            if (mVibrator != null) {
                switch (usageHint) {
                    case USAGE_RINGTONE:
                        return mVibrator.getDefaultRingVibrationIntensity();
                    case USAGE_NOTIFICATION:
                        return mVibrator.getDefaultNotificationVibrationIntensity();
                    case USAGE_TOUCH:
                    case USAGE_HARDWARE_FEEDBACK:
                    case USAGE_PHYSICAL_EMULATION:
                        return mVibrator.getDefaultHapticFeedbackIntensity();
                }
            }
        }
        return Vibrator.VIBRATION_INTENSITY_MEDIUM;
    }

    /**
     * Return the current vibration intensity set for given usage at the user settings.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The vibration intensity, one of Vibrator.VIBRATION_INTENSITY_*
     */
    public int getCurrentIntensity(int usageHint) {
        synchronized (mLock) {
            switch (usageHint) {
                case USAGE_RINGTONE:
                    return mRingIntensity;
                case USAGE_NOTIFICATION:
                    return mNotificationIntensity;
                case USAGE_TOUCH:
                    return mHapticFeedbackIntensity;
                case USAGE_HARDWARE_FEEDBACK:
                case USAGE_PHYSICAL_EMULATION:
                    return mHardwareFeedbackIntensity;
                case USAGE_ALARM:
                    return Vibrator.VIBRATION_INTENSITY_HIGH;
                default:
                    return Vibrator.VIBRATION_INTENSITY_MEDIUM;
            }
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

            int intensity = getCurrentIntensity(usage);
            if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
                return Vibration.Status.IGNORED_FOR_SETTINGS;
            }

            if (!shouldVibrateForRingerModeLocked(usage)) {
                return Vibration.Status.IGNORED_FOR_RINGER_MODE;
            }
        }
        return null;
    }

    /**
     * Return {@code true} if the device should vibrate for current ringer mode.
     *
     * <p>This checks the current {@link AudioManager#getRingerModeInternal()} against user settings
     * for touch and ringtone usages only. All other usages are allowed by this method.
     */
    @GuardedBy("mLock")
    private boolean shouldVibrateForRingerModeLocked(int usageHint) {
        // If audio manager was not loaded yet then assume most restrictive mode.
        int ringerMode = (mAudioManager == null)
                ? AudioManager.RINGER_MODE_SILENT
                : mAudioManager.getRingerModeInternal();

        switch (usageHint) {
            case USAGE_TOUCH:
                // Touch feedback disabled when phone is on silent mode.
                return ringerMode != AudioManager.RINGER_MODE_SILENT;
            case USAGE_RINGTONE:
                switch (ringerMode) {
                    case AudioManager.RINGER_MODE_SILENT:
                        return false;
                    case AudioManager.RINGER_MODE_VIBRATE:
                        return true;
                    default:
                        // Ringtone vibrations also depend on 2 other settings:
                        return mVibrateWhenRinging || mApplyRampingRinger;
                }
            default:
                // All other usages ignore ringer mode settings.
                return true;
        }
    }

    /** Updates all vibration settings and triggers registered listeners. */
    @VisibleForTesting
    void updateSettings() {
        synchronized (mLock) {
            mVibrateWhenRinging = getSystemSetting(Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
            mApplyRampingRinger = getSystemSetting(Settings.System.APPLY_RAMPING_RINGER, 0) != 0;
            mHapticFeedbackIntensity = getSystemSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                    getDefaultIntensity(USAGE_TOUCH));
            mHardwareFeedbackIntensity = getSystemSetting(
                    Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY,
                    getHardwareFeedbackIntensityWhenSettingIsMissing(mHapticFeedbackIntensity));
            mNotificationIntensity = getSystemSetting(
                    Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                    getDefaultIntensity(USAGE_NOTIFICATION));
            mRingIntensity = getSystemSetting(Settings.System.RING_VIBRATION_INTENSITY,
                    getDefaultIntensity(USAGE_RINGTONE));
            mVibrateInputDevices = getSystemSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0) > 0;
        }
        notifyListeners();
    }

    /**
     * Return the value to be used for {@link Settings.System#HARDWARE_HAPTIC_FEEDBACK_INTENSITY}
     * when the value was not set by the user.
     *
     * <p>This should adapt the behavior preceding the introduction of this new setting key, which
     * is to apply {@link Settings.System#HAPTIC_FEEDBACK_INTENSITY} unless it's disabled.
     */
    private int getHardwareFeedbackIntensityWhenSettingIsMissing(int hapticFeedbackIntensity) {
        if (hapticFeedbackIntensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            return getDefaultIntensity(USAGE_HARDWARE_FEEDBACK);
        }
        return hapticFeedbackIntensity;
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            return "VibrationSettings{"
                    + "mVibrateInputDevices=" + mVibrateInputDevices
                    + ", mVibrateWhenRinging=" + mVibrateWhenRinging
                    + ", mApplyRampingRinger=" + mApplyRampingRinger
                    + ", mBatterySaverMode=" + mBatterySaverMode
                    + ", mProcStatesCache=" + mUidObserver.mProcStatesCache
                    + ", mHapticChannelMaxVibrationAmplitude="
                    + getHapticChannelMaxVibrationAmplitude()
                    + ", mRampStepDuration=" + mRampStepDuration
                    + ", mRampDownDuration=" + mRampDownDuration
                    + ", mHardwareHapticFeedbackIntensity="
                    + intensityToString(getCurrentIntensity(USAGE_HARDWARE_FEEDBACK))
                    + ", mHapticFeedbackIntensity="
                    + intensityToString(getCurrentIntensity(USAGE_TOUCH))
                    + ", mHapticFeedbackDefaultIntensity="
                    + intensityToString(getDefaultIntensity(USAGE_TOUCH))
                    + ", mNotificationIntensity="
                    + intensityToString(getCurrentIntensity(USAGE_NOTIFICATION))
                    + ", mNotificationDefaultIntensity="
                    + intensityToString(getDefaultIntensity(USAGE_NOTIFICATION))
                    + ", mRingIntensity="
                    + intensityToString(getCurrentIntensity(USAGE_RINGTONE))
                    + ", mRingDefaultIntensity="
                    + intensityToString(getDefaultIntensity(USAGE_RINGTONE))
                    + '}';
        }
    }

    /** Write current settings into given {@link ProtoOutputStream}. */
    public void dumpProto(ProtoOutputStream proto) {
        synchronized (mLock) {
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_INTENSITY,
                    mHapticFeedbackIntensity);
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_TOUCH));
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_INTENSITY,
                    mNotificationIntensity);
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_NOTIFICATION));
            proto.write(VibratorManagerServiceDumpProto.RING_INTENSITY,
                    mRingIntensity);
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

    private float getHapticChannelMaxVibrationAmplitude() {
        synchronized (mLock) {
            return mVibrator == null ? Float.NaN : mVibrator.getHapticChannelMaximumAmplitude();
        }
    }

    private int getSystemSetting(String settingName, int defaultValue) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                settingName, defaultValue, UserHandle.USER_CURRENT);
    }

    private void registerSettingsObserver(Uri settingUri) {
        mContext.getContentResolver().registerContentObserver(
                settingUri, /* notifyForDescendants= */ true, mSettingObserver,
                UserHandle.USER_ALL);
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
    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /** Implementation of {@link BroadcastReceiver} to update settings on current user change. */
    @VisibleForTesting
    final class UserObserver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                updateSettings();
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
    }
}
