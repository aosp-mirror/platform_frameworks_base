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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.vibrator.IVibrator;
import android.os.CombinedVibrationEffect;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorManagerService;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vibrator.Vibration;
import com.android.server.vibrator.VibrationScaler;
import com.android.server.vibrator.VibrationSettings;
import com.android.server.vibrator.VibratorController;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/** System implementation of {@link IVibratorManagerService}. */
public class VibratorManagerService extends IVibratorManagerService.Stub {
    private static final String TAG = "VibratorManagerService";
    private static final boolean DEBUG = false;
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();

    /** Lifecycle responsible for initializing this class at the right system server phases. */
    public static class Lifecycle extends SystemService {
        private VibratorManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new VibratorManagerService(getContext(), new Injector());
            publishBinderService("vibrator_manager", mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
                mService.systemReady();
            }
        }
    }

    private final Object mLock = new Object();
    private final Context mContext;
    private final Handler mHandler;
    private final AppOpsManager mAppOps;
    private final NativeWrapper mNativeWrapper;
    private final int[] mVibratorIds;
    private final SparseArray<VibratorController> mVibrators;
    @GuardedBy("mLock")
    private final SparseArray<AlwaysOnVibration> mAlwaysOnEffects = new SparseArray<>();

    private VibrationSettings mVibrationSettings;
    private VibrationScaler mVibrationScaler;

    static native long nativeInit();

    static native long nativeGetFinalizer();

    static native int[] nativeGetVibratorIds(long nativeServicePtr);

    @VisibleForTesting
    VibratorManagerService(Context context, Injector injector) {
        mContext = context;
        mHandler = injector.createHandler(Looper.myLooper());
        mNativeWrapper = injector.getNativeWrapper();
        mNativeWrapper.init();

        mAppOps = mContext.getSystemService(AppOpsManager.class);

        int[] vibratorIds = mNativeWrapper.getVibratorIds();
        if (vibratorIds == null) {
            mVibratorIds = new int[0];
            mVibrators = new SparseArray<>(0);
        } else {
            // Keep original vibrator id order, which might be meaningful.
            mVibratorIds = vibratorIds;
            mVibrators = new SparseArray<>(mVibratorIds.length);
            VibrationCompleteListener listener = new VibrationCompleteListener(this);
            for (int vibratorId : vibratorIds) {
                mVibrators.put(vibratorId, injector.createVibratorController(vibratorId, listener));
            }
        }
    }

    /** Finish initialization at boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}. */
    @VisibleForTesting
    void systemReady() {
        Slog.v(TAG, "Initializing VibratorManager service...");
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "systemReady");
        try {
            mVibrationSettings = new VibrationSettings(mContext, mHandler);
            mVibrationScaler = new VibrationScaler(mContext, mVibrationSettings);

            mVibrationSettings.addListener(this::updateServiceState);

            updateServiceState();
        } finally {
            Slog.v(TAG, "VibratorManager service initialized");
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    @Nullable
    public VibratorInfo getVibratorInfo(int vibratorId) {
        VibratorController controller = mVibrators.get(vibratorId);
        return controller == null ? null : controller.getVibratorInfo();
    }

    @Override // Binder call
    public int[] getVibratorIds() {
        return Arrays.copyOf(mVibratorIds, mVibratorIds.length);
    }

    @Override // Binder call
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibrationEffect effect, @Nullable VibrationAttributes attrs) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "setAlwaysOnEffect");
        try {
            if (!hasPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)) {
                throw new SecurityException("Requires VIBRATE_ALWAYS_ON permission");
            }
            if (effect == null) {
                synchronized (mLock) {
                    mAlwaysOnEffects.delete(alwaysOnId);
                    onAllVibratorsLocked(v -> {
                        if (v.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                            v.updateAlwaysOn(alwaysOnId, /* effect= */ null);
                        }
                    });
                }
                return true;
            }
            if (!isEffectValid(effect)) {
                return false;
            }
            attrs = fixupVibrationAttributes(attrs);
            synchronized (mLock) {
                SparseArray<VibrationEffect.Prebaked> effects = fixupAlwaysOnEffectsLocked(effect);
                if (effects == null) {
                    // Invalid effects set in CombinedVibrationEffect, or always-on capability is
                    // missing on individual vibrators.
                    return false;
                }
                AlwaysOnVibration alwaysOnVibration = new AlwaysOnVibration(
                        alwaysOnId, uid, opPkg, attrs, effects);
                mAlwaysOnEffects.put(alwaysOnId, alwaysOnVibration);
                updateAlwaysOnLocked(alwaysOnVibration);
            }
            return true;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void vibrate(int uid, String opPkg, @NonNull CombinedVibrationEffect effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override // Binder call
    public void cancelVibrate(IBinder token) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback cb, ResultReceiver resultReceiver) {
        new VibratorManagerShellCommand(this).exec(this, in, out, err, args, cb, resultReceiver);
    }

    private void updateServiceState() {
        synchronized (mLock) {
            for (int i = 0; i < mAlwaysOnEffects.size(); i++) {
                updateAlwaysOnLocked(mAlwaysOnEffects.valueAt(i));
            }
        }
    }

    @GuardedBy("mLock")
    private void updateAlwaysOnLocked(AlwaysOnVibration vib) {
        for (int i = 0; i < vib.effects.size(); i++) {
            VibratorController vibrator = mVibrators.get(vib.effects.keyAt(i));
            VibrationEffect.Prebaked effect = vib.effects.valueAt(i);
            if (vibrator == null) {
                continue;
            }
            Vibration.Status ignoredStatus = shouldIgnoreVibrationLocked(
                    vib.uid, vib.opPkg, vib.attrs);
            if (ignoredStatus == null) {
                effect = mVibrationScaler.scale(effect, vib.attrs.getUsage());
            } else {
                // Vibration should not run, use null effect to remove registered effect.
                effect = null;
            }
            vibrator.updateAlwaysOn(vib.alwaysOnId, effect);
        }
    }

    /**
     * Check if a vibration with given {@code uid}, {@code opPkg} and {@code attrs} should be
     * ignored by this service.
     *
     * @param uid   The user id of this vibration
     * @param opPkg The package name of this vibration
     * @param attrs The attributes of this vibration
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.Status shouldIgnoreVibrationLocked(int uid, String opPkg,
            VibrationAttributes attrs) {
        if (!mVibrationSettings.shouldVibrateForPowerMode(attrs.getUsage())) {
            return Vibration.Status.IGNORED_FOR_POWER;
        }

        int intensity = mVibrationSettings.getCurrentIntensity(attrs.getUsage());
        if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            return Vibration.Status.IGNORED_FOR_SETTINGS;
        }

        if (!mVibrationSettings.shouldVibrateForRingerMode(attrs.getUsage())) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
            }
            return Vibration.Status.IGNORED_RINGTONE;
        }

        int mode = getAppOpMode(uid, opPkg, attrs);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                Slog.w(TAG, "Would be an error: vibrate from uid " + uid);
                return Vibration.Status.IGNORED_ERROR_APP_OPS;
            } else {
                return Vibration.Status.IGNORED_APP_OPS;
            }
        }

        return null;
    }

    /**
     * Check which mode should be set for a vibration with given {@code uid}, {@code opPkg} and
     * {@code attrs}. This will return one of the AppOpsManager.MODE_*.
     */
    private int getAppOpMode(int uid, String opPkg, VibrationAttributes attrs) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                attrs.getAudioUsage(), uid, opPkg);
        if (mode == AppOpsManager.MODE_ALLOWED) {
            mode = mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, uid, opPkg);
        }
        if (mode == AppOpsManager.MODE_IGNORED
                && attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
            // If we're just ignoring the vibration op then this is set by DND and we should ignore
            // if we're asked to bypass. AppOps won't be able to record this operation, so make
            // sure we at least note it in the logs for debugging.
            Slog.d(TAG, "Bypassing DND for vibrate from uid " + uid);
            mode = AppOpsManager.MODE_ALLOWED;
        }
        return mode;
    }

    /**
     * Validate the incoming {@link CombinedVibrationEffect}.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the CombinedVibrationEffect is non-null and valid
     */
    private static boolean isEffectValid(@Nullable CombinedVibrationEffect effect) {
        if (effect == null) {
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying CombinedVibrationEffect.", e);
            return false;
        }
        return true;
    }

    /**
     * Return new {@link VibrationAttributes} that only applies flags that this user has permissions
     * to use.
     */
    private VibrationAttributes fixupVibrationAttributes(@Nullable VibrationAttributes attrs) {
        if (attrs == null) {
            attrs = DEFAULT_ATTRIBUTES;
        }
        if (attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
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

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<VibrationEffect.Prebaked> fixupAlwaysOnEffectsLocked(
            CombinedVibrationEffect effect) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "fixupAlwaysOnEffectsLocked");
        try {
            SparseArray<VibrationEffect> effects;
            if (effect instanceof CombinedVibrationEffect.Mono) {
                VibrationEffect syncedEffect = ((CombinedVibrationEffect.Mono) effect).getEffect();
                effects = transformAllVibratorsLocked(unused -> syncedEffect);
            } else if (effect instanceof CombinedVibrationEffect.Stereo) {
                effects = ((CombinedVibrationEffect.Stereo) effect).getEffects();
            } else {
                // Only synced combinations can be used for always-on effects.
                return null;
            }
            SparseArray<VibrationEffect.Prebaked> result = new SparseArray<>();
            for (int i = 0; i < effects.size(); i++) {
                VibrationEffect prebaked = effects.valueAt(i);
                if (!(prebaked instanceof VibrationEffect.Prebaked)) {
                    Slog.e(TAG, "Only prebaked effects supported for always-on.");
                    return null;
                }
                int vibratorId = effects.keyAt(i);
                VibratorController vibrator = mVibrators.get(vibratorId);
                if (vibrator != null && vibrator.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                    result.put(vibratorId, (VibrationEffect.Prebaked) prebaked);
                }
            }
            if (result.size() == 0) {
                return null;
            }
            return result;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @GuardedBy("mLock")
    private void onAllVibratorsLocked(Consumer<VibratorController> consumer) {
        for (int i = 0; i < mVibrators.size(); i++) {
            consumer.accept(mVibrators.valueAt(i));
        }
    }

    @GuardedBy("mLock")
    private <T> SparseArray<T> transformAllVibratorsLocked(Function<VibratorController, T> fn) {
        SparseArray<T> ret = new SparseArray<>(mVibrators.size());
        for (int i = 0; i < mVibrators.size(); i++) {
            ret.put(mVibrators.keyAt(i), fn.apply(mVibrators.valueAt(i)));
        }
        return ret;
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

        VibratorController createVibratorController(int vibratorId,
                VibratorController.OnVibrationCompleteListener listener) {
            return new VibratorController(vibratorId, listener);
        }
    }

    /**
     * Implementation of {@link VibratorController.OnVibrationCompleteListener} with a weak
     * reference to this service.
     */
    private static final class VibrationCompleteListener implements
            VibratorController.OnVibrationCompleteListener {
        private WeakReference<VibratorManagerService> mServiceRef;

        VibrationCompleteListener(VibratorManagerService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onComplete(int vibratorId, long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                // TODO(b/159207608): finish vibration if all vibrators finished for this vibration
            }
        }
    }

    /**
     * Combination of prekabed vibrations on multiple vibrators, with the same {@link
     * VibrationAttributes}, that can be set for always-on effects.
     */
    private static final class AlwaysOnVibration {
        public final int alwaysOnId;
        public final int uid;
        public final String opPkg;
        public final VibrationAttributes attrs;
        public final SparseArray<VibrationEffect.Prebaked> effects;

        AlwaysOnVibration(int alwaysOnId, int uid, String opPkg, VibrationAttributes attrs,
                SparseArray<VibrationEffect.Prebaked> effects) {
            this.alwaysOnId = alwaysOnId;
            this.uid = uid;
            this.opPkg = opPkg;
            this.attrs = attrs;
            this.effects = effects;
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorManagerService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Returns native pointer to newly created controller and connects with HAL service. */
        public void init() {
            mNativeServicePtr = VibratorManagerService.nativeInit();
            long finalizerPtr = VibratorManagerService.nativeGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorManagerService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Returns vibrator ids. */
        public int[] getVibratorIds() {
            return VibratorManagerService.nativeGetVibratorIds(mNativeServicePtr);
        }
    }

    /** Provide limited functionality from {@link VibratorManagerService} as shell commands. */
    private final class VibratorManagerShellCommand extends ShellCommand {

        private final IBinder mToken;

        private VibratorManagerShellCommand(IBinder token) {
            mToken = token;
        }

        @Override
        public int onCommand(String cmd) {
            if ("list".equals(cmd)) {
                return runListVibrators();
            }
            return handleDefaultCommands(cmd);
        }

        private int runListVibrators() {
            try (PrintWriter pw = getOutPrintWriter();) {
                if (mVibratorIds.length == 0) {
                    pw.println("No vibrator found");
                } else {
                    for (int id : mVibratorIds) {
                        pw.println(id);
                    }
                }
                pw.println("");
                return 0;
            }
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator Manager commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  list");
                pw.println("    Prints the id of device vibrators. This does not include any ");
                pw.println("    connected input device.");
                pw.println("");
            }
        }
    }
}
