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

import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import java.util.Objects;

/**
 * Implementation of {@link IVibratorControlService} which allows the registration of
 * {@link IVibratorController} to set and receive vibration params.
 *
 * @hide
 */
public final class VibratorControlService extends IVibratorControlService.Stub {
    private static final String TAG = "VibratorControlService";

    private final VibratorControllerHolder mVibratorControllerHolder;
    private final VibrationScaler mVibrationScaler;
    private final Object mLock;

    public VibratorControlService(VibratorControllerHolder vibratorControllerHolder,
            VibrationScaler vibrationScaler, Object lock) {
        mVibratorControllerHolder = vibratorControllerHolder;
        mVibrationScaler = vibrationScaler;
        mLock = lock;
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
            updateAdaptiveHapticsScales(/* params= */ null);
            mVibratorControllerHolder.setVibratorController(null);
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
            //TODO(305942827): Update this method to only clear the specified vibration types.
            // Perhaps look into whether it makes more sense to have this clear all scales and
            // rely on setVibrationParams for clearing the scales for specific vibrations.
            updateAdaptiveHapticsScales(/* params= */ null);
        }
    }

    @Override
    public void onRequestVibrationParamsComplete(
            @NonNull IBinder requestToken, @SuppressLint("ArrayReturn") VibrationParam[] result)
            throws RemoteException {
        // TODO(305942827): Cache the vibration params in VibrationScaler
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
     * Extracts the vibration scales and caches them in {@link VibrationScaler}.
     *
     * @param params the new vibration params to cache.
     */
    private void updateAdaptiveHapticsScales(@Nullable VibrationParam[] params) {
        if (params == null || params.length == 0) {
            mVibrationScaler.updateAdaptiveHapticsScales(null);
            return;
        }

        SparseArray<Float> vibrationScales = new SparseArray<>();
        for (int i = 0; i < params.length; i++) {
            ScaleParam scaleParam = params[i].getScale();
            extractVibrationScales(scaleParam, vibrationScales);
        }
        mVibrationScaler.updateAdaptiveHapticsScales(vibrationScales);
    }

    /**
     * Extracts the vibration scales and map them to their corresponding
     * {@link android.os.VibrationAttributes} usages.
     */
    private void extractVibrationScales(ScaleParam scaleParam, SparseArray<Float> vibrationScales) {
        if ((ScaleParam.TYPE_ALARM & scaleParam.typesMask) != 0) {
            vibrationScales.put(USAGE_ALARM, scaleParam.scale);
        }

        if ((ScaleParam.TYPE_NOTIFICATION & scaleParam.typesMask) != 0) {
            vibrationScales.put(USAGE_NOTIFICATION, scaleParam.scale);
            vibrationScales.put(USAGE_COMMUNICATION_REQUEST, scaleParam.scale);
        }

        if ((ScaleParam.TYPE_RINGTONE & scaleParam.typesMask) != 0) {
            vibrationScales.put(USAGE_RINGTONE, scaleParam.scale);
        }

        if ((ScaleParam.TYPE_MEDIA & scaleParam.typesMask) != 0) {
            vibrationScales.put(USAGE_MEDIA, scaleParam.scale);
            vibrationScales.put(USAGE_UNKNOWN, scaleParam.scale);
        }

        if ((ScaleParam.TYPE_INTERACTIVE & scaleParam.typesMask) != 0) {
            vibrationScales.put(USAGE_TOUCH, scaleParam.scale);
            vibrationScales.put(USAGE_HARDWARE_FEEDBACK, scaleParam.scale);
        }
    }
}
