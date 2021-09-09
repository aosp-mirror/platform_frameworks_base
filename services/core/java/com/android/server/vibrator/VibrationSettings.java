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
import java.util.List;

/** Controls all the system settings related to vibration. */
final class VibrationSettings {
    private static final String TAG = "VibrationSettings";

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
    private int mZenMode;
    @GuardedBy("mLock")
    private int mHapticFeedbackIntensity;
    @GuardedBy("mLock")
    private int mNotificationIntensity;
    @GuardedBy("mLock")
    private int mRingIntensity;
    @GuardedBy("mLock")
    private boolean mLowPowerMode;

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
                            shouldNotifyListeners = result.batterySaverEnabled != mLowPowerMode;
                            mLowPowerMode = result.batterySaverEnabled;
                        }
                        if (shouldNotifyListeners) {
                            notifyListeners();
                        }
                    }
                });

        mContext.registerReceiver(mUserReceiver, new IntentFilter(Intent.ACTION_USER_SWITCHED));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_INPUT_DEVICES));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING));
        registerSettingsObserver(Settings.Global.getUriFor(Settings.Global.APPLY_RAMPING_RINGER));
        registerSettingsObserver(Settings.Global.getUriFor(Settings.Global.ZEN_MODE));
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
        if (isAlarm(usageHint)) {
            return Vibrator.VIBRATION_INTENSITY_HIGH;
        }
        synchronized (mLock) {
            if (mVibrator != null) {
                if (isRingtone(usageHint)) {
                    return mVibrator.getDefaultRingVibrationIntensity();
                } else if (isNotification(usageHint)) {
                    return mVibrator.getDefaultNotificationVibrationIntensity();
                } else if (isHapticFeedback(usageHint)) {
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
            if (isRingtone(usageHint)) {
                return mRingIntensity;
            } else if (isNotification(usageHint)) {
                return mNotificationIntensity;
            } else if (isHapticFeedback(usageHint)) {
                return mHapticFeedbackIntensity;
            } else if (isAlarm(usageHint)) {
                return Vibrator.VIBRATION_INTENSITY_HIGH;
            } else {
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

    /**
     * Return {@code true} if the device should vibrate for current ringer mode.
     *
     * <p>This checks the current {@link AudioManager#getRingerModeInternal()} against user settings
     * for ringtone usage only. All other usages are allowed independently of ringer mode.
     */
    public boolean shouldVibrateForRingerMode(int usageHint) {
        if (!isRingtone(usageHint)) {
            return true;
        }
        synchronized (mLock) {
            if (mAudioManager == null) {
                return false;
            }
            int ringerMode = mAudioManager.getRingerModeInternal();
            if (mVibrateWhenRinging) {
                return ringerMode != AudioManager.RINGER_MODE_SILENT;
            } else if (mApplyRampingRinger) {
                return ringerMode != AudioManager.RINGER_MODE_SILENT;
            } else {
                return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
            }
        }
    }

    /**
     * Returns {@code true} if this vibration is allowed for given {@code uid}.
     *
     * <p>This checks if the user is aware of this foreground process, or if the vibration usage is
     * allowed to play in the background (i.e. it's a notification, ringtone or alarm vibration).
     */
    public boolean shouldVibrateForUid(int uid, int usageHint) {
        return mUidObserver.isUidForeground(uid) || isClassAlarm(usageHint);
    }

    /**
     * Returns {@code true} if this vibration is allowed for current power mode state.
     *
     * <p>This checks if the device is in battery saver mode, in which case only alarm, ringtone and
     * {@link VibrationAttributes#USAGE_COMMUNICATION_REQUEST} usages are allowed to vibrate.
     */
    public boolean shouldVibrateForPowerMode(int usageHint) {
        return !mLowPowerMode || isRingtone(usageHint) || isAlarm(usageHint)
                || usageHint == VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
    }

    /** Return {@code true} if input devices should vibrate instead of this device. */
    public boolean shouldVibrateInputDevices() {
        return mVibrateInputDevices;
    }

    /** Return {@code true} if setting for {@link Settings.Global#ZEN_MODE} is not OFF. */
    public boolean isInZenMode() {
        return mZenMode != Settings.Global.ZEN_MODE_OFF;
    }

    private static boolean isNotification(int usageHint) {
        return usageHint == VibrationAttributes.USAGE_NOTIFICATION;
    }

    private static boolean isRingtone(int usageHint) {
        return usageHint == VibrationAttributes.USAGE_RINGTONE;
    }

    private static boolean isHapticFeedback(int usageHint) {
        return usageHint == VibrationAttributes.USAGE_TOUCH;
    }

    private static boolean isAlarm(int usageHint) {
        return usageHint == VibrationAttributes.USAGE_ALARM;
    }

    private static boolean isClassAlarm(int usageHint) {
        return (usageHint & VibrationAttributes.USAGE_CLASS_MASK)
                == VibrationAttributes.USAGE_CLASS_ALARM;
    }

    /** Updates all vibration settings and triggers registered listeners. */
    public void updateSettings() {
        synchronized (mLock) {
            mVibrateWhenRinging = getSystemSetting(Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
            mApplyRampingRinger = getGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0) != 0;
            mHapticFeedbackIntensity = getSystemSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                    getDefaultIntensity(VibrationAttributes.USAGE_TOUCH));
            mNotificationIntensity = getSystemSetting(
                    Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                    getDefaultIntensity(VibrationAttributes.USAGE_NOTIFICATION));
            mRingIntensity = getSystemSetting(Settings.System.RING_VIBRATION_INTENSITY,
                    getDefaultIntensity(VibrationAttributes.USAGE_RINGTONE));
            mVibrateInputDevices = getSystemSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0) > 0;
            mZenMode = getGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
        }
        notifyListeners();
    }

    @Override
    public String toString() {
        return "VibrationSettings{"
                + "mVibrateInputDevices=" + mVibrateInputDevices
                + ", mVibrateWhenRinging=" + mVibrateWhenRinging
                + ", mApplyRampingRinger=" + mApplyRampingRinger
                + ", mLowPowerMode=" + mLowPowerMode
                + ", mZenMode=" + Settings.Global.zenModeToString(mZenMode)
                + ", mProcStatesCache=" + mUidObserver.mProcStatesCache
                + ", mHapticChannelMaxVibrationAmplitude=" + getHapticChannelMaxVibrationAmplitude()
                + ", mRampStepDuration=" + mRampStepDuration
                + ", mRampDownDuration=" + mRampDownDuration
                + ", mHapticFeedbackIntensity="
                + intensityToString(getCurrentIntensity(VibrationAttributes.USAGE_TOUCH))
                + ", mHapticFeedbackDefaultIntensity="
                + intensityToString(getDefaultIntensity(VibrationAttributes.USAGE_TOUCH))
                + ", mNotificationIntensity="
                + intensityToString(getCurrentIntensity(VibrationAttributes.USAGE_NOTIFICATION))
                + ", mNotificationDefaultIntensity="
                + intensityToString(getDefaultIntensity(VibrationAttributes.USAGE_NOTIFICATION))
                + ", mRingIntensity="
                + intensityToString(getCurrentIntensity(VibrationAttributes.USAGE_RINGTONE))
                + ", mRingDefaultIntensity="
                + intensityToString(getDefaultIntensity(VibrationAttributes.USAGE_RINGTONE))
                + '}';
    }

    /** Write current settings into given {@link ProtoOutputStream}. */
    public void dumpProto(ProtoOutputStream proto) {
        synchronized (mLock) {
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_INTENSITY,
                    mHapticFeedbackIntensity);
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_DEFAULT_INTENSITY,
                    getDefaultIntensity(VibrationAttributes.USAGE_TOUCH));
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_INTENSITY,
                    mNotificationIntensity);
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_DEFAULT_INTENSITY,
                    getDefaultIntensity(VibrationAttributes.USAGE_NOTIFICATION));
            proto.write(VibratorManagerServiceDumpProto.RING_INTENSITY,
                    mRingIntensity);
            proto.write(VibratorManagerServiceDumpProto.RING_DEFAULT_INTENSITY,
                    getDefaultIntensity(VibrationAttributes.USAGE_RINGTONE));
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

    private int getGlobalSetting(String settingName, int defaultValue) {
        return Settings.Global.getInt(mContext.getContentResolver(), settingName, defaultValue);
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
