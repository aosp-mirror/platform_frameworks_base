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

package com.android.server.biometrics.sensors.face.aidl;

import static android.hardware.face.FaceManager.getErrorString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.face.FaceAuthenticateOptions;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.DetectionConsumer;

import java.util.function.Supplier;

/**
 * Performs face detection without exposing any matching information (e.g. accept/reject have the
 * same haptic, lockout counter is not increased).
 */
public class FaceDetectClient extends AcquisitionClient<AidlSession> implements DetectionConsumer {

    private static final String TAG = "FaceDetectClient";

    private final boolean mIsStrongBiometric;
    private final FaceAuthenticateOptions mOptions;
    @Nullable private ICancellationSignal mCancellationSignal;
    @Nullable private SensorPrivacyManager mSensorPrivacyManager;
    @NonNull private final AuthenticationStateListeners mAuthenticationStateListeners;

    FaceDetectClient(@NonNull Context context, @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FaceAuthenticateOptions options,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            boolean isStrongBiometric) {
        this(context, lazyDaemon, token, requestId, listener, options,
                logger, biometricContext, authenticationStateListeners, isStrongBiometric,
                context.getSystemService(SensorPrivacyManager.class));
    }

    @VisibleForTesting
    FaceDetectClient(@NonNull Context context, @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull FaceAuthenticateOptions options,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull AuthenticationStateListeners authenticationStateListeners,
            boolean isStrongBiometric, SensorPrivacyManager sensorPrivacyManager) {
        super(context, lazyDaemon, token, listener, options.getUserId(),
                options.getOpPackageName(), 0 /* cookie */, options.getSensorId(),
                false /* shouldVibrate */, logger, biometricContext);
        setRequestId(requestId);
        mAuthenticationStateListeners = authenticationStateListeners;
        mIsStrongBiometric = isStrongBiometric;
        mSensorPrivacyManager = sensorPrivacyManager;
        mOptions = options;
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void stopHalOperation() {
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FACE, BiometricRequestConstants.REASON_AUTH_KEYGUARD)
                .build()
        );
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
        mAuthenticationStateListeners.onAuthenticationStarted(new AuthenticationStartedInfo
                .Builder(BiometricSourceType.FACE, BiometricRequestConstants.REASON_AUTH_KEYGUARD)
                .build()
        );
        if (mSensorPrivacyManager != null
                && mSensorPrivacyManager
                .isSensorPrivacyEnabled(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                    SensorPrivacyManager.Sensors.CAMERA)) {
            onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
            return;
        }

        try {
            doDetectInteraction();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting face detect", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void onError(@BiometricConstants.Errors int error, int vendorCode) {
        mAuthenticationStateListeners.onAuthenticationError(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD,
                        getErrorString(getContext(), error, vendorCode), error).build()
        );
        super.onError(error, vendorCode);
        mAuthenticationStateListeners.onAuthenticationStopped(new AuthenticationStoppedInfo
                .Builder(BiometricSourceType.FACE, BiometricRequestConstants.REASON_AUTH_KEYGUARD)
                .build()
        );
    }

    private void doDetectInteraction() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        if (session.hasContextMethods()) {
            final OperationContextExt opContext = getOperationContext();
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    mCancellationSignal = session.getSession().detectInteractionWithContext(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception when requesting face detect", e);
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
            getListener().onDetected(getSensorId(), getTargetUserId(), mIsStrongBiometric);
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
