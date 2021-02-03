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

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.IVibrator;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.CombinedVibrationEffect;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
import android.os.IVibratorService;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.vibrator.InputDeviceDelegate;
import com.android.server.vibrator.Vibration;
import com.android.server.vibrator.VibrationScaler;
import com.android.server.vibrator.VibrationSettings;
import com.android.server.vibrator.VibrationThread;
import com.android.server.vibrator.VibratorController;
import com.android.server.vibrator.VibratorController.OnVibrationCompleteListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

/** System implementation of {@link IVibratorService}. */
public class VibratorService extends IVibratorService.Stub {
    private static final String TAG = "VibratorService";
    private static final boolean DEBUG = false;
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";

    // Default vibration attributes. Used when vibration is requested without attributes
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();

    // Used to generate globally unique vibration ids.
    private final AtomicInteger mNextVibrationId = new AtomicInteger(1); // 0 = no callback

    private final LinkedList<Vibration.DebugInfo> mPreviousRingVibrations;
    private final LinkedList<Vibration.DebugInfo> mPreviousNotificationVibrations;
    private final LinkedList<Vibration.DebugInfo> mPreviousAlarmVibrations;
    private final LinkedList<Vibration.DebugInfo> mPreviousExternalVibrations;
    private final LinkedList<Vibration.DebugInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private final Handler mH;
    private final Object mLock = new Object();
    private final VibratorController mVibratorController;
    private final VibrationCallbacks mVibrationCallbacks = new VibrationCallbacks();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStatsService;
    private final String mSystemUiPackage;
    private VibrationSettings mVibrationSettings;
    private VibrationScaler mVibrationScaler;
    private InputDeviceDelegate mInputDeviceDelegate;

    @GuardedBy("mLock")
    private VibrationThread mThread;
    @GuardedBy("mLock")
    private VibrationThread mNextVibrationThread;

    @GuardedBy("mLock")
    private Vibration mCurrentVibration;
    private int mCurVibUid = -1;
    private ExternalVibrationHolder mCurrentExternalVibration;

    /**
     * Implementation of {@link VibrationThread.VibrationCallbacks} that reports finished
     * vibrations.
     */
    private final class VibrationCallbacks implements VibrationThread.VibrationCallbacks {

        @Override
        public void prepareSyncedVibration(int requiredCapabilities, int[] vibratorIds) {
        }

        @Override
        public void triggerSyncedVibration(long vibrationId) {
        }

        @Override
        public void onVibrationEnded(long vibrationId, Vibration.Status status) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration thread finished with status " + status);
            }
            synchronized (mLock) {
                if (mCurrentVibration != null && mCurrentVibration.id == vibrationId) {
                    mThread = null;
                    reportFinishVibrationLocked(status);
                    if (mNextVibrationThread != null) {
                        startVibrationThreadLocked(mNextVibrationThread);
                        mNextVibrationThread = null;
                    }
                }
            }
        }
    }

    /**
     * Implementation of {@link OnVibrationCompleteListener} with a weak reference to this service.
     */
    private static final class VibrationCompleteListener implements OnVibrationCompleteListener {
        private WeakReference<VibratorService> mServiceRef;

        VibrationCompleteListener(VibratorService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onComplete(int vibratorId, long vibrationId) {
            VibratorService service = mServiceRef.get();
            if (service != null) {
                service.onVibrationComplete(vibratorId, vibrationId);
            }
        }
    }

    /** Holder for a {@link ExternalVibration}. */
    private final class ExternalVibrationHolder {

        public final ExternalVibration externalVibration;
        public int scale;

        private final long mStartTimeDebug;
        private long mEndTimeDebug;
        private Vibration.Status mStatus;

        private ExternalVibrationHolder(ExternalVibration externalVibration) {
            this.externalVibration = externalVibration;
            this.scale = IExternalVibratorService.SCALE_NONE;
            mStartTimeDebug = System.currentTimeMillis();
            mStatus = Vibration.Status.RUNNING;
        }

        public void end(Vibration.Status status) {
            if (mStatus != Vibration.Status.RUNNING) {
                // Vibration already ended, keep first ending status set and ignore this one.
                return;
            }
            mStatus = status;
            mEndTimeDebug = System.currentTimeMillis();
        }

        public Vibration.DebugInfo getDebugInfo() {
            return new Vibration.DebugInfo(
                    mStartTimeDebug, mEndTimeDebug, /* effect= */ null, /* originalEffect= */ null,
                    scale, externalVibration.getVibrationAttributes(),
                    externalVibration.getUid(), externalVibration.getPackage(),
                    /* reason= */ null, mStatus);
        }
    }

    VibratorService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    VibratorService(Context context, Injector injector) {
        mH = injector.createHandler(Looper.myLooper());
        mVibratorController = injector.createVibratorController(
                new VibrationCompleteListener(this));

        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        mVibratorController.off();

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

        mPreviousRingVibrations = new LinkedList<>();
        mPreviousNotificationVibrations = new LinkedList<>();
        mPreviousAlarmVibrations = new LinkedList<>();
        mPreviousVibrations = new LinkedList<>();
        mPreviousExternalVibrations = new LinkedList<>();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);

        injector.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
    }

    public void systemReady() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "VibratorService#systemReady");
        try {
            mVibrationSettings = new VibrationSettings(mContext, mH);
            mVibrationScaler = new VibrationScaler(mContext, mVibrationSettings);
            mInputDeviceDelegate = new InputDeviceDelegate(mContext, mH);

            mVibrationSettings.addListener(this::updateVibrators);

            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mVibrationSettings.updateSettings();
                }
            }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mH);

            updateVibrators();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    /** Callback for when vibration is complete, to be called by native. */
    @VisibleForTesting
    public void onVibrationComplete(int vibratorId, long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.id == vibrationId
                    && mThread != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration onComplete callback, notifying VibrationThread");
                }
                // Let the thread playing the vibration handle the callback, since it might be
                // expecting the vibrator to turn off multiple times during a single vibration.
                mThread.vibratorComplete(vibratorId);
            }
        }
    }

    @Override // Binder call
    public boolean hasVibrator() {
        // For now, we choose to ignore the presence of input devices that have vibrators
        // when reporting whether the device has a vibrator.  Applications often use this
        // information to decide whether to enable certain features so they expect the
        // result of hasVibrator() to be constant.  For now, just report whether
        // the device has a built-in vibrator.
        return mVibratorController.isAvailable();
    }

    @Override // Binder call
    public boolean isVibrating() {
        if (!hasPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)) {
            throw new SecurityException("Requires ACCESS_VIBRATOR_STATE permission");
        }
        return mVibratorController.isVibrating();
    }

    @Override // Binder call
    public VibratorInfo getVibratorInfo() {
        return mVibratorController.getVibratorInfo();
    }

    @Override // Binder call
    public boolean registerVibratorStateListener(IVibratorStateListener listener) {
        if (!hasPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)) {
            throw new SecurityException("Requires ACCESS_VIBRATOR_STATE permission");
        }
        return mVibratorController.registerVibratorStateListener(listener);
    }

    @Override // Binder call
    @GuardedBy("mLock")
    public boolean unregisterVibratorStateListener(IVibratorStateListener listener) {
        if (!hasPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)) {
            throw new SecurityException("Requires ACCESS_VIBRATOR_STATE permission");
        }
        return mVibratorController.unregisterVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean hasAmplitudeControl() {
        // Input device vibrators always support amplitude controls.
        return mInputDeviceDelegate.isAvailable()
                || mVibratorController.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL);
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

    private VibrationEffect fixupVibrationEffect(VibrationEffect effect) {
        if (effect instanceof VibrationEffect.Prebaked
                && ((VibrationEffect.Prebaked) effect).shouldFallback()) {
            VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
            VibrationEffect fallback = mVibrationSettings.getFallbackEffect(prebaked.getId());
            return new VibrationEffect.Prebaked(prebaked.getId(), prebaked.getEffectStrength(),
                    fallback);
        }
        return effect;
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
            effect = fixupVibrationEffect(effect);
            attrs = fixupVibrationAttributes(attrs);
            Vibration vib = new Vibration(token, mNextVibrationId.getAndIncrement(),
                    CombinedVibrationEffect.createSynced(effect), attrs, uid, opPkg, reason);

            // If our current vibration is longer than the new vibration and is the same amplitude,
            // then just let the current one finish.
            synchronized (mLock) {
                VibrationEffect currentEffect =
                        mCurrentVibration == null ? null : getEffect(mCurrentVibration);
                if (effect instanceof VibrationEffect.OneShot
                        && currentEffect instanceof VibrationEffect.OneShot) {
                    VibrationEffect.OneShot newOneShot = (VibrationEffect.OneShot) effect;
                    VibrationEffect.OneShot currentOneShot =
                            (VibrationEffect.OneShot) currentEffect;
                    if (currentOneShot.getDuration() > newOneShot.getDuration()
                            && newOneShot.getAmplitude() == currentOneShot.getAmplitude()) {
                        if (DEBUG) {
                            Slog.d(TAG,
                                    "Ignoring incoming vibration in favor of current vibration");
                        }
                        endVibrationLocked(vib, Vibration.Status.IGNORED_FOR_ONGOING);
                        return;
                    }
                }


                // If something has external control of the vibrator, assume that it's more
                // important for now.
                if (mCurrentExternalVibration != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
                    }
                    endVibrationLocked(vib, Vibration.Status.IGNORED_FOR_EXTERNAL);
                    return;
                }

                // If the current vibration is repeating and the incoming one is non-repeating,
                // then ignore the non-repeating vibration. This is so that we don't cancel
                // vibrations that are meant to grab the attention of the user, like ringtones and
                // alarms, in favor of one-shot vibrations that are likely quite short.
                if (!isRepeatingVibration(effect)
                        && mCurrentVibration != null
                        && isRepeatingVibration(currentEffect)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring incoming vibration in favor of alarm vibration");
                    }
                    endVibrationLocked(vib, Vibration.Status.IGNORED_FOR_ALARM);
                    return;
                }

                if (!mVibrationSettings.shouldVibrateForUid(uid, vib.attrs.getUsage())) {
                    Slog.e(TAG, "Ignoring incoming vibration as process with"
                            + " uid= " + uid + " is background,"
                            + " attrs= " + vib.attrs);
                    endVibrationLocked(vib, Vibration.Status.IGNORED_BACKGROUND);
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    doCancelVibrateLocked(Vibration.Status.CANCELLED);
                    startVibrationLocked(vib);
                    boolean isNextVibration = mNextVibrationThread != null
                            && vib.equals(mNextVibrationThread.getVibration());

                    if (!vib.hasEnded() && !vib.equals(mCurrentVibration) && !isNextVibration) {
                        // Vibration was unexpectedly ignored: add to list for debugging
                        endVibrationLocked(vib, Vibration.Status.IGNORED);
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

    private static <T extends VibrationEffect> T getEffect(Vibration vib) {
        return (T) ((CombinedVibrationEffect.Mono) vib.getEffect()).getEffect();
    }

    private void endVibrationLocked(Vibration vib, Vibration.Status status) {
        final LinkedList<Vibration.DebugInfo> previousVibrations;
        switch (vib.attrs.getUsage()) {
            case VibrationAttributes.USAGE_NOTIFICATION:
                previousVibrations = mPreviousNotificationVibrations;
                break;
            case VibrationAttributes.USAGE_RINGTONE:
                previousVibrations = mPreviousRingVibrations;
                break;
            case VibrationAttributes.USAGE_ALARM:
                previousVibrations = mPreviousAlarmVibrations;
                break;
            default:
                previousVibrations = mPreviousVibrations;
        }
        if (previousVibrations.size() > mPreviousVibrationsLimit) {
            previousVibrations.removeFirst();
        }
        vib.end(status);
        previousVibrations.addLast(vib.getDebugInfo());
    }

    private void endVibrationLocked(ExternalVibrationHolder vib, Vibration.Status status) {
        if (mPreviousExternalVibrations.size() > mPreviousVibrationsLimit) {
            mPreviousExternalVibrations.removeFirst();
        }
        vib.end(status);
        mPreviousExternalVibrations.addLast(vib.getDebugInfo());
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
                    mNextVibrationThread = null;
                    doCancelVibrateLocked(Vibration.Status.CANCELLED);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void doCancelVibrateLocked(Vibration.Status status) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doCancelVibrateLocked");
        try {
            if (mThread != null) {
                mThread.cancel();
            }
            mInputDeviceDelegate.cancelVibrateIfAvailable();
            if (mCurrentExternalVibration != null) {
                endVibrationLocked(mCurrentExternalVibration, status);
                mCurrentExternalVibration.externalVibration.mute();
                mCurrentExternalVibration = null;
                mVibratorController.setExternalControl(false);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
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
            boolean inputDevicesAvailable = mInputDeviceDelegate.vibrateIfAvailable(
                    vib.uid, vib.opPkg, vib.getEffect(), vib.reason, vib.attrs);
            if (inputDevicesAvailable) {
                endVibrationLocked(vib, Vibration.Status.FORWARDED_TO_INPUT_DEVICES);
            } else if (mThread == null) {
                startVibrationThreadLocked(new VibrationThread(vib, mVibratorController, mWakeLock,
                        mBatteryStatsService, mVibrationCallbacks));
            } else {
                mNextVibrationThread = new VibrationThread(vib, mVibratorController, mWakeLock,
                        mBatteryStatsService, mVibrationCallbacks);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void startVibrationThreadLocked(VibrationThread thread) {
        Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
        mCurrentVibration = thread.getVibration();
        mThread = thread;
        mThread.start();
    }

    /** Scale the vibration effect by the intensity as appropriate based its intent. */
    private void applyVibrationIntensityScalingLocked(Vibration vib) {
        vib.updateEffect(mVibrationScaler.scale(vib.getEffect(), vib.attrs.getUsage()));
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
        if (!mVibrationSettings.shouldVibrateForPowerMode(vib.attrs.getUsage())) {
            endVibrationLocked(vib, Vibration.Status.IGNORED_FOR_POWER);
            return false;
        }

        int intensity = mVibrationSettings.getCurrentIntensity(vib.attrs.getUsage());
        if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            endVibrationLocked(vib, Vibration.Status.IGNORED_FOR_SETTINGS);
            return false;
        }

        if (!mVibrationSettings.shouldVibrateForRingerMode(vib.attrs.getUsage())) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
            }
            endVibrationLocked(vib, Vibration.Status.IGNORED_RINGTONE);
            return false;
        }

        final int mode = getAppOpMode(vib.uid, vib.opPkg, vib.attrs);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                Slog.w(TAG, "Would be an error: vibrate from uid " + vib.uid);
                endVibrationLocked(vib, Vibration.Status.IGNORED_ERROR_APP_OPS);
            } else {
                endVibrationLocked(vib, Vibration.Status.IGNORED_APP_OPS);
            }
            return false;
        }

        return true;
    }

    @GuardedBy("mLock")
    private void reportFinishVibrationLocked(Vibration.Status status) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "reportFinishVibrationLocked");
        try {
            if (mCurrentVibration != null) {
                endVibrationLocked(mCurrentVibration, status);
                mAppOps.finishOp(AppOpsManager.OP_VIBRATE, mCurrentVibration.uid,
                        mCurrentVibration.opPkg);

                Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                mCurrentVibration = null;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @VisibleForTesting
    void updateVibrators() {
        synchronized (mLock) {
            boolean inputDevicesChanged = mInputDeviceDelegate.updateInputDeviceVibrators(
                    mVibrationSettings.shouldVibrateInputDevices());

            if (mCurrentVibration == null) {
                return;
            }

            if (inputDevicesChanged || !mVibrationSettings.shouldVibrateForPowerMode(
                    mCurrentVibration.attrs.getUsage())) {
                // If the state changes out from under us then just reset.
                doCancelVibrateLocked(Vibration.Status.CANCELLED);
            }
        }
    }

    private boolean isSystemHapticFeedback(Vibration vib) {
        if (vib.attrs.getUsage() != VibrationAttributes.USAGE_TOUCH) {
            return false;
        }
        return vib.uid == Process.SYSTEM_UID || vib.uid == 0 || mSystemUiPackage.equals(vib.opPkg);
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("Vibrator Service:");
        synchronized (mLock) {
            pw.print("  mCurrentVibration=");
            if (mCurrentVibration != null) {
                pw.println(mCurrentVibration.getDebugInfo().toString());
            } else {
                pw.println("null");
            }
            pw.print("  mCurrentExternalVibration=");
            if (mCurrentExternalVibration != null) {
                pw.println(mCurrentExternalVibration.getDebugInfo().toString());
            } else {
                pw.println("null");
            }
            pw.println("  mVibratorController=" + mVibratorController);
            pw.println("  mVibrationSettings=" + mVibrationSettings);
            pw.println();
            pw.println("  Previous ring vibrations:");
            for (Vibration.DebugInfo info : mPreviousRingVibrations) {
                pw.print("    ");
                pw.println(info.toString());
            }

            pw.println("  Previous notification vibrations:");
            for (Vibration.DebugInfo info : mPreviousNotificationVibrations) {
                pw.println("    " + info);
            }

            pw.println("  Previous alarm vibrations:");
            for (Vibration.DebugInfo info : mPreviousAlarmVibrations) {
                pw.println("    " + info);
            }

            pw.println("  Previous vibrations:");
            for (Vibration.DebugInfo info : mPreviousVibrations) {
                pw.println("    " + info);
            }

            pw.println("  Previous external vibrations:");
            for (Vibration.DebugInfo info : mPreviousExternalVibrations) {
                pw.println("    " + info);
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            if (mCurrentVibration != null) {
                mCurrentVibration.getDebugInfo().dumpProto(proto,
                        VibratorServiceDumpProto.CURRENT_VIBRATION);
            }
            if (mCurrentExternalVibration != null) {
                mCurrentExternalVibration.getDebugInfo().dumpProto(proto,
                        VibratorServiceDumpProto.CURRENT_EXTERNAL_VIBRATION);
            }
            proto.write(VibratorServiceDumpProto.IS_VIBRATING, mVibratorController.isVibrating());
            proto.write(VibratorServiceDumpProto.VIBRATOR_UNDER_EXTERNAL_CONTROL,
                    mVibratorController.isUnderExternalControl());
            mVibrationSettings.dumpProto(proto);

            for (Vibration.DebugInfo info : mPreviousRingVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_RING_VIBRATIONS);
            }

            for (Vibration.DebugInfo info : mPreviousNotificationVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_NOTIFICATION_VIBRATIONS);
            }

            for (Vibration.DebugInfo info : mPreviousAlarmVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS);
            }

            for (Vibration.DebugInfo info : mPreviousVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_VIBRATIONS);
            }

            for (Vibration.DebugInfo info : mPreviousExternalVibrations) {
                info.dumpProto(proto, VibratorServiceDumpProto.PREVIOUS_EXTERNAL_VIBRATIONS);
            }
        }
        proto.flush();
    }

    /** Point of injection for test dependencies */
    @VisibleForTesting
    static class Injector {

        VibratorController createVibratorController(OnVibrationCompleteListener listener) {
            return new VibratorController(/* vibratorId= */ -1, listener);
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
                    if (mCurrentVibration != null && !isSystemHapticFeedback(mCurrentVibration)) {
                        mNextVibrationThread = null;
                        doCancelVibrateLocked(Vibration.Status.CANCELLED);
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
            if (!mVibratorController.hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
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
                    endVibrationLocked(vibHolder, Vibration.Status.IGNORED_ERROR_APP_OPS);
                } else {
                    endVibrationLocked(vibHolder, Vibration.Status.IGNORED_APP_OPS);
                }
                return IExternalVibratorService.SCALE_MUTE;
            }

            VibrationThread cancelingVibration = null;
            int scale;
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
                    mNextVibrationThread = null;
                    doCancelVibrateLocked(Vibration.Status.CANCELLED);
                    cancelingVibration = mThread;
                } else {
                    endVibrationLocked(mCurrentExternalVibration, Vibration.Status.CANCELLED);
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
                scale = mCurrentExternalVibration.scale;
            }
            if (cancelingVibration != null) {
                try {
                    cancelingVibration.join();
                } catch (InterruptedException e) {
                    Slog.w("Interrupted while waiting current vibration to be cancelled before "
                            + "starting external vibration", e);
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Vibrator going under external control.");
            }
            mVibratorController.setExternalControl(true);
            if (DEBUG) {
                Slog.e(TAG, "Playing external vibration: " + vib);
            }
            return scale;
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    if (DEBUG) {
                        Slog.e(TAG, "Stopping external vibration" + vib);
                    }
                    doCancelExternalVibrateLocked(Vibration.Status.FINISHED);
                }
            }
        }

        private void doCancelExternalVibrateLocked(Vibration.Status status) {
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
                mVibratorController.setExternalControl(false);
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
                        doCancelExternalVibrateLocked(Vibration.Status.CANCELLED);
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
                if (mVibratorController.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                    pw.println("  Always on effects");
                }
                if (mVibratorController.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
                    pw.println("  Compose effects");
                }
                if (mVibratorController.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
                    pw.println("  Amplitude control");
                }
                if (mVibratorController.hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                    pw.println("  External control");
                }
                if (mVibratorController.hasCapability(IVibrator.CAP_EXTERNAL_AMPLITUDE_CONTROL)) {
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
