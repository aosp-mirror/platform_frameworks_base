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

package android.service.remotelockscreenvalidation;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.RemoteLockscreenValidationResult;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

/**
 * Provides an interface to validate a remote device's lockscreen
 * @hide
 */
@SystemApi
public abstract class RemoteLockscreenValidationService extends Service {

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_REMOTE_LOCKSCREEN_VALIDATION_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.remotelockscreenvalidation.RemoteLockscreenValidationService";
    private static final String TAG = RemoteLockscreenValidationService.class.getSimpleName();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final IRemoteLockscreenValidationService mInterface =
            new IRemoteLockscreenValidationService.Stub() {
                @Override
                public void validateLockscreenGuess(
                        byte[] guess, IRemoteLockscreenValidationCallback callback) {
                    mHandler.sendMessage(obtainMessage(
                            RemoteLockscreenValidationService::onValidateLockscreenGuess,
                            RemoteLockscreenValidationService.this, guess,
                            new OutcomeReceiver<RemoteLockscreenValidationResult,
                                    Exception>() {
                                @Override
                                public void onResult(RemoteLockscreenValidationResult result) {
                                    try {
                                        callback.onSuccess(result);
                                    } catch (RemoteException e) {
                                        e.rethrowFromSystemServer();
                                    }
                                }
                                @Override
                                public void onError(Exception e) {
                                    try {
                                        callback.onFailure(e.getMessage());
                                    } catch (RemoteException ex) {
                                        ex.rethrowFromSystemServer();
                                    }
                                }
                            }
                    ));
                }
            };

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.w(TAG, "Wrong action");
            return null;
        }
        return mInterface.asBinder();
    }

    /**
     * Validates the lockscreen guess.
     *
     * <p>Implementation should send guess to remote device and perform lockscreen validation
     * using {@link android.app.KeyguardManager#validateRemoteLockScreen}.
     *
     * @param guess lockscreen guess
     * @param callback object used to relay the response of the guess validation
     */
    public abstract void onValidateLockscreenGuess(@NonNull byte[] guess,
            @NonNull OutcomeReceiver<RemoteLockscreenValidationResult, Exception> callback);
}
