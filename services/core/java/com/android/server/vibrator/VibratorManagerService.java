/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_VIBRATOR;
import static android.os.VibrationAttributes.USAGE_CLASS_ALARM;
import static android.os.VibrationEffect.VibrationParameter.targetAmplitude;
import static android.os.VibrationEffect.VibrationParameter.targetFrequency;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.vibrator.IVibrator;
import android.hardware.vibrator.IVibratorManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.ExternalVibration;
import android.os.ExternalVibrationScale;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IExternalVibratorService;
import android.os.IVibratorManagerService;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.IVibrationSessionCallback;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibrationEffectSegment;
import android.os.vibrator.VibratorInfoFactory;
import android.os.vibrator.persistence.ParsedVibration;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.HapticFeedbackConstants;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.pm.BackgroundUserSoundNotifier;
import com.android.server.vibrator.VibrationSession.CallerInfo;
import com.android.server.vibrator.VibrationSession.DebugInfo;
import com.android.server.vibrator.VibrationSession.Status;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** System implementation of {@link IVibratorManagerService}. */
public class VibratorManagerService extends IVibratorManagerService.Stub {
    private static final String TAG = "VibratorManagerService";
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";
    private static final String VIBRATOR_CONTROL_SERVICE =
            "android.frameworks.vibrator.IVibratorControlService/default";
    private static final boolean DEBUG = false;
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();
    private static final int ATTRIBUTES_ALL_BYPASS_FLAGS =
            VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY
                    | VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;

    /** Fixed large duration used to note repeating vibrations to {@link IBatteryStats}. */
    private static final long BATTERY_STATS_REPEATING_VIBRATION_DURATION = 5_000;

    /**
     * Maximum millis to wait for a vibration thread cancellation to "clean up" and finish, when
     * blocking for an external vibration. In practice, this should be plenty.
     */
    private static final long VIBRATION_CANCEL_WAIT_MILLIS = 5000;

    /** Lifecycle responsible for initializing this class at the right system server phases. */
    public static class Lifecycle extends SystemService {
        private VibratorManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new VibratorManagerService(getContext(), new Injector());
            publishBinderService(Context.VIBRATOR_MANAGER_SERVICE, mService);
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
    private final Injector mInjector;
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final VibratorFrameworkStatsLogger mFrameworkStatsLogger;
    private final Handler mHandler;
    private final VibrationThread mVibrationThread;
    private final AppOpsManager mAppOps;
    private final NativeWrapper mNativeWrapper;
    private final VibratorManagerRecords mVibratorManagerRecords;
    private final long mCapabilities;
    private final int[] mVibratorIds;
    private final SparseArray<VibratorController> mVibrators;
    private final VibrationThreadCallbacks mVibrationThreadCallbacks =
            new VibrationThreadCallbacks();
    private final ExternalVibrationCallbacks mExternalVibrationCallbacks =
            new ExternalVibrationCallbacks();
    private final VendorVibrationSessionCallbacks mVendorVibrationSessionCallbacks =
            new VendorVibrationSessionCallbacks();
    @GuardedBy("mLock")
    private final SparseArray<AlwaysOnVibration> mAlwaysOnEffects = new SparseArray<>();
    @GuardedBy("mLock")
    private VibrationSession mCurrentSession;
    @GuardedBy("mLock")
    private VibrationSession mNextSession;
    @GuardedBy("mLock")
    private boolean mServiceReady;

    @VisibleForTesting
    final VibrationSettings mVibrationSettings;
    private final VibrationConfig mVibrationConfig;
    private final VibrationScaler mVibrationScaler;
    private final VibratorControlService mVibratorControlService;
    private final InputDeviceDelegate mInputDeviceDelegate;
    private final DeviceAdapter mDeviceAdapter;

    @GuardedBy("mLock")
    @Nullable private SparseArray<VibratorInfo> mVibratorInfos;
    @GuardedBy("mLock")
    @Nullable private VibratorInfo mCombinedVibratorInfo;
    @GuardedBy("mLock")
    @Nullable private HapticFeedbackVibrationProvider mHapticFeedbackVibrationProvider;

    @VisibleForTesting
    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // When the system is entering a non-interactive state, we want to cancel
                // vibrations in case a misbehaving app has abandoned them.
                synchronized (mLock) {
                    maybeClearCurrentAndNextSessionsLocked(
                            VibratorManagerService.this::shouldCancelOnScreenOffLocked,
                            Status.CANCELLED_BY_SCREEN_OFF);
                }
            } else if (android.multiuser.Flags.addUiForSoundsFromBackgroundUsers()
                    && intent.getAction().equals(BackgroundUserSoundNotifier.ACTION_MUTE_SOUND)) {
                synchronized (mLock) {
                    maybeClearCurrentAndNextSessionsLocked(
                            VibratorManagerService.this::shouldCancelOnFgUserRequest,
                            Status.CANCELLED_BY_FOREGROUND_USER);
                }
            }
        }
    };

    @VisibleForTesting
    final AppOpsManager.OnOpChangedInternalListener mAppOpsChangeListener =
            new AppOpsManager.OnOpChangedInternalListener() {
                @Override
                public void onOpChanged(int op, String packageName) {
                    if (op != AppOpsManager.OP_VIBRATE) {
                        return;
                    }
                    synchronized (mLock) {
                        maybeClearCurrentAndNextSessionsLocked(
                                VibratorManagerService.this::shouldCancelAppOpModeChangedLocked,
                                Status.CANCELLED_BY_APP_OPS);
                    }
                }
            };

    static native long nativeInit(VibratorManagerNativeCallbacks listener);

    static native long nativeGetFinalizer();

    static native long nativeGetCapabilities(long nativeServicePtr);

    static native int[] nativeGetVibratorIds(long nativeServicePtr);

    static native boolean nativePrepareSynced(long nativeServicePtr, int[] vibratorIds);

    static native boolean nativeTriggerSynced(long nativeServicePtr, long vibrationId);

    static native void nativeCancelSynced(long nativeServicePtr);

    static native boolean nativeStartSession(long nativeServicePtr, long sessionId,
            int[] vibratorIds);

    static native void nativeEndSession(long nativeServicePtr, long sessionId, boolean shouldAbort);

    static native void nativeClearSessions(long nativeServicePtr);

    @VisibleForTesting
    VibratorManagerService(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
        mHandler = injector.createHandler(Looper.myLooper());
        mFrameworkStatsLogger = injector.getFrameworkStatsLogger(mHandler);

        mVibrationConfig = new VibrationConfig(context.getResources());
        mVibrationSettings = new VibrationSettings(mContext, mHandler, mVibrationConfig);
        mVibrationScaler = new VibrationScaler(mVibrationConfig, mVibrationSettings);
        mVibratorControlService = new VibratorControlService(mContext,
                injector.createVibratorControllerHolder(), mVibrationScaler, mVibrationSettings,
                mFrameworkStatsLogger, mLock);
        mInputDeviceDelegate = new InputDeviceDelegate(mContext, mHandler);

        VibrationCompleteListener listener = new VibrationCompleteListener(this);
        mNativeWrapper = injector.getNativeWrapper();
        mNativeWrapper.init(listener);

        int recentDumpSizeLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_recentVibrationsDumpSizeLimit);
        int dumpSizeLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpSizeLimit);
        int dumpAggregationTimeLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer
                        .config_previousVibrationsDumpAggregationTimeMillisLimit);
        mVibratorManagerRecords = new VibratorManagerRecords(
                recentDumpSizeLimit, dumpSizeLimit, dumpAggregationTimeLimit);

        mBatteryStatsService = injector.getBatteryStatsService();

        mAppOps = mContext.getSystemService(AppOpsManager.class);
        if (Flags.cancelByAppops()) {
            mAppOps.startWatchingMode(AppOpsManager.OP_VIBRATE, null, mAppOpsChangeListener);
        }

        PowerManager pm = context.getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);
        mVibrationThread = new VibrationThread(mWakeLock, mVibrationThreadCallbacks);
        mVibrationThread.start();

        // Load vibrator hardware info. The vibrator ids and manager capabilities are loaded only
        // once and assumed unchanged for the lifecycle of this service. Each individual vibrator
        // can still retry loading each individual vibrator hardware spec once more at systemReady.
        mCapabilities = mNativeWrapper.getCapabilities();
        int[] vibratorIds = mNativeWrapper.getVibratorIds();
        if (vibratorIds == null) {
            mVibratorIds = new int[0];
            mVibrators = new SparseArray<>(0);
        } else {
            // Keep original vibrator id order, which might be meaningful.
            mVibratorIds = vibratorIds;
            mVibrators = new SparseArray<>(mVibratorIds.length);
            for (int vibratorId : vibratorIds) {
                VibratorController vibratorController =
                        injector.createVibratorController(vibratorId, listener);
                mVibrators.put(vibratorId, vibratorController);
            }
        }

        // Load vibrator adapter, that depends on hardware info.
        mDeviceAdapter = new DeviceAdapter(mVibrationSettings, mVibrators);

        // Reset the hardware to a default state, in case this is a runtime restart instead of a
        // fresh boot.
        mNativeWrapper.cancelSynced();
        if (Flags.vendorVibrationEffects()) {
            mNativeWrapper.clearSessions();
        }
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).reset();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (android.multiuser.Flags.addUiForSoundsFromBackgroundUsers()) {
            filter.addAction(BackgroundUserSoundNotifier.ACTION_MUTE_SOUND);
        }
        context.registerReceiver(mIntentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        injector.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
        if (injector.isServiceDeclared(VIBRATOR_CONTROL_SERVICE)) {
            injector.addService(VIBRATOR_CONTROL_SERVICE, mVibratorControlService);
        }

    }

    /** Finish initialization at boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}. */
    @VisibleForTesting
    void systemReady() {
        Slog.v(TAG, "Initializing VibratorManager service...");
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "systemReady");
        try {
            // Will retry to load each vibrator's info, if any request have failed.
            for (int i = 0; i < mVibrators.size(); i++) {
                mVibrators.valueAt(i).reloadVibratorInfoIfNeeded();
            }

            synchronized (mLock) {
                mVibratorInfos = transformAllVibratorsLocked(VibratorController::getVibratorInfo);
                VibratorInfo[] infos = new VibratorInfo[mVibratorInfos.size()];
                for (int i = 0; i < mVibratorInfos.size(); i++) {
                    infos[i] = mVibratorInfos.valueAt(i);
                }
                mCombinedVibratorInfo = VibratorInfoFactory.create(/* id= */ -1, infos);
            }

            mVibrationSettings.onSystemReady();
            mInputDeviceDelegate.onSystemReady();

            mVibrationSettings.addListener(this::updateServiceState);

            // Will update settings and input devices.
            updateServiceState();
        } finally {
            synchronized (mLock) {
                mServiceReady = true;
            }
            Slog.v(TAG, "VibratorManager service initialized");
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public int[] getVibratorIds() {
        return Arrays.copyOf(mVibratorIds, mVibratorIds.length);
    }

    @Override // Binder call
    public int getCapabilities() {
        return (int) mCapabilities;
    }

    @Override // Binder call
    @Nullable
    public VibratorInfo getVibratorInfo(int vibratorId) {
        final VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return null;
        }
        final VibratorInfo info = controller.getVibratorInfo();
        synchronized (mLock) {
            if (mServiceReady) {
                return info;
            }
        }
        // If the service is not ready and the load was unsuccessful then return null while waiting
        // for the service to be ready. It will retry to load the complete info from the HAL.
        return controller.isVibratorInfoLoadSuccessful() ? info : null;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    @Override // Binder call
    public boolean isVibrating(int vibratorId) {
        isVibrating_enforcePermission();
        VibratorController controller = mVibrators.get(vibratorId);
        return controller != null && controller.isVibrating();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    @Override // Binder call
    public boolean registerVibratorStateListener(int vibratorId, IVibratorStateListener listener) {
        registerVibratorStateListener_enforcePermission();
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.registerVibratorStateListener(listener);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    @Override // Binder call
    public boolean unregisterVibratorStateListener(int vibratorId,
            IVibratorStateListener listener) {
        unregisterVibratorStateListener_enforcePermission();
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.unregisterVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibration effect, @Nullable VibrationAttributes attrs) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "setAlwaysOnEffect");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE_ALWAYS_ON,
                    "setAlwaysOnEffect");

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
            attrs = fixupVibrationAttributes(attrs, effect);
            synchronized (mLock) {
                SparseArray<PrebakedSegment> effects = fixupAlwaysOnEffectsLocked(effect);
                if (effects == null) {
                    // Invalid effects set in CombinedVibrationEffect, or always-on capability is
                    // missing on individual vibrators.
                    return false;
                }
                AlwaysOnVibration alwaysOnVibration = new AlwaysOnVibration(alwaysOnId,
                        new CallerInfo(attrs, uid, Context.DEVICE_ID_DEFAULT, opPkg, null),
                        effects);
                mAlwaysOnEffects.put(alwaysOnId, alwaysOnVibration);
                updateAlwaysOnLocked(alwaysOnVibration);
            }
            return true;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void vibrate(int uid, int deviceId, String opPkg, @NonNull CombinedVibration effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "vibrate");
        try {
            vibrateWithPermissionCheck(uid, deviceId, opPkg, effect, attrs, reason, token);
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void performHapticFeedback(int uid, int deviceId, String opPkg, int constant,
            String reason, int flags, int privFlags) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "performHapticFeedback");
        // Note that the `performHapticFeedback` method does not take a token argument from the
        // caller, and instead, uses this service as the token. This is to mitigate performance
        // impact that would otherwise be caused due to marshal latency. Haptic feedback effects are
        // short-lived, so we don't need to cancel when the process dies.
        try {
            performHapticFeedbackInternal(uid, deviceId, opPkg, constant, reason, /* token= */
                    this, flags, privFlags);
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void performHapticFeedbackForInputDevice(int uid, int deviceId, String opPkg,
            int constant, int inputDeviceId, int inputSource, String reason, int flags,
            int privFlags) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "performHapticFeedbackForInputDevice");
        try {
            performHapticFeedbackForInputDeviceInternal(uid, deviceId, opPkg, constant,
                    inputDeviceId,
                    inputSource, reason, /* token= */ this, flags, privFlags);
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    /**
     * An internal-only version of performHapticFeedback that allows the caller access to the
     * {@link HalVibration}.
     * The Vibration is only returned if it is ongoing after this method returns.
     */
    @VisibleForTesting
    @Nullable
    HalVibration performHapticFeedbackInternal(
            int uid, int deviceId, String opPkg, int constant, String reason,
            IBinder token, int flags, int privFlags) {
        // Make sure we report the constant id in the requested haptic feedback reason.
        reason = "performHapticFeedback(constant=" + constant + "): " + reason;
        HapticFeedbackVibrationProvider hapticVibrationProvider = getHapticVibrationProvider();
        Status ignoreStatus = shouldIgnoreHapticFeedback(constant, reason, hapticVibrationProvider);
        if (ignoreStatus != null) {
            logAndRecordPerformHapticFeedbackAttempt(uid, deviceId, opPkg, reason, ignoreStatus);
            return null;
        }
        return performHapticFeedbackWithEffect(uid, deviceId, opPkg, constant, reason, token,
                hapticVibrationProvider.getVibration(constant),
                hapticVibrationProvider.getVibrationAttributes(
                        constant, flags, privFlags));
    }

    /**
     * An internal-only version of performHapticFeedback that allows the caller access to the
     * {@link HalVibration}.
     * The Vibration is only returned if it is ongoing after this method returns.
     */
    @VisibleForTesting
    @Nullable
    HalVibration performHapticFeedbackForInputDeviceInternal(
            int uid, int deviceId, String opPkg, int constant, int inputDeviceId, int inputSource,
            String reason, IBinder token, int flags, int privFlags) {
        // Make sure we report the constant id in the requested haptic feedback reason.
        reason = "performHapticFeedbackForInputDevice(constant=" + constant + ", inputDeviceId="
                + inputDeviceId + ", inputSource=" + inputSource + "): " + reason;
        HapticFeedbackVibrationProvider hapticVibrationProvider = getHapticVibrationProvider();
        Status ignoreStatus = shouldIgnoreHapticFeedback(constant, reason, hapticVibrationProvider);
        if (ignoreStatus != null) {
            logAndRecordPerformHapticFeedbackAttempt(uid, deviceId, opPkg, reason, ignoreStatus);
            return null;
        }
        return performHapticFeedbackWithEffect(uid, deviceId, opPkg, constant, reason, token,
                hapticVibrationProvider.getVibration(constant, inputSource),
                hapticVibrationProvider.getVibrationAttributes(constant, inputSource, flags,
                        privFlags));
    }

    private HalVibration performHapticFeedbackWithEffect(int uid, int deviceId, String opPkg,
            int constant, String reason, IBinder token, VibrationEffect effect,
            VibrationAttributes attrs) {
        if (effect == null) {
            logAndRecordPerformHapticFeedbackAttempt(uid, deviceId, opPkg, reason,
                    Status.IGNORED_UNSUPPORTED);
            Slog.w(TAG,
                    "performHapticFeedbackWithEffect; vibration absent for constant " + constant);
            return null;
        }
        CombinedVibration vib = CombinedVibration.createParallel(effect);
        VibratorFrameworkStatsLogger.logPerformHapticsFeedbackIfKeyboard(uid, constant);
        return vibrateWithoutPermissionCheck(uid, deviceId, opPkg, vib, attrs, reason, token);
    }

    /**
     * An internal-only version of vibrate that allows the caller access to the
     * {@link HalVibration}.
     * The Vibration is only returned if it is ongoing after this method returns.
     */
    @VisibleForTesting
    @Nullable
    HalVibration vibrateWithPermissionCheck(int uid, int deviceId, String opPkg,
            @NonNull CombinedVibration effect, @Nullable VibrationAttributes attrs,
            String reason, IBinder token) {
        attrs = fixupVibrationAttributes(attrs, effect);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.VIBRATE, "vibrate");
        return vibrateInternal(uid, deviceId, opPkg, effect, attrs, reason, token);
    }

    HalVibration vibrateWithoutPermissionCheck(int uid, int deviceId, String opPkg,
            @NonNull CombinedVibration effect, @NonNull VibrationAttributes attrs,
            String reason, IBinder token) {
        return vibrateInternal(uid, deviceId, opPkg, effect, attrs, reason, token);
    }

    private HalVibration vibrateInternal(int uid, int deviceId, String opPkg,
            @NonNull CombinedVibration effect, @NonNull VibrationAttributes attrs,
            String reason, IBinder token) {
        CallerInfo callerInfo = new CallerInfo(attrs, uid, deviceId, opPkg, reason);
        if (token == null) {
            Slog.e(TAG, "token must not be null");
            logAndRecordVibrationAttempt(effect, callerInfo, Status.IGNORED_ERROR_TOKEN);
            return null;
        }
        enforceUpdateAppOpsStatsPermission(uid);
        if (!isEffectValid(effect)) {
            logAndRecordVibrationAttempt(effect, callerInfo, Status.IGNORED_UNSUPPORTED);
            return null;
        }
        if (effect.hasVendorEffects()) {
            if (!Flags.vendorVibrationEffects()) {
                Slog.e(TAG, "vibrate; vendor effects feature disabled");
                logAndRecordVibrationAttempt(effect, callerInfo, Status.IGNORED_UNSUPPORTED);
                return null;
            }
            if (!hasPermission(android.Manifest.permission.VIBRATE_VENDOR_EFFECTS)) {
                Slog.e(TAG, "vibrate; no permission for vendor effects");
                logAndRecordVibrationAttempt(effect, callerInfo, Status.IGNORED_MISSING_PERMISSION);
                return null;
            }
        }
        // Create Vibration.Stats as close to the received request as possible, for tracking.
        SingleVibrationSession session = new SingleVibrationSession(token, callerInfo, effect);
        HalVibration vib = session.getVibration();
        vib.fillFallbacks(mVibrationSettings::getFallbackEffect);

        if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
            // Force update of user settings before checking if this vibration effect should
            // be ignored or scaled.
            mVibrationSettings.update();
        }

        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Starting vibrate for vibration " + vib.id);
            }

            // Check if user settings or DnD is set to ignore this vibration.
            Status ignoreStatus = shouldIgnoreVibrationLocked(callerInfo);
            CallerInfo ignoredBy = null;

            // Check if ongoing vibration is more important than this vibration.
            if (ignoreStatus == null) {
                Vibration.EndInfo vibrationEndInfo = shouldIgnoreForOngoingLocked(session);
                if (vibrationEndInfo != null) {
                    ignoreStatus = vibrationEndInfo.status;
                    ignoredBy = vibrationEndInfo.endedBy;
                }
            }

            // If not ignored so far then try to start this vibration.
            if (ignoreStatus == null) {
                // TODO(b/378492007): Investigate if we can move this around AppOpsManager calls
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mCurrentSession != null) {
                        if (shouldPipelineVibrationLocked(mCurrentSession, vib)) {
                            // Don't cancel the current vibration if it's pipeline-able.
                            // Note that if there is a pending next vibration that can't be
                            // pipelined, it will have already cancelled the current one, so we
                            // don't need to consider it here as well.
                            if (DEBUG) {
                                Slog.d(TAG, "Pipelining vibration " + vib.id);
                            }
                        } else {
                            vib.stats.reportInterruptedAnotherVibration(
                                    mCurrentSession.getCallerInfo());
                            mCurrentSession.requestEnd(Status.CANCELLED_SUPERSEDED, callerInfo,
                                    /* immediate= */ false);
                        }
                    }
                    clearNextSessionLocked(Status.CANCELLED_SUPERSEDED, callerInfo);
                    ignoreStatus = startVibrationLocked(session);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            // Ignored or failed to start the vibration, end it and report metrics right away.
            if (ignoreStatus != null) {
                endSessionLocked(session, ignoreStatus, ignoredBy);
            }
            return vib;
        }
    }

    @Override // Binder call
    public void cancelVibrate(int usageFilter, IBinder token) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "cancelVibrate");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE,
                    "cancelVibrate");

            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration");
                }
                // TODO(b/378492007): Investigate if we can move this around AppOpsManager calls
                final long ident = Binder.clearCallingIdentity();
                try {
                    // TODO(b/370948466): investigate why token not checked on external vibrations.
                    IBinder cancelToken =
                            (mNextSession instanceof ExternalVibrationSession) ? null : token;
                    if (shouldCancelSession(mNextSession, usageFilter, cancelToken)) {
                        clearNextSessionLocked(Status.CANCELLED_BY_USER);
                    }
                    cancelToken =
                            (mCurrentSession instanceof ExternalVibrationSession) ? null : token;
                    if (shouldCancelSession(mCurrentSession, usageFilter, cancelToken)) {
                        mCurrentSession.requestEnd(Status.CANCELLED_BY_USER);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @android.annotation.EnforcePermission(allOf = {
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.VIBRATE_VENDOR_EFFECTS,
            android.Manifest.permission.START_VIBRATION_SESSIONS,
    })
    @Override // Binder call
    public ICancellationSignal startVendorVibrationSession(int uid, int deviceId, String opPkg,
            int[] vibratorIds, VibrationAttributes attrs, String reason,
            IVibrationSessionCallback callback) {
        startVendorVibrationSession_enforcePermission();
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "startVibrationSession");
        try {
            VendorVibrationSession session = startVendorVibrationSessionInternal(
                    uid, deviceId, opPkg, vibratorIds, attrs, reason, callback);
            return session == null ? null : session.getCancellationSignal();
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    VendorVibrationSession startVendorVibrationSessionInternal(int uid, int deviceId, String opPkg,
            int[] vibratorIds, VibrationAttributes attrs, String reason,
            IVibrationSessionCallback callback) {
        if (!Flags.vendorVibrationEffects()) {
            throw new UnsupportedOperationException("Vibration sessions not supported");
        }
        attrs = fixupVibrationAttributes(attrs, /* effect= */ null);
        CallerInfo callerInfo = new CallerInfo(attrs, uid, deviceId, opPkg, reason);
        if (callback == null) {
            Slog.e(TAG, "session callback must not be null");
            logAndRecordSessionAttempt(callerInfo, Status.IGNORED_ERROR_TOKEN);
            return null;
        }
        if (vibratorIds == null) {
            vibratorIds = new int[0];
        }
        enforceUpdateAppOpsStatsPermission(uid);

        // Create session with adapter that only uses the session vibrators.
        SparseArray<VibratorController> sessionVibrators = new SparseArray<>(vibratorIds.length);
        for (int vibratorId : vibratorIds) {
            VibratorController controller = mVibrators.get(vibratorId);
            if (controller != null) {
                sessionVibrators.put(vibratorId, controller);
            }
        }
        DeviceAdapter deviceAdapter = new DeviceAdapter(mVibrationSettings, sessionVibrators);
        VendorVibrationSession session = new VendorVibrationSession(callerInfo, mHandler,
                mVendorVibrationSessionCallbacks, deviceAdapter, callback);

        if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
            // Force update of user settings before checking if this vibration effect should
            // be ignored or scaled.
            mVibrationSettings.update();
        }

        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Starting session " + session.getSessionId());
            }

            Status ignoreStatus = null;
            CallerInfo ignoredBy = null;

            // Check if HAL has capability to start sessions.
            if ((mCapabilities & IVibratorManager.CAP_START_SESSIONS) == 0) {
                if (DEBUG) {
                    Slog.d(TAG, "Missing capability to start sessions, ignoring request");
                }
                ignoreStatus = Status.IGNORED_UNSUPPORTED;
            }

            // Check if vibrator IDs requested are available.
            if (ignoreStatus == null) {
                if (vibratorIds.length == 0
                        || vibratorIds.length != deviceAdapter.getAvailableVibratorIds().length) {
                    Slog.e(TAG, "Bad vibrator ids to start session, ignoring request."
                            + " requested=" + Arrays.toString(vibratorIds)
                            + " available=" + Arrays.toString(mVibratorIds));
                    ignoreStatus = Status.IGNORED_UNSUPPORTED;
                }
            }

            // Check if user settings or DnD is set to ignore this session.
            if (ignoreStatus == null) {
                ignoreStatus = shouldIgnoreVibrationLocked(callerInfo);
            }

            // Check if ongoing vibration is more important than this session.
            if (ignoreStatus == null) {
                Vibration.EndInfo vibrationEndInfo = shouldIgnoreForOngoingLocked(session);
                if (vibrationEndInfo != null) {
                    ignoreStatus = vibrationEndInfo.status;
                    ignoredBy = vibrationEndInfo.endedBy;
                }
            }

            if (ignoreStatus == null) {
                // TODO(b/378492007): Investigate if we can move this around AppOpsManager calls
                final long ident = Binder.clearCallingIdentity();
                try {
                    // If not ignored so far then stop ongoing sessions before starting this one.
                    clearNextSessionLocked(Status.CANCELLED_SUPERSEDED, callerInfo);
                    if (mCurrentSession != null) {
                        mNextSession = session;
                        mCurrentSession.requestEnd(Status.CANCELLED_SUPERSEDED, callerInfo,
                                /* immediate= */ false);
                    } else {
                        ignoreStatus = startVendorSessionLocked(session);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            // Ignored or failed to start the session, end it and report metrics right away.
            if (ignoreStatus != null) {
                endSessionLocked(session, ignoreStatus, ignoredBy);
            }
            return session;
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private Status startVendorSessionLocked(VendorVibrationSession session) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "startSessionLocked");
        try {
            long sessionId = session.getSessionId();
            if (DEBUG) {
                Slog.d(TAG, "Starting session " + sessionId + " in HAL");
            }
            if (session.isEnded()) {
                // Session already ended, possibly cancelled by app cancellation signal.
                return session.getStatus();
            }
            int mode = startAppOpModeLocked(session.getCallerInfo());
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                    Trace.asyncTraceBegin(TRACE_TAG_VIBRATOR, "vibration", 0);
                    // Make sure mCurrentVibration is set while triggering the HAL.
                    mCurrentSession = session;
                    if (!session.linkToDeath()) {
                        mCurrentSession = null;
                        return Status.IGNORED_ERROR_TOKEN;
                    }
                    if (!mNativeWrapper.startSession(sessionId, session.getVibratorIds())) {
                        Slog.e(TAG, "Error starting session " + sessionId + " on vibrators "
                                + Arrays.toString(session.getVibratorIds()));
                        session.unlinkToDeath();
                        mCurrentSession = null;
                        return Status.IGNORED_UNSUPPORTED;
                    }
                    session.notifyStart();
                    return null;
                case AppOpsManager.MODE_ERRORED:
                    Slog.w(TAG, "Start AppOpsManager operation errored for uid "
                            + session.getCallerInfo().uid);
                    return Status.IGNORED_ERROR_APP_OPS;
                default:
                    return Status.IGNORED_APP_OPS;
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

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
                dumpText(pw);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void dumpText(PrintWriter w) {
        if (DEBUG) {
            Slog.d(TAG, "Dumping vibrator manager service to text...");
        }
        IndentingPrintWriter pw = new IndentingPrintWriter(w, /* singleIndent= */ "  ");
        synchronized (mLock) {
            pw.println("VibratorManagerService:");
            pw.increaseIndent();

            mVibrationSettings.dump(pw);
            pw.println();

            mVibrationScaler.dump(pw);
            pw.println();

            pw.println("Vibrators:");
            pw.increaseIndent();
            for (int i = 0; i < mVibrators.size(); i++) {
                mVibrators.valueAt(i).dump(pw);
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("CurrentVibration:");
            pw.increaseIndent();
            if (mCurrentSession != null) {
                mCurrentSession.getDebugInfo().dump(pw);
            } else {
                pw.println("null");
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("NextVibration:");
            pw.increaseIndent();
            if (mNextSession != null) {
                mNextSession.getDebugInfo().dump(pw);
            } else {
                pw.println("null");
            }
            pw.decreaseIndent();
        }

        pw.println();
        pw.println();
        mVibratorManagerRecords.dump(pw);

        pw.println();
        pw.println();
        mVibratorControlService.dump(pw);
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        if (DEBUG) {
            Slog.d(TAG, "Dumping vibrator manager service to proto...");
        }
        synchronized (mLock) {
            mVibrationSettings.dump(proto);
            mVibrationScaler.dump(proto);
            if (mCurrentSession != null) {
                mCurrentSession.getDebugInfo().dump(proto,
                        VibratorManagerServiceDumpProto.CURRENT_VIBRATION);
            }
            for (int i = 0; i < mVibrators.size(); i++) {
                proto.write(VibratorManagerServiceDumpProto.VIBRATOR_IDS, mVibrators.keyAt(i));
            }
        }
        mVibratorManagerRecords.dump(proto);
        mVibratorControlService.dump(proto);
        proto.flush();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback cb, ResultReceiver resultReceiver) {
        new VibratorManagerShellCommand(cb.getShellCallbackBinder())
                .exec(this, in, out, err, args, cb, resultReceiver);
    }

    @VisibleForTesting
    void updateServiceState() {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Updating device state...");
            }
            boolean inputDevicesChanged = mInputDeviceDelegate.updateInputDeviceVibrators(
                    mVibrationSettings.shouldVibrateInputDevices());

            for (int i = 0; i < mAlwaysOnEffects.size(); i++) {
                updateAlwaysOnLocked(mAlwaysOnEffects.valueAt(i));
            }

            // TODO(b/372241975): investigate why external vibrations were not handled here before
            if (mCurrentSession == null
                    || (mCurrentSession instanceof ExternalVibrationSession)) {
                return;
            }

            Status ignoreStatus = shouldIgnoreVibrationLocked(mCurrentSession.getCallerInfo());
            if (inputDevicesChanged || (ignoreStatus != null)) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration because settings changed: "
                            + (inputDevicesChanged ? "input devices changed" : ignoreStatus));
                }
                mCurrentSession.requestEnd(Status.CANCELLED_BY_SETTINGS_UPDATE);
            }
        }
    }

    private void setExternalControl(boolean externalControl, VibrationStats vibrationStats) {
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).setExternalControl(externalControl);
            vibrationStats.reportSetExternalControl();
        }
    }

    @GuardedBy("mLock")
    private void updateAlwaysOnLocked(AlwaysOnVibration vib) {
        for (int i = 0; i < vib.effects.size(); i++) {
            VibratorController vibrator = mVibrators.get(vib.effects.keyAt(i));
            PrebakedSegment effect = vib.effects.valueAt(i);
            if (vibrator == null) {
                continue;
            }
            Status ignoreStatus = shouldIgnoreVibrationLocked(vib.callerInfo);
            if (ignoreStatus == null) {
                effect = mVibrationScaler.scale(effect, vib.callerInfo.attrs.getUsage());
            } else {
                // Vibration should not run, use null effect to remove registered effect.
                effect = null;
            }
            vibrator.updateAlwaysOn(vib.alwaysOnId, effect);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private Status startVibrationLocked(SingleVibrationSession session) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "startVibrationLocked");
        try {
            if (mInputDeviceDelegate.isAvailable()) {
                return startVibrationOnInputDevicesLocked(session.getVibration());
            }
            if (mCurrentSession == null) {
                return startVibrationOnThreadLocked(session);
            }
            // If there's already a vibration queued (waiting for the previous one to finish
            // cancelling), end it cleanly and replace it with the new one.
            // Note that we don't consider pipelining here, because new pipelined ones should
            // replace pending non-executing pipelined ones anyway.
            clearNextSessionLocked(Status.IGNORED_SUPERSEDED, session.getCallerInfo());
            mNextSession = session;
            return null;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private Status startVibrationOnThreadLocked(SingleVibrationSession session) {
        if (DEBUG) {
            Slog.d(TAG, "Starting vibration " + session.getVibration().id +  " on thread");
        }
        VibrationStepConductor conductor = createVibrationStepConductor(session.getVibration());
        session.setVibrationConductor(conductor);
        int mode = startAppOpModeLocked(session.getCallerInfo());
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                Trace.asyncTraceBegin(TRACE_TAG_VIBRATOR, "vibration", 0);
                // Make sure mCurrentVibration is set while triggering the VibrationThread.
                mCurrentSession = session;
                if (!mCurrentSession.linkToDeath()) {
                    // Shouldn't happen. The method call already logs.
                    mCurrentSession = null;  // Aborted.
                    return Status.IGNORED_ERROR_TOKEN;
                }
                if (!mVibrationThread.runVibrationOnVibrationThread(conductor)) {
                    // Shouldn't happen. The method call already logs.
                    session.setVibrationConductor(null); // Rejected by thread, clear it in session.
                    mCurrentSession = null;  // Aborted.
                    return Status.IGNORED_ERROR_SCHEDULING;
                }
                return null;
            case AppOpsManager.MODE_ERRORED:
                Slog.w(TAG, "Start AppOpsManager operation errored for uid "
                        + session.getCallerInfo().uid);
                return Status.IGNORED_ERROR_APP_OPS;
            default:
                return Status.IGNORED_APP_OPS;
        }
    }

    @GuardedBy("mLock")
    private void maybeStartNextSessionLocked() {
        if (mNextSession instanceof SingleVibrationSession session) {
            mNextSession = null;
            Status errorStatus = startVibrationOnThreadLocked(session);
            if (errorStatus != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Error starting next vibration " + session.getVibration().id);
                }
                endSessionLocked(session, errorStatus);
            }
        } else if (mNextSession instanceof VendorVibrationSession session) {
            mNextSession = null;
            Status errorStatus = startVendorSessionLocked(session);
            if (errorStatus != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Error starting next session " + session.getSessionId());
                }
                endSessionLocked(session, errorStatus);
            }
        } // External vibrations cannot be started asynchronously.
    }

    @GuardedBy("mLock")
    private void endSessionLocked(VibrationSession session, Status status) {
        endSessionLocked(session, status, /* endedBy= */ null);
    }

    @GuardedBy("mLock")
    private void endSessionLocked(VibrationSession session, Status status, CallerInfo endedBy) {
        session.requestEnd(status, endedBy, /* immediate= */ false);
        logAndRecordVibration(session.getDebugInfo());
    }

    private VibrationStepConductor createVibrationStepConductor(HalVibration vib) {
        return createVibrationStepConductor(vib, mDeviceAdapter, /* isInSession= */ false);
    }

    private VibrationStepConductor createSessionVibrationStepConductor(HalVibration vib,
            DeviceAdapter deviceAdapter) {
        return createVibrationStepConductor(vib, deviceAdapter, /* isInSession= */ true);
    }

    private VibrationStepConductor createVibrationStepConductor(HalVibration vib,
            DeviceAdapter deviceAdapter, boolean isInSession) {
        CompletableFuture<Void> requestVibrationParamsFuture = null;

        if (Flags.adaptiveHapticsEnabled()
                && mVibratorControlService.shouldRequestVibrationParams(
                vib.callerInfo.attrs.getUsage())) {
            requestVibrationParamsFuture =
                    mVibratorControlService.triggerVibrationParamsRequest(
                            vib.callerInfo.uid, vib.callerInfo.attrs.getUsage(),
                            mVibrationSettings.getRequestVibrationParamsTimeoutMs());
        }

        return new VibrationStepConductor(vib, isInSession, mVibrationSettings,
                deviceAdapter, mVibrationScaler, mFrameworkStatsLogger,
                requestVibrationParamsFuture, mVibrationThreadCallbacks);
    }

    private Status startVibrationOnInputDevicesLocked(HalVibration vib) {
        // Scale resolves the default amplitudes from the effect before scaling them.
        vib.scaleEffects(mVibrationScaler);
        mInputDeviceDelegate.vibrateIfAvailable(vib.callerInfo, vib.getEffectToPlay());
        return Status.FORWARDED_TO_INPUT_DEVICES;
    }

    private void logAndRecordPerformHapticFeedbackAttempt(int uid, int deviceId, String opPkg,
            String reason, Status status) {
        CallerInfo callerInfo = new CallerInfo(
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_UNKNOWN),
                uid, deviceId, opPkg, reason);
        logAndRecordVibrationAttempt(/* effect= */ null, callerInfo, status);
    }

    private void logAndRecordVibrationAttempt(@Nullable CombinedVibration effect,
            CallerInfo callerInfo, Status status) {
        logAndRecordVibration(createVibrationAttemptDebugInfo(effect, callerInfo, status));
    }

    private void logAndRecordSessionAttempt(CallerInfo callerInfo, Status status) {
        logAndRecordVibration(
                new VendorVibrationSession.DebugInfoImpl(status, callerInfo,
                        SystemClock.uptimeMillis(), System.currentTimeMillis(),
                        /* startTime= */ 0, /* endUptime= */ 0, /* endTime= */ 0,
                        /* endedByVendor= */ false, /* vibrations= */ null));
    }

    private void logAndRecordVibration(DebugInfo info) {
        info.logMetrics(mFrameworkStatsLogger);
        logVibrationStatus(info.getCallerInfo().uid, info.getCallerInfo().attrs, info.getStatus());
        mVibratorManagerRecords.record(info);
    }

    private DebugInfo createVibrationAttemptDebugInfo(@Nullable CombinedVibration effect,
            CallerInfo callerInfo, Status status) {
        return new Vibration.DebugInfoImpl(status, callerInfo,
                VibrationStats.StatsInfo.findVibrationType(effect), new VibrationStats(),
                effect, /* originalEffect= */ null, VibrationScaler.SCALE_NONE,
                VibrationScaler.ADAPTIVE_SCALE_NONE);
    }

    private void logVibrationStatus(int uid, VibrationAttributes attrs, Status status) {
        switch (status) {
            case IGNORED_BACKGROUND:
                Slog.e(TAG, "Ignoring incoming vibration as process with"
                        + " uid= " + uid + " is background," + " attrs= " + attrs);
                break;
            case IGNORED_ERROR_APP_OPS:
                Slog.w(TAG, "Would be an error: vibrate from uid " + uid);
                break;
            case IGNORED_FOR_EXTERNAL:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
                }
                break;
            case IGNORED_FOR_HIGHER_IMPORTANCE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of ongoing vibration"
                            + " with higher importance");
                }
                break;
            case IGNORED_FOR_ONGOING:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of repeating vibration");
                }
                break;
            case IGNORED_FOR_RINGER_MODE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration because of ringer mode, attrs="
                            + attrs);
                }
                break;
            case IGNORED_FROM_VIRTUAL_DEVICE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration because it came from a virtual"
                            + " device, attrs= " + attrs);
                }
                break;
            default:
                if (DEBUG) {
                    Slog.d(TAG, "Vibration for uid=" + uid + " and with attrs=" + attrs
                            + " ended with status " + status);
                }
        }
    }

    private void onVibrationSessionComplete(long sessionId) {
        synchronized (mLock) {
            if (mCurrentSession == null || mCurrentSession.getSessionId() != sessionId) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration session " + sessionId + " callback ignored");
                }
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "Vibration session " + sessionId + " complete, notifying session");
            }
            mCurrentSession.notifySessionCallback();
        }
    }

    private void onSyncedVibrationComplete(long vibrationId) {
        synchronized (mLock) {
            if (mCurrentSession != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Synced vibration " + vibrationId + " complete, notifying thread");
                }
                mCurrentSession.notifySyncedVibratorsCallback(vibrationId);
            }
        }
    }

    private void onVibrationComplete(int vibratorId, long vibrationId) {
        synchronized (mLock) {
            if (mCurrentSession != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration " + vibrationId + " on vibrator " + vibratorId
                            + " complete, notifying thread");
                }
                mCurrentSession.notifyVibratorCallback(vibratorId, vibrationId);
            }
        }
    }

    /**
     * Check if given vibration should be ignored by this service because of the ongoing vibration.
     *
     * @return a Vibration.EndInfo if the vibration should be ignored, null otherwise.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.EndInfo shouldIgnoreForOngoingLocked(VibrationSession session) {
        if (mNextSession != null) {
            Vibration.EndInfo vibrationEndInfo = shouldIgnoreForOngoing(session,
                    mNextSession);
            if (vibrationEndInfo != null) {
                // Next vibration has higher importance than the new one, so the new vibration
                // should be ignored.
                return vibrationEndInfo;
            }
        }

        if (mCurrentSession != null) {
            if (mCurrentSession.wasEndRequested()) {
                // Current session has ended or is cancelling, should not block incoming vibrations.
                return null;
            }

            return shouldIgnoreForOngoing(session, mCurrentSession);
        }

        return null;
    }

    /**
     * Checks if the ongoing vibration has higher importance than the new one. If they have similar
     * importance, then {@link VibrationSession#isRepeating()} is used as a tiebreaker.
     *
     * @return a Vibration.EndInfo if the vibration should be ignored, null otherwise.
     */
    @Nullable
    private static Vibration.EndInfo shouldIgnoreForOngoing(
            @NonNull VibrationSession newSession, @NonNull VibrationSession ongoingSession) {

        int newSessionImportance = getVibrationImportance(newSession);
        int ongoingSessionImportance = getVibrationImportance(ongoingSession);

        if (newSessionImportance > ongoingSessionImportance) {
            // New vibration has higher importance and should not be ignored.
            return null;
        }

        if (ongoingSessionImportance > newSessionImportance) {
            // Existing vibration has higher importance and should not be cancelled.
            return new Vibration.EndInfo(Status.IGNORED_FOR_HIGHER_IMPORTANCE,
                    ongoingSession.getCallerInfo());
        }

        // Same importance, use repeating as a tiebreaker.
        if (ongoingSession.isRepeating() && !newSession.isRepeating()) {
            // Ongoing vibration is repeating and new one is not, give priority to ongoing
            return new Vibration.EndInfo(Status.IGNORED_FOR_ONGOING,
                    ongoingSession.getCallerInfo());
        }
        // New vibration is repeating or this is a complete tie between them,
        // give priority to new vibration.
        return null;
    }

    /**
     * Gets the vibration importance based on usage. In the case where usage is unknown, it maps
     * repeating vibrations to ringtones and non-repeating vibrations to touches.
     *
     * @return a numeric representation for the vibration importance, larger values represent a
     * higher importance
     */
    private static int getVibrationImportance(VibrationSession session) {
        int usage = session.getCallerInfo().attrs.getUsage();
        if (usage == VibrationAttributes.USAGE_UNKNOWN) {
            if (session.isRepeating()) {
                usage = VibrationAttributes.USAGE_RINGTONE;
            } else {
                usage = VibrationAttributes.USAGE_TOUCH;
            }
        }

        switch (usage) {
            case VibrationAttributes.USAGE_RINGTONE:
                return 5;
            case VibrationAttributes.USAGE_ALARM:
                return 4;
            case VibrationAttributes.USAGE_NOTIFICATION:
                return 3;
            case VibrationAttributes.USAGE_COMMUNICATION_REQUEST:
            case VibrationAttributes.USAGE_ACCESSIBILITY:
                return 2;
            case VibrationAttributes.USAGE_HARDWARE_FEEDBACK:
            case VibrationAttributes.USAGE_PHYSICAL_EMULATION:
                return 1;
            case VibrationAttributes.USAGE_MEDIA:
            case VibrationAttributes.USAGE_TOUCH:
            default:
                return 0;
        }
    }

    /** Returns true if ongoing session should pipeline with the next vibration requested. */
    @GuardedBy("mLock")
    private boolean shouldPipelineVibrationLocked(VibrationSession currentSession,
            HalVibration nextVibration) {
        if (!(currentSession instanceof SingleVibrationSession currentVibration)) {
            // Only single vibration session can be pipelined.
            return false;
        }
        return currentVibration.getVibration().canPipelineWith(nextVibration, mVibratorInfos,
                mVibrationConfig.getVibrationPipelineMaxDurationMs());
    }

    /**
     * Check if given vibration should be ignored by this service.
     *
     * @return a Vibration.EndInfo if the vibration should be ignored, null otherwise.
     */
    @GuardedBy("mLock")
    @Nullable
    private Status shouldIgnoreVibrationLocked(CallerInfo callerInfo) {
        Status statusFromSettings = mVibrationSettings.shouldIgnoreVibration(callerInfo);
        if (statusFromSettings != null) {
            return statusFromSettings;
        }

        int mode = checkAppOpModeLocked(callerInfo);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                return Status.IGNORED_ERROR_APP_OPS;
            } else {
                return Status.IGNORED_APP_OPS;
            }
        }

        return null;
    }

    @Nullable
    private Status shouldIgnoreHapticFeedback(int constant, String reason,
            HapticFeedbackVibrationProvider hapticVibrationProvider) {
        if (hapticVibrationProvider == null) {
            Slog.e(TAG, reason + "; haptic vibration provider not ready.");
            return Status.IGNORED_ERROR_SCHEDULING;
        }
        if (hapticVibrationProvider.isRestrictedHapticFeedback(constant)
                && !hasPermission(android.Manifest.permission.VIBRATE_SYSTEM_CONSTANTS)) {
            Slog.w(TAG, reason + "; no permission for system constant " + constant);
            return Status.IGNORED_MISSING_PERMISSION;
        }
        return null;
    }

    /**
     * Return true if the vibration has the same token and usage belongs to given usage class.
     *
     * @param session     The ongoing or pending vibration session to be cancelled.
     * @param usageFilter The vibration usages to be cancelled, any bitwise combination of
     *                    VibrationAttributes.USAGE_* values.
     * @param tokenFilter The binder token to identify the vibration origin. Only vibrations
     *                    started with the same token can be cancelled with it.
     */
    private boolean shouldCancelSession(@Nullable VibrationSession session, int usageFilter,
            @Nullable IBinder tokenFilter) {
        if (session == null) {
            return false;
        }
        if (session instanceof VendorVibrationSession) {
            // Vendor sessions should not be cancelled by Vibrator.cancel API.
            return false;
        }
        if ((tokenFilter != null) && (tokenFilter != session.getCallerToken())) {
            // Vibration from a different app, this should not cancel it.
            return false;
        }
        int usage = session.getCallerInfo().attrs.getUsage();
        if (usage == VibrationAttributes.USAGE_UNKNOWN) {
            // Special case, usage UNKNOWN would match all filters. Instead it should only match if
            // it's cancelling that usage specifically, or if cancelling all usages.
            return usageFilter == VibrationAttributes.USAGE_UNKNOWN
                    || usageFilter == VibrationAttributes.USAGE_FILTER_MATCH_ALL;
        }
        return (usageFilter & usage) == usage;
    }

    /**
     * Check which mode should be set for a vibration with given {@code uid}, {@code opPkg} and
     * {@code attrs}. This will return one of the AppOpsManager.MODE_*.
     */
    @GuardedBy("mLock")
    private int checkAppOpModeLocked(CallerInfo callerInfo) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                callerInfo.attrs.getAudioUsage(), callerInfo.uid, callerInfo.opPkg);
        int fixedMode = fixupAppOpModeLocked(mode, callerInfo.attrs);
        if (mode != fixedMode && fixedMode == AppOpsManager.MODE_ALLOWED) {
            // If we're just ignoring the vibration op then this is set by DND and we should ignore
            // if we're asked to bypass. AppOps won't be able to record this operation, so make
            // sure we at least note it in the logs for debugging.
            Slog.d(TAG, "Bypassing DND for vibrate from uid " + callerInfo.uid);
        }
        return fixedMode;
    }

    /** Start an operation in {@link AppOpsManager}, if allowed. */
    @GuardedBy("mLock")
    private int startAppOpModeLocked(CallerInfo callerInfo) {
        return fixupAppOpModeLocked(
                mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, callerInfo.uid, callerInfo.opPkg),
                callerInfo.attrs);
    }

    /**
     * Finish a previously started operation in {@link AppOpsManager}. This will be a noop if no
     * operation with same uid was previously started.
     */
    @GuardedBy("mLock")
    private void finishAppOpModeLocked(CallerInfo callerInfo) {
        mAppOps.finishOp(AppOpsManager.OP_VIBRATE, callerInfo.uid, callerInfo.opPkg);
    }

    /**
     * Enforces {@link android.Manifest.permission#UPDATE_APP_OPS_STATS} to incoming UID if it's
     * different from the calling UID.
     */
    private void enforceUpdateAppOpsStatsPermission(int uid) {
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
     * Validate the incoming {@link CombinedVibration}.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the CombinedVibrationEffect is non-null and valid
     */
    private static boolean isEffectValid(@Nullable CombinedVibration effect) {
        if (effect == null) {
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying vibration: " + effect, e);
            return false;
        }
        return true;
    }

    /**
     * Return new {@link VibrationAttributes} that only applies flags that this user has permissions
     * to use.
     */
    @NonNull
    private VibrationAttributes fixupVibrationAttributes(@Nullable VibrationAttributes attrs,
            @Nullable CombinedVibration effect) {
        if (attrs == null) {
            attrs = DEFAULT_ATTRIBUTES;
        }
        int usage = attrs.getUsage();
        if ((usage == VibrationAttributes.USAGE_UNKNOWN)
                && (effect != null) && effect.isHapticFeedbackCandidate()) {
            usage = VibrationAttributes.USAGE_TOUCH;
        }
        int flags = attrs.getFlags();
        if ((flags & ATTRIBUTES_ALL_BYPASS_FLAGS) != 0) {
            if (!(hasPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    || hasPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                    || hasPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
                // Remove bypass flags from attributes if the app does not have permissions.
                flags &= ~ATTRIBUTES_ALL_BYPASS_FLAGS;
            }
        }
        if ((usage == attrs.getUsage()) && (flags == attrs.getFlags())) {
            return attrs;
        }
        return new VibrationAttributes.Builder(attrs)
                .setUsage(usage)
                .setFlags(flags, attrs.getFlags())
                .build();
    }

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<PrebakedSegment> fixupAlwaysOnEffectsLocked(CombinedVibration effect) {
        SparseArray<VibrationEffect> effects;
        if (effect instanceof CombinedVibration.Mono) {
            VibrationEffect syncedEffect = ((CombinedVibration.Mono) effect).getEffect();
            effects = transformAllVibratorsLocked(unused -> syncedEffect);
        } else if (effect instanceof CombinedVibration.Stereo) {
            effects = ((CombinedVibration.Stereo) effect).getEffects();
        } else {
            // Only synced combinations can be used for always-on effects.
            return null;
        }
        SparseArray<PrebakedSegment> result = new SparseArray<>();
        for (int i = 0; i < effects.size(); i++) {
            PrebakedSegment prebaked = extractPrebakedSegment(effects.valueAt(i));
            if (prebaked == null) {
                Slog.e(TAG, "Only prebaked effects supported for always-on.");
                return null;
            }
            int vibratorId = effects.keyAt(i);
            VibratorController vibrator = mVibrators.get(vibratorId);
            if (vibrator != null && vibrator.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                result.put(vibratorId, prebaked);
            }
        }
        if (result.size() == 0) {
            return null;
        }
        return result;
    }

    @Nullable
    private static PrebakedSegment extractPrebakedSegment(VibrationEffect effect) {
        if (effect instanceof VibrationEffect.Composed composed) {
            if (composed.getSegments().size() == 1) {
                VibrationEffectSegment segment = composed.getSegments().get(0);
                if (segment instanceof PrebakedSegment prebaked) {
                    return prebaked;
                }
            }
        }
        return null;
    }

    /**
     * Check given mode, one of the AppOpsManager.MODE_*, against {@link VibrationAttributes} to
     * allow bypassing {@link AppOpsManager} checks.
     */
    @GuardedBy("mLock")
    private int fixupAppOpModeLocked(int mode, VibrationAttributes attrs) {
        if (mode == AppOpsManager.MODE_IGNORED
                && attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
            return AppOpsManager.MODE_ALLOWED;
        }
        return mode;
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @GuardedBy("mLock")
    private boolean shouldCancelOnScreenOffLocked(@Nullable VibrationSession session) {
        if (session == null) {
            return false;
        }
        return mVibrationSettings.shouldCancelVibrationOnScreenOff(session.getCallerInfo(),
                session.getCreateUptimeMillis());
    }

    @GuardedBy("mLock")
    private boolean shouldCancelAppOpModeChangedLocked(@Nullable VibrationSession session) {
        if (session == null) {
            return false;
        }
        return checkAppOpModeLocked(session.getCallerInfo()) != AppOpsManager.MODE_ALLOWED;
    }

    private boolean shouldCancelOnFgUserRequest(@Nullable VibrationSession session) {
        if (session == null) {
            return false;
        }
        return session.getCallerInfo().attrs.getUsageClass() == USAGE_CLASS_ALARM;
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

        IBatteryStats getBatteryStatsService() {
            return IBatteryStats.Stub.asInterface(ServiceManager.getService(
                    BatteryStats.SERVICE_NAME));
        }

        VibratorFrameworkStatsLogger getFrameworkStatsLogger(Handler handler) {
            return new VibratorFrameworkStatsLogger(handler);
        }

        VibratorController createVibratorController(int vibratorId,
                VibratorController.OnVibrationCompleteListener listener) {
            return new VibratorController(vibratorId, listener);
        }

        HapticFeedbackVibrationProvider createHapticFeedbackVibrationProvider(
                Resources resources, VibratorInfo vibratorInfo) {
            return new HapticFeedbackVibrationProvider(resources, vibratorInfo);
        }

        void addService(String name, IBinder service) {
            ServiceManager.addService(name, service);
        }

        VibratorControllerHolder createVibratorControllerHolder() {
            return new VibratorControllerHolder();
        }

        boolean isServiceDeclared(String name) {
            return ServiceManager.isDeclared(name);
        }
    }

    /**
     * Implementation of {@link VibrationThread.VibratorManagerHooks} that controls synced
     * vibrations and reports them when finished.
     */
    private final class VibrationThreadCallbacks implements VibrationThread.VibratorManagerHooks {

        @Override
        public boolean prepareSyncedVibration(long requiredCapabilities, int[] vibratorIds) {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "prepareSyncedVibration");
            try {
                if ((mCapabilities & requiredCapabilities) != requiredCapabilities) {
                    // This sync step requires capabilities this device doesn't have, skipping
                    // sync...
                    return false;
                }
                return mNativeWrapper.prepareSynced(vibratorIds);
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public boolean triggerSyncedVibration(long vibrationId) {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "triggerSyncedVibration");
            try {
                return mNativeWrapper.triggerSynced(vibrationId);
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void cancelSyncedVibration() {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "cancelSyncedVibration");
            try {
                mNativeWrapper.cancelSynced();
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void noteVibratorOn(int uid, long duration) {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "noteVibratorOn");
            try {
                if (duration <= 0) {
                    // Tried to turn vibrator ON and got:
                    // duration == 0: Unsupported effect/method or zero-amplitude segment.
                    // duration < 0: Unexpected error triggering the vibrator.
                    // Skip battery stats and atom metric for VibratorStageChanged to ON.
                    return;
                }
                if (duration == Long.MAX_VALUE) {
                    // Repeating duration has started. Report a fixed duration here, noteVibratorOff
                    // should be called when this is cancelled.
                    duration = BATTERY_STATS_REPEATING_VIBRATION_DURATION;
                }
                mBatteryStatsService.noteVibratorOn(uid, duration);
                mFrameworkStatsLogger.writeVibratorStateOnAsync(uid, duration);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error logging VibratorStateChanged to ON", e);
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void noteVibratorOff(int uid) {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "noteVibratorOff");
            try {
                mBatteryStatsService.noteVibratorOff(uid);
                mFrameworkStatsLogger.writeVibratorStateOffAsync(uid);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error logging VibratorStateChanged to OFF", e);
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void onVibrationThreadReleased(long vibrationId) {
            if (DEBUG) {
                Slog.d(TAG, "VibrationThread released vibration " + vibrationId);
            }
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "onVibrationThreadReleased");
            try {
                synchronized (mLock) {
                    if (mCurrentSession instanceof SingleVibrationSession session) {
                        if (Build.IS_DEBUGGABLE && (session.getVibration().id != vibrationId)) {
                            Slog.wtf(TAG, TextUtils.formatSimple(
                                    "VibrationId mismatch on vibration thread release."
                                            + " expected=%d, released=%d",
                                    session.getVibration().id, vibrationId));
                        }
                        finishAppOpModeLocked(mCurrentSession.getCallerInfo());
                        clearCurrentSessionLocked();
                        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                        // Start next vibration if it's waiting for the thread.
                        maybeStartNextSessionLocked();
                    } else if (mCurrentSession instanceof VendorVibrationSession session) {
                        VibrationStepConductor conductor = session.clearVibrationConductor();
                        if (Build.IS_DEBUGGABLE) {
                            if (conductor == null) {
                                Slog.wtf(TAG, "Vendor session without ongoing vibration on"
                                        + " thread release. currentSession=" + mCurrentSession);
                            } else if (conductor.getVibration().id != vibrationId) {
                                Slog.wtf(TAG, TextUtils.formatSimple(
                                        "VibrationId mismatch on vibration thread release."
                                                + " expected=%d, released=%d",
                                        conductor.getVibration().id, vibrationId));
                            }
                        }
                    } else if (Build.IS_DEBUGGABLE) {
                        Slog.wtf(TAG, "VibrationSession invalid on vibration thread release."
                                + " currentSession=" + mCurrentSession);
                    }
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }
    }

    /**
     * Implementation of {@link ExternalVibrationSession.VibratorManagerHooks} that controls
     * external vibrations and reports them when finished.
     */
    private final class ExternalVibrationCallbacks
            implements ExternalVibrationSession.VibratorManagerHooks {

        @Override
        public void onExternalVibrationReleased(long vibrationId) {
            if (DEBUG) {
                Slog.d(TAG, "External vibration " + vibrationId + " released");
            }
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "onExternalVibrationReleased");
            try {
                synchronized (mLock) {
                    if (!(mCurrentSession instanceof ExternalVibrationSession session)) {
                        if (Build.IS_DEBUGGABLE) {
                            Slog.wtf(TAG, "VibrationSession invalid on external vibration release."
                                    + " currentSession=" + mCurrentSession);
                        }
                        // Only external vibration sessions are ended by this callback. Abort.
                        return;
                    }
                    if (Build.IS_DEBUGGABLE && (session.id != vibrationId)) {
                        Slog.wtf(TAG, TextUtils.formatSimple(
                                "VibrationId mismatch on external vibration release."
                                        + " expected=%d, released=%d", session.id, vibrationId));
                    }
                    setExternalControl(false, session.stats);
                    clearCurrentSessionLocked();
                    // Start next vibration if it's waiting for the external control to be over.
                    maybeStartNextSessionLocked();
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }
    }

    /**
     * Implementation of {@link ExternalVibrationSession.VibratorManagerHooks} that controls
     * external vibrations and reports them when finished.
     */
    private final class VendorVibrationSessionCallbacks
            implements VendorVibrationSession.VibratorManagerHooks {

        @Override
        public void vibrate(long sessionId, CallerInfo callerInfo, CombinedVibration effect) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration session " + sessionId + " vibration requested");
            }
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "sessionVibrate");
            try {
                synchronized (mLock) {
                    if (!(mCurrentSession instanceof VendorVibrationSession session)) {
                        if (Build.IS_DEBUGGABLE) {
                            Slog.wtf(TAG, "VibrationSession invalid on session vibrate."
                                    + " currentSession=" + mCurrentSession);
                        }
                        // Only vendor vibration sessions can handle this call. Abort.
                        return;
                    }
                    if (session.getSessionId() != sessionId) {
                        if (Build.IS_DEBUGGABLE) {
                            Slog.wtf(TAG, TextUtils.formatSimple(
                                    "SessionId mismatch on vendor vibration session vibrate."
                                            + " expected=%d, released=%d",
                                    session.getSessionId(), sessionId));
                        }
                        // Only the ongoing vendor vibration sessions can handle this call. Abort.
                        return;
                    }
                    if (session.wasEndRequested()) {
                        if (DEBUG) {
                            Slog.d(TAG, "session vibrate; session is ending, vibration ignored");
                        }
                        session.notifyVibrationAttempt(createVibrationAttemptDebugInfo(effect,
                                callerInfo, Status.IGNORED_ERROR_SCHEDULING));
                        return;
                    }
                    if (!isEffectValid(effect)) {
                        session.notifyVibrationAttempt(createVibrationAttemptDebugInfo(effect,
                                callerInfo, Status.IGNORED_UNSUPPORTED));
                        return;
                    }
                    if (effect.getDuration() == Long.MAX_VALUE) {
                        // Repeating effects cannot be played by the service in a session.
                        session.notifyVibrationAttempt(createVibrationAttemptDebugInfo(effect,
                                callerInfo, Status.IGNORED_UNSUPPORTED));
                        return;
                    }
                    // Create Vibration.Stats as close to the request as possible, for tracking.
                    HalVibration vib = new HalVibration(callerInfo, effect);
                    vib.fillFallbacks(mVibrationSettings::getFallbackEffect);

                    if (callerInfo.attrs.isFlagSet(
                            VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
                        // Force update of user settings before checking if this vibration effect
                        // should be ignored or scaled.
                        mVibrationSettings.update();
                    }

                    if (DEBUG) {
                        Slog.d(TAG, "Starting vibrate for vibration " + vib.id
                                + " in session " + sessionId);
                    }

                    VibrationStepConductor conductor =
                            createSessionVibrationStepConductor(vib, session.getDeviceAdapter());
                    if (session.maybeSetVibrationConductor(conductor)) {
                        if (!mVibrationThread.runVibrationOnVibrationThread(conductor)) {
                            // Shouldn't happen. The method call already logs.
                            vib.end(new Vibration.EndInfo(Status.IGNORED_ERROR_SCHEDULING));
                            session.clearVibrationConductor(); // Rejected by thread, clear it.
                        }
                    } else {
                        // Cannot set vibration in session, log failed attempt.
                        session.notifyVibrationAttempt(createVibrationAttemptDebugInfo(effect,
                                callerInfo, Status.IGNORED_ERROR_SCHEDULING));
                    }
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void endSession(long sessionId, boolean shouldAbort) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration session " + sessionId
                        + (shouldAbort ? " aborting" : " ending"));
            }
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "endSession");
            try {
                mNativeWrapper.endSession(sessionId, shouldAbort);
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void onSessionReleased(long sessionId) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration session " + sessionId + " released");
            }
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "onVendorSessionReleased");
            try {
                synchronized (mLock) {
                    if (!(mCurrentSession instanceof VendorVibrationSession session)) {
                        if (Build.IS_DEBUGGABLE) {
                            Slog.wtf(TAG, "VibrationSession invalid on vibration session release."
                                    + " currentSession=" + mCurrentSession);
                        }
                        // Only vendor vibration sessions are ended by this callback. Abort.
                        return;
                    }
                    if (Build.IS_DEBUGGABLE && (session.getSessionId() != sessionId)) {
                        Slog.wtf(TAG, TextUtils.formatSimple(
                                "SessionId mismatch on vendor vibration session release."
                                        + " expected=%d, released=%d",
                                session.getSessionId(), sessionId));
                    }
                    // Make sure all controllers in session are reset after session ended.
                    // This will update the vibrator state to isVibrating = false for listeners.
                    for (int vibratorId : session.getVibratorIds()) {
                        mVibrators.get(vibratorId).off();
                    }
                    finishAppOpModeLocked(mCurrentSession.getCallerInfo());
                    clearCurrentSessionLocked();
                    // Start next vibration if it's waiting for the HAL session to be over.
                    maybeStartNextSessionLocked();
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }
    }

    /** Listener for vibrator manager completion callbacks from native. */
    @VisibleForTesting
    interface VibratorManagerNativeCallbacks {

        /** Callback triggered when synced vibration is complete. */
        void onSyncedVibrationComplete(long vibrationId);

        /** Callback triggered when vibration session is complete. */
        void onVibrationSessionComplete(long sessionId);
    }

    /**
     * Implementation of listeners to native vibrators with a weak reference to this service.
     */
    private static final class VibrationCompleteListener implements
            VibratorController.OnVibrationCompleteListener, VibratorManagerNativeCallbacks {
        private WeakReference<VibratorManagerService> mServiceRef;

        VibrationCompleteListener(VibratorManagerService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onSyncedVibrationComplete(long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onSyncedVibrationComplete(vibrationId);
            }
        }

        @Override
        public void onVibrationSessionComplete(long sessionId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onVibrationSessionComplete(sessionId);
            }
        }

        @Override
        public void onComplete(int vibratorId, long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onVibrationComplete(vibratorId, vibrationId);
            }
        }
    }

    /**
     * Combination of prekabed vibrations on multiple vibrators, with the same {@link
     * VibrationAttributes}, that can be set for always-on effects.
     */
    private static final class AlwaysOnVibration {
        public final int alwaysOnId;
        public final CallerInfo callerInfo;
        public final SparseArray<PrebakedSegment> effects;

        AlwaysOnVibration(int alwaysOnId, CallerInfo callerInfo,
                SparseArray<PrebakedSegment> effects) {
            this.alwaysOnId = alwaysOnId;
            this.callerInfo = callerInfo;
            this.effects = effects;
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorManagerService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Returns native pointer to newly created controller and connects with HAL service. */
        public void init(VibratorManagerNativeCallbacks listener) {
            mNativeServicePtr = nativeInit(listener);
            long finalizerPtr = nativeGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorManagerService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Returns manager capabilities. */
        public long getCapabilities() {
            return nativeGetCapabilities(mNativeServicePtr);
        }

        /** Returns vibrator ids. */
        public int[] getVibratorIds() {
            return nativeGetVibratorIds(mNativeServicePtr);
        }

        /** Prepare vibrators for triggering vibrations in sync. */
        public boolean prepareSynced(@NonNull int[] vibratorIds) {
            return nativePrepareSynced(mNativeServicePtr, vibratorIds);
        }

        /** Trigger prepared synced vibration. */
        public boolean triggerSynced(long vibrationId) {
            return nativeTriggerSynced(mNativeServicePtr, vibrationId);
        }

        /** Cancel prepared synced vibration. */
        public void cancelSynced() {
            nativeCancelSynced(mNativeServicePtr);
        }

        /** Start vibration session. */
        public boolean startSession(long sessionId, @NonNull int[] vibratorIds) {
            return nativeStartSession(mNativeServicePtr, sessionId, vibratorIds);
        }

        /** End vibration session. */
        public void endSession(long sessionId, boolean shouldAbort) {
            nativeEndSession(mNativeServicePtr, sessionId, shouldAbort);
        }

        /** Clear vibration sessions. */
        public void clearSessions() {
            nativeClearSessions(mNativeServicePtr);
        }
    }

    /** Keep records of vibrations played and provide debug information for this service. */
    private static final class VibratorManagerRecords {
        private final VibrationRecords mAggregatedVibrationHistory;
        private final VibrationRecords mRecentVibrations;

        VibratorManagerRecords(int recentVibrationSizeLimit, int aggregationSizeLimit,
                int aggregationTimeLimit) {
            mAggregatedVibrationHistory =
                    new VibrationRecords(aggregationSizeLimit, aggregationTimeLimit);
            // Recent vibrations are not aggregated, to help debugging issues that just happened.
            mRecentVibrations =
                    new VibrationRecords(recentVibrationSizeLimit, /* aggregationTimeLimit= */ 0);
        }

        synchronized void record(DebugInfo info) {
            GroupedAggregatedLogRecords.AggregatedLogRecord<VibrationRecord> droppedRecord =
                    mRecentVibrations.add(new VibrationRecord(info));
            if (droppedRecord != null) {
                // Move dropped record from recent list to aggregated history list.
                mAggregatedVibrationHistory.add(droppedRecord.getLatest());
            }
        }

        synchronized void dump(IndentingPrintWriter pw) {
            pw.println("Recent vibrations:");
            pw.increaseIndent();
            mRecentVibrations.dump(pw);
            pw.decreaseIndent();

            pw.println();
            pw.println();
            pw.println("Aggregated vibration history:");
            pw.increaseIndent();
            mAggregatedVibrationHistory.dump(pw);
            pw.decreaseIndent();
        }

        synchronized void dump(ProtoOutputStream proto) {
            mRecentVibrations.dump(proto);
        }
    }

    /** Keep records of vibrations played and provide debug information for this service. */
    private static final class VibrationRecords
            extends GroupedAggregatedLogRecords<VibrationRecord> {

        VibrationRecords(int sizeLimit, int aggregationTimeLimit) {
            super(sizeLimit, aggregationTimeLimit);
        }

        @Override
        void dumpGroupHeader(IndentingPrintWriter pw, int usage) {
            pw.println(VibrationAttributes.usageToString(usage) + ":");
        }

        @Override
        long findGroupKeyProtoFieldId(int usage) {
            return switch (usage) {
                case VibrationAttributes.USAGE_RINGTONE ->
                    VibratorManagerServiceDumpProto.PREVIOUS_RING_VIBRATIONS;
                case VibrationAttributes.USAGE_NOTIFICATION ->
                    VibratorManagerServiceDumpProto.PREVIOUS_NOTIFICATION_VIBRATIONS;
                case VibrationAttributes.USAGE_ALARM ->
                    VibratorManagerServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS;
                default ->
                    VibratorManagerServiceDumpProto.PREVIOUS_VIBRATIONS;
            };
        }
    }

    /**
     * Record for a single {@link DebugInfo}, that can be grouped by usage and aggregated by UID,
     * {@link VibrationAttributes} and {@link CombinedVibration}.
     */
    private static final class VibrationRecord
            implements GroupedAggregatedLogRecords.SingleLogRecord {
        private final DebugInfo mInfo;

        VibrationRecord(DebugInfo info) {
            mInfo = info;
        }

        @Override
        public int getGroupKey() {
            return mInfo.getCallerInfo().attrs.getUsage();
        }

        @Override
        public long getCreateUptimeMs() {
            return mInfo.getCreateUptimeMillis();
        }

        @Override
        public boolean mayAggregate(GroupedAggregatedLogRecords.SingleLogRecord record) {
            if (!(record instanceof VibrationRecord)) {
                return false;
            }
            DebugInfo info = ((VibrationRecord) record).mInfo;
            return mInfo.getCallerInfo().uid == info.getCallerInfo().uid
                    && Objects.equals(mInfo.getCallerInfo().attrs, info.getCallerInfo().attrs)
                    && Objects.equals(mInfo.getDumpAggregationKey(), info.getDumpAggregationKey());
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            // Prints a compact version of each vibration request for dumpsys.
            mInfo.dumpCompact(pw);
        }

        @Override
        public void dump(ProtoOutputStream proto, long fieldId) {
            mInfo.dump(proto, fieldId);
        }
    }

    /** Clears mNextVibration if set, ending it cleanly */
    @GuardedBy("mLock")
    private void clearNextSessionLocked(Status status) {
        clearNextSessionLocked(status, /* endedBy= */ null);
    }

    /** Clears mNextVibration if set, ending it cleanly */
    @GuardedBy("mLock")
    private void clearNextSessionLocked(Status status, CallerInfo endedBy) {
        if (mNextSession != null) {
            if (DEBUG) {
                Slog.d(TAG, "Dropping pending vibration from " + mNextSession.getCallerInfo()
                        + " with status: " + status);
            }
            // Clearing next vibration before playing it, end it and report metrics right away.
            endSessionLocked(mNextSession, status, endedBy);
            mNextSession = null;
        }
    }

    /** Clears mCurrentVibration if set, reporting metrics */
    @GuardedBy("mLock")
    private void clearCurrentSessionLocked() {
        if (mCurrentSession != null) {
            mCurrentSession.unlinkToDeath();
            logAndRecordVibration(mCurrentSession.getDebugInfo());
            mCurrentSession = null;
            mLock.notify(); // Notify if waiting for current vibration to end.
        }
    }

    @GuardedBy("mLock")
    private void maybeClearCurrentAndNextSessionsLocked(
            Predicate<VibrationSession> shouldEndSessionPredicate, Status endStatus) {
        // TODO(b/372241975): investigate why external vibrations were not handled here before
        if (!(mNextSession instanceof ExternalVibrationSession)
                && shouldEndSessionPredicate.test(mNextSession)) {
            clearNextSessionLocked(endStatus);
        }
        if (!(mCurrentSession instanceof ExternalVibrationSession)
                && shouldEndSessionPredicate.test(mCurrentSession)) {
            mCurrentSession.requestEnd(endStatus);
        }
    }

    /**
     * Waits until the current vibration finished processing, timing out after the given
     * number of milliseconds.
     *
     * @return true if the vibration completed, or false if waiting timed out.
     */
    public boolean waitForCurrentSessionEnd(long maxWaitMillis) {
        long now = SystemClock.elapsedRealtime();
        long deadline = now + maxWaitMillis;
        synchronized (mLock) {
            while (true) {
                if (mCurrentSession == null) {
                    return true;  // Done
                }
                if (now >= deadline) {  // Note that thread.wait(0) waits indefinitely.
                    return false;  // Timed out.
                }
                try {
                    mLock.wait(deadline - now);
                } catch (InterruptedException e) {
                    Slog.w(TAG, "VibratorManagerService interrupted waiting to stop, continuing");
                }
                now = SystemClock.elapsedRealtime();
            }
        }
    }

    private HapticFeedbackVibrationProvider getHapticVibrationProvider() {
        synchronized (mLock) {
            // Used a cached haptic vibration provider if one exists.
            if (mHapticFeedbackVibrationProvider != null) {
                return mHapticFeedbackVibrationProvider;
            }
            VibratorInfo combinedVibratorInfo = getCombinedVibratorInfo();
            if (combinedVibratorInfo == null) {
                return null;
            }
            return mHapticFeedbackVibrationProvider =
                    mInjector.createHapticFeedbackVibrationProvider(
                            mContext.getResources(), combinedVibratorInfo);
        }
    }

    @Nullable
    private VibratorInfo getCombinedVibratorInfo() {
        synchronized (mLock) {
            // This is only initialized at system ready, when all vibrator infos are fully loaded.
            return mCombinedVibratorInfo;
        }
    }

    /** Implementation of {@link IExternalVibratorService} to be triggered on external control. */
    @VisibleForTesting
    final class ExternalVibratorService extends IExternalVibratorService.Stub {

        @Override
        public ExternalVibrationScale onExternalVibrationStart(ExternalVibration vib) {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "onExternalVibrationStart");
            try {
                // Create Vibration.Stats as close to the received request as possible, for
                // tracking.
                ExternalVibrationSession session = new ExternalVibrationSession(vib,
                        mExternalVibrationCallbacks);
                // Mute the request until we run all the checks and accept the vibration.
                session.muteScale();
                boolean waitForCompletion = false;

                synchronized (mLock) {
                    if (!hasExternalControlCapability()) {
                        endSessionLocked(session, Status.IGNORED_UNSUPPORTED);
                        return session.getScale();
                    }

                    if (ActivityManager.checkComponentPermission(
                            android.Manifest.permission.VIBRATE,
                            vib.getUid(), -1 /*owningUid*/, true /*exported*/)
                            != PackageManager.PERMISSION_GRANTED) {
                        Slog.w(TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid()
                                + " tried to play externally controlled vibration"
                                + " without VIBRATE permission, ignoring.");
                        endSessionLocked(session, Status.IGNORED_MISSING_PERMISSION);
                        return session.getScale();
                    }

                    Status ignoreStatus = shouldIgnoreVibrationLocked(session.callerInfo);
                    if (ignoreStatus != null) {
                        endSessionLocked(session, ignoreStatus);
                        return session.getScale();
                    }

                    if ((mCurrentSession instanceof ExternalVibrationSession evs)
                            && evs.isHoldingSameVibration(vib)) {
                        // We are already playing this external vibration, so we can return the same
                        // scale calculated in the previous call to this method.
                        return evs.getScale();
                    }

                    // Check if ongoing vibration is more important than this vibration.
                    Vibration.EndInfo ignoreInfo = shouldIgnoreForOngoingLocked(session);
                    if (ignoreInfo != null) {
                        endSessionLocked(session, ignoreInfo.status, ignoreInfo.endedBy);
                        return session.getScale();
                    }

                    // First clear next request, so it won't start when the current one ends.
                    clearNextSessionLocked(Status.IGNORED_FOR_EXTERNAL, session.callerInfo);
                    mNextSession = session;

                    if (mCurrentSession != null) {
                        // Cancel any vibration that may be playing and ready the vibrator, even if
                        // we have an externally controlled vibration playing already.
                        // Since the interface defines that only one externally controlled
                        // vibration can play at a time, we need to first mute the ongoing vibration
                        // and then return a scale from this function for the new one, so we can be
                        // assured that the ongoing will be muted in favor of the new vibration.
                        //
                        // Note that this doesn't support multiple concurrent external controls,
                        // as we would need to mute the old one still if it came from a different
                        // controller.
                        session.stats.reportInterruptedAnotherVibration(
                                mCurrentSession.getCallerInfo());
                        mCurrentSession.requestEnd(Status.CANCELLED_SUPERSEDED,
                                session.callerInfo, /* immediate= */ true);
                        waitForCompletion = true;
                    }
                }
                // Wait for lock and interact with HAL to set external control outside main lock.
                if (waitForCompletion) {
                    if (!waitForCurrentSessionEnd(VIBRATION_CANCEL_WAIT_MILLIS)) {
                        Slog.e(TAG, "Timed out waiting for vibration to cancel");
                        synchronized (mLock) {
                            if (mNextSession == session) {
                                mNextSession = null;
                            }
                            endSessionLocked(session, Status.IGNORED_ERROR_CANCELLING);
                            return session.getScale();
                        }
                    }
                }
                synchronized (mLock) {
                    if (mNextSession == session) {
                        // This is still the next vibration to be played.
                        mNextSession = null;
                    } else {
                        // A new request took the place of this one, maybe with higher importance.
                        // Next vibration already cleared with the right status, just return here.
                        return session.getScale();
                    }
                    if (!session.linkToDeath()) {
                        endSessionLocked(session, Status.IGNORED_ERROR_TOKEN);
                        return session.getScale();
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "Vibrator going under external control.");
                    }
                    setExternalControl(true, session.stats);
                    if (DEBUG) {
                        Slog.d(TAG, "Playing external vibration: " + vib);
                    }
                    VibrationAttributes attrs = fixupVibrationAttributes(
                            vib.getVibrationAttributes(), /* effect= */ null);
                    if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
                        // Force update of user settings before checking if this vibration effect
                        // should be ignored or scaled.
                        mVibrationSettings.update();
                    }
                    mCurrentSession = session;
                    session.scale(mVibrationScaler, attrs.getUsage());

                    // Vibrator will start receiving data from external channels after this point.
                    // Report current time as the vibration start time, for debugging.
                    session.stats.reportStarted();
                    return session.getScale();
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            Trace.traceBegin(TRACE_TAG_VIBRATOR, "onExternalVibrationStop");
            try {
                synchronized (mLock) {
                    if ((mCurrentSession instanceof ExternalVibrationSession evs)
                            && evs.isHoldingSameVibration(vib)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Stopping external vibration: " + vib);
                        }
                        mCurrentSession.requestEnd(Status.FINISHED);
                    }
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }

        private boolean hasExternalControlCapability() {
            for (int i = 0; i < mVibrators.size(); i++) {
                if (mVibrators.valueAt(i).hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Provide limited functionality from {@link VibratorManagerService} as shell commands. */
    private final class VibratorManagerShellCommand extends ShellCommand {
        public static final String SHELL_PACKAGE_NAME = "com.android.shell";
        public static final long VIBRATION_END_TIMEOUT_MS = 500; // Clean up shouldn't be too long.

        private final class CommonOptions {
            public boolean force = false;
            public String description = "Shell command";
            public boolean background = false;

            CommonOptions() {
                String nextArg;
                while ((nextArg = peekNextArg()) != null) {
                    switch (nextArg) {
                        case "-f":
                            getNextArgRequired(); // consume "-f"
                            force = true;
                            break;
                        case "-B":
                            getNextArgRequired(); // consume "-B"
                            background = true;
                            break;
                        case "-d":
                            getNextArgRequired(); // consume "-d"
                            description = getNextArgRequired();
                            break;
                        default:
                            // nextArg is not a common option, finish reading.
                            return;
                    }
                }
            }
        }

        private final IBinder mShellCallbacksToken;

        private VibratorManagerShellCommand(IBinder shellCallbacksToken) {
            mShellCallbacksToken = shellCallbacksToken;
        }

        @Override
        public int onCommand(String cmd) {
            try {
                if ("list".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: list");
                    return runListVibrators();
                }
                if ("synced".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: synced");
                    return runMono();
                }
                if ("combined".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: combined");
                    return runStereo();
                }
                if ("sequential".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: sequential");
                    return runSequential();
                }
                if ("xml".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: xml");
                    return runXml();
                }
                if ("cancel".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: cancel");
                    return runCancel();
                }
                if ("feedback".equals(cmd)) {
                    Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: feedback");
                    return runHapticFeedback();
                }
                Trace.traceBegin(TRACE_TAG_VIBRATOR, "onCommand: default");
                return handleDefaultCommands(cmd);
            } finally {
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
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

        /**
         * Runs a CombinedVibration using the configured common options and attributes.
         */
        private void runVibrate(CommonOptions commonOptions, CombinedVibration combined) {
            VibrationAttributes attrs = createVibrationAttributes(commonOptions);
            // If running in the background, bind to death of the server binder rather than the
            // client, and the cancel command likewise uses the server binder reference to
            // only cancel background vibrations.
            IBinder deathBinder = commonOptions.background ? VibratorManagerService.this
                    : mShellCallbacksToken;
            int uid = Binder.getCallingUid();
            // Resolve the package name for the client based on the process UID, to cover cases like
            // rooted shell clients using ROOT_UID.
            String resolvedPackageName = AppOpsManager.resolvePackageName(uid, SHELL_PACKAGE_NAME);
            HalVibration vib = vibrateWithPermissionCheck(uid, Context.DEVICE_ID_DEFAULT,
                    resolvedPackageName, combined, attrs, commonOptions.description, deathBinder);
            maybeWaitOnVibration(vib, commonOptions);
        }

        private int runMono() {
            runVibrate(new CommonOptions(), CombinedVibration.createParallel(nextEffect()));
            return 0;
        }

        private int runStereo() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibration.ParallelCombination combination =
                    CombinedVibration.startParallel();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                combination.addVibrator(vibratorId, nextEffect());
            }
            runVibrate(commonOptions, combination.combine());
            return 0;
        }

        private int runSequential() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibration.SequentialCombination combination =
                    CombinedVibration.startSequential();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                combination.addNext(vibratorId, nextEffect());
            }
            runVibrate(commonOptions, combination.combine());
            return 0;
        }

        private int runXml() {
            CommonOptions commonOptions = new CommonOptions();
            String xml = getNextArgRequired();
            CombinedVibration vibration = parseXml(xml);
            runVibrate(commonOptions, vibration);
            return 0;
        }

        private int runCancel() {
            // Cancel is only needed if the vibration was run in the background, otherwise it's
            // terminated by the shell command ending. In these cases, the token was that of the
            // service rather than the client.
            cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, VibratorManagerService.this);
            return 0;
        }

        private int runHapticFeedback() {
            CommonOptions commonOptions = new CommonOptions();
            int constant = Integer.parseInt(getNextArgRequired());

            IBinder deathBinder = commonOptions.background ? VibratorManagerService.this
                    : mShellCallbacksToken;
            int flags = commonOptions.force
                    ? HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING : 0;
            HalVibration vib = performHapticFeedbackInternal(Binder.getCallingUid(),
                    Context.DEVICE_ID_DEFAULT, SHELL_PACKAGE_NAME, constant,
                    /* reason= */ commonOptions.description, deathBinder, flags, /* privFlags */ 0);
            maybeWaitOnVibration(vib, commonOptions);

            return 0;
        }

        private VibrationEffect nextEffect() {
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            String nextArg;

            while ((nextArg = peekNextArg()) != null) {
                if ("oneshot".equals(nextArg)) {
                    addOneShotToComposition(composition);
                } else if ("waveform".equals(nextArg)) {
                    addWaveformToComposition(composition);
                } else if ("prebaked".equals(nextArg)) {
                    addPrebakedToComposition(composition);
                } else if ("primitives".equals(nextArg)) {
                    addPrimitivesToComposition(composition);
                } else {
                    // nextArg is not an effect, finish reading.
                    break;
                }
            }

            return composition.compose();
        }

        private void addOneShotToComposition(VibrationEffect.Composition composition) {
            boolean hasAmplitude = false;
            int delay = 0;

            getNextArgRequired(); // consume "oneshot"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitude = true;
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
            }

            long duration = Long.parseLong(getNextArgRequired());
            int amplitude = hasAmplitude ? Integer.parseInt(getNextArgRequired())
                    : VibrationEffect.DEFAULT_AMPLITUDE;
            composition.addOffDuration(Duration.ofMillis(delay));
            composition.addEffect(VibrationEffect.createOneShot(duration, amplitude));
        }

        private void addWaveformToComposition(VibrationEffect.Composition composition) {
            boolean hasAmplitudes = false;
            boolean hasFrequencies = false;
            boolean isContinuous = false;
            int repeat = -1;
            int delay = 0;

            getNextArgRequired(); // consume "waveform"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitudes = true;
                } else if ("-r".equals(nextOption)) {
                    repeat = Integer.parseInt(getNextArgRequired());
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                } else if ("-f".equals(nextOption)) {
                    hasFrequencies = true;
                } else if ("-c".equals(nextOption)) {
                    isContinuous = true;
                }
            }
            List<Integer> durations = new ArrayList<>();
            List<Float> amplitudes = new ArrayList<>();
            List<Float> frequencies = new ArrayList<>();

            float nextAmplitude = 0;
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                try {
                    durations.add(Integer.parseInt(nextArg));
                    getNextArgRequired(); // consume the duration
                } catch (NumberFormatException e) {
                    // nextArg is not a duration, finish reading.
                    break;
                }
                if (hasAmplitudes) {
                    amplitudes.add(
                            Float.parseFloat(getNextArgRequired()) / VibrationEffect.MAX_AMPLITUDE);
                } else {
                    amplitudes.add(nextAmplitude);
                    nextAmplitude = 1 - nextAmplitude;
                }
                if (hasFrequencies) {
                    frequencies.add(Float.parseFloat(getNextArgRequired()));
                }
            }

            // Add delay before the waveform.
            composition.addOffDuration(Duration.ofMillis(delay));

            VibrationEffect.WaveformBuilder waveform = VibrationEffect.startWaveform();
            for (int i = 0; i < durations.size(); i++) {
                Duration transitionDuration = isContinuous
                        ? Duration.ofMillis(durations.get(i))
                        : Duration.ZERO;
                Duration sustainDuration = isContinuous
                        ? Duration.ZERO
                        : Duration.ofMillis(durations.get(i));

                if (hasFrequencies) {
                    waveform.addTransition(transitionDuration, targetAmplitude(amplitudes.get(i)),
                            targetFrequency(frequencies.get(i)));
                } else {
                    waveform.addTransition(transitionDuration, targetAmplitude(amplitudes.get(i)));
                }
                if (!sustainDuration.isZero()) {
                    // Add sustain only takes positive durations. Skip this since we already
                    // did a transition to the desired values (even when duration is zero).
                    waveform.addSustain(sustainDuration);
                }

                if ((i > 0) && (i == repeat)) {
                    // Add segment that is not repeated to the composition and reset builder.
                    composition.addEffect(waveform.build());

                    if (hasFrequencies) {
                        waveform = VibrationEffect.startWaveform(targetAmplitude(amplitudes.get(i)),
                                targetFrequency(frequencies.get(i)));
                    } else {
                        waveform = VibrationEffect.startWaveform(
                                targetAmplitude(amplitudes.get(i)));
                    }
                }
            }
            if (repeat < 0) {
                composition.addEffect(waveform.build());
            } else {
                // The waveform was already split at the repeat index, just repeat what remains.
                composition.repeatEffectIndefinitely(waveform.build());
            }
        }

        private void addPrebakedToComposition(VibrationEffect.Composition composition) {
            boolean shouldFallback = false;
            int delay = 0;

            getNextArgRequired(); // consume "prebaked"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-b".equals(nextOption)) {
                    shouldFallback = true;
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
            }

            int effectId = Integer.parseInt(getNextArgRequired());
            composition.addOffDuration(Duration.ofMillis(delay));
            composition.addEffect(VibrationEffect.get(effectId, shouldFallback));
        }

        private void addPrimitivesToComposition(VibrationEffect.Composition composition) {
            getNextArgRequired(); // consume "primitives"
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                int delay = 0;
                if ("-w".equals(nextArg)) {
                    getNextArgRequired(); // consume "-w"
                    delay = Integer.parseInt(getNextArgRequired());
                    nextArg = peekNextArg();
                }
                try {
                    composition.addPrimitive(Integer.parseInt(nextArg), /* scale= */ 1, delay);
                    getNextArgRequired(); // consume the primitive id
                } catch (NumberFormatException | NullPointerException e) {
                    // nextArg is not describing a primitive, leave it to be consumed by outer loops
                    break;
                }
            }
        }

        private VibrationAttributes createVibrationAttributes(CommonOptions commonOptions) {
            // This will bypass user settings, Do Not Disturb and other interruption policies.
            final int flags = commonOptions.force ? ATTRIBUTES_ALL_BYPASS_FLAGS : 0;
            return new VibrationAttributes.Builder()
                    .setFlags(flags)
                    // Used to allow vibrations when the adb shell process is running in background.
                    // This will apply the NOTIFICATION_VIBRATION_INTENSITY setting.
                    .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                    .build();
        }

        private CombinedVibration parseXml(String xml) {
            try {
                ParsedVibration parsedVibration =
                        VibrationXmlParser.parseDocument(new StringReader(xml));
                VibratorInfo combinedVibratorInfo = getCombinedVibratorInfo();
                if (combinedVibratorInfo == null) {
                    throw new IllegalStateException(
                            "No combined vibrator info to parse vibration XML " + xml);
                }
                VibrationEffect effect = parsedVibration.resolve(combinedVibratorInfo);
                if (effect == null) {
                    throw new IllegalArgumentException(
                            "Parsed vibration cannot be resolved for vibration XML " + xml);
                }
                return CombinedVibration.createParallel(effect);
            } catch (IOException e) {
                throw new RuntimeException("Error parsing vibration XML " + xml, e);
            }
        }

        private void maybeWaitOnVibration(HalVibration vib, CommonOptions commonOptions) {
            if (vib != null && !commonOptions.background) {
                try {
                    // Waits for the client vibration to finish, but the VibrationThread may still
                    // do cleanup after this.
                    vib.waitForEnd();
                    // Wait for vibration clean up and possible ramp down before ending.
                    mVibrationThread.waitForThreadIdle(
                            mVibrationSettings.getRampDownDuration() + VIBRATION_END_TIMEOUT_MS);
                } catch (InterruptedException e) {
                }
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
                pw.println("  synced [options] <effect>...");
                pw.println("    Vibrates effect on all vibrators in sync.");
                pw.println("  combined [options] (-v <vibrator-id> <effect>...)...");
                pw.println("    Vibrates different effects on each vibrator in sync.");
                pw.println("  sequential [options] (-v <vibrator-id> <effect>...)...");
                pw.println("    Vibrates different effects on each vibrator in sequence.");
                pw.println("  xml [options] <xml>");
                pw.println("    Vibrates using combined vibration described in given XML string");
                pw.println("    on all vibrators in sync. The XML could be:");
                pw.println("        XML containing a single effect, or");
                pw.println("        A vibration select XML containing multiple effects.");
                pw.println("    Vibrates using combined vibration described in given XML string.");
                pw.println("    XML containing a single effect it runs on all vibrators in sync.");
                pw.println("  cancel");
                pw.println("    Cancels any active vibration");
                pw.println("  feedback [-f] [-d <description>] <constant>");
                pw.println("    Performs a haptic feedback with the given constant.");
                pw.println("    The force (-f) option enables the `always` configuration, which");
                pw.println("    plays the haptic irrespective of the vibration intensity settings");
                pw.println("");
                pw.println("Effect commands:");
                pw.println("  oneshot [-w delay] [-a] <duration> [<amplitude>]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on ");
                pw.println("    DND (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -a is provided, the command accepts a second argument for ");
                pw.println("    amplitude, in a scale of 1-255.");
                pw.print("  waveform [-w delay] [-r index] [-a] [-f] [-c] ");
                pw.println("(<duration> [<amplitude>] [<frequency>])...");
                pw.println("    Vibrates for durations and amplitudes in list; ignored when ");
                pw.println("    device is on DND (Do Not Disturb) mode; touch feedback strength ");
                pw.println("    user setting will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -r is provided, the waveform loops back to the specified");
                pw.println("    index (e.g. 0 loops from the beginning)");
                pw.println("    If -a is provided, the command expects amplitude to follow each");
                pw.println("    duration; otherwise, it accepts durations only and alternates");
                pw.println("    off/on");
                pw.println("    If -f is provided, the command expects frequency to follow each");
                pw.println("    amplitude or duration; otherwise, it uses resonant frequency");
                pw.println("    If -c is provided, the waveform is continuous and will ramp");
                pw.println("    between values; otherwise each entry is a fixed step.");
                pw.println("    Duration is in milliseconds; amplitude is a scale of 1-255;");
                pw.println("    frequency is an absolute value in hertz;");
                pw.println("  prebaked [-w delay] [-b] <effect-id>");
                pw.println("    Vibrates with prebaked effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -b is provided, the prebaked fallback effect will be played if");
                pw.println("    the device doesn't support the given effect-id.");
                pw.println("  primitives ([-w delay] <primitive-id>)...");
                pw.println("    Vibrates with a composed effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale primitive intensities.");
                pw.println("    If -w is provided, the next primitive will be played after the ");
                pw.println("    specified wait time in milliseconds.");
                pw.println("");
                pw.println("Common Options:");
                pw.println("  -f");
                pw.println("    Force. Ignore Do Not Disturb setting.");
                pw.println("  -B");
                pw.println("    Run in the background; without this option the shell cmd will");
                pw.println("    block until the vibration has completed.");
                pw.println("  -d <description>");
                pw.println("    Add description to the vibration.");
                pw.println("");
            }
        }
    }
}
