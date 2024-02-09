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
import android.frameworks.vibrator.IVibratorControlService;
import android.frameworks.vibrator.IVibratorController;
import android.frameworks.vibrator.ScaleParam;
import android.frameworks.vibrator.VibrationParam;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.VibrationAttributes;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link IVibratorControlService} which allows the registration of
 * {@link IVibratorController} to set and receive vibration params.
 *
 * @hide
 */
public final class VibratorControlService extends IVibratorControlService.Stub {
    private static final String TAG = "VibratorControlService";
    private static final int UNRECOGNIZED_VIBRATION_TYPE = -1;
    private static final int NO_SCALE = -1;

    private final VibratorControllerHolder mVibratorControllerHolder;
    private final VibrationScaler mVibrationScaler;
    private final Object mLock;
    private final int[] mRequestVibrationParamsForUsages;

    @GuardedBy("mLock")
    private CompletableFuture<Void> mRequestVibrationParamsFuture = null;
    @GuardedBy("mLock")
    private IBinder mRequestVibrationParamsToken;

    public VibratorControlService(VibratorControllerHolder vibratorControllerHolder,
            VibrationScaler vibrationScaler, VibrationSettings vibrationSettings, Object lock) {
        mVibratorControllerHolder = vibratorControllerHolder;
        mVibrationScaler = vibrationScaler;
        mLock = lock;
        mRequestVibrationParamsForUsages = vibrationSettings.getRequestVibrationParamsForUsages();
    }

    @Override
    public void registerVibratorController(IVibratorController controller)
            throws RemoteException {
        synchronized (mLock) {
            mVibratorControllerHolder.setVibratorController(controller);
        }
    }

    @Override
    public void unregisterVibratorController(@NonNull IVibratorController controller)
            throws RemoteException {
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
            @NonNull IVibratorController token) throws RemoteException {
        Objects.requireNonNull(token);

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

            updateAdaptiveHapticsScales(params);
        }
    }

    @Override
    public void clearVibrationParams(int types, @NonNull IVibratorController token)
            throws RemoteException {
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
        }
    }

    @Override
    public void onRequestVibrationParamsComplete(
            @NonNull IBinder requestToken, @SuppressLint("ArrayReturn") VibrationParam[] result)
            throws RemoteException {
        Objects.requireNonNull(requestToken);

        synchronized (mLock) {
            if (mRequestVibrationParamsToken == null) {
                Slog.wtf(TAG,
                        "New vibration params received but no token was cached in the service. "
                                + "New vibration params ignored.");
                return;
            }

            if (!Objects.equals(requestToken, mRequestVibrationParamsToken)) {
                Slog.w(TAG,
                        "New vibration params received but the provided token does not match the "
                                + "cached one. New vibration params ignored.");
                return;
            }

            updateAdaptiveHapticsScales(result);
            endOngoingRequestVibrationParamsLocked(/* wasCancelled= */ false);
        }
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        return this.VERSION;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
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
            @VibrationAttributes.Usage int usage, int timeoutInMillis) {
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
                mRequestVibrationParamsFuture = new CompletableFuture<>();
                mRequestVibrationParamsToken = new Binder();
                vibratorController.requestVibrationParams(vibrationType, timeoutInMillis,
                        mRequestVibrationParamsToken);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to request vibration params.", e);
                endOngoingRequestVibrationParamsLocked(/* wasCancelled= */ true);
            }

            return mRequestVibrationParamsFuture;
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
     * Returns the {@link #mRequestVibrationParamsToken} which is used to validate
     * {@link #onRequestVibrationParamsComplete(IBinder, VibrationParam[])} calls.
     */
    @VisibleForTesting
    public IBinder getRequestVibrationParamsToken() {
        synchronized (mLock) {
            return mRequestVibrationParamsToken;
        }
    }

    /**
     * Completes or cancels the vibration params request future and resets the future and token
     * to null.
     * @param wasCancelled specifies whether the future should be ended by being cancelled or not.
     */
    @GuardedBy("mLock")
    private void endOngoingRequestVibrationParamsLocked(boolean wasCancelled) {
        mRequestVibrationParamsToken = null;
        if (mRequestVibrationParamsFuture == null) {
            return;
        }

        if (wasCancelled) {
            mRequestVibrationParamsFuture.cancel(/* mayInterruptIfRunning= */ true);
        } else {
            mRequestVibrationParamsFuture.complete(null);
        }

        mRequestVibrationParamsFuture = null;
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

    /**
     * Updates the adaptive haptics scales cached in {@link VibrationScaler} with the
     * provided params.
     *
     * @param params the new vibration params.
     */
    private void updateAdaptiveHapticsScales(@Nullable VibrationParam[] params) {
        for (VibrationParam param : params) {
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
        if ((ScaleParam.TYPE_ALARM & types) != 0) {
            updateOrRemoveAdaptiveHapticsScale(USAGE_ALARM, scale);
        }

        if ((ScaleParam.TYPE_NOTIFICATION & types) != 0) {
            updateOrRemoveAdaptiveHapticsScale(USAGE_NOTIFICATION, scale);
            updateOrRemoveAdaptiveHapticsScale(USAGE_COMMUNICATION_REQUEST, scale);
        }

        if ((ScaleParam.TYPE_RINGTONE & types) != 0) {
            updateOrRemoveAdaptiveHapticsScale(USAGE_RINGTONE, scale);
        }

        if ((ScaleParam.TYPE_MEDIA & types) != 0) {
            updateOrRemoveAdaptiveHapticsScale(USAGE_MEDIA, scale);
            updateOrRemoveAdaptiveHapticsScale(USAGE_UNKNOWN, scale);
        }

        if ((ScaleParam.TYPE_INTERACTIVE & types) != 0) {
            updateOrRemoveAdaptiveHapticsScale(USAGE_TOUCH, scale);
            updateOrRemoveAdaptiveHapticsScale(USAGE_HARDWARE_FEEDBACK, scale);
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
}
