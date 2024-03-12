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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.frameworks.vibrator.IVibratorControlService;
import android.frameworks.vibrator.IVibratorController;
import android.frameworks.vibrator.VibrationParam;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

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
    private final Object mLock;

    public VibratorControlService(VibratorControllerHolder vibratorControllerHolder, Object lock) {
        mVibratorControllerHolder = vibratorControllerHolder;
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
            mVibratorControllerHolder.setVibratorController(null);
        }
    }

    @Override
    public void setVibrationParams(
            @SuppressLint("ArrayReturn") VibrationParam[] params, IVibratorController token)
            throws RemoteException {
        // TODO(b/305939964): Add set vibration implementation.
    }

    @Override
    public void clearVibrationParams(int types, IVibratorController token) throws RemoteException {
        // TODO(b/305939964): Add clear vibration implementation.
    }

    @Override
    public void onRequestVibrationParamsComplete(
            IBinder requestToken, @SuppressLint("ArrayReturn") VibrationParam[] result)
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
}
