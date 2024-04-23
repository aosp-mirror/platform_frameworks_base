/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.frameworks.vibrator.IVibratorControlService;
import android.frameworks.vibrator.IVibratorController;
import android.frameworks.vibrator.ScaleParam;
import android.frameworks.vibrator.VibrationParam;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link IVibratorControlService} which allows the registration of
 * {@link IVibratorController} to set and receive vibration params.
 */
final class VibratorControlService extends IVibratorControlService.Stub {
    private static final String TAG = "VibratorControlService";
    private static final int UNRECOGNIZED_VIBRATION_TYPE = -1;
    private static final int NO_SCALE = -1;

    private static final DateTimeFormatter DEBUG_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final VibrationParamsRecords mVibrationParamsRecords;
    private final VibratorControllerHolder mVibratorControllerHolder;
    private final VibrationScaler mVibrationScaler;
    private final VibratorFrameworkStatsLogger mStatsLogger;
    private final Object mLock;
    private final int[] mRequestVibrationParamsForUsages;

    @GuardedBy("mLock")
    @Nullable
    private VibrationParamRequest mVibrationParamRequest = null;

    VibratorControlService(Context context,
            VibratorControllerHolder vibratorControllerHolder, VibrationScaler vibrationScaler,
            VibrationSettings vibrationSettings, VibratorFrameworkStatsLogger statsLogger,
            Object lock) {
        mVibratorControllerHolder = vibratorControllerHolder;
        mVibrationScaler = vibrationScaler;
        mStatsLogger = statsLogger;
        mLock = lock;
        mRequestVibrationParamsForUsages = vibrationSettings.getRequestVibrationParamsForUsages();

        int dumpSizeLimit = context.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpSizeLimit);
        int dumpAggregationTimeLimit = context.getResources().getInteger(
                com.android.internal.R.integer
                        .config_previousVibrationsDumpAggregationTimeMillisLimit);
        mVibrationParamsRecords =
                new VibrationParamsRecords(dumpSizeLimit, dumpAggregationTimeLimit);
    }

    @Override
    public void registerVibratorController(@NonNull IVibratorController controller) {
        Objects.requireNonNull(controller);

        synchronized (mLock) {
            mVibratorControllerHolder.setVibratorController(controller);
        }
    }

    @Override
    public void unregisterVibratorController(@NonNull IVibratorController controller) {
        Objects.requireNonNull(controller);

        synchronized (mLock) {
            if (mVibratorControllerHolder.getVibratorController() == null) {
                Slog.w(TAG, "Received request to unregister IVibratorController = "
                        + controller + ", but no controller was previously registered. Request "
                        + "Ignored.");
                return;
            }
            if (!Objects.equals(mVibratorControllerHolder.getVibratorController().asBinder(),
                    controller.asBinder())) {
                Slog.wtf(TAG, "Failed to unregister IVibratorController. The provided "
                        + "controller doesn't match the registered one. " + this);
                return;
            }
            mVibrationScaler.clearAdaptiveHapticsScales();
            mVibratorControllerHolder.setVibratorController(null);
            endOngoingRequestVibrationParamsLocked(/* wasCancelled= */ true);
        }
    }

    @Override
    public void setVibrationParams(@SuppressLint("ArrayReturn") VibrationParam[] params,
            @NonNull IVibratorController token) {
        Objects.requireNonNull(token);
        requireContainsNoNullElement(params);

        synchronized (mLock) {
            if (mVibratorControllerHolder.getVibratorController() == null) {
                Slog.w(TAG, "Received request to set VibrationParams for IVibratorController = "
                        + token + ", but no controller was previously registered. Request "
                        + "Ignored.");
                return;
            }
            if (!Objects.equals(mVibratorControllerHolder.getVibratorController().asBinder(),
                    token.asBinder())) {
                Slog.wtf(TAG, "Failed to set new VibrationParams. The provided "
                        + "controller doesn't match the registered one. " + this);
                return;
            }
            if (params == null) {
                // Adaptive haptics scales cannot be set to null. Ignoring request.
                Slog.d(TAG,
                        "New vibration params received but are null. New vibration "
                                + "params ignored.");
                return;
            }

            updateAdaptiveHapticsScales(params);
            recordUpdateVibrationParams(params, /* fromRequest= */ false);
        }
    }

    @Override
    public void clearVibrationParams(int types, @NonNull IVibratorController token) {
        Objects.requireNonNull(token);

        synchronized (mLock) {
            if (mVibratorControllerHolder.getVibratorController() == null) {
                Slog.w(TAG, "Received request to clear VibrationParams for IVibratorController = "
                        + token + ", but no controller was previously registered. Request "
                        + "Ignored.");
                return;
            }
            if (!Objects.equals(mVibratorControllerHolder.getVibratorController().asBinder(),
                    token.asBinder())) {
                Slog.wtf(TAG, "Failed to clear VibrationParams. The provided "
                        + "controller doesn't match the registered one. " + this);
                return;
            }

            updateAdaptiveHapticsScales(types, NO_SCALE);
            recordClearVibrationParams(types);
        }
    }

    @Override
    public void onRequestVibrationParamsComplete(
            @NonNull IBinder requestToken, @SuppressLint("ArrayReturn") VibrationParam[] result) {
        Objects.requireNonNull(requestToken);
        requireContainsNoNullElement(result);

        synchronized (mLock) {
            if (mVibrationParamRequest == null) {
                Slog.wtf(TAG,
                        "New vibration params received but no token was cached in the service. "
                                + "New vibration params ignored.");
                mStatsLogger.logVibrationParamResponseIgnored();
                return;
            }

            if (!Objects.equals(requestToken, mVibrationParamRequest.token)) {
                Slog.w(TAG,
                        "New vibration params received but the provided token does not match the "
                                + "cached one. New vibration params ignored.");
                mStatsLogger.logVibrationParamResponseIgnored();
                return;
            }

            long latencyMs = SystemClock.uptimeMillis() - mVibrationParamRequest.uptimeMs;
            mStatsLogger.logVibrationParamRequestLatency(mVibrationParamRequest.uid, latencyMs);

            if (result == null) {
                Slog.d(TAG,
                        "New vibration params received but are null. New vibration "
                                + "params ignored.");
                return;
            }

            updateAdaptiveHapticsScales(result);
            endOngoingRequestVibrationParamsLocked(/* wasCancelled= */ false);
            recordUpdateVibrationParams(result, /* fromRequest= */ true);
        }
    }

    @Override
    public int getInterfaceVersion() {
        return this.VERSION;
    }

    @Override
    public String getInterfaceHash() {
        return this.HASH;
    }

    /**
     * If an {@link IVibratorController} is registered to the service, it will request the latest
     * vibration params and return a {@link CompletableFuture} that completes when the request is
     * fulfilled. Otherwise, ignores the call and returns null.
     *
     * @param usage a {@link android.os.VibrationAttributes} usage.
     * @param timeoutInMillis the request's timeout in millis.
     * @return a {@link CompletableFuture} to track the completion of the vibration param
     * request, or null if no {@link IVibratorController} is registered.
     */
    @Nullable
    public CompletableFuture<Void> triggerVibrationParamsRequest(
            int uid, @VibrationAttributes.Usage int usage, int timeoutInMillis) {
        synchronized (mLock) {
            IVibratorController vibratorController =
                    mVibratorControllerHolder.getVibratorController();
            if (vibratorController == null) {
                Slog.d(TAG, "Unable to request vibration params. There is no registered "
                        + "IVibrationController.");
                return null;
            }

            int vibrationType = mapToAdaptiveVibrationType(usage);
            if (vibrationType == UNRECOGNIZED_VIBRATION_TYPE) {
                Slog.d(TAG, "Unable to request vibration params. The provided usage " + usage
                        + " is unrecognized.");
                return null;
            }

            try {
                endOngoingRequestVibrationParamsLocked(/* wasCancelled= */ true);
                mVibrationParamRequest = new VibrationParamRequest(uid);
                vibratorController.requestVibrationParams(vibrationType, timeoutInMillis,
                        mVibrationParamRequest.token);
                return mVibrationParamRequest.future;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to request vibration params.", e);
                endOngoingRequestVibrationParamsLocked(/* wasCancelled= */ true);
            }

            return null;
        }
    }

    /**
     * If an {@link IVibratorController} is registered to the service, then it checks whether to
     * request new vibration params before playing the vibration. Returns true if the
     * usage is for high latency vibrations, e.g. ringtone and notification, and can be delayed
     * slightly. Otherwise, returns false.
     *
     * @param usage a {@link android.os.VibrationAttributes} usage.
     * @return true if usage is for high latency vibrations, false otherwise.
     */
    public boolean shouldRequestVibrationParams(@VibrationAttributes.Usage int usage) {
        synchronized (mLock) {
            IVibratorController vibratorController =
                    mVibratorControllerHolder.getVibratorController();
            if (vibratorController == null) {
                return false;
            }

            return ArrayUtils.contains(mRequestVibrationParamsForUsages, usage);
        }
    }

    /**
     * Returns the binder token which is used to validate
     * {@link #onRequestVibrationParamsComplete(IBinder, VibrationParam[])} calls.
     */
    @VisibleForTesting
    public IBinder getRequestVibrationParamsToken() {
        synchronized (mLock) {
            return mVibrationParamRequest == null ? null : mVibrationParamRequest.token;
        }
    }

    /** Write current settings into given {@link PrintWriter}. */
    void dump(IndentingPrintWriter pw) {
        boolean isVibratorControllerRegistered;
        boolean hasPendingVibrationParamsRequest;
        synchronized (mLock) {
            isVibratorControllerRegistered =
                    mVibratorControllerHolder.getVibratorController() != null;
            hasPendingVibrationParamsRequest = mVibrationParamRequest != null;
        }

        pw.println("VibratorControlService:");
        pw.increaseIndent();
        pw.println("isVibratorControllerRegistered = " + isVibratorControllerRegistered);
        pw.println("hasPendingVibrationParamsRequest = " + hasPendingVibrationParamsRequest);

        pw.println();
        pw.println("Vibration parameters update history:");
        pw.increaseIndent();
        mVibrationParamsRecords.dump(pw);
        pw.decreaseIndent();

        pw.decreaseIndent();
    }

    /** Write current settings into given {@link ProtoOutputStream}. */
    void dump(ProtoOutputStream proto) {
        boolean isVibratorControllerRegistered;
        synchronized (mLock) {
            isVibratorControllerRegistered =
                    mVibratorControllerHolder.getVibratorController() != null;
        }
        proto.write(VibratorManagerServiceDumpProto.IS_VIBRATOR_CONTROLLER_REGISTERED,
                isVibratorControllerRegistered);
        mVibrationParamsRecords.dump(proto);
    }

    /**
     * Completes or cancels the vibration params request future and resets the future and token
     * to null.
     * @param wasCancelled specifies whether the future should be ended by being cancelled or not.
     */
    @GuardedBy("mLock")
    private void endOngoingRequestVibrationParamsLocked(boolean wasCancelled) {
        if (mVibrationParamRequest != null) {
            mVibrationParamRequest.endRequest(wasCancelled);
        }
        mVibrationParamRequest = null;
    }

    private static int mapToAdaptiveVibrationType(@VibrationAttributes.Usage int usage) {
        switch (usage) {
            case USAGE_ALARM -> {
                return ScaleParam.TYPE_ALARM;
            }
            case USAGE_NOTIFICATION, USAGE_COMMUNICATION_REQUEST -> {
                return ScaleParam.TYPE_NOTIFICATION;
            }
            case USAGE_RINGTONE -> {
                return ScaleParam.TYPE_RINGTONE;
            }
            case USAGE_MEDIA, USAGE_UNKNOWN -> {
                return ScaleParam.TYPE_MEDIA;
            }
            case USAGE_TOUCH, USAGE_HARDWARE_FEEDBACK, USAGE_ACCESSIBILITY,
                    USAGE_PHYSICAL_EMULATION -> {
                return ScaleParam.TYPE_INTERACTIVE;
            }
            default -> {
                Slog.w(TAG, "Unrecognized vibration usage " + usage);
                return UNRECOGNIZED_VIBRATION_TYPE;
            }
        }
    }

    private static int[] mapFromAdaptiveVibrationTypeToVibrationUsages(int types) {
        IntArray usages = new IntArray(15);
        if ((ScaleParam.TYPE_ALARM & types) != 0) {
            usages.add(USAGE_ALARM);
        }

        if ((ScaleParam.TYPE_NOTIFICATION & types) != 0) {
            usages.add(USAGE_NOTIFICATION);
            usages.add(USAGE_COMMUNICATION_REQUEST);
        }

        if ((ScaleParam.TYPE_RINGTONE & types) != 0) {
            usages.add(USAGE_RINGTONE);
        }

        if ((ScaleParam.TYPE_MEDIA & types) != 0) {
            usages.add(USAGE_MEDIA);
            usages.add(USAGE_UNKNOWN);
        }

        if ((ScaleParam.TYPE_INTERACTIVE & types) != 0) {
            usages.add(USAGE_TOUCH);
            usages.add(USAGE_HARDWARE_FEEDBACK);
        }
        return usages.toArray();
    }

    /**
     * Updates the adaptive haptics scales cached in {@link VibrationScaler} with the
     * provided params.
     *
     * @param params the new vibration params.
     */
    private void updateAdaptiveHapticsScales(@NonNull VibrationParam[] params) {
        Objects.requireNonNull(params);

        for (VibrationParam param : params) {
            if (param.getTag() != VibrationParam.scale) {
                Slog.e(TAG, "Unsupported vibration param: " + param);
                continue;
            }
            ScaleParam scaleParam = param.getScale();
            updateAdaptiveHapticsScales(scaleParam.typesMask, scaleParam.scale);
        }
    }

    /**
     * Updates the adaptive haptics scales, cached in {@link VibrationScaler}, for the provided
     * vibration types.
     *
     * @param types The type of vibrations.
     * @param scale The scaling factor that should be applied to the vibrations.
     */
    private void updateAdaptiveHapticsScales(int types, float scale) {
        mStatsLogger.logVibrationParamScale(scale);
        for (int usage : mapFromAdaptiveVibrationTypeToVibrationUsages(types)) {
            updateOrRemoveAdaptiveHapticsScale(usage, scale);
        }
    }

    /**
     * Updates or removes the adaptive haptics scale for the specified usage. If the scale is set
     * to {@link #NO_SCALE} then it will be removed from the cached usage scales in
     * {@link VibrationScaler}. Otherwise, the cached usage scale will be updated by the new value.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*.
     * @param scale     The scaling factor that should be applied to the vibrations. If set to
     *                  {@link #NO_SCALE} then the scale will be removed.
     */
    private void updateOrRemoveAdaptiveHapticsScale(@VibrationAttributes.Usage int usageHint,
            float scale) {
        if (scale == NO_SCALE) {
            mVibrationScaler.removeAdaptiveHapticsScale(usageHint);
            return;
        }

        mVibrationScaler.updateAdaptiveHapticsScale(usageHint, scale);
    }

    private void recordUpdateVibrationParams(@NonNull VibrationParam[] params,
            boolean fromRequest) {
        Objects.requireNonNull(params);

        VibrationParamsRecords.Operation operation =
                fromRequest ? VibrationParamsRecords.Operation.PULL
                        : VibrationParamsRecords.Operation.PUSH;
        long createTime = SystemClock.uptimeMillis();
        for (VibrationParam param : params) {
            if (param.getTag() != VibrationParam.scale) {
                Slog.w(TAG, "Unsupported vibration param ignored from dumpsys records: " + param);
                continue;
            }
            ScaleParam scaleParam = param.getScale();
            mVibrationParamsRecords.add(new VibrationScaleParamRecord(operation, createTime,
                    scaleParam.typesMask, scaleParam.scale));
        }
    }

    private void recordClearVibrationParams(int typesMask) {
        long createTime = SystemClock.uptimeMillis();
        mVibrationParamsRecords.add(new VibrationScaleParamRecord(
                VibrationParamsRecords.Operation.CLEAR, createTime, typesMask, NO_SCALE));
    }

    private void requireContainsNoNullElement(VibrationParam[] params) {
        if (ArrayUtils.contains(params, null)) {
            throw new IllegalArgumentException(
                    "Invalid vibration params received: null values are not permitted.");
        }
    }

    /**
     * Keep records of {@link VibrationParam} values received by this service from a registered
     * {@link VibratorController} and provide debug information for this service.
     */
    private static final class VibrationParamsRecords
            extends GroupedAggregatedLogRecords<VibrationScaleParamRecord> {

        /** The type of operations on vibration parameters that the service is recording. */
        enum Operation {
            PULL, PUSH, CLEAR
        };

        VibrationParamsRecords(int sizeLimit, int aggregationTimeLimit) {
            super(sizeLimit, aggregationTimeLimit);
        }

        @Override
        synchronized void dumpGroupHeader(IndentingPrintWriter pw, int paramType) {
            if (paramType == VibrationParam.scale) {
                pw.println("SCALE:");
            } else {
                pw.println("UNKNOWN:");
            }
        }

        @Override
        synchronized long findGroupKeyProtoFieldId(int usage) {
            return VibratorManagerServiceDumpProto.PREVIOUS_VIBRATION_PARAMS;
        }
    }

    /** Represents a request for {@link VibrationParam}. */
    private static final class VibrationParamRequest {
        public final CompletableFuture<Void> future = new CompletableFuture<>();
        public final IBinder token = new Binder();
        public final int uid;
        public final long uptimeMs;

        VibrationParamRequest(int uid) {
            this.uid = uid;
            uptimeMs = SystemClock.uptimeMillis();
        }

        public void endRequest(boolean wasCancelled) {
            if (wasCancelled) {
                future.cancel(/* mayInterruptIfRunning= */ true);
            } else {
                future.complete(null);
            }
        }
    }

    /**
     * Record for a single {@link Vibration.DebugInfo}, that can be grouped by usage and aggregated
     * by UID, {@link VibrationAttributes} and {@link VibrationEffect}.
     */
    private static final class VibrationScaleParamRecord
            implements GroupedAggregatedLogRecords.SingleLogRecord {

        private final VibrationParamsRecords.Operation mOperation;
        private final long mCreateTime;
        private final int mTypesMask;
        private final float mScale;

        VibrationScaleParamRecord(VibrationParamsRecords.Operation operation, long createTime,
                int typesMask, float scale) {
            mOperation = operation;
            mCreateTime = createTime;
            mTypesMask = typesMask;
            mScale = scale;
        }

        @Override
        public int getGroupKey() {
            return VibrationParam.scale;
        }

        @Override
        public long getCreateUptimeMs() {
            return mCreateTime;
        }

        @Override
        public boolean mayAggregate(GroupedAggregatedLogRecords.SingleLogRecord record) {
            if (!(record instanceof VibrationScaleParamRecord param)) {
                return false;
            }
            return mTypesMask == param.mTypesMask && mOperation == param.mOperation;
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            String line = String.format(Locale.ROOT,
                    "%s | %6s | scale: %5s | typesMask: %6s | usages: %s",
                    DEBUG_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(mCreateTime)),
                    mOperation.name().toLowerCase(Locale.ROOT),
                    (mScale == NO_SCALE) ? "" : String.format(Locale.ROOT, "%.2f", mScale),
                    Long.toBinaryString(mTypesMask), createVibrationUsagesString());
            pw.println(line);
        }

        @Override
        public void dump(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(VibrationParamProto.CREATE_TIME, mCreateTime);
            proto.write(VibrationParamProto.IS_FROM_REQUEST,
                    mOperation == VibrationParamsRecords.Operation.PULL);

            final long scaleToken = proto.start(VibrationParamProto.SCALE);
            proto.write(VibrationScaleParamProto.TYPES_MASK, mTypesMask);
            proto.write(VibrationScaleParamProto.SCALE, mScale);
            proto.end(scaleToken);

            proto.end(token);
        }

        private String createVibrationUsagesString() {
            StringBuilder sb = new StringBuilder();
            int[] usages = mapFromAdaptiveVibrationTypeToVibrationUsages(mTypesMask);
            for (int i = 0; i < usages.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(VibrationAttributes.usageToString(usages[i]));
            }
            return sb.toString();
        }
    }
}
