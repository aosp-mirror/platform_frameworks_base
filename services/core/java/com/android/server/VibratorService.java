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
import android.hardware.input.InputManager;
import android.hardware.vibrator.IVibrator;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
import android.os.IVibratorService;
import android.os.IVibratorStateListener;
import android.os.Looper;
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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.WorkSource;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.vibrator.VibrationScaler;
import com.android.server.vibrator.VibrationSettings;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class VibratorService extends IVibratorService.Stub
        implements InputManager.InputDeviceListener {
    private static final String TAG = "VibratorService";
    private static final SimpleDateFormat DEBUG_DATE_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private static final boolean DEBUG = false;
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";

    private static final long[] DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS = { 0, 30, 100, 30 };

    // Default vibration attributes. Used when vibration is requested without attributes
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();

    // Used to generate globally unique vibration ids.
    private final AtomicInteger mNextVibrationId = new AtomicInteger(1); // 0 = no callback

    private final LinkedList<VibrationInfo> mPreviousRingVibrations;
    private final LinkedList<VibrationInfo> mPreviousNotificationVibrations;
    private final LinkedList<VibrationInfo> mPreviousAlarmVibrations;
    private final LinkedList<VibrationInfo> mPreviousExternalVibrations;
    private final LinkedList<VibrationInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private final boolean mAllowPriorityVibrationsInLowPowerMode;
    private final List<Integer> mSupportedEffects;
    private final List<Integer> mSupportedPrimitives;
    private final long mCapabilities;
    private final int mDefaultVibrationAmplitude;
    private final SparseArray<VibrationEffect> mFallbackEffects;
    private final SparseArray<Integer> mProcStatesCache = new SparseArray<>();
    private final WorkSource mTmpWorkSource = new WorkSource();
    private final Handler mH;
    private final Object mLock = new Object();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStatsService;
    private final String mSystemUiPackage;
    private PowerManagerInternal mPowerManagerInternal;
    private InputManager mIm;
    private VibrationSettings mVibrationSettings;
    private VibrationScaler mVibrationScaler;

    private final NativeWrapper mNativeWrapper;
    private volatile VibrateWaveformThread mThread;

    // mInputDeviceVibrators lock should be acquired after mLock, if both are
    // to be acquired
    private final ArrayList<Vibrator> mInputDeviceVibrators = new ArrayList<>();
    private boolean mVibrateInputDevicesSetting; // guarded by mInputDeviceVibrators
    private boolean mInputDeviceListenerRegistered; // guarded by mInputDeviceVibrators

    @GuardedBy("mLock")
    private Vibration mCurrentVibration;
    private int mCurVibUid = -1;
    private ExternalVibrationHolder mCurrentExternalVibration;
    private boolean mVibratorUnderExternalControl;
    private boolean mLowPowerMode;
    @GuardedBy("mLock")
    private boolean mIsVibrating;
    @GuardedBy("mLock")
    private final RemoteCallbackList<IVibratorStateListener> mVibratorStateListeners =
            new RemoteCallbackList<>();
    private SparseArray<Vibration> mAlwaysOnEffects = new SparseArray<>();

    static native long vibratorInit(OnCompleteListener listener);

    static native long vibratorGetFinalizer();

    static native boolean vibratorExists(long nativeServicePtr);

    static native void vibratorOn(long nativeServicePtr, long milliseconds, long vibrationId);

    static native void vibratorOff(long nativeServicePtr);

    static native void vibratorSetAmplitude(long nativeServicePtr, int amplitude);

    static native int[] vibratorGetSupportedEffects(long nativeServicePtr);

    static native int[] vibratorGetSupportedPrimitives(long nativeServicePtr);

    static native long vibratorPerformEffect(
            long nativeServicePtr, long effect, long strength, long vibrationId);

    static native void vibratorPerformComposedEffect(long nativeServicePtr,
            VibrationEffect.Composition.PrimitiveEffect[] effect, long vibrationId);

    static native void vibratorSetExternalControl(long nativeServicePtr, boolean enabled);

    static native long vibratorGetCapabilities(long nativeServicePtr);
    static native void vibratorAlwaysOnEnable(long nativeServicePtr, long id, long effect,
            long strength);
    static native void vibratorAlwaysOnDisable(long nativeServicePtr, long id);

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

    /** Listener for vibration completion callbacks from native. */
    public interface OnCompleteListener {

        /** Callback triggered when vibration is complete, identified by {@link Vibration#id}. */
        void onComplete(long vibrationId);
    }

    /** Holder for a {@link VibrationEffect}. */
    private final class Vibration implements IBinder.DeathRecipient {

        public final IBinder token;
        // Start time in CLOCK_BOOTTIME base.
        public final long startTime;
        public final VibrationAttributes attrs;
        public final long id;
        public final int uid;
        public final String opPkg;
        public final String reason;

        // The actual effect to be played.
        public VibrationEffect effect;
        // The original effect that was requested. Typically these two things differ because
        // the effect was scaled based on the users vibration intensity settings.
        public VibrationEffect originalEffect;
        // The scale applied to the original effect.
        public float scale;

        // Start/end times in unix epoch time. Only to be used for debugging purposes and to
        // correlate with other system events, any duration calculations should be done use
        // startTime so as not to be affected by discontinuities created by RTC adjustments.
        private final long mStartTimeDebug;
        private long mEndTimeDebug;
        private VibrationInfo.Status mStatus;

        private Vibration(IBinder token, VibrationEffect effect,
                VibrationAttributes attrs, int uid, String opPkg, String reason) {
            this.token = token;
            this.effect = effect;
            this.id = mNextVibrationId.getAndIncrement();
            this.startTime = SystemClock.elapsedRealtime();
            this.attrs = attrs;
            this.uid = uid;
            this.opPkg = opPkg;
            this.reason = reason;
            mStartTimeDebug = System.currentTimeMillis();
            mStatus = VibrationInfo.Status.RUNNING;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (this == mCurrentVibration) {
                    if (DEBUG) {
                        Slog.d(TAG, "Vibration finished because binder died, cleaning up");
                    }
                    doCancelVibrateLocked(VibrationInfo.Status.CANCELLED);
                }
            }
        }

        public void end(VibrationInfo.Status status) {
            if (hasEnded()) {
                // Vibration already ended, keep first ending status set and ignore this one.
                return;
            }
            mStatus = status;
            mEndTimeDebug = System.currentTimeMillis();
        }

        public boolean hasEnded() {
            return mStatus != VibrationInfo.Status.RUNNING;
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
                    mStartTimeDebug, mEndTimeDebug, effect, originalEffect, scale, attrs,
                    uid, opPkg, reason, mStatus);
        }
    }

    /** Holder for a {@link ExternalVibration}. */
    private final class ExternalVibrationHolder {

        public final ExternalVibration externalVibration;
        public int scale;

        private final long mStartTimeDebug;
        private long mEndTimeDebug;
        private VibrationInfo.Status mStatus;

        private ExternalVibrationHolder(ExternalVibration externalVibration) {
            this.externalVibration = externalVibration;
            this.scale = IExternalVibratorService.SCALE_NONE;
            mStartTimeDebug = System.currentTimeMillis();
            mStatus = VibrationInfo.Status.RUNNING;
        }

        public void end(VibrationInfo.Status status) {
            if (mStatus != VibrationInfo.Status.RUNNING) {
                // Vibration already ended, keep first ending status set and ignore this one.
                return;
            }
            mStatus = status;
            mEndTimeDebug = System.currentTimeMillis();
        }

        public VibrationInfo toInfo() {
            return new VibrationInfo(
                    mStartTimeDebug, mEndTimeDebug, /* effect= */ null, /* originalEffect= */ null,
                    scale, externalVibration.getVibrationAttributes(),
                    externalVibration.getUid(), externalVibration.getPackage(),
                    /* reason= */ null, mStatus);
        }
    }

    /** Debug information about vibrations. */
    private static class VibrationInfo {

        public enum Status {
            RUNNING,
            FINISHED,
            CANCELLED,
            ERROR_APP_OPS,
            IGNORED,
            IGNORED_APP_OPS,
            IGNORED_BACKGROUND,
            IGNORED_RINGTONE,
            IGNORED_UNKNOWN_VIBRATION,
            IGNORED_UNSUPPORTED,
            IGNORED_FOR_ALARM,
            IGNORED_FOR_EXTERNAL,
            IGNORED_FOR_ONGOING,
            IGNORED_FOR_POWER,
            IGNORED_FOR_SETTINGS,
        }

        private final long mStartTimeDebug;
        private final long mEndTimeDebug;
        private final VibrationEffect mEffect;
        private final VibrationEffect mOriginalEffect;
        private final float mScale;
        private final VibrationAttributes mAttrs;
        private final int mUid;
        private final String mOpPkg;
        private final String mReason;
        private final VibrationInfo.Status mStatus;

        VibrationInfo(long startTimeDebug, long endTimeDebug, VibrationEffect effect,
                VibrationEffect originalEffect, float scale, VibrationAttributes attrs,
                int uid, String opPkg, String reason, VibrationInfo.Status status) {
            mStartTimeDebug = startTimeDebug;
            mEndTimeDebug = endTimeDebug;
            mEffect = effect;
            mOriginalEffect = originalEffect;
            mScale = scale;
            mAttrs = attrs;
            mUid = uid;
            mOpPkg = opPkg;
            mReason = reason;
            mStatus = status;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("startTime: ")
                    .append(DEBUG_DATE_FORMAT.format(new Date(mStartTimeDebug)))
                    .append(", endTime: ")
                    .append(mEndTimeDebug == 0 ? null
                            : DEBUG_DATE_FORMAT.format(new Date(mEndTimeDebug)))
                    .append(", status: ")
                    .append(mStatus.name().toLowerCase())
                    .append(", effect: ")
                    .append(mEffect)
                    .append(", originalEffect: ")
                    .append(mOriginalEffect)
                    .append(", scale: ")
                    .append(String.format("%.2f", mScale))
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
            final long token = proto.start(fieldId);
            proto.write(VibrationProto.START_TIME, mStartTimeDebug);
            proto.write(VibrationProto.END_TIME, mEndTimeDebug);
            proto.write(VibrationProto.STATUS, mStatus.ordinal());

            final long attrsToken = proto.start(VibrationProto.ATTRIBUTES);
            proto.write(VibrationAttributesProto.USAGE, mAttrs.getUsage());
            proto.write(VibrationAttributesProto.AUDIO_USAGE, mAttrs.getAudioUsage());
            proto.write(VibrationAttributesProto.FLAGS, mAttrs.getFlags());
            proto.end(attrsToken);

            if (mEffect != null) {
                dumpEffect(proto, VibrationProto.EFFECT, mEffect);
            }
            if (mOriginalEffect != null) {
                dumpEffect(proto, VibrationProto.ORIGINAL_EFFECT, mOriginalEffect);
            }

            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId, VibrationEffect effect) {
            final long token = proto.start(fieldId);
            if (effect instanceof VibrationEffect.OneShot) {
                dumpEffect(proto, VibrationEffectProto.ONESHOT, (VibrationEffect.OneShot) effect);
            } else if (effect instanceof VibrationEffect.Waveform) {
                dumpEffect(proto, VibrationEffectProto.WAVEFORM, (VibrationEffect.Waveform) effect);
            } else if (effect instanceof VibrationEffect.Prebaked) {
                dumpEffect(proto, VibrationEffectProto.PREBAKED, (VibrationEffect.Prebaked) effect);
            } else if (effect instanceof VibrationEffect.Composed) {
                dumpEffect(proto, VibrationEffectProto.COMPOSED, (VibrationEffect.Composed) effect);
            }
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.OneShot effect) {
            final long token = proto.start(fieldId);
            proto.write(OneShotProto.DURATION, (int) effect.getDuration());
            proto.write(OneShotProto.AMPLITUDE, effect.getAmplitude());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.Waveform effect) {
            final long token = proto.start(fieldId);
            for (long timing : effect.getTimings()) {
                proto.write(WaveformProto.TIMINGS, (int) timing);
            }
            for (int amplitude : effect.getAmplitudes()) {
                proto.write(WaveformProto.AMPLITUDES, amplitude);
            }
            proto.write(WaveformProto.REPEAT, effect.getRepeatIndex() >= 0);
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.Prebaked effect) {
            final long token = proto.start(fieldId);
            proto.write(PrebakedProto.EFFECT_ID, effect.getId());
            proto.write(PrebakedProto.EFFECT_STRENGTH, effect.getEffectStrength());
            proto.write(PrebakedProto.FALLBACK, effect.shouldFallback());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.Composed effect) {
            final long token = proto.start(fieldId);
            for (PrimitiveEffect primitive : effect.getPrimitiveEffects()) {
                proto.write(ComposedProto.EFFECT_IDS, primitive.id);
                proto.write(ComposedProto.EFFECT_SCALES, primitive.scale);
                proto.write(ComposedProto.DELAYS, primitive.delay);
            }
            proto.end(token);
        }
    }

    VibratorService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    VibratorService(Context context, Injector injector) {
        mNativeWrapper = injector.getNativeWrapper();
        mH = injector.createHandler(Looper.myLooper());

        mNativeWrapper.vibratorInit(this::onVibrationComplete);

        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        mNativeWrapper.vibratorOff();

        mSupportedEffects = asList(mNativeWrapper.vibratorGetSupportedEffects());
        mSupportedPrimitives = asList(mNativeWrapper.vibratorGetSupportedPrimitives());
        mCapabilities = mNativeWrapper.vibratorGetCapabilities();

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

        injector.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
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
            mVibrationSettings = new VibrationSettings(mContext, mH);
            mVibrationScaler = new VibrationScaler(mContext, mVibrationSettings);

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

            mVibrationSettings.addListener(this::updateVibrators);

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

    /** Callback for when vibration is complete, to be called by native. */
    @VisibleForTesting
    public void onVibrationComplete(long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration finished by callback, cleaning up");
                }
                doCancelVibrateLocked(VibrationInfo.Status.FINISHED);
            }
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
            return hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)
                    && mInputDeviceVibrators.isEmpty();
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
        if (!hasCapability(IVibrator.CAP_COMPOSE_EFFECTS) || mSupportedPrimitives == null) {
            return supported;
        }
        for (int i = 0; i < primitiveIds.length; i++) {
            supported[i] = mSupportedPrimitives.contains(primitiveIds[i]);
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
                mNativeWrapper.vibratorAlwaysOnDisable(alwaysOnId);
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
                attrs = new VibrationAttributes.Builder(attrs)
                                .setFlags(flags, attrs.getFlags()).build();
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
            Vibration vib = new Vibration(token, effect, attrs, uid, opPkg, reason);

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
                        endVibrationLocked(vib, VibrationInfo.Status.IGNORED_FOR_ONGOING);
                        return;
                    }
                }


                // If something has external control of the vibrator, assume that it's more
                // important for now.
                if (mCurrentExternalVibration != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
                    }
                    endVibrationLocked(vib, VibrationInfo.Status.IGNORED_FOR_EXTERNAL);
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
                    endVibrationLocked(vib, VibrationInfo.Status.IGNORED_FOR_ALARM);
                    return;
                }

                if (mProcStatesCache.get(uid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                        > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
                        && !vib.isNotification() && !vib.isRingtone() && !vib.isAlarm()) {
                    Slog.e(TAG, "Ignoring incoming vibration as process with"
                            + " uid= " + uid + " is background,"
                            + " attrs= " + vib.attrs);
                    endVibrationLocked(vib, VibrationInfo.Status.IGNORED_BACKGROUND);
                    return;
                }
                linkVibration(vib);
                final long ident = Binder.clearCallingIdentity();
                try {
                    doCancelVibrateLocked(VibrationInfo.Status.CANCELLED);
                    startVibrationLocked(vib);

                    if (!vib.hasEnded() && mCurrentVibration.id != vib.id) {
                        // Vibration was unexpectedly ignored: add to list for debugging
                        endVibrationLocked(vib, VibrationInfo.Status.IGNORED);
                    }
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

    private void endVibrationLocked(Vibration vib, VibrationInfo.Status status) {
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
        vib.end(status);
        previousVibrations.addLast(vib.toInfo());
    }

    private void endVibrationLocked(ExternalVibrationHolder vib, VibrationInfo.Status status) {
        if (mPreviousExternalVibrations.size() > mPreviousVibrationsLimit) {
            mPreviousExternalVibrations.removeFirst();
        }
        vib.end(status);
        mPreviousExternalVibrations.addLast(vib.toInfo());
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
                final long ident = Binder.clearCallingIdentity();
                try {
                    doCancelVibrateLocked(VibrationInfo.Status.CANCELLED);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void doCancelVibrateLocked(VibrationInfo.Status status) {
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doCancelVibrateLocked");
        try {
            if (mThread != null) {
                mThread.cancel();
                mThread = null;
            }
            if (mCurrentExternalVibration != null) {
                endVibrationLocked(mCurrentExternalVibration, status);
                mCurrentExternalVibration.externalVibration.mute();
                mCurrentExternalVibration = null;
                setVibratorUnderExternalControl(false);
            }
            doVibratorOff();
            reportFinishVibrationLocked(status);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    // Callback for whenever the current vibration has finished played out
    public void onVibrationFinished() {
        if (DEBUG) {
            Slog.d(TAG, "Vibration finished, cleaning up");
        }
        synchronized (mLock) {
            // Make sure the vibration is really done. This also reports that the vibration is
            // finished.
            doCancelVibrateLocked(VibrationInfo.Status.FINISHED);
        }
    }

    @GuardedBy("mLock")
    private void startVibrationLocked(final Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationLocked");
        try {
            if (!shouldVibrate(vib)) {
                return;
            }
            applyVibrationIntensityScalingLocked(vib);
            startVibrationInnerLocked(vib);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void startVibrationInnerLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationInnerLocked");
        try {
            // Set current vibration before starting it, so callback will work.
            mCurrentVibration = vib;
            if (vib.effect instanceof VibrationEffect.OneShot) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                doVibratorOn(vib);
            } else if (vib.effect instanceof VibrationEffect.Waveform) {
                // mThread better be null here. doCancelVibrate should always be
                // called before startNextVibrationLocked or startVibrationLocked.
                mThread = new VibrateWaveformThread(vib);
                mThread.start();
            } else if (vib.effect instanceof VibrationEffect.Prebaked) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                doVibratorPrebakedEffectLocked(vib);
            } else if (vib.effect instanceof VibrationEffect.Composed) {
                Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                doVibratorComposedEffectLocked(vib);
            } else {
                Slog.e(TAG, "Unknown vibration type, ignoring");
                endVibrationLocked(vib, VibrationInfo.Status.IGNORED_UNKNOWN_VIBRATION);
                // The set current vibration is not actually playing, so drop it.
                mCurrentVibration = null;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private boolean shouldVibrateForPowerModeLocked(Vibration vib) {
        if (!mLowPowerMode) {
            return true;
        }

        int usage = vib.attrs.getUsage();
        return usage == VibrationAttributes.USAGE_RINGTONE
                || usage == VibrationAttributes.USAGE_ALARM
                || usage == VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
    }

    /** Scale the vibration effect by the intensity as appropriate based its intent. */
    private void applyVibrationIntensityScalingLocked(Vibration vib) {
        VibrationEffect scaled = mVibrationScaler.scale(vib.effect, vib.attrs.getUsage());
        if (!scaled.equals(vib.effect)) {
            vib.originalEffect = vib.effect;
            vib.effect = scaled;
        }
    }

    private static boolean shouldBypassDnd(VibrationAttributes attrs) {
        return attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY);
    }

    private int getAppOpMode(int uid, String packageName, VibrationAttributes attrs) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                attrs.getAudioUsage(), uid, packageName);
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

    private boolean shouldVibrate(Vibration vib) {
        if (!shouldVibrateForPowerModeLocked(vib)) {
            endVibrationLocked(vib, VibrationInfo.Status.IGNORED_FOR_POWER);
            return false;
        }

        int intensity = mVibrationSettings.getCurrentIntensity(vib.attrs.getUsage());
        if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            endVibrationLocked(vib, VibrationInfo.Status.IGNORED_FOR_SETTINGS);
            return false;
        }

        if (vib.isRingtone() && !mVibrationSettings.shouldVibrateForRingtone()) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
            }
            endVibrationLocked(vib, VibrationInfo.Status.IGNORED_RINGTONE);
            return false;
        }

        final int mode = getAppOpMode(vib.uid, vib.opPkg, vib.attrs);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                Slog.w(TAG, "Would be an error: vibrate from uid " + vib.uid);
                endVibrationLocked(vib, VibrationInfo.Status.ERROR_APP_OPS);
            } else {
                endVibrationLocked(vib, VibrationInfo.Status.IGNORED_APP_OPS);
            }
            return false;
        }

        return true;
    }

    @GuardedBy("mLock")
    private void reportFinishVibrationLocked(VibrationInfo.Status status) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "reportFinishVibrationLocked");
        try {
            if (mCurrentVibration != null) {
                endVibrationLocked(mCurrentVibration, status);
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

            if (devicesUpdated || lowPowerModeUpdated) {
                // If the state changes out from under us then just reset.
                doCancelVibrateLocked(VibrationInfo.Status.CANCELLED);
            }

            updateAlwaysOnLocked();
        }
    }

    private boolean updateInputDeviceVibratorsLocked() {
        boolean changed = false;
        boolean vibrateInputDevices = mVibrationSettings.shouldVibrateInputDevices();
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

    private void updateAlwaysOnLocked(int id, Vibration vib) {
        if (!shouldVibrate(vib)) {
            mNativeWrapper.vibratorAlwaysOnDisable(id);
        } else {
            VibrationEffect.Prebaked scaled = mVibrationScaler.scale(vib.effect,
                    vib.attrs.getUsage());
            mNativeWrapper.vibratorAlwaysOnEnable(id, scaled.getId(), scaled.getEffectStrength());
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
        return mNativeWrapper.vibratorExists();
    }

    private void doVibratorOn(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorOn");
        try {
            synchronized (mInputDeviceVibrators) {
                final VibrationEffect.OneShot oneShot = vib.effect.resolve(
                        mDefaultVibrationAmplitude);
                if (DEBUG) {
                    Slog.d(TAG, "Turning vibrator on for " + oneShot.getDuration() + " ms"
                            + " with amplitude " + oneShot.getAmplitude() + ".");
                }
                noteVibratorOnLocked(vib.uid, oneShot.getDuration());
                final int vibratorCount = mInputDeviceVibrators.size();
                if (vibratorCount != 0) {
                    for (int i = 0; i < vibratorCount; i++) {
                        mInputDeviceVibrators.get(i).vibrate(vib.uid, vib.opPkg, oneShot,
                                vib.reason, vib.attrs);
                    }
                } else {
                    // Note: ordering is important here! Many haptic drivers will reset their
                    // amplitude when enabled, so we always have to enable first, then set the
                    // amplitude.
                    mNativeWrapper.vibratorOn(oneShot.getDuration(), vib.id);
                    doVibratorSetAmplitude(oneShot.getAmplitude());
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void doVibratorSetAmplitude(int amplitude) {
        if (hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
            mNativeWrapper.vibratorSetAmplitude(amplitude);
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
                    mNativeWrapper.vibratorOff();
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void doVibratorPrebakedEffectLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorPrebakedEffectLocked");
        try {
            final VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) vib.effect;
            final boolean usingInputDeviceVibrators;
            synchronized (mInputDeviceVibrators) {
                usingInputDeviceVibrators = !mInputDeviceVibrators.isEmpty();
            }
            // Input devices don't support prebaked effect, so skip trying it with them.
            if (!usingInputDeviceVibrators) {
                long duration = mNativeWrapper.vibratorPerformEffect(
                        prebaked.getId(), prebaked.getEffectStrength(), vib.id);
                if (duration > 0) {
                    noteVibratorOnLocked(vib.uid, duration);
                    return;
                }
            }
            endVibrationLocked(vib, VibrationInfo.Status.IGNORED_UNSUPPORTED);
            // The set current vibration is not actually playing, so drop it.
            mCurrentVibration = null;

            if (!prebaked.shouldFallback()) {
                return;
            }
            VibrationEffect effect = getFallbackEffect(prebaked.getId());
            if (effect == null) {
                Slog.w(TAG, "Failed to play prebaked effect, no fallback");
                return;
            }
            Vibration fallbackVib = new Vibration(vib.token, effect, vib.attrs, vib.uid,
                    vib.opPkg, vib.reason + " (fallback)");
            // Set current vibration before starting it, so callback will work.
            mCurrentVibration = fallbackVib;
            linkVibration(fallbackVib);
            applyVibrationIntensityScalingLocked(fallbackVib);
            startVibrationInnerLocked(fallbackVib);
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
            if (usingInputDeviceVibrators || !hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
                endVibrationLocked(vib, VibrationInfo.Status.IGNORED_UNSUPPORTED);
                // The set current vibration is not actually playing, so drop it.
                mCurrentVibration = null;
                return;
            }

            PrimitiveEffect[] primitiveEffects =
                    composed.getPrimitiveEffects().toArray(new PrimitiveEffect[0]);
            mNativeWrapper.vibratorPerformComposedEffect(primitiveEffects, vib.id);

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
        mNativeWrapper.vibratorSetExternalControl(externalControl);
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
            pw.print("  mCurrentExternalVibration=");
            if (mCurrentExternalVibration != null) {
                pw.println(mCurrentExternalVibration.toInfo().toString());
            } else {
                pw.println("null");
            }
            pw.println("  mVibratorUnderExternalControl=" + mVibratorUnderExternalControl);
            pw.println("  mIsVibrating=" + mIsVibrating);
            pw.println("  mVibratorStateListeners Count="
                    + mVibratorStateListeners.getRegisteredCallbackCount());
            pw.println("  mLowPowerMode=" + mLowPowerMode);
            pw.println("  mVibrationSettings=" + mVibrationSettings);
            pw.println("  mSupportedEffects=" + mSupportedEffects);
            pw.println("  mSupportedPrimitives=" + mSupportedPrimitives);
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
            for (VibrationInfo info : mPreviousExternalVibrations) {
                pw.println("    " + info);
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
            if (mCurrentExternalVibration != null) {
                mCurrentExternalVibration.toInfo().dumpProto(proto,
                        VibratorServiceDumpProto.CURRENT_EXTERNAL_VIBRATION);
            }
            proto.write(VibratorServiceDumpProto.IS_VIBRATING, mIsVibrating);
            proto.write(VibratorServiceDumpProto.VIBRATOR_UNDER_EXTERNAL_CONTROL,
                    mVibratorUnderExternalControl);
            proto.write(VibratorServiceDumpProto.LOW_POWER_MODE, mLowPowerMode);
            proto.write(VibratorServiceDumpProto.HAPTIC_FEEDBACK_INTENSITY,
                    mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_TOUCH));
            proto.write(VibratorServiceDumpProto.HAPTIC_FEEDBACK_DEFAULT_INTENSITY,
                    mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_TOUCH));
            proto.write(VibratorServiceDumpProto.NOTIFICATION_INTENSITY,
                    mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_NOTIFICATION));
            proto.write(VibratorServiceDumpProto.NOTIFICATION_DEFAULT_INTENSITY,
                    mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_NOTIFICATION));
            proto.write(VibratorServiceDumpProto.RING_INTENSITY,
                    mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_RINGTONE));
            proto.write(VibratorServiceDumpProto.RING_DEFAULT_INTENSITY,
                    mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_RINGTONE));

            for (VibrationInfo info : mPreviousRingVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_RING_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousNotificationVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_NOTIFICATION_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousAlarmVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_VIBRATIONS);
            }

            for (VibrationInfo info : mPreviousExternalVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_EXTERNAL_VIBRATIONS);
            }
        }
        proto.flush();
    }

    /** Thread that plays a single {@link VibrationEffect.Waveform}. */
    private class VibrateWaveformThread extends Thread {
        private final VibrationEffect.Waveform mWaveform;
        private final Vibration mVibration;

        private boolean mForceStop;

        VibrateWaveformThread(Vibration vib) {
            mWaveform = (VibrationEffect.Waveform) vib.effect;
            mVibration = new Vibration(vib.token, /* effect= */ null, vib.attrs, vib.uid,
                    vib.opPkg, vib.reason);
            mTmpWorkSource.set(vib.uid);
            mWakeLock.setWorkSource(mTmpWorkSource);
        }

        private void delayLocked(long wakeUpTime) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "delayLocked");
            try {
                long durationRemaining = wakeUpTime - SystemClock.uptimeMillis();
                while (durationRemaining > 0) {
                    try {
                        this.wait(durationRemaining);
                    } catch (InterruptedException e) {
                    }
                    if (mForceStop) {
                        break;
                    }
                    durationRemaining = wakeUpTime - SystemClock.uptimeMillis();
                }
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
                    long nextStepStartTime = SystemClock.uptimeMillis();
                    long nextVibratorStopTime = 0;
                    while (!mForceStop) {
                        if (index < len) {
                            final int amplitude = amplitudes[index];
                            final long duration = timings[index++];
                            if (duration <= 0) {
                                continue;
                            }
                            if (amplitude != 0) {
                                long now = SystemClock.uptimeMillis();
                                if (nextVibratorStopTime <= now) {
                                    // Telling the vibrator to start multiple times usually causes
                                    // effects to feel "choppy" because the motor resets at every on
                                    // command.  Instead we figure out how long our next "on" period
                                    // is going to be, tell the motor to stay on for the full
                                    // duration, and then wake up to change the amplitude at the
                                    // appropriate intervals.
                                    long onDuration = getTotalOnDuration(
                                            timings, amplitudes, index - 1, repeat);
                                    mVibration.effect = VibrationEffect.createOneShot(
                                            onDuration, amplitude);
                                    doVibratorOn(mVibration);
                                    nextVibratorStopTime = now + onDuration;
                                } else {
                                    // Vibrator is already ON, so just change its amplitude.
                                    doVibratorSetAmplitude(amplitude);
                                }
                            } else {
                                // Previous vibration should have already finished, but we make sure
                                // the vibrator will be off for the next step when amplitude is 0.
                                doVibratorOff();
                            }

                            // We wait until the time this waveform step was supposed to end,
                            // calculated from the time it was supposed to start. All start times
                            // are calculated from the waveform original start time by adding the
                            // input durations. Any scheduling or processing delay should not affect
                            // this step's perceived total duration. They will be amortized here.
                            nextStepStartTime += duration;
                            delayLocked(nextStepStartTime);
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

    /** Wrapper around the static-native methods of {@link VibratorService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Checks if vibrator exists on device. */
        public boolean vibratorExists() {
            return VibratorService.vibratorExists(mNativeServicePtr);
        }

        /** Initializes connection to vibrator HAL service. */
        public void vibratorInit(OnCompleteListener listener) {
            mNativeServicePtr = VibratorService.vibratorInit(listener);
            long finalizerPtr = VibratorService.vibratorGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Turns vibrator on for given time. */
        public void vibratorOn(long milliseconds, long vibrationId) {
            VibratorService.vibratorOn(mNativeServicePtr, milliseconds, vibrationId);
        }

        /** Turns vibrator off. */
        public void vibratorOff() {
            VibratorService.vibratorOff(mNativeServicePtr);
        }

        /** Sets the amplitude for the vibrator to run. */
        public void vibratorSetAmplitude(int amplitude) {
            VibratorService.vibratorSetAmplitude(mNativeServicePtr, amplitude);
        }

        /** Returns all predefined effects supported by the device vibrator. */
        public int[] vibratorGetSupportedEffects() {
            return VibratorService.vibratorGetSupportedEffects(mNativeServicePtr);
        }

        /** Returns all compose primitives supported by the device vibrator. */
        public int[] vibratorGetSupportedPrimitives() {
            return VibratorService.vibratorGetSupportedPrimitives(mNativeServicePtr);
        }

        /** Turns vibrator on to perform one of the supported effects. */
        public long vibratorPerformEffect(long effect, long strength, long vibrationId) {
            return VibratorService.vibratorPerformEffect(
                    mNativeServicePtr, effect, strength, vibrationId);
        }

        /** Turns vibrator on to perform one of the supported composed effects. */
        public void vibratorPerformComposedEffect(
                VibrationEffect.Composition.PrimitiveEffect[] effect, long vibrationId) {
            VibratorService.vibratorPerformComposedEffect(mNativeServicePtr, effect,
                    vibrationId);
        }

        /** Enabled the device vibrator to be controlled by another service. */
        public void vibratorSetExternalControl(boolean enabled) {
            VibratorService.vibratorSetExternalControl(mNativeServicePtr, enabled);
        }

        /** Returns all capabilities of the device vibrator. */
        public long vibratorGetCapabilities() {
            return VibratorService.vibratorGetCapabilities(mNativeServicePtr);
        }

        /** Enable always-on vibration with given id and effect. */
        public void vibratorAlwaysOnEnable(long id, long effect, long strength) {
            VibratorService.vibratorAlwaysOnEnable(mNativeServicePtr, id, effect, strength);
        }

        /** Disable always-on vibration for given id. */
        public void vibratorAlwaysOnDisable(long id) {
            VibratorService.vibratorAlwaysOnDisable(mNativeServicePtr, id);
        }
    }

    /** Point of injection for test dependencies */
    @VisibleForTesting
    static class Injector {

        NativeWrapper getNativeWrapper() {
            return new NativeWrapper();
        }

        Handler createHandler(Looper looper) {
            return new Handler(looper);
        }

        void addService(String name, IBinder service) {
            ServiceManager.addService(name, service);
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
                        doCancelVibrateLocked(VibrationInfo.Status.CANCELLED);
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
            if (!hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                return IExternalVibratorService.SCALE_MUTE;
            }
            if (ActivityManager.checkComponentPermission(android.Manifest.permission.VIBRATE,
                    vib.getUid(), -1 /*owningUid*/, true /*exported*/)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid()
                        + " tried to play externally controlled vibration"
                        + " without VIBRATE permission, ignoring.");
                return IExternalVibratorService.SCALE_MUTE;
            }

            int mode = getAppOpMode(vib.getUid(), vib.getPackage(), vib.getVibrationAttributes());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                ExternalVibrationHolder vibHolder = new ExternalVibrationHolder(vib);
                vibHolder.scale = SCALE_MUTE;
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Slog.w(TAG, "Would be an error: external vibrate from uid " + vib.getUid());
                    endVibrationLocked(vibHolder, VibrationInfo.Status.ERROR_APP_OPS);
                } else {
                    endVibrationLocked(vibHolder, VibrationInfo.Status.IGNORED_APP_OPS);
                }
                return IExternalVibratorService.SCALE_MUTE;
            }

            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    // We are already playing this external vibration, so we can return the same
                    // scale calculated in the previous call to this method.
                    return mCurrentExternalVibration.scale;
                }
                if (mCurrentExternalVibration == null) {
                    // If we're not under external control right now, then cancel any normal
                    // vibration that may be playing and ready the vibrator for external control.
                    doCancelVibrateLocked(VibrationInfo.Status.CANCELLED);
                    setVibratorUnderExternalControl(true);
                } else {
                    endVibrationLocked(mCurrentExternalVibration, VibrationInfo.Status.CANCELLED);
                }
                // At this point we either have an externally controlled vibration playing, or
                // no vibration playing. Since the interface defines that only one externally
                // controlled vibration can play at a time, by returning something other than
                // SCALE_MUTE from this function we can be assured that if we are currently
                // playing vibration, it will be muted in favor of the new vibration.
                //
                // Note that this doesn't support multiple concurrent external controls, as we
                // would need to mute the old one still if it came from a different controller.
                mCurrentExternalVibration = new ExternalVibrationHolder(vib);
                mCurrentExternalDeathRecipient = new ExternalVibrationDeathRecipient();
                vib.linkToDeath(mCurrentExternalDeathRecipient);
                mCurrentExternalVibration.scale = mVibrationScaler.getExternalVibrationScale(
                        vib.getVibrationAttributes().getUsage());
                if (DEBUG) {
                    Slog.e(TAG, "Playing external vibration: " + vib);
                }
                return mCurrentExternalVibration.scale;
            }
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    if (DEBUG) {
                        Slog.e(TAG, "Stopping external vibration" + vib);
                    }
                    doCancelExternalVibrateLocked(VibrationInfo.Status.FINISHED);
                }
            }
        }

        private void doCancelExternalVibrateLocked(VibrationInfo.Status status) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doCancelExternalVibrateLocked");
            try {
                if (mCurrentExternalVibration == null) {
                    return;
                }
                endVibrationLocked(mCurrentExternalVibration, status);
                mCurrentExternalVibration.externalVibration.unlinkToDeath(
                        mCurrentExternalDeathRecipient);
                mCurrentExternalDeathRecipient = null;
                mCurrentExternalVibration = null;
                setVibratorUnderExternalControl(false);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private class ExternalVibrationDeathRecipient implements IBinder.DeathRecipient {
            public void binderDied() {
                synchronized (mLock) {
                    if (mCurrentExternalVibration != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "External vibration finished because binder died");
                        }
                        doCancelExternalVibrateLocked(VibrationInfo.Status.CANCELLED);
                    }
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
            if (mVibrationSettings.isInZenMode() && !opts.force) {
                try (PrintWriter pw = getOutPrintWriter();) {
                    pw.print("Ignoring because device is on DND mode ");
                    return true;
                }
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
                if (hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
                    pw.println("  Amplitude control");
                }
                if (hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
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
                    .setFlags(flags, VibrationAttributes.FLAG_ALL_SUPPORTED)
                    // Used to apply Settings.System.HAPTIC_FEEDBACK_INTENSITY to scale effects.
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
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
