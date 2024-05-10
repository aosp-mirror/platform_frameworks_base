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

package com.android.server.biometrics.sensors;

import static com.android.systemui.shared.Flags.sidefpsControllerRefactor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Single entry point & holder for controllers managing UI overlays for biometrics.
 *
 * For common operations, like {@link #show(int, int, AcquisitionClient)}, modalities are
 * skipped if they are not present (provided as null via the constructor).
 *
 * Use the getters, such as {@link #ifUdfps(OverlayControllerConsumer)}, to get a controller for
 * operations that are unique to a single modality.
 */
public final class SensorOverlays {

    private static final String TAG = "SensorOverlays";

    @NonNull private final Optional<IUdfpsOverlayController> mUdfpsOverlayController;

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    @NonNull private final Optional<ISidefpsController> mSidefpsController;

    /**
     * Create an overlay controller for each modality.
     *
     * @param udfpsOverlayController under display fps or null if not present on device
     * @param sidefpsController side fps or null if not present on device
     */
    public SensorOverlays(
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @Nullable ISidefpsController sidefpsController) {
        mUdfpsOverlayController = Optional.ofNullable(udfpsOverlayController);
        mSidefpsController = Optional.ofNullable(sidefpsController);
    }

    /**
     * Create an overlay controller for each modality.
     *
     * @param udfpsOverlayController under display fps or null if not present on device
     */
    public SensorOverlays(@Nullable IUdfpsOverlayController udfpsOverlayController) {
        mUdfpsOverlayController = Optional.ofNullable(udfpsOverlayController);
        // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
        mSidefpsController = Optional.empty();
    }

    /**
     * Show the overlay.
     *
     * @param sensorId sensor id
     * @param reason reason for showing
     * @param client client performing operation
     */
    public void show(int sensorId, @BiometricRequestConstants.RequestReason int reason,
            @NonNull AcquisitionClient<?> client) {
        if (!sidefpsControllerRefactor()) {
            if (mSidefpsController.isPresent()) {
                try {
                    mSidefpsController.get().show(sensorId, reason);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception when showing the side-fps overlay", e);
                }
            }
        }

        if (mUdfpsOverlayController.isPresent()) {
            final IUdfpsOverlayControllerCallback callback =
                    new IUdfpsOverlayControllerCallback.Stub() {
                        @Override
                        public void onUserCanceled() {
                            client.onUserCanceled();
                        }
                    };

            try {
                mUdfpsOverlayController.get().showUdfpsOverlay(
                        client.getRequestId(), sensorId, reason, callback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when showing the UDFPS overlay", e);
            }
        }
    }

    /**
     * Hide the overlay.
     *
     * @param sensorId sensor id
     */
    public void hide(int sensorId) {
        if (!sidefpsControllerRefactor()) {
            if (mSidefpsController.isPresent()) {
                try {
                    mSidefpsController.get().hide(sensorId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception when hiding the side-fps overlay", e);
                }
            }
        }

        if (mUdfpsOverlayController.isPresent()) {
            try {
                mUdfpsOverlayController.get().hideUdfpsOverlay(sensorId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when hiding the UDFPS overlay", e);
            }
        }
    }

    /**
     * Use the udfps controller, if present.
     * @param consumer action
     */
    public void ifUdfps(OverlayControllerConsumer<IUdfpsOverlayController> consumer) {
        if (mUdfpsOverlayController.isPresent()) {
            try {
                consumer.accept(mUdfpsOverlayController.get());
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception using overlay controller", e);
            }
        }
    }

    /**
     * Consumer for a biometric overlay controller.
     *
     * This behaves like a normal {@link Consumer} except that it will trap and log
     * any thrown {@link RemoteException}.
     *
     * @param <T> the type of the input to the operation
     **/
    @FunctionalInterface
    public interface OverlayControllerConsumer<T> {
        /** Perform the operation. */
        void accept(T t) throws RemoteException;
    }
}
