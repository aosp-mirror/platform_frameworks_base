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

import static android.os.VibrationEffect.Composition.PrimitiveEffect;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.hardware.vibrator.IVibrator;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.icu.text.DateFormat;
import android.media.AudioManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
import android.os.IVibratorService;
import android.os.IVibratorStateListener;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.DebugUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class VibratorService extends IVibratorService.Stub
        implements InputManager.InputDeviceListener {
    private static final String TAG = "VibratorService";
    private static final boolean DEBUG = false;
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";

    private static final long[] DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS = { 0, 30, 100, 30 };

    // Scale levels. Each level, except MUTE, is defined as the delta between the current setting
    // and the default intensity for that type of vibration (i.e. current - default).
    private static final int SCALE_MUTE = IExternalVibratorService.SCALE_MUTE; // -100
    private static final int SCALE_VERY_LOW = IExternalVibratorService.SCALE_VERY_LOW; // -2
    private static final int SCALE_LOW = IExternalVibratorService.SCALE_LOW; // -1
    private static final int SCALE_NONE = IExternalVibratorService.SCALE_NONE; // 0
    private static final int SCALE_HIGH = IExternalVibratorService.SCALE_HIGH; // 1
    private static final int SCALE_VERY_HIGH = IExternalVibratorService.SCALE_VERY_HIGH; // 2

    // Gamma adjustments for scale levels.
    private static final float SCALE_VERY_LOW_GAMMA = 2.0f;
    private static final float SCALE_LOW_GAMMA = 1.5f;
    private static final float SCALE_NONE_GAMMA = 1.0f;
    private static final float SCALE_HIGH_GAMMA = 0.5f;
    private static final float SCALE_VERY_HIGH_GAMMA = 0.25f;

    // Max amplitudes for scale levels. If one is not listed, then the max amplitude is the default
    // max amplitude.
    private static final int SCALE_VERY_LOW_MAX_AMPLITUDE = 168; // 2/3 * 255
    private static final int SCALE_LOW_MAX_AMPLITUDE = 192; // 3/4 * 255

    // Default vibration attributes. Used when vibration is requested without attributes
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();

    // If HAL supports callbacks set the timeout to ASYNC_TIMEOUT_MULTIPLIER * duration.
    private static final long ASYNC_TIMEOUT_MULTIPLIER = 2;


    // A mapping from the intensity adjustment to the scaling to apply, where the intensity
    // adjustment is defined as the delta between the default intensity level and the user selected
    // intensity level. It's important that we apply the scaling on the delta between the two so
    // that the default intensity level applies no scaling to application provided effects.
    private final SparseArray<ScaleLevel> mScaleLevels;
    private final LinkedList<VibrationInfo> mPreviousRingVibrations;
    private final LinkedList<VibrationInfo> mPreviousNotificationVibrations;
    private final LinkedList<VibrationInfo> mPreviousAlarmVibrations;
    private final LinkedList<ExternalVibration> mPreviousExternalVibrations;
    private final LinkedList<VibrationInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private final boolean mAllowPriorityVibrationsInLowPowerMode;
    private final boolean mSupportsAmplitudeControl;
    private final boolean mSupportsExternalControl;
    private final List<Integer> mSupportedEffects;
    private final long mCapabilities;
    private final int mDefaultVibrationAmplitude;
    private final SparseArray<VibrationEffect> mFallbackEffects;
    private final SparseArray<Integer> mProcStatesCache = new SparseArray<>();
    private final WorkSource mTmpWorkSource = new WorkSource();
    private final Handler mH = new Handler();
    private final Object mLock = new Object();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStatsService;
    private final String mSystemUiPackage;
    private PowerManagerInternal mPowerManagerInternal;
    private InputManager mIm;
    private Vibrator mVibrator;
    private SettingsObserver mSettingObserver;

    private volatile VibrateThread mThread;

    // mInputDeviceVibrators lock should be acquired after mLock, if both are
    // to be acquired
    private final ArrayList<Vibrator> mInputDeviceVibrators = new ArrayList<>();
    private boolean mVibrateInputDevicesSetting; // guarded by mInputDeviceVibrators
    private boolean mInputDeviceListenerRegistered; // guarded by mInputDeviceVibrators

    @GuardedBy("mLock")
    private Vibration mCurrentVibration;
    private int mCurVibUid = -1;
    private ExternalVibration mCurrentExternalVibration;
    private boolean mVibratorUnderExternalControl;
    private boolean mLowPowerMode;
    @GuardedBy("mLock")
    private boolean mIsVibrating;
    @GuardedBy("mLock")
    private final RemoteCallbackList<IVibratorStateListener> mVibratorStateListeners =
                new RemoteCallbackList<>();
    private int mHapticFeedbackIntensity;
    private int mNotificationIntensity;
    private int mRingIntensity;
    private SparseArray<Vibration> mAlwaysOnEffects = new SparseArray<>();

    static native boolean vibratorExists();
    static native void vibratorInit();
    static native void vibratorOn(long milliseconds);
    static native void vibratorOff();
    static native boolean vibratorSupportsAmplitudeControl();
    static native void vibratorSetAmplitude(int amplitude);
    static native int[] vibratorGetSupportedEffects();
    static native long vibratorPerformEffect(long effect, long strength, Vibration vibration,
            boolean withCallback);
    static native void vibratorPerformComposedEffect(
            VibrationEffect.Composition.PrimitiveEffect[] effect, Vibration vibration);
    static native boolean vibratorSupportsExternalControl();
    static native void vibratorSetExternalControl(boolean enabled);
    static native long vibratorGetCapabilities();
    static native void vibratorAlwaysOnEnable(long id, long effect, long strength);
    static native void vibratorAlwaysOnDisable(long id);

    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq,
                int capability) {
            mProcStatesCache.put(uid, procState);
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            mProcStatesCache.delete(uid);
        }

        @Override public void onUidActive(int uid) {
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    private class Vibration implements IBinder.DeathRecipient {
        public final IBinder token;
        // Start time in CLOCK_BOOTTIME base.
        public final long startTime;
        // Start time in unix epoch time. Only to be used for debugging purposes and to correlate
        // with other system events, any duration calculations should be done use startTime so as
        // not to be affected by discontinuities created by RTC adjustments.
        public final long startTimeDebug;
        public final VibrationAttributes attrs;
        public final int uid;
        public final String opPkg;
        public final String reason;

        // The actual effect to be played.
        public VibrationEffect effect;
        // The original effect that was requested. This is non-null only when the original effect
        // differs from the effect that's being played. Typically these two things differ because
        // the effect was scaled based on the users vibration intensity settings.
        public VibrationEffect originalEffect;

        private Vibration(IBinder token, VibrationEffect effect,
                VibrationAttributes attrs, int uid, String opPkg, String reason) {
            this.token = token;
            this.effect = effect;
            this.startTime = SystemClock.elapsedRealtime();
            this.startTimeDebug = System.currentTimeMillis();
            this.attrs = attrs;
            this.uid = uid;
            this.opPkg = opPkg;
            this.reason = reason;
        }

        public void binderDied() {
            synchronized (mLock) {
                if (this == mCurrentVibration) {
                    doCancelVibrateLocked();
                }
            }
        }

        // Called by native
        @SuppressWarnings("unused")
        private void onComplete() {
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
            return VibratorService.this.isHapticFeedback(attrs.getUsage());
        }

        public boolean isNotification() {
            return VibratorService.this.isNotification(attrs.getUsage());
        }

        public boolean isRingtone() {
            return VibratorService.this.isRingtone(attrs.getUsage());
        }

        public boolean isAlarm() {
            return VibratorService.this.isAlarm(attrs.getUsage());
        }

        public boolean isFromSystem() {
            return uid == Process.SYSTEM_UID || uid == 0 || mSystemUiPackage.equals(opPkg);
        }

        public VibrationInfo toInfo() {
            return new VibrationInfo(
                    startTimeDebug, effect, originalEffect, attrs, uid, opPkg, reason);
        }
    }

    private static class VibrationInfo {
        private final long mStartTimeDebug;
        private final VibrationEffect mEffect;
        private final VibrationEffect mOriginalEffect;
        private final VibrationAttributes mAttrs;
        private final int mUid;
        private final String mOpPkg;
        private final String mReason;

        VibrationInfo(long startTimeDebug, VibrationEffect effect,
                VibrationEffect originalEffect, VibrationAttributes attrs, int uid,
                String opPkg, String reason) {
            mStartTimeDebug = startTimeDebug;
            mEffect = effect;
            mOriginalEffect = originalEffect;
            mAttrs = attrs;
            mUid = uid;
            mOpPkg = opPkg;
            mReason = reason;
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
                    .append(", attrs: ")
                    .append(mAttrs)
                    .append(", uid: ")
                    .append(mUid)
                    .append(", opPkg: ")
                    .append(mOpPkg)
                    .append(", reason: ")
                    .append(mReason)
                    .toString();
        }

        void dumpProto(ProtoOutputStream proto, long fieldId) {
            synchronized (this) {
                final long token = proto.start(fieldId);
                proto.write(VibrationProto.START_TIME, mStartTimeDebug);
                proto.end(token);
            }
        }
    }

    private static final class ScaleLevel {
        public final float gamma;
        public final int maxAmplitude;

        public ScaleLevel(float gamma) {
            this(gamma, VibrationEffect.MAX_AMPLITUDE);
        }

        public ScaleLevel(float gamma, int maxAmplitude) {
            this.gamma = gamma;
            this.maxAmplitude = maxAmplitude;
        }

        @Override
        public String toString() {
            return "ScaleLevel{gamma=" + gamma + ", maxAmplitude=" + maxAmplitude + "}";
        }
    }

    VibratorService(Context context) {
        vibratorInit();
        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        vibratorOff();

        mSupportsAmplitudeControl = vibratorSupportsAmplitudeControl();
        mSupportsExternalControl = vibratorSupportsExternalControl();
        mSupportedEffects = asList(vibratorGetSupportedEffects());
        mCapabilities = vibratorGetCapabilities();

        mContext = context;
        PowerManager pm = context.getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);

        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mBatteryStatsService = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));
        mSystemUiPackage = LocalServices.getService(PackageManagerInternal.class)
                .getSystemUiServiceComponent().getPackageName();

        mPreviousVibrationsLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpLimit);

        mDefaultVibrationAmplitude = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultVibrationAmplitude);

        mAllowPriorityVibrationsInLowPowerMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowPriorityVibrationsInLowPowerMode);

        mPreviousRingVibrations = new LinkedList<>();
        mPreviousNotificationVibrations = new LinkedList<>();
        mPreviousAlarmVibrations = new LinkedList<>();
        mPreviousVibrations = new LinkedList<>();
        mPreviousExternalVibrations = new LinkedList<>();

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

        mFallbackEffects = new SparseArray<>();
        mFallbackEffects.put(VibrationEffect.EFFECT_CLICK, clickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_DOUBLE_CLICK, doubleClickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_TICK, tickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_HEAVY_CLICK, heavyClickEffect);

        mFallbackEffects.put(VibrationEffect.EFFECT_TEXTURE_TICK,
                VibrationEffect.get(VibrationEffect.EFFECT_TICK, false));

        mScaleLevels = new SparseArray<>();
        mScaleLevels.put(SCALE_VERY_LOW,
                new ScaleLevel(SCALE_VERY_LOW_GAMMA, SCALE_VERY_LOW_MAX_AMPLITUDE));
        mScaleLevels.put(SCALE_LOW, new ScaleLevel(SCALE_LOW_GAMMA, SCALE_LOW_MAX_AMPLITUDE));
        mScaleLevels.put(SCALE_NONE, new ScaleLevel(SCALE_NONE_GAMMA));
        mScaleLevels.put(SCALE_HIGH, new ScaleLevel(SCALE_HIGH_GAMMA));
        mScaleLevels.put(SCALE_VERY_HIGH, new ScaleLevel(SCALE_VERY_HIGH_GAMMA));

        ServiceManager.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
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

            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.RING_VIBRATION_INTENSITY),
                    true, mSettingObserver, UserHandle.USER_ALL);

            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ZEN_MODE),
                    true, mSettingObserver, UserHandle.USER_ALL);

            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateVibrators();
                }
            }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mH);

            try {
                ActivityManager.getService().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }

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
    public boolean isVibrating() {
        if (!hasPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)) {
            throw new SecurityException("Requires ACCESS_VIBRATOR_STATE permission");
        }
        synchronized (mLock) {
            return mIsVibrating;
        }
    }

    @GuardedBy("mLock")
    private void notifyStateListenerLocked(IVibratorStateListener listener) {
        try {
            listener.onVibrating(mIsVibrating);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Vibrator callback failed to call", e);
        }
    }

    @GuardedBy("mLock")
    private void notifyStateListenersLocked() {
        final int length = mVibratorStateListeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                final IVibratorStateListener listener =
                        mVibratorStateListeners.getBroadcastItem(i);
                notifyStateListenerLocked(listener);
            }
        } finally {
            mVibratorStateListeners.finishBroadcast();
        }
    }

    @Override // Binder call
    public boolean registerVibratorStateListener(IVibratorStateListener listener) {
        if (!hasPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)) {
            throw new SecurityException("Requires ACCESS_VIBRATOR_STATE permission");
        }
        synchronized (mLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mVibratorStateListeners.register(listener)) {
                    return false;
                }
                // Notify its callback after new client registered.
                notifyStateListenerLocked(listener);
                return true;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override // Binder call
    @GuardedBy("mLock")
    public boolean unregisterVibratorStateListener(IVibratorStateListener listener) {
        if (!hasPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)) {
            throw new SecurityException("Requires ACCESS_VIBRATOR_STATE permission");
        }
        synchronized (mLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                return mVibratorStateListeners.unregister(listener);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override // Binder call
    public boolean hasAmplitudeControl() {
        synchronized (mInputDeviceVibrators) {
            // Input device vibrators don't support amplitude controls yet, but are still used over
            // the system vibrator when connected.
            return mSupportsAmplitudeControl && mInputDeviceVibrators.isEmpty();
        }
    }

    @Override // Binder call
    public int[] areEffectsSupported(int[] effectIds) {
        int[] supported = new int[effectIds.length];
        if (mSupportedEffects == null) {
            Arrays.fill(supported, Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN);
        } else {
            for (int i = 0; i < effectIds.length; i++) {
                supported[i] = mSupportedEffects.contains(effectIds[i])
                        ? Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                        : Vibrator.VIBRATION_EFFECT_SUPPORT_NO;
            }
        }
        return supported;
    }

    @Override // Binder call
    public boolean[] arePrimitivesSupported(int[] primitiveIds) {
        boolean[] supported = new boolean[primitiveIds.length];
        if (hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
            Arrays.fill(supported, true);
        }
        return supported;
    }


    private static List<Integer> asList(int... vals) {
        if (vals == null) {
            return null;
        }
        List<Integer> l = new ArrayList<>(vals.length);
        for (int val : vals) {
            l.add(val);
        }
        return l;
    }

    @Override // Binder call
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId, VibrationEffect effect,
            VibrationAttributes attrs) {
        if (!hasPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)) {
            throw new SecurityException("Requires VIBRATE_ALWAYS_ON permission");
        }
        if (!hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
            Slog.e(TAG, "Always-on effects not supported.");
            return false;
        }
        if (effect == null) {
            synchronized (mLock) {
                mAlwaysOnEffects.delete(alwaysOnId);
                vibratorAlwaysOnDisable(alwaysOnId);
            }
        } else {
            if (!verifyVibrationEffect(effect)) {
                return false;
            }
            if (!(effect instanceof VibrationEffect.Prebaked)) {
                Slog.e(TAG, "Only prebaked effects supported for always-on.");
                return false;
            }
            attrs = fixupVibrationAttributes(attrs);
            synchronized (mLock) {
                Vibration vib = new Vibration(null, effect, attrs, uid, opPkg, null);
                mAlwaysOnEffects.put(alwaysOnId, vib);
                updateAlwaysOnLocked(alwaysOnId, vib);
            }
        }
        return true;
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

    private VibrationAttributes fixupVibrationAttributes(VibrationAttributes attrs) {
        if (attrs == null) {
            attrs = DEFAULT_ATTRIBUTES;
        }
        if (shouldBypassDnd(attrs)) {
            if (!(hasPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    || hasPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                    || hasPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
                final int flags = attrs.getFlags()
                        & ~VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY;
                attrs = new VibrationAttributes.Builder(attrs).replaceFlags(flags).build();
            }
        }

        return attrs;
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
    public void vibrate(int uid, String opPkg, VibrationEffect effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "vibrate, reason = " + reason);
        try {
            if (!hasPermission(android.Manifest.permission.VIBRATE)) {
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

            attrs = fixupVibrationAttributes(attrs);

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


                // If something has external control of the vibrator, assume that it's more
                // important for now.
                if (mCurrentExternalVibration != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
                    }
                    return;
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

                Vibration vib = new Vibration(token, effect, attrs, uid, opPkg, reason);
                if (mProcStatesCache.get(uid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                        > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
                        && !vib.isNotification() && !vib.isRingtone() && !vib.isAlarm()) {
                    Slog.e(TAG, "Ignoring incoming vibration as process with"
                            + " uid= " + uid + " is background,"
                            + " attrs= " + vib.attrs);
                    return;
                }
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

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isRepeatingVibration(VibrationEffect effect) {
        return effect.getDuration() == Long.MAX_VALUE;
    }

    private void addToPreviousVibrationsLocked(Vibration vib) {
        final LinkedList<VibrationInfo> previousVibrations;
        if (vib.isRingtone()) {
            previousVibrations = mPreviousRingVibrations;
        } else if (vib.isNotification()) {
            previousVibrations = mPreviousNotificationVibrations;
        } else if (vib.isAlarm()) {
            previousVibrations = mPreviousAlarmVibrations;
        } else {
            previousVibrations = mPreviousVibrations;
        }

        if (previousVibrations.size() > mPreviousVibrationsLimit) {
            previousVibrations.removeFirst();
        }
        previousVibrations.addLast(vib.toInfo());
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
            if (mCurrentExternalVibration != null) {
                mCurrentExternalVibration.mute();
                mCurrentExternalVibration = null;
                setVibratorUnderExternalControl(false);
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
            final int intensity = getCurrentIntensityLocked(vib);
            if (!shouldVibrate(vib, intensity)) {
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
                doVibratorOn(oneShot.getDuration(), oneShot.getAmplitude(), vib.uid, vib.attrs);
                mH.postDelayed(mVibrationEndRunnable, oneShot.getDuration());
            } else if (vib.effect instanceof VibrationEffect.Waveform) {
                // mThread better be null here. doCancelVibrate should always be
                // called before startNextVibrationLocked or startVibrationLocked.
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) vib.effect;
                mThread = new VibrateThread(waveform, vib.uid, vib.attrs);
                mThread.start();
            } else if (vib.effect instanceof VibrationEffect.Prebaked) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                long timeout = doVibratorPrebakedEffectLocked(vib);
                if (timeout > 0) {
                    mH.postDelayed(mVibrationEndRunnable, timeout);
                }
            } else if (vib.effect instanceof  VibrationEffect.Composed) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                doVibratorComposedEffectLocked(vib);
                // FIXME: We rely on the completion callback here, but I don't think we require that
                // devices which support composition also support the completion callback. If we
                // ever get a device that supports the former but not the latter, then we have no
                // real way of knowing how long a given effect should last.
                mH.postDelayed(mVibrationEndRunnable, 10000);
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

        int usage = vib.attrs.getUsage();
        return usage == VibrationAttributes.USAGE_RINGTONE
                || usage == VibrationAttributes.USAGE_ALARM
                || usage == VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
    }

    private int getCurrentIntensityLocked(Vibration vib) {
        if (vib.isRingtone()) {
            return mRingIntensity;
        } else if (vib.isNotification()) {
            return mNotificationIntensity;
        } else if (vib.isHapticFeedback()) {
            return mHapticFeedbackIntensity;
        } else if (vib.isAlarm()) {
            return Vibrator.VIBRATION_INTENSITY_HIGH;
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

        final int defaultIntensity;
        if (vib.isRingtone()) {
            defaultIntensity = mVibrator.getDefaultRingVibrationIntensity();
        } else if (vib.isNotification()) {
            defaultIntensity = mVibrator.getDefaultNotificationVibrationIntensity();
        } else if (vib.isHapticFeedback()) {
            defaultIntensity = mVibrator.getDefaultHapticFeedbackIntensity();
        } else if (vib.isAlarm()) {
            defaultIntensity = Vibrator.VIBRATION_INTENSITY_HIGH;
        } else {
            // If we don't know what kind of vibration we're playing then just skip scaling for
            // now.
            return;
        }

        final ScaleLevel scale = mScaleLevels.get(intensity - defaultIntensity);
        if (scale == null) {
            // We should have scaling levels for all cases, so not being able to scale because of a
            // missing level is unexpected.
            Slog.e(TAG, "No configured scaling level!"
                    + " (current=" + intensity + ", default= " + defaultIntensity + ")");
            return;
        }

        VibrationEffect scaledEffect = null;
        if (vib.effect instanceof VibrationEffect.OneShot) {
            VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) vib.effect;
            oneShot = oneShot.resolve(mDefaultVibrationAmplitude);
            scaledEffect = oneShot.scale(scale.gamma, scale.maxAmplitude);
        } else if (vib.effect instanceof VibrationEffect.Waveform) {
            VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) vib.effect;
            waveform = waveform.resolve(mDefaultVibrationAmplitude);
            scaledEffect = waveform.scale(scale.gamma, scale.maxAmplitude);
        } else if (vib.effect instanceof VibrationEffect.Composed) {
            VibrationEffect.Composed composed = (VibrationEffect.Composed) vib.effect;
            scaledEffect = composed.scale(scale.gamma, scale.maxAmplitude);
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
        } else if (Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.APPLY_RAMPING_RINGER, 0) != 0) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    private static boolean shouldBypassDnd(VibrationAttributes attrs) {
        return attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY);
    }

    private int getAppOpMode(int uid, String packageName, VibrationAttributes attrs) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                attrs.getAudioAttributes().getUsage(), uid, packageName);
        if (mode == AppOpsManager.MODE_ALLOWED) {
            mode = mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, uid, packageName);
        }

        if (mode == AppOpsManager.MODE_IGNORED && shouldBypassDnd(attrs)) {
            // If we're just ignoring the vibration op then this is set by DND and we should ignore
            // if we're asked to bypass. AppOps won't be able to record this operation, so make
            // sure we at least note it in the logs for debugging.
            Slog.d(TAG, "Bypassing DND for vibrate from uid " + uid);
            mode = AppOpsManager.MODE_ALLOWED;
        }
        return mode;
    }

    private boolean shouldVibrate(Vibration vib, int intensity) {
        if (!isAllowedToVibrateLocked(vib)) {
            return false;
        }

        if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            return false;
        }

        if (vib.isRingtone() && !shouldVibrateForRingtone()) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
            }
            return false;
        }

        final int mode = getAppOpMode(vib.uid, vib.opPkg, vib.attrs);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                Slog.w(TAG, "Would be an error: vibrate from uid " + vib.uid);
            }
            return false;
        }

        return true;
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

            updateAlwaysOnLocked();
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
        mRingIntensity = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.RING_VIBRATION_INTENSITY,
                mVibrator.getDefaultRingVibrationIntensity(), UserHandle.USER_CURRENT);
    }

    private void updateAlwaysOnLocked(int id, Vibration vib) {
        final int intensity = getCurrentIntensityLocked(vib);
        if (!shouldVibrate(vib, intensity)) {
            vibratorAlwaysOnDisable(id);
        } else {
            final VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) vib.effect;
            final int strength = intensityToEffectStrength(intensity);
            vibratorAlwaysOnEnable(id, prebaked.getId(), strength);
        }
    }

    private void updateAlwaysOnLocked() {
        for (int i = 0; i < mAlwaysOnEffects.size(); i++) {
            int id = mAlwaysOnEffects.keyAt(i);
            Vibration vib = mAlwaysOnEffects.valueAt(i);
            updateAlwaysOnLocked(id, vib);
        }
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

    private void doVibratorOn(long millis, int amplitude, int uid, VibrationAttributes attrs) {
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
                    for (int i = 0; i < vibratorCount; i++) {
                        mInputDeviceVibrators.get(i).vibrate(millis, attrs.getAudioAttributes());
                    }
                } else {
                    // Note: ordering is important here! Many haptic drivers will reset their
                    // amplitude when enabled, so we always have to enable first, then set the
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
                long duration = vibratorPerformEffect(prebaked.getId(),
                        prebaked.getEffectStrength(), vib,
                        hasCapability(IVibrator.CAP_PERFORM_CALLBACK));
                long timeout = duration;
                if (hasCapability(IVibrator.CAP_PERFORM_CALLBACK)) {
                    timeout *= ASYNC_TIMEOUT_MULTIPLIER;
                }
                if (timeout > 0) {
                    noteVibratorOnLocked(vib.uid, duration);
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
            Vibration fallbackVib = new Vibration(vib.token, effect, vib.attrs, vib.uid,
                    vib.opPkg, vib.reason + " (fallback)");
            final int intensity = getCurrentIntensityLocked(fallbackVib);
            linkVibration(fallbackVib);
            applyVibrationIntensityScalingLocked(fallbackVib, intensity);
            startVibrationInnerLocked(fallbackVib);
            return 0;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void doVibratorComposedEffectLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorComposedEffectLocked");

        try {
            final VibrationEffect.Composed composed = (VibrationEffect.Composed) vib.effect;
            final boolean usingInputDeviceVibrators;
            synchronized (mInputDeviceVibrators) {
                usingInputDeviceVibrators = !mInputDeviceVibrators.isEmpty();
            }
            // Input devices don't support composed effect, so skip trying it with them.
            if (usingInputDeviceVibrators) {
                return;
            }

            if (!hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
                return;
            }

            PrimitiveEffect[] primitiveEffects =
                    composed.getPrimitiveEffects().toArray(new PrimitiveEffect[0]);
            vibratorPerformComposedEffect(primitiveEffects, vib);

            // Composed effects don't actually give us an estimated duration, so we just guess here.
            noteVibratorOnLocked(vib.uid, 10 * primitiveEffects.length);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }

    }

    private boolean hasCapability(long capability) {
        return (mCapabilities & capability) == capability;
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

    private void noteVibratorOnLocked(int uid, long millis) {
        try {
            mBatteryStatsService.noteVibratorOn(uid, millis);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED, uid, null,
                    FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__ON, millis);
            mCurVibUid = uid;
            if (!mIsVibrating) {
                mIsVibrating = true;
                notifyStateListenersLocked();
            }
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOffLocked() {
        if (mCurVibUid >= 0) {
            try {
                mBatteryStatsService.noteVibratorOff(mCurVibUid);
                FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                        mCurVibUid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__OFF, 0);
            } catch (RemoteException e) { }
            mCurVibUid = -1;
        }
        if (mIsVibrating) {
            mIsVibrating = false;
            notifyStateListenersLocked();
        }
    }

    private void setVibratorUnderExternalControl(boolean externalControl) {
        if (DEBUG) {
            if (externalControl) {
                Slog.d(TAG, "Vibrator going under external control.");
            } else {
                Slog.d(TAG, "Taking back control of vibrator.");
            }
        }
        mVibratorUnderExternalControl = externalControl;
        vibratorSetExternalControl(externalControl);
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("Vibrator Service:");
        synchronized (mLock) {
            pw.print("  mCurrentVibration=");
            if (mCurrentVibration != null) {
                pw.println(mCurrentVibration.toInfo().toString());
            } else {
                pw.println("null");
            }
            pw.print("  mCurrentExternalVibration=" + mCurrentExternalVibration);
            pw.println("  mVibratorUnderExternalControl=" + mVibratorUnderExternalControl);
            pw.println("  mIsVibrating=" + mIsVibrating);
            pw.println("  mVibratorStateListeners Count=" +
                            mVibratorStateListeners.getRegisteredCallbackCount());
            pw.println("  mLowPowerMode=" + mLowPowerMode);
            pw.println("  mHapticFeedbackIntensity=" + mHapticFeedbackIntensity);
            pw.println("  mNotificationIntensity=" + mNotificationIntensity);
            pw.println("  mRingIntensity=" + mRingIntensity);
            pw.println("  mSupportedEffects=" + mSupportedEffects);
            pw.println();
            pw.println("  Previous ring vibrations:");
            for (VibrationInfo info : mPreviousRingVibrations) {
                pw.print("    ");
                pw.println(info.toString());
            }

            pw.println("  Previous notification vibrations:");
            for (VibrationInfo info : mPreviousNotificationVibrations) {
                pw.println("    " + info);
            }

            pw.println("  Previous alarm vibrations:");
            for (VibrationInfo info : mPreviousAlarmVibrations) {
                pw.println("    " + info);
            }

            pw.println("  Previous vibrations:");
            for (VibrationInfo info : mPreviousVibrations) {
                pw.println("    " + info);
            }

            pw.println("  Previous external vibrations:");
            for (ExternalVibration vib : mPreviousExternalVibrations) {
                pw.println("    " + vib);
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            if (mCurrentVibration != null) {
                mCurrentVibration.toInfo().dumpProto(proto,
                    VibratorServiceDumpProto.CURRENT_VIBRATION);
            }
            proto.write(VibratorServiceDumpProto.IS_VIBRATING, mIsVibrating);
            proto.write(VibratorServiceDumpProto.VIBRATOR_UNDER_EXTERNAL_CONTROL,
                mVibratorUnderExternalControl);
            proto.write(VibratorServiceDumpProto.LOW_POWER_MODE, mLowPowerMode);
            proto.write(VibratorServiceDumpProto.HAPTIC_FEEDBACK_INTENSITY,
                mHapticFeedbackIntensity);
            proto.write(VibratorServiceDumpProto.NOTIFICATION_INTENSITY,
                mNotificationIntensity);
            proto.write(VibratorServiceDumpProto.RING_INTENSITY, mRingIntensity);

            for (VibrationInfo info : mPreviousRingVibrations) {
                info.dumpProto(proto,
                    VibratorServiceDumpProto.PREVIOUS_RING_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousNotificationVibrations) {
                info.dumpProto(proto,
                    VibratorServiceDumpProto.PREVIOUS_NOTIFICATION_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousAlarmVibrations) {
                info.dumpProto(proto,
                    VibratorServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousVibrations) {
                info.dumpProto(proto,
                    VibratorServiceDumpProto.PREVIOUS_VIBRATIONS);
            }
        }
        proto.flush();
    }

    private class VibrateThread extends Thread {
        private final VibrationEffect.Waveform mWaveform;
        private final int mUid;
        private final VibrationAttributes mAttrs;

        private boolean mForceStop;

        VibrateThread(VibrationEffect.Waveform waveform, int uid, VibrationAttributes attrs) {
            mWaveform = waveform;
            mUid = uid;
            mAttrs = attrs;
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
                                    doVibratorOn(onDuration, amplitude, mUid, mAttrs);
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
            while (amplitudes[i] != 0) {
                timing += timings[i++];
                if (i >= timings.length) {
                    if (repeatIndex >= 0) {
                        i = repeatIndex;
                        // prevent infinite loop
                        repeatIndex = -1;
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

        final long ident = Binder.clearCallingIdentity();

        boolean isDumpProto = false;
        for (String arg : args) {
            if (arg.equals("--proto")) {
                isDumpProto = true;
            }
        }
        try {
            if (isDumpProto) {
                dumpProto(fd);
            } else {
                dumpInternal(pw);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new VibratorShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    final class ExternalVibratorService extends IExternalVibratorService.Stub {
        ExternalVibrationDeathRecipient mCurrentExternalDeathRecipient;

        @Override
        public int onExternalVibrationStart(ExternalVibration vib) {
            if (!mSupportsExternalControl) {
                return SCALE_MUTE;
            }
            if (ActivityManager.checkComponentPermission(android.Manifest.permission.VIBRATE,
                    vib.getUid(), -1 /*owningUid*/, true /*exported*/)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid()
                        + " tried to play externally controlled vibration"
                        + " without VIBRATE permission, ignoring.");
                return SCALE_MUTE;
            }

            int mode = getAppOpMode(vib.getUid(), vib.getPackage(), vib.getVibrationAttributes());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Slog.w(TAG, "Would be an error: external vibrate from uid " + vib.getUid());
                }
                return SCALE_MUTE;
            }

            final int scaleLevel;
            synchronized (mLock) {
                if (!vib.equals(mCurrentExternalVibration)) {
                    if (mCurrentExternalVibration == null) {
                        // If we're not under external control right now, then cancel any normal
                        // vibration that may be playing and ready the vibrator for external
                        // control.
                        doCancelVibrateLocked();
                        setVibratorUnderExternalControl(true);
                    }
                    // At this point we either have an externally controlled vibration playing, or
                    // no vibration playing. Since the interface defines that only one externally
                    // controlled vibration can play at a time, by returning something other than
                    // SCALE_MUTE from this function we can be assured that if we are currently
                    // playing vibration, it will be muted in favor of the new vibration.
                    //
                    // Note that this doesn't support multiple concurrent external controls, as we
                    // would need to mute the old one still if it came from a different controller.
                    mCurrentExternalVibration = vib;
                    mCurrentExternalDeathRecipient = new ExternalVibrationDeathRecipient();
                    mCurrentExternalVibration.linkToDeath(mCurrentExternalDeathRecipient);
                    if (mPreviousExternalVibrations.size() > mPreviousVibrationsLimit) {
                        mPreviousExternalVibrations.removeFirst();
                    }
                    mPreviousExternalVibrations.addLast(vib);
                    if (DEBUG) {
                        Slog.e(TAG, "Playing external vibration: " + vib);
                    }
                }
                final int usage = vib.getVibrationAttributes().getUsage();
                final int defaultIntensity;
                final int currentIntensity;
                if (isRingtone(usage)) {
                    defaultIntensity = mVibrator.getDefaultRingVibrationIntensity();
                    currentIntensity = mRingIntensity;
                } else if (isNotification(usage)) {
                    defaultIntensity = mVibrator.getDefaultNotificationVibrationIntensity();
                    currentIntensity = mNotificationIntensity;
                } else if (isHapticFeedback(usage)) {
                    defaultIntensity = mVibrator.getDefaultHapticFeedbackIntensity();
                    currentIntensity = mHapticFeedbackIntensity;
                } else if (isAlarm(usage)) {
                    defaultIntensity = Vibrator.VIBRATION_INTENSITY_HIGH;
                    currentIntensity = Vibrator.VIBRATION_INTENSITY_HIGH;
                } else {
                    defaultIntensity = 0;
                    currentIntensity = 0;
                }
                scaleLevel = currentIntensity - defaultIntensity;
            }
            if (scaleLevel >= SCALE_VERY_LOW && scaleLevel <= SCALE_VERY_HIGH) {
                return scaleLevel;
            } else {
                // Presumably we want to play this but something about our scaling has gone
                // wrong, so just play with no scaling.
                Slog.w(TAG, "Error in scaling calculations, ended up with invalid scale level "
                        + scaleLevel + " for vibration " + vib);
                return SCALE_NONE;
            }
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (mLock) {
                if (vib.equals(mCurrentExternalVibration)) {
                    mCurrentExternalVibration.unlinkToDeath(mCurrentExternalDeathRecipient);
                    mCurrentExternalDeathRecipient = null;
                    mCurrentExternalVibration = null;
                    setVibratorUnderExternalControl(false);
                    if (DEBUG) {
                        Slog.e(TAG, "Stopping external vibration" + vib);
                    }
                }
            }
        }

        private class ExternalVibrationDeathRecipient implements IBinder.DeathRecipient {
            public void binderDied() {
                synchronized (mLock) {
                    onExternalVibrationStop(mCurrentExternalVibration);
                }
            }
        }
    }

    private final class VibratorShellCommand extends ShellCommand {

        private final IBinder mToken;

        private final class CommonOptions {
            public boolean force = false;
            public void check(String opt) {
                switch (opt) {
                    case "-f":
                        force = true;
                        break;
                }
            }
        }

        private VibratorShellCommand(IBinder token) {
            mToken = token;
        }

        @Override
        public int onCommand(String cmd) {
            if ("vibrate".equals(cmd)) {
                return runVibrate();
            } else if ("waveform".equals(cmd)) {
                return runWaveform();
            } else if ("prebaked".equals(cmd)) {
                return runPrebaked();
            } else if ("capabilities".equals(cmd)) {
                return runCapabilities();
            } else if ("cancel".equals(cmd)) {
                cancelVibrate(mToken);
                return 0;
            }
            return handleDefaultCommands(cmd);
        }

        private boolean checkDoNotDisturb(CommonOptions opts) {
            try {
                final int zenMode = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.ZEN_MODE);
                if (zenMode != Settings.Global.ZEN_MODE_OFF && !opts.force) {
                    try (PrintWriter pw = getOutPrintWriter();) {
                        pw.print("Ignoring because device is on DND mode ");
                        pw.println(DebugUtils.flagsToString(Settings.Global.class, "ZEN_MODE_",
                                zenMode));
                        return true;
                    }
                }
            } catch (SettingNotFoundException e) {
                // ignore
            }

            return false;
        }

        private int runVibrate() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "runVibrate");
            try {
                CommonOptions commonOptions = new CommonOptions();

                String opt;
                while ((opt = getNextOption()) != null) {
                    commonOptions.check(opt);
                }

                if (checkDoNotDisturb(commonOptions)) {
                    return 0;
                }

                final long duration = Long.parseLong(getNextArgRequired());
                String description = getNextArg();
                if (description == null) {
                    description = "Shell command";
                }

                VibrationEffect effect =
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE);
                VibrationAttributes attrs = createVibrationAttributes(commonOptions);
                vibrate(Binder.getCallingUid(), description, effect, attrs, "Shell Command",
                        mToken);
                return 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private int runWaveform() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "runWaveform");
            try {
                String description = "Shell command";
                int repeat = -1;
                ArrayList<Integer> amplitudesList = null;
                CommonOptions commonOptions = new CommonOptions();

                String opt;
                while ((opt = getNextOption()) != null) {
                    switch (opt) {
                        case "-d":
                            description = getNextArgRequired();
                            break;
                        case "-r":
                            repeat = Integer.parseInt(getNextArgRequired());
                            break;
                        case "-a":
                            if (amplitudesList == null) {
                                amplitudesList = new ArrayList<Integer>();
                            }
                            break;
                        default:
                            commonOptions.check(opt);
                            break;
                    }
                }

                if (checkDoNotDisturb(commonOptions)) {
                    return 0;
                }

                ArrayList<Long> timingsList = new ArrayList<Long>();

                String arg;
                while ((arg = getNextArg()) != null) {
                    if (amplitudesList != null && amplitudesList.size() < timingsList.size()) {
                        amplitudesList.add(Integer.parseInt(arg));
                    } else {
                        timingsList.add(Long.parseLong(arg));
                    }
                }

                VibrationEffect effect;
                long[] timings = timingsList.stream().mapToLong(Long::longValue).toArray();
                if (amplitudesList == null) {
                    effect = VibrationEffect.createWaveform(timings, repeat);
                } else {
                    int[] amplitudes =
                            amplitudesList.stream().mapToInt(Integer::intValue).toArray();
                    effect = VibrationEffect.createWaveform(timings, amplitudes, repeat);
                }
                VibrationAttributes attrs = createVibrationAttributes(commonOptions);
                vibrate(Binder.getCallingUid(), description, effect, attrs, "Shell Command",
                        mToken);
                return 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private int runPrebaked() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "runPrebaked");
            try {
                CommonOptions commonOptions = new CommonOptions();
                boolean shouldFallback = false;

                String opt;
                while ((opt = getNextOption()) != null) {
                    if ("-b".equals(opt)) {
                        shouldFallback = true;
                    } else {
                        commonOptions.check(opt);
                    }
                }

                if (checkDoNotDisturb(commonOptions)) {
                    return 0;
                }

                final int id = Integer.parseInt(getNextArgRequired());

                String description = getNextArg();
                if (description == null) {
                    description = "Shell command";
                }

                VibrationEffect effect = VibrationEffect.get(id, shouldFallback);
                VibrationAttributes attrs = createVibrationAttributes(commonOptions);
                vibrate(Binder.getCallingUid(), description, effect, attrs, "Shell Command",
                        mToken);
                return 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private int runCapabilities() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "runCapabilities");
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator capabilities:");
                if (hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                    pw.println("  Always on effects");
                }
                if (hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
                    pw.println("  Compose effects");
                }
                if (mSupportsAmplitudeControl || hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
                    pw.println("  Amplitude control");
                }
                if (mSupportsExternalControl || hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                    pw.println("  External control");
                }
                if (hasCapability(IVibrator.CAP_EXTERNAL_AMPLITUDE_CONTROL)) {
                    pw.println("  External amplitude control");
                }
                pw.println("");
                return 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private VibrationAttributes createVibrationAttributes(CommonOptions commonOptions) {
            final int flags = commonOptions.force
                    ? VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY
                    : 0;
            return new VibrationAttributes.Builder()
                    // Used to apply Settings.System.HAPTIC_FEEDBACK_INTENSITY to scale effects.
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .replaceFlags(flags)
                    .build();
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  vibrate duration [description]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on ");
                pw.println("    DND (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("  waveform [-d description] [-r index] [-a] duration [amplitude] ...");
                pw.println("    Vibrates for durations and amplitudes in list; ignored when ");
                pw.println("    device is on DND (Do Not Disturb) mode; touch feedback strength ");
                pw.println("    user setting will be used to scale amplitude.");
                pw.println("    If -r is provided, the waveform loops back to the specified");
                pw.println("    index (e.g. 0 loops from the beginning)");
                pw.println("    If -a is provided, the command accepts duration-amplitude pairs;");
                pw.println("    otherwise, it accepts durations only and alternates off/on");
                pw.println("    Duration is in milliseconds; amplitude is a scale of 1-255.");
                pw.println("  prebaked [-b] effect-id [description]");
                pw.println("    Vibrates with prebaked effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -b is provided, the prebaked fallback effect will be played if");
                pw.println("    the device doesn't support the given effect-id.");
                pw.println("  capabilities");
                pw.println("    Prints capabilities of this device.");
                pw.println("  cancel");
                pw.println("    Cancels any active vibration");
                pw.println("Common Options:");
                pw.println("  -f - Force. Ignore Do Not Disturb setting.");
                pw.println("");
            }
        }
    }

}
