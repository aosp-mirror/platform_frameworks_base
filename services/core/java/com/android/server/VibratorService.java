/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.icu.text.DateFormat;
import android.media.AudioManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.IVibratorService;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.IBinder;
import android.os.Binder;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.DebugUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;
import android.media.AudioAttributes;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Date;

public class VibratorService extends IVibratorService.Stub
        implements InputManager.InputDeviceListener {
    private static final String TAG = "VibratorService";
    private static final boolean DEBUG = false;
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private static final long[] DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS = { 0, 30, 100, 30 };

    private static final float GAMMA_SCALE_FACTOR_MINIMUM = 2.0f;
    private static final float GAMMA_SCALE_FACTOR_LOW = 1.5f;
    private static final float GAMMA_SCALE_FACTOR_HIGH = 0.5f;
    private static final float GAMMA_SCALE_FACTOR_NONE = 1.0f;

    private static final int MAX_AMPLITUDE_MINIMUM_INTENSITY = 168; // 2/3 * 255
    private static final int MAX_AMPLITUDE_LOW_INTENSITY = 192; // 3/4 * 255

    // If a vibration is playing for longer than 5s, it's probably not haptic feedback.
    private static final long MAX_HAPTIC_FEEDBACK_DURATION = 5000;

    private final LinkedList<VibrationInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private final boolean mAllowPriorityVibrationsInLowPowerMode;
    private final boolean mSupportsAmplitudeControl;
    private final int mDefaultVibrationAmplitude;
    private final SparseArray<VibrationEffect> mFallbackEffects;
    private final WorkSource mTmpWorkSource = new WorkSource();
    private final Handler mH = new Handler();
    private final Object mLock = new Object();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStatsService;
    private PowerManagerInternal mPowerManagerInternal;
    private InputManager mIm;
    private Vibrator mVibrator;
    private SettingsObserver mSettingObserver;

    private volatile VibrateThread mThread;

    // mInputDeviceVibrators lock should be acquired after mLock, if both are
    // to be acquired
    private final ArrayList<Vibrator> mInputDeviceVibrators = new ArrayList<Vibrator>();
    private boolean mVibrateInputDevicesSetting; // guarded by mInputDeviceVibrators
    private boolean mInputDeviceListenerRegistered; // guarded by mInputDeviceVibrators

    @GuardedBy("mLock")
    private Vibration mCurrentVibration;
    private int mCurVibUid = -1;
    private boolean mLowPowerMode;
    private int mHapticFeedbackIntensity;
    private int mNotificationIntensity;

    native static boolean vibratorExists();
    native static void vibratorInit();
    native static void vibratorOn(long milliseconds);
    native static void vibratorOff();
    native static boolean vibratorSupportsAmplitudeControl();
    native static void vibratorSetAmplitude(int amplitude);
    native static long vibratorPerformEffect(long effect, long strength);

    private class Vibration implements IBinder.DeathRecipient {
        public final IBinder token;
        // Start time in CLOCK_BOOTTIME base.
        public final long startTime;
        // Start time in unix epoch time. Only to be used for debugging purposes and to correlate
        // with other system events, any duration calculations should be done use startTime so as
        // not to be affected by discontinuities created by RTC adjustments.
        public final long startTimeDebug;
        public final int usageHint;
        public final int uid;
        public final String opPkg;

        // The actual effect to be played.
        public VibrationEffect effect;
        // The original effect that was requested. This is non-null only when the original effect
        // differs from the effect that's being played. Typically these two things differ because
        // the effect was scaled based on the users vibration intensity settings.
        public VibrationEffect originalEffect;

        private Vibration(IBinder token, VibrationEffect effect,
                int usageHint, int uid, String opPkg) {
            this.token = token;
            this.effect = effect;
            this.startTime = SystemClock.elapsedRealtime();
            this.startTimeDebug = System.currentTimeMillis();
            this.usageHint = usageHint;
            this.uid = uid;
            this.opPkg = opPkg;
        }

        public void binderDied() {
            synchronized (mLock) {
                if (this == mCurrentVibration) {
                    doCancelVibrateLocked();
                }
            }
        }

        public boolean hasTimeoutLongerThan(long millis) {
            final long duration = effect.getDuration();
            return duration >= 0 && duration > millis;
        }

        public boolean isHapticFeedback() {
            if (effect instanceof VibrationEffect.Prebaked) {
                VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
                switch (prebaked.getId()) {
                    case VibrationEffect.EFFECT_CLICK:
                    case VibrationEffect.EFFECT_DOUBLE_CLICK:
                    case VibrationEffect.EFFECT_HEAVY_CLICK:
                    case VibrationEffect.EFFECT_TICK:
                        return true;
                    default:
                        Slog.w(TAG, "Unknown prebaked vibration effect, "
                                + "assuming it isn't haptic feedback.");
                        return false;
                }
            }
            final long duration = effect.getDuration();
            return duration >= 0 && duration < MAX_HAPTIC_FEEDBACK_DURATION;
        }

        public boolean isNotification() {
            switch (usageHint) {
                case AudioAttributes.USAGE_NOTIFICATION:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isRingtone() {
            return usageHint == AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
        }

        public boolean isFromSystem() {
            return uid == Process.SYSTEM_UID || uid == 0 || SYSTEM_UI_PACKAGE.equals(opPkg);
        }

        public VibrationInfo toInfo() {
            return new VibrationInfo(
                    startTimeDebug, effect, originalEffect, usageHint, uid, opPkg);
        }
    }

    private static class VibrationInfo {
        private final long mStartTimeDebug;
        private final VibrationEffect mEffect;
        private final VibrationEffect mOriginalEffect;
        private final int mUsageHint;
        private final int mUid;
        private final String mOpPkg;

        public VibrationInfo(long startTimeDebug, VibrationEffect effect,
                VibrationEffect originalEffect, int usageHint, int uid, String opPkg) {
            mStartTimeDebug = startTimeDebug;
            mEffect = effect;
            mOriginalEffect = originalEffect;
            mUsageHint = usageHint;
            mUid = uid;
            mOpPkg = opPkg;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("startTime: ")
                    .append(DateFormat.getDateTimeInstance().format(new Date(mStartTimeDebug)))
                    .append(", effect: ")
                    .append(mEffect)
                    .append(", originalEffect: ")
                    .append(mOriginalEffect)
                    .append(", usageHint: ")
                    .append(mUsageHint)
                    .append(", uid: ")
                    .append(mUid)
                    .append(", opPkg: ")
                    .append(mOpPkg)
                    .toString();
        }
    }

    VibratorService(Context context) {
        vibratorInit();
        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        vibratorOff();

        mSupportsAmplitudeControl = vibratorSupportsAmplitudeControl();

        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);

        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mBatteryStatsService = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        mPreviousVibrationsLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpLimit);

        mDefaultVibrationAmplitude = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultVibrationAmplitude);

        mAllowPriorityVibrationsInLowPowerMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowPriorityVibrationsInLowPowerMode);

        mPreviousVibrations = new LinkedList<>();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);

        VibrationEffect clickEffect = createEffectFromResource(
                com.android.internal.R.array.config_virtualKeyVibePattern);
        VibrationEffect doubleClickEffect = VibrationEffect.createWaveform(
                DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS, -1 /*repeatIndex*/);
        VibrationEffect heavyClickEffect = createEffectFromResource(
                com.android.internal.R.array.config_longPressVibePattern);
        VibrationEffect tickEffect = createEffectFromResource(
                com.android.internal.R.array.config_clockTickVibePattern);

        mFallbackEffects = new SparseArray<VibrationEffect>();
        mFallbackEffects.put(VibrationEffect.EFFECT_CLICK, clickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_DOUBLE_CLICK, doubleClickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_TICK, tickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_HEAVY_CLICK, heavyClickEffect);
    }

    private VibrationEffect createEffectFromResource(int resId) {
        long[] timings = getLongIntArray(mContext.getResources(), resId);
        return createEffectFromTimings(timings);
    }

    private static VibrationEffect createEffectFromTimings(long[] timings) {
        if (timings == null || timings.length == 0) {
            return null;
        } else if (timings.length == 1) {
            return VibrationEffect.createOneShot(timings[0], VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            return VibrationEffect.createWaveform(timings, -1);
        }
    }

    public void systemReady() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "VibratorService#systemReady");
        try {
            mIm = mContext.getSystemService(InputManager.class);
            mVibrator = mContext.getSystemService(Vibrator.class);
            mSettingObserver = new SettingsObserver(mH);

            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            mPowerManagerInternal.registerLowPowerModeObserver(
                    new PowerManagerInternal.LowPowerModeListener() {
                        @Override
                        public int getServiceType() {
                            return ServiceType.VIBRATION;
                        }

                        @Override
                        public void onLowPowerModeChanged(PowerSaveState result) {
                            updateVibrators();
                        }
            });

            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.VIBRATE_INPUT_DEVICES),
                    true, mSettingObserver, UserHandle.USER_ALL);

            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_INTENSITY),
                    true, mSettingObserver, UserHandle.USER_ALL);

            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIFICATION_VIBRATION_INTENSITY),
                    true, mSettingObserver, UserHandle.USER_ALL);

            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateVibrators();
                }
            }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mH);

            updateVibrators();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean SelfChange) {
            updateVibrators();
        }
    }

    @Override // Binder call
    public boolean hasVibrator() {
        return doVibratorExists();
    }

    @Override // Binder call
    public boolean hasAmplitudeControl() {
        synchronized (mInputDeviceVibrators) {
            // Input device vibrators don't support amplitude controls yet, but are still used over
            // the system vibrator when connected.
            return mSupportsAmplitudeControl && mInputDeviceVibrators.isEmpty();
        }
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    /**
     * Validate the incoming VibrationEffect.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the VibrationEffect is valid
     */
    private static boolean verifyVibrationEffect(VibrationEffect effect) {
        if (effect == null) {
            // Effect must not be null.
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying VibrationEffect.", e);
            return false;
        }
        return true;
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

    @Override // Binder call
    public void vibrate(int uid, String opPkg, VibrationEffect effect, int usageHint,
            IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "vibrate");
        try {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires VIBRATE permission");
            }
            if (token == null) {
                Slog.e(TAG, "token must not be null");
                return;
            }
            verifyIncomingUid(uid);
            if (!verifyVibrationEffect(effect)) {
                return;
            }

            // If our current vibration is longer than the new vibration and is the same amplitude,
            // then just let the current one finish.
            synchronized (mLock) {
                if (effect instanceof VibrationEffect.OneShot
                        && mCurrentVibration != null
                        && mCurrentVibration.effect instanceof VibrationEffect.OneShot) {
                    VibrationEffect.OneShot newOneShot = (VibrationEffect.OneShot) effect;
                    VibrationEffect.OneShot currentOneShot =
                            (VibrationEffect.OneShot) mCurrentVibration.effect;
                    if (mCurrentVibration.hasTimeoutLongerThan(newOneShot.getDuration())
                            && newOneShot.getAmplitude() == currentOneShot.getAmplitude()) {
                        if (DEBUG) {
                            Slog.d(TAG,
                                    "Ignoring incoming vibration in favor of current vibration");
                        }
                        return;
                    }
                }

                // If the current vibration is repeating and the incoming one is non-repeating,
                // then ignore the non-repeating vibration. This is so that we don't cancel
                // vibrations that are meant to grab the attention of the user, like ringtones and
                // alarms, in favor of one-shot vibrations that are likely quite short.
                if (!isRepeatingVibration(effect)
                        && mCurrentVibration != null
                        && isRepeatingVibration(mCurrentVibration.effect)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring incoming vibration in favor of alarm vibration");
                    }
                    return;
                }

                Vibration vib = new Vibration(token, effect, usageHint, uid, opPkg);
                linkVibration(vib);
                long ident = Binder.clearCallingIdentity();
                try {
                    doCancelVibrateLocked();
                    startVibrationLocked(vib);
                    addToPreviousVibrationsLocked(vib);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private static boolean isRepeatingVibration(VibrationEffect effect) {
        return effect.getDuration() == Long.MAX_VALUE;
    }

    private void addToPreviousVibrationsLocked(Vibration vib) {
        if (mPreviousVibrations.size() > mPreviousVibrationsLimit) {
            mPreviousVibrations.removeFirst();
        }
        mPreviousVibrations.addLast(vib.toInfo());
    }

    @Override // Binder call
    public void cancelVibrate(IBinder token) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.VIBRATE,
                "cancelVibrate");

        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.token == token) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration.");
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    doCancelVibrateLocked();
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    private final Runnable mVibrationEndRunnable = new Runnable() {
        @Override
        public void run() {
            onVibrationFinished();
        }
    };

    @GuardedBy("mLock")
    private void doCancelVibrateLocked() {
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doCancelVibrateLocked");
        try {
            mH.removeCallbacks(mVibrationEndRunnable);
            if (mThread != null) {
                mThread.cancel();
                mThread = null;
            }
            doVibratorOff();
            reportFinishVibrationLocked();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    // Callback for whenever the current vibration has finished played out
    public void onVibrationFinished() {
        if (DEBUG) {
            Slog.e(TAG, "Vibration finished, cleaning up");
        }
        synchronized (mLock) {
            // Make sure the vibration is really done. This also reports that the vibration is
            // finished.
            doCancelVibrateLocked();
        }
    }

    @GuardedBy("mLock")
    private void startVibrationLocked(final Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationLocked");
        try {
            if (!isAllowedToVibrateLocked(vib)) {
                return;
            }

            final int intensity = getCurrentIntensityLocked(vib);
            if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
                return;
            }

            if (vib.isRingtone() && !shouldVibrateForRingtone()) {
                if (DEBUG) {
                    Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
                }
                return;
            }

            final int mode = getAppOpMode(vib);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                if (mode == AppOpsManager.MODE_ERRORED) {
                    // We might be getting calls from within system_server, so we don't actually
                    // want to throw a SecurityException here.
                    Slog.w(TAG, "Would be an error: vibrate from uid " + vib.uid);
                }
                return;
            }
            applyVibrationIntensityScalingLocked(vib, intensity);
            startVibrationInnerLocked(vib);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void startVibrationInnerLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationInnerLocked");
        try {
            mCurrentVibration = vib;
            if (vib.effect instanceof VibrationEffect.OneShot) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) vib.effect;
                doVibratorOn(oneShot.getDuration(), oneShot.getAmplitude(), vib.uid, vib.usageHint);
                mH.postDelayed(mVibrationEndRunnable, oneShot.getDuration());
            } else if (vib.effect instanceof VibrationEffect.Waveform) {
                // mThread better be null here. doCancelVibrate should always be
                // called before startNextVibrationLocked or startVibrationLocked.
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) vib.effect;
                mThread = new VibrateThread(waveform, vib.uid, vib.usageHint);
                mThread.start();
            } else if (vib.effect instanceof VibrationEffect.Prebaked) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                long timeout = doVibratorPrebakedEffectLocked(vib);
                if (timeout > 0) {
                    mH.postDelayed(mVibrationEndRunnable, timeout);
                }
            } else {
                Slog.e(TAG, "Unknown vibration type, ignoring");
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private boolean isAllowedToVibrateLocked(Vibration vib) {
        if (!mLowPowerMode) {
            return true;
        }

        if (vib.usageHint == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
            return true;
        }

        if (vib.usageHint == AudioAttributes.USAGE_ALARM ||
                vib.usageHint == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY ||
                vib.usageHint == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST) {
            return true;
        }

        return false;
    }

    private int getCurrentIntensityLocked(Vibration vib) {
        if (vib.isNotification() || vib.isRingtone()){
            return mNotificationIntensity;
        } else if (vib.isHapticFeedback()) {
            return mHapticFeedbackIntensity;
        } else {
            return Vibrator.VIBRATION_INTENSITY_MEDIUM;
        }
    }

    /**
     * Scale the vibration effect by the intensity as appropriate based its intent.
     */
    private void applyVibrationIntensityScalingLocked(Vibration vib, int intensity) {
        if (vib.effect instanceof VibrationEffect.Prebaked) {
            // Prebaked effects are always just a direct translation from intensity to
            // EffectStrength.
            VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked)vib.effect;
            prebaked.setEffectStrength(intensityToEffectStrength(intensity));
            return;
        }

        final float gamma;
        final int maxAmplitude;
        if (vib.isNotification() || vib.isRingtone()) {
            if (intensity == Vibrator.VIBRATION_INTENSITY_LOW) {
                gamma = GAMMA_SCALE_FACTOR_MINIMUM;
                maxAmplitude = MAX_AMPLITUDE_MINIMUM_INTENSITY;
            } else if (intensity == Vibrator.VIBRATION_INTENSITY_MEDIUM) {
                gamma = GAMMA_SCALE_FACTOR_LOW;
                maxAmplitude = MAX_AMPLITUDE_LOW_INTENSITY;
            } else {
                gamma = GAMMA_SCALE_FACTOR_NONE;
                maxAmplitude = VibrationEffect.MAX_AMPLITUDE;
            }
        } else {
            if (intensity == Vibrator.VIBRATION_INTENSITY_LOW) {
                gamma = GAMMA_SCALE_FACTOR_LOW;
                maxAmplitude = MAX_AMPLITUDE_LOW_INTENSITY;
            } else if (intensity == Vibrator.VIBRATION_INTENSITY_HIGH) {
                gamma = GAMMA_SCALE_FACTOR_HIGH;
                maxAmplitude = VibrationEffect.MAX_AMPLITUDE;
            } else {
                gamma = GAMMA_SCALE_FACTOR_NONE;
                maxAmplitude = VibrationEffect.MAX_AMPLITUDE;
            }
        }

        VibrationEffect scaledEffect = null;
        if (vib.effect instanceof VibrationEffect.OneShot) {
            VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) vib.effect;
            oneShot = oneShot.resolve(mDefaultVibrationAmplitude);
            scaledEffect = oneShot.scale(gamma, maxAmplitude);
        } else if (vib.effect instanceof VibrationEffect.Waveform) {
            VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) vib.effect;
            waveform = waveform.resolve(mDefaultVibrationAmplitude);
            scaledEffect = waveform.scale(gamma, maxAmplitude);
        } else {
            Slog.w(TAG, "Unable to apply intensity scaling, unknown VibrationEffect type");
        }

        if (scaledEffect != null) {
            vib.originalEffect = vib.effect;
            vib.effect = scaledEffect;
        }
    }

    private boolean shouldVibrateForRingtone() {
        AudioManager audioManager = mContext.getSystemService(AudioManager.class);
        int ringerMode = audioManager.getRingerModeInternal();
        // "Also vibrate for calls" Setting in Sound
        if (Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.VIBRATE_WHEN_RINGING, 0) != 0) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    private int getAppOpMode(Vibration vib) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                vib.usageHint, vib.uid, vib.opPkg);
        if (mode == AppOpsManager.MODE_ALLOWED) {
            mode = mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, vib.uid, vib.opPkg);
        }
        return mode;
    }

    @GuardedBy("mLock")
    private void reportFinishVibrationLocked() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "reportFinishVibrationLocked");
        try {
            if (mCurrentVibration != null) {
                mAppOps.finishOp(AppOpsManager.OP_VIBRATE, mCurrentVibration.uid,
                        mCurrentVibration.opPkg);
                unlinkVibration(mCurrentVibration);
                mCurrentVibration = null;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void linkVibration(Vibration vib) {
        // Only link against waveforms since they potentially don't have a finish if
        // they're repeating. Let other effects just play out until they're done.
        if (vib.effect instanceof VibrationEffect.Waveform) {
            try {
                vib.token.linkToDeath(vib, 0);
            } catch (RemoteException e) {
                return;
            }
        }
    }

    private void unlinkVibration(Vibration vib) {
        if (vib.effect instanceof VibrationEffect.Waveform) {
            vib.token.unlinkToDeath(vib, 0);
        }
    }

    private void updateVibrators() {
        synchronized (mLock) {
            boolean devicesUpdated = updateInputDeviceVibratorsLocked();
            boolean lowPowerModeUpdated = updateLowPowerModeLocked();
            updateVibrationIntensityLocked();

            if (devicesUpdated || lowPowerModeUpdated) {
                // If the state changes out from under us then just reset.
                doCancelVibrateLocked();
            }
        }
    }

    private boolean updateInputDeviceVibratorsLocked() {
        boolean changed = false;
        boolean vibrateInputDevices = false;
        try {
            vibrateInputDevices = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.VIBRATE_INPUT_DEVICES, UserHandle.USER_CURRENT) > 0;
        } catch (SettingNotFoundException snfe) {
        }
        if (vibrateInputDevices != mVibrateInputDevicesSetting) {
            changed = true;
            mVibrateInputDevicesSetting = vibrateInputDevices;
        }

        if (mVibrateInputDevicesSetting) {
            if (!mInputDeviceListenerRegistered) {
                mInputDeviceListenerRegistered = true;
                mIm.registerInputDeviceListener(this, mH);
            }
        } else {
            if (mInputDeviceListenerRegistered) {
                mInputDeviceListenerRegistered = false;
                mIm.unregisterInputDeviceListener(this);
            }
        }

        mInputDeviceVibrators.clear();
        if (mVibrateInputDevicesSetting) {
            int[] ids = mIm.getInputDeviceIds();
            for (int i = 0; i < ids.length; i++) {
                InputDevice device = mIm.getInputDevice(ids[i]);
                Vibrator vibrator = device.getVibrator();
                if (vibrator.hasVibrator()) {
                    mInputDeviceVibrators.add(vibrator);
                }
            }
            return true;
        }
        return changed;
    }

    private boolean updateLowPowerModeLocked() {
        boolean lowPowerMode = mPowerManagerInternal
                .getLowPowerState(ServiceType.VIBRATION).batterySaverEnabled;
        if (lowPowerMode != mLowPowerMode) {
            mLowPowerMode = lowPowerMode;
            return true;
        }
        return false;
    }

    private void updateVibrationIntensityLocked() {
        mHapticFeedbackIntensity = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                mVibrator.getDefaultHapticFeedbackIntensity(), UserHandle.USER_CURRENT);
        mNotificationIntensity = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                mVibrator.getDefaultNotificationVibrationIntensity(), UserHandle.USER_CURRENT);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateVibrators();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateVibrators();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateVibrators();
    }

    private boolean doVibratorExists() {
        // For now, we choose to ignore the presence of input devices that have vibrators
        // when reporting whether the device has a vibrator.  Applications often use this
        // information to decide whether to enable certain features so they expect the
        // result of hasVibrator() to be constant.  For now, just report whether
        // the device has a built-in vibrator.
        //synchronized (mInputDeviceVibrators) {
        //    return !mInputDeviceVibrators.isEmpty() || vibratorExists();
        //}
        return vibratorExists();
    }

    private void doVibratorOn(long millis, int amplitude, int uid, int usageHint) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorOn");
        try {
            synchronized (mInputDeviceVibrators) {
                if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                    amplitude = mDefaultVibrationAmplitude;
                }
                if (DEBUG) {
                    Slog.d(TAG, "Turning vibrator on for " + millis + " ms" +
                            " with amplitude " + amplitude + ".");
                }
                noteVibratorOnLocked(uid, millis);
                final int vibratorCount = mInputDeviceVibrators.size();
                if (vibratorCount != 0) {
                    final AudioAttributes attributes =
                            new AudioAttributes.Builder().setUsage(usageHint).build();
                    for (int i = 0; i < vibratorCount; i++) {
                        mInputDeviceVibrators.get(i).vibrate(millis, attributes);
                    }
                } else {
                    // Note: ordering is important here! Many haptic drivers will reset their
                    // amplitude when enabled, so we always have to enable frst, then set the
                    // amplitude.
                    vibratorOn(millis);
                    doVibratorSetAmplitude(amplitude);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void doVibratorSetAmplitude(int amplitude) {
        if (mSupportsAmplitudeControl) {
            vibratorSetAmplitude(amplitude);
        }
    }

    private void doVibratorOff() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorOff");
        try {
            synchronized (mInputDeviceVibrators) {
                if (DEBUG) {
                    Slog.d(TAG, "Turning vibrator off.");
                }
                noteVibratorOffLocked();
                final int vibratorCount = mInputDeviceVibrators.size();
                if (vibratorCount != 0) {
                    for (int i = 0; i < vibratorCount; i++) {
                        mInputDeviceVibrators.get(i).cancel();
                    }
                } else {
                    vibratorOff();
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private long doVibratorPrebakedEffectLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorPrebakedEffectLocked");
        try {
            final VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) vib.effect;
            final boolean usingInputDeviceVibrators;
            synchronized (mInputDeviceVibrators) {
                usingInputDeviceVibrators = !mInputDeviceVibrators.isEmpty();
            }
            // Input devices don't support prebaked effect, so skip trying it with them.
            if (!usingInputDeviceVibrators) {
                long timeout = vibratorPerformEffect(prebaked.getId(),
                        prebaked.getEffectStrength());
                if (timeout > 0) {
                    noteVibratorOnLocked(vib.uid, timeout);
                    return timeout;
                }
            }
            if (!prebaked.shouldFallback()) {
                return 0;
            }
            VibrationEffect effect = getFallbackEffect(prebaked.getId());
            if (effect == null) {
                Slog.w(TAG, "Failed to play prebaked effect, no fallback");
                return 0;
            }
            Vibration fallbackVib =
                    new Vibration(vib.token, effect, vib.usageHint, vib.uid, vib.opPkg);
            final int intensity = getCurrentIntensityLocked(fallbackVib);
            linkVibration(fallbackVib);
            applyVibrationIntensityScalingLocked(fallbackVib, intensity);
            startVibrationInnerLocked(fallbackVib);
            return 0;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private VibrationEffect getFallbackEffect(int effectId) {
        return mFallbackEffects.get(effectId);
    }

    /**
     * Return the current desired effect strength.
     *
     * If the returned value is &lt; 0 then the vibration shouldn't be played at all.
     */
    private static int intensityToEffectStrength(int intensity) {
        switch (intensity) {
            case Vibrator.VIBRATION_INTENSITY_LOW:
                return EffectStrength.LIGHT;
            case Vibrator.VIBRATION_INTENSITY_MEDIUM:
                return EffectStrength.MEDIUM;
            case Vibrator.VIBRATION_INTENSITY_HIGH:
                return EffectStrength.STRONG;
            default:
                Slog.w(TAG, "Got unexpected vibration intensity: " + intensity);
                return EffectStrength.STRONG;
        }
    }

    private void noteVibratorOnLocked(int uid, long millis) {
        try {
            mBatteryStatsService.noteVibratorOn(uid, millis);
            mCurVibUid = uid;
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOffLocked() {
        if (mCurVibUid >= 0) {
            try {
                mBatteryStatsService.noteVibratorOff(mCurVibUid);
            } catch (RemoteException e) { }
            mCurVibUid = -1;
        }
    }

    private class VibrateThread extends Thread {
        private final VibrationEffect.Waveform mWaveform;
        private final int mUid;
        private final int mUsageHint;

        private boolean mForceStop;

        VibrateThread(VibrationEffect.Waveform waveform, int uid, int usageHint) {
            mWaveform = waveform;
            mUid = uid;
            mUsageHint = usageHint;
            mTmpWorkSource.set(uid);
            mWakeLock.setWorkSource(mTmpWorkSource);
        }

        private long delayLocked(long duration) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "delayLocked");
            try {
                long durationRemaining = duration;
                if (duration > 0) {
                    final long bedtime = duration + SystemClock.uptimeMillis();
                    do {
                        try {
                            this.wait(durationRemaining);
                        }
                        catch (InterruptedException e) { }
                        if (mForceStop) {
                            break;
                        }
                        durationRemaining = bedtime - SystemClock.uptimeMillis();
                    } while (durationRemaining > 0);
                    return duration - durationRemaining;
                }
                return 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            mWakeLock.acquire();
            try {
                boolean finished = playWaveform();
                if (finished) {
                    onVibrationFinished();
                }
            } finally {
                mWakeLock.release();
            }
        }

        /**
         * Play the waveform.
         *
         * @return true if it finished naturally, false otherwise (e.g. it was canceled).
         */
        public boolean playWaveform() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "playWaveform");
            try {
                synchronized (this) {
                    final long[] timings = mWaveform.getTimings();
                    final int[] amplitudes = mWaveform.getAmplitudes();
                    final int len = timings.length;
                    final int repeat = mWaveform.getRepeatIndex();

                    int index = 0;
                    long onDuration = 0;
                    while (!mForceStop) {
                        if (index < len) {
                            final int amplitude = amplitudes[index];
                            final long duration = timings[index++];
                            if (duration <= 0) {
                                continue;
                            }
                            if (amplitude != 0) {
                                if (onDuration <= 0) {
                                    // Telling the vibrator to start multiple times usually causes
                                    // effects to feel "choppy" because the motor resets at every on
                                    // command.  Instead we figure out how long our next "on" period
                                    // is going to be, tell the motor to stay on for the full
                                    // duration, and then wake up to change the amplitude at the
                                    // appropriate intervals.
                                    onDuration = getTotalOnDuration(timings, amplitudes, index - 1,
                                            repeat);
                                    doVibratorOn(onDuration, amplitude, mUid, mUsageHint);
                                } else {
                                    doVibratorSetAmplitude(amplitude);
                                }
                            }

                            long waitTime = delayLocked(duration);
                            if (amplitude != 0) {
                                onDuration -= waitTime;
                            }
                        } else if (repeat < 0) {
                            break;
                        } else {
                            index = repeat;
                        }
                    }
                    return !mForceStop;
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        public void cancel() {
            synchronized (this) {
                mThread.mForceStop = true;
                mThread.notify();
            }
        }

        /**
         * Get the duration the vibrator will be on starting at startIndex until the next time it's
         * off.
         */
        private long getTotalOnDuration(
                long[] timings, int[] amplitudes, int startIndex, int repeatIndex) {
            int i = startIndex;
            long timing = 0;
            while(amplitudes[i] != 0) {
                timing += timings[i++];
                if (i >= timings.length) {
                    if (repeatIndex >= 0) {
                        i = repeatIndex;
                    } else {
                        break;
                    }
                }
                if (i == startIndex) {
                    return 1000;
                }
            }
            return timing;
        }
    }

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                synchronized (mLock) {
                    // When the system is entering a non-interactive state, we want
                    // to cancel vibrations in case a misbehaving app has abandoned
                    // them.  However it may happen that the system is currently playing
                    // haptic feedback as part of the transition.  So we don't cancel
                    // system vibrations.
                    if (mCurrentVibration != null
                            && !(mCurrentVibration.isHapticFeedback()
                                && mCurrentVibration.isFromSystem())) {
                        doCancelVibrateLocked();
                    }
                }
            }
        }
    };

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("Vibrator Service:");
        synchronized (mLock) {
            pw.print("  mCurrentVibration=");
            if (mCurrentVibration != null) {
                pw.println(mCurrentVibration.toInfo().toString());
            } else {
                pw.println("null");
            }
            pw.println("  mLowPowerMode=" + mLowPowerMode);
            pw.println("  mHapticFeedbackIntensity=" + mHapticFeedbackIntensity);
            pw.println("  mNotificationIntensity=" + mNotificationIntensity);
            pw.println("");
            pw.println("  Previous vibrations:");
            for (VibrationInfo info : mPreviousVibrations) {
                pw.print("    ");
                pw.println(info.toString());
            }
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver)
            throws RemoteException {
        new VibratorShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    private final class VibratorShellCommand extends ShellCommand {

        private static final long MAX_VIBRATION_MS = 200;

        private final IBinder mToken;

        private VibratorShellCommand(IBinder token) {
            mToken = token;
        }

        @Override
        public int onCommand(String cmd) {
            if ("vibrate".equals(cmd)) {
                return runVibrate();
            }
            return handleDefaultCommands(cmd);
        }

        private int runVibrate() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "runVibrate");
            try {
                try {
                    final int zenMode = Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.ZEN_MODE);
                    if (zenMode != Settings.Global.ZEN_MODE_OFF) {
                        try (PrintWriter pw = getOutPrintWriter();) {
                            pw.print("Ignoring because device is on DND mode ");
                            pw.println(DebugUtils.flagsToString(Settings.Global.class, "ZEN_MODE_",
                                    zenMode));
                            return 0;
                        }
                    }
                } catch (SettingNotFoundException e) {
                    // ignore
                }

                final long duration = Long.parseLong(getNextArgRequired());
                if (duration > MAX_VIBRATION_MS) {
                    throw new IllegalArgumentException("maximum duration is " + MAX_VIBRATION_MS);
                }
                String description = getNextArg();
                if (description == null) {
                    description = "Shell command";
                }

                VibrationEffect effect =
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE);
                vibrate(Binder.getCallingUid(), description, effect, AudioAttributes.USAGE_UNKNOWN,
                        mToken);
                return 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  vibrate duration [description]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode.");
                pw.println("");
            }
        }
    }

}
