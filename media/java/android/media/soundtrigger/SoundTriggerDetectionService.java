/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.soundtrigger;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.UUID;

/**
 * A service that allows interaction with the actual sound trigger detection on the system.
 *
 * <p> Sound trigger detection refers to detectors that match generic sound patterns that are
 * not voice-based. The voice-based recognition models should utilize the {@link
 * android.service.voice.VoiceInteractionService} instead. Access to this class needs to be
 * protected by the {@value android.Manifest.permission.BIND_SOUND_TRIGGER_DETECTION_SERVICE}
 * permission granted only to the system.
 *
 * <p>This service has to be explicitly started by an app, the system does not scan for and start
 * these services.
 *
 * <p>If an operation ({@link #onGenericRecognitionEvent}, {@link #onError},
 * {@link #onRecognitionPaused}, {@link #onRecognitionResumed}) is triggered the service is
 * considered as running in the foreground. Once the operation is processed the service should call
 * {@link #operationFinished(UUID, int)}. If this does not happen in
 * {@link SoundTriggerManager#getDetectionServiceOperationsTimeout()} milliseconds
 * {@link #onStopOperation(UUID, Bundle, int)} is called and the service is unbound.
 *
 * <p>The total amount of operations per day might be limited.
 *
 * @hide
 */
@SystemApi
public abstract class SoundTriggerDetectionService extends Service {
    private static final String LOG_TAG = SoundTriggerDetectionService.class.getSimpleName();

    private static final boolean DEBUG = false;

    private final Object mLock = new Object();

    /**
     * Client indexed by model uuid. This is needed for the {@link #operationFinished(UUID, int)}
     * callbacks.
     */
    @GuardedBy("mLock")
    private final ArrayMap<UUID, ISoundTriggerDetectionServiceClient> mClients =
            new ArrayMap<>();

    private Handler mHandler;

    /**
     * @hide
     */
    @Override
    protected final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new Handler(base.getMainLooper());
    }

    private void setClient(@NonNull UUID uuid, @Nullable Bundle params,
            @NonNull ISoundTriggerDetectionServiceClient client) {
        if (DEBUG) Log.i(LOG_TAG, uuid + ": handle setClient");

        synchronized (mLock) {
            mClients.put(uuid, client);
        }
        onConnected(uuid, params);
    }

    private void removeClient(@NonNull UUID uuid, @Nullable Bundle params) {
        if (DEBUG) Log.i(LOG_TAG, uuid + ": handle removeClient");

        synchronized (mLock) {
            mClients.remove(uuid);
        }
        onDisconnected(uuid, params);
    }

    /**
     * The system has connected to this service for the recognition registered for the model
     * {@code uuid}.
     *
     * <p> This is called before any operations are delivered.
     *
     * @param uuid   The {@code uuid} of the model the recognitions is registered for
     * @param params The {@code params} passed when the recognition was started
     */
    @MainThread
    public void onConnected(@NonNull UUID uuid, @Nullable Bundle params) {
        /* do nothing */
    }

    /**
     * The system has disconnected from this service for the recognition registered for the model
     * {@code uuid}.
     *
     * <p>Once this is called {@link #operationFinished} cannot be called anymore for
     * {@code uuid}.
     *
     * <p> {@link #onConnected(UUID, Bundle)} is called before any further operations are delivered.
     *
     * @param uuid   The {@code uuid} of the model the recognitions is registered for
     * @param params The {@code params} passed when the recognition was started
     */
    @MainThread
    public void onDisconnected(@NonNull UUID uuid, @Nullable Bundle params) {
        /* do nothing */
    }

    /**
     * A new generic sound trigger event has been detected.
     *
     * @param uuid   The {@code uuid} of the model the recognition is registered for
     * @param params The {@code params} passed when the recognition was started
     * @param opId The id of this operation. Once the operation is done, this service needs to call
     *             {@link #operationFinished(UUID, int)}
     * @param event The event that has been detected
     */
    @MainThread
    public void onGenericRecognitionEvent(@NonNull UUID uuid, @Nullable Bundle params, int opId,
            @NonNull SoundTrigger.RecognitionEvent event) {
        operationFinished(uuid, opId);
    }

    /**
     * A error has been detected.
     *
     * @param uuid   The {@code uuid} of the model the recognition is registered for
     * @param params The {@code params} passed when the recognition was started
     * @param opId The id of this operation. Once the operation is done, this service needs to call
     *             {@link #operationFinished(UUID, int)}
     * @param status The error code detected
     */
    @MainThread
    public void onError(@NonNull UUID uuid, @Nullable Bundle params, int opId, int status) {
        operationFinished(uuid, opId);
    }

    /**
     * An operation took too long and should be stopped.
     *
     * @param uuid   The {@code uuid} of the model the recognition is registered for
     * @param params The {@code params} passed when the recognition was started
     * @param opId The id of the operation that took too long
     */
    @MainThread
    public abstract void onStopOperation(@NonNull UUID uuid, @Nullable Bundle params, int opId);

    /**
     * Tell that the system that an operation has been fully processed.
     *
     * @param uuid The {@code uuid} of the model the recognition is registered for
     * @param opId The id of the operation that is processed
     */
    public final void operationFinished(@Nullable UUID uuid, int opId) {
        try {
            ISoundTriggerDetectionServiceClient client;
            synchronized (mLock) {
                client = mClients.get(uuid);

                if (client == null) {
                    Log.w(LOG_TAG, "operationFinished called, but no client for "
                            + uuid + ". Was this called after onDisconnected?");
                    return;
                }
            }
            client.onOpFinished(opId);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "operationFinished, remote exception for client " + uuid, e);
        }
    }

    /**
     * @hide
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new ISoundTriggerDetectionService.Stub() {
            private final Object mBinderLock = new Object();

            /** Cached params bundles indexed by the model uuid */
            @GuardedBy("mBinderLock")
            public final ArrayMap<UUID, Bundle> mParams = new ArrayMap<>();

            @Override
            public void setClient(ParcelUuid puuid, Bundle params,
                    ISoundTriggerDetectionServiceClient client) {
                UUID uuid = puuid.getUuid();
                synchronized (mBinderLock) {
                    mParams.put(uuid, params);
                }

                if (DEBUG) Log.i(LOG_TAG, uuid + ": setClient(" + params + ")");
                mHandler.sendMessage(obtainMessage(SoundTriggerDetectionService::setClient,
                        SoundTriggerDetectionService.this, uuid, params, client));
            }

            @Override
            public void removeClient(ParcelUuid puuid) {
                UUID uuid = puuid.getUuid();
                Bundle params;
                synchronized (mBinderLock) {
                    params = mParams.remove(uuid);
                }

                if (DEBUG) Log.i(LOG_TAG, uuid + ": removeClient");
                mHandler.sendMessage(obtainMessage(SoundTriggerDetectionService::removeClient,
                        SoundTriggerDetectionService.this, uuid, params));
            }

            @Override
            public void onGenericRecognitionEvent(ParcelUuid puuid, int opId,
                    SoundTrigger.GenericRecognitionEvent event) {
                UUID uuid = puuid.getUuid();
                Bundle params;
                synchronized (mBinderLock) {
                    params = mParams.get(uuid);
                }

                if (DEBUG) Log.i(LOG_TAG, uuid + "(" + opId + "): onGenericRecognitionEvent");
                mHandler.sendMessage(
                        obtainMessage(SoundTriggerDetectionService::onGenericRecognitionEvent,
                                SoundTriggerDetectionService.this, uuid, params, opId, event));
            }

            @Override
            public void onError(ParcelUuid puuid, int opId, int status) {
                UUID uuid = puuid.getUuid();
                Bundle params;
                synchronized (mBinderLock) {
                    params = mParams.get(uuid);
                }

                if (DEBUG) Log.i(LOG_TAG, uuid + "(" + opId + "): onError(" + status + ")");
                mHandler.sendMessage(obtainMessage(SoundTriggerDetectionService::onError,
                        SoundTriggerDetectionService.this, uuid, params, opId, status));
            }

            @Override
            public void onStopOperation(ParcelUuid puuid, int opId) {
                UUID uuid = puuid.getUuid();
                Bundle params;
                synchronized (mBinderLock) {
                    params = mParams.get(uuid);
                }

                if (DEBUG) Log.i(LOG_TAG, uuid + "(" + opId + "): onStopOperation");
                mHandler.sendMessage(obtainMessage(SoundTriggerDetectionService::onStopOperation,
                        SoundTriggerDetectionService.this, uuid, params, opId));
            }
        };
    }

    @CallSuper
    @Override
    public boolean onUnbind(Intent intent) {
        mClients.clear();

        return false;
    }
}
