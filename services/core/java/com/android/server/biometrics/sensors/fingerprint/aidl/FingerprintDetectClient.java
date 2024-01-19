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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static com.android.systemui.shared.Flags.sidefpsControllerRefactor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.DetectionConsumer;
import com.android.server.biometrics.sensors.SensorOverlays;

import java.util.function.Supplier;

/**
 * Performs fingerprint detection without exposing any matching information (e.g. accept/reject
 * have the same haptic, lockout counter is not increased).
 */
public class FingerprintDetectClient extends AcquisitionClient<AidlSession>
        implements DetectionConsumer {

    private static final String TAG = "FingerprintDetectClient";

    private final boolean mIsStrongBiometric;
    private final FingerprintAuthenticateOptions mOptions;
    @NonNull private final SensorOverlays mSensorOverlays;
    @Nullable private ICancellationSignal mCancellationSignal;

    public FingerprintDetectClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FingerprintAuthenticateOptions options,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            boolean isStrongBiometric) {
        super(context, lazyDaemon, token, listener, options.getUserId(),
                options.getOpPackageName(), 0 /* cookie */, options.getSensorId(),
                true /* shouldVibrate */, biometricLogger, biometricContext);
        setRequestId(requestId);
        mIsStrongBiometric = isStrongBiometric;
        if (sidefpsControllerRefactor()) {
            mSensorOverlays = new SensorOverlays(udfpsOverlayController);
        } else {
            mSensorOverlays = new SensorOverlays(
                    udfpsOverlayController, null /* sideFpsController */);
        }
        mOptions = options;
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());
        unsubscribeBiometricContext();

        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), BiometricRequestConstants.REASON_AUTH_KEYGUARD,
                this);

        try {
            if (Flags.deHidl()) {
                startDetectInteraction();
            } else {
                mCancellationSignal = doDetectInteraction();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting finger detect", e);
            mSensorOverlays.hide(getSensorId());
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doDetectInteraction() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        if (session.hasContextMethods()) {
            final OperationContextExt opContext = getOperationContext();
            final ICancellationSignal cancel = session.getSession().detectInteractionWithContext(
                    opContext.toAidlContext(mOptions));
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            });
            return cancel;
        } else {
            return session.getSession().detectInteraction();
        }
    }

    private void startDetectInteraction() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        if (session.hasContextMethods()) {
            final OperationContextExt opContext = getOperationContext();
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    mCancellationSignal = session.getSession().detectInteractionWithContext(
                            ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to start detect interaction", e);
                    mSensorOverlays.hide(getSensorId());
                    mCallback.onClientFinished(this, false /* success */);
                }
            }, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            }, mOptions);
        } else {
            mCancellationSignal = session.getSession().detectInteraction();
        }
    }

    @Override
    public void onInteractionDetected() {
        vibrateSuccess();

        try {
            if (getListener() != null) {
                getListener().onDetected(getSensorId(), getTargetUserId(), mIsStrongBiometric);
            } else {
                Slog.e(TAG, "Listener is null!");
            }
            mCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when sending onDetected", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_DETECT_INTERACTION;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }
}
