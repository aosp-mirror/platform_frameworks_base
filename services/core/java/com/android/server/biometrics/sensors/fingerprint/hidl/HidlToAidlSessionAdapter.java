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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.annotation.NonNull;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.fingerprint.UdfpsHelper;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintGetAuthenticatorIdClient;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintInvalidationClient;

import java.util.function.Supplier;

/**
 * Adapter to convert HIDL methods into AIDL interface {@link ISession}.
 */
public class HidlToAidlSessionAdapter implements ISession {

    private final String TAG = "HidlToAidlSessionAdapter";

    @VisibleForTesting
    static final int ENROLL_TIMEOUT_SEC = 60;
    @NonNull
    private final Supplier<IBiometricsFingerprint> mSession;
    private final int mUserId;
    private HidlToAidlCallbackConverter mHidlToAidlCallbackConverter;

    public HidlToAidlSessionAdapter(Supplier<IBiometricsFingerprint> session, int userId,
            AidlResponseHandler aidlResponseHandler) {
        mSession = session;
        mUserId = userId;
        setCallback(aidlResponseHandler);
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public void generateChallenge() throws RemoteException {
        long challenge = mSession.get().preEnroll();
        mHidlToAidlCallbackConverter.onChallengeGenerated(challenge);
    }

    @Override
    public void revokeChallenge(long challenge) throws RemoteException {
        mSession.get().postEnroll();
        mHidlToAidlCallbackConverter.onChallengeRevoked(0L);
    }

    @Override
    public ICancellationSignal enroll(HardwareAuthToken hat) throws RemoteException {
        mSession.get().enroll(HardwareAuthTokenUtils.toByteArray(hat), mUserId,
                ENROLL_TIMEOUT_SEC);
        return new Cancellation();
    }

    @Override
    public ICancellationSignal authenticate(long operationId) throws RemoteException {
        mSession.get().authenticate(operationId, mUserId);
        return new Cancellation();
    }

    @Override
    public ICancellationSignal detectInteraction() throws RemoteException {
        mSession.get().authenticate(0, mUserId);
        return new Cancellation();
    }

    @Override
    public void enumerateEnrollments() throws RemoteException {
        mSession.get().enumerate();
    }

    @Override
    public void removeEnrollments(int[] enrollmentIds) throws RemoteException {
        if (enrollmentIds.length > 1) {
            mSession.get().remove(mUserId, 0);
        } else {
            mSession.get().remove(mUserId, enrollmentIds[0]);
        }
    }

    @Override
    public void onPointerDown(int pointerId, int x, int y, float minor, float major)
            throws RemoteException {
        UdfpsHelper.onFingerDown(mSession.get(), x, y, minor, major);
    }

    @Override
    public void onPointerUp(int pointerId) throws RemoteException {
        UdfpsHelper.onFingerUp(mSession.get());
    }

    @Override
    public void getAuthenticatorId() throws RemoteException {
        Log.e(TAG, "getAuthenticatorId unsupported in HIDL");
        mHidlToAidlCallbackConverter.unsupportedClientScheduled(
                FingerprintGetAuthenticatorIdClient.class);
    }

    @Override
    public void invalidateAuthenticatorId() throws RemoteException {
        Log.e(TAG, "invalidateAuthenticatorId unsupported in HIDL");
        mHidlToAidlCallbackConverter.unsupportedClientScheduled(
                FingerprintInvalidationClient.class);
    }

    @Override
    public void resetLockout(HardwareAuthToken hat) throws RemoteException {
        mHidlToAidlCallbackConverter.onResetLockout();
    }

    @Override
    public void close() throws RemoteException {
        Log.e(TAG, "close unsupported in HIDL");
    }

    @Override
    public void onUiReady() throws RemoteException {
        Log.e(TAG, "onUiReady unsupported in HIDL");
    }

    @Override
    public ICancellationSignal authenticateWithContext(long operationId, OperationContext context)
            throws RemoteException {
        Log.e(TAG, "authenticateWithContext unsupported in HIDL");
        return authenticate(operationId);
    }

    @Override
    public ICancellationSignal enrollWithContext(HardwareAuthToken hat, OperationContext context)
            throws RemoteException {
        Log.e(TAG, "enrollWithContext unsupported in HIDL");
        return enroll(hat);
    }

    @Override
    public ICancellationSignal detectInteractionWithContext(OperationContext context)
            throws RemoteException {
        Log.e(TAG, "enrollWithContext unsupported in HIDL");
        return detectInteraction();
    }

    @Override
    public void onPointerDownWithContext(PointerContext context) throws RemoteException {
        Log.e(TAG, "onPointerDownWithContext unsupported in HIDL");
        onPointerDown(context.pointerId, (int) context.x, (int) context.y, context.minor,
                context.major);
    }

    @Override
    public void onPointerUpWithContext(PointerContext context) throws RemoteException {
        Log.e(TAG, "onPointerUpWithContext unsupported in HIDL");
        onPointerUp(context.pointerId);
    }

    @Override
    public void onContextChanged(OperationContext context) throws RemoteException {
        Log.e(TAG, "onContextChanged unsupported in HIDL");
    }

    @Override
    public void onPointerCancelWithContext(PointerContext context) throws RemoteException {
        Log.e(TAG, "onPointerCancelWithContext unsupported in HIDL");
    }

    @Override
    public void setIgnoreDisplayTouches(boolean shouldIgnore) throws RemoteException {
        Log.e(TAG, "setIgnoreDisplayTouches unsupported in HIDL");
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        Log.e(TAG, "getInterfaceVersion unsupported in HIDL");
        return 0;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        Log.e(TAG, "getInterfaceHash unsupported in HIDL");
        return null;
    }

    public long getAuthenticatorIdForUpdateClient() throws RemoteException {
        return mSession.get().getAuthenticatorId();
    }

    public void setActiveGroup(int userId, String absolutePath) throws RemoteException {
        mSession.get().setActiveGroup(userId, absolutePath);
    }

    private void setCallback(AidlResponseHandler aidlResponseHandler) {
        mHidlToAidlCallbackConverter = new HidlToAidlCallbackConverter(aidlResponseHandler);
        try {
            if (mSession.get() != null) {
                long halId = mSession.get().setNotify(mHidlToAidlCallbackConverter);
                Slog.d(TAG, "Fingerprint HAL ready, HAL ID: " + halId);
                if (halId == 0) {
                    Slog.d(TAG, "Unable to set HIDL callback.");
                }
            } else {
                Slog.e(TAG, "Unable to set HIDL callback. HIDL daemon is null.");
            }
        } catch (RemoteException e) {
            Slog.d(TAG, "Failed to set callback");
        }
    }

    private class Cancellation extends ICancellationSignal.Stub {

        Cancellation() {}
        @Override
        public void cancel() throws RemoteException {
            try {
                mSession.get().cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when requesting cancel", e);
            }
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return 0;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return null;
        }
    }
}
