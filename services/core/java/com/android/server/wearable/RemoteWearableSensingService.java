/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wearable;

import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;

import android.app.wearable.Flags;
import android.app.wearable.IWearableSensingCallback;
import android.app.wearable.WearableSensingManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.SharedMemory;
import android.service.wearable.IWearableSensingService;
import android.service.wearable.WearableSensingService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.ServiceConnector;

import java.io.IOException;

/** Manages the connection to the remote wearable sensing service. */
final class RemoteWearableSensingService extends ServiceConnector.Impl<IWearableSensingService> {
    private static final String TAG =
            com.android.server.wearable.RemoteWearableSensingService.class.getSimpleName();
    private final static boolean DEBUG = false;

    private final Object mSecureConnectionLock = new Object();

    // mNextSecureConnectionContext will only be non-null when we are waiting for the
    // WearableSensingService process to restart. It will be set to null after it is passed into
    // WearableSensingService.
    @GuardedBy("mSecureConnectionLock")
    private SecureWearableConnectionContext mNextSecureConnectionContext;

    @GuardedBy("mSecureConnectionLock")
    private boolean mSecureConnectionProvided = false;

    RemoteWearableSensingService(Context context, ComponentName serviceName,
            int userId) {
        super(context, new Intent(
                        WearableSensingService.SERVICE_INTERFACE).setComponent(serviceName),
                BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES, userId,
                IWearableSensingService.Stub::asInterface);

        // Bind right away
        connect();
    }

    @Override
    protected long getAutoDisconnectTimeoutMs() {
        // Disable automatic unbinding.
        return -1;
    }

    /**
     * Provides a secure connection to the wearable.
     *
     * @param secureWearableConnection The secure connection to the wearable
     * @param wearableSensingCallback The callback for requests such as openFile from the
     *     WearableSensingService.
     * @param statusCallback The callback for service status
     */
    public void provideSecureConnection(
            ParcelFileDescriptor secureWearableConnection,
            IWearableSensingCallback wearableSensingCallback,
            RemoteCallback statusCallback) {
        if (DEBUG) {
            Slog.i(TAG, "#provideSecureConnection");
        }
        if (!Flags.enableRestartWssProcess()) {
            Slog.d(
                    TAG,
                    "FLAG_ENABLE_RESTART_WSS_PROCESS is disabled. Do not attempt to restart the"
                        + " WearableSensingService process");
            provideSecureConnectionInternal(
                    secureWearableConnection, wearableSensingCallback, statusCallback);
            return;
        }
        synchronized (mSecureConnectionLock) {
            if (mNextSecureConnectionContext != null) {
                // A process restart is in progress, #binderDied is about to be called. Replace
                // the previous mNextSecureConnectionContext with the current one
                Slog.i(
                        TAG,
                        "A new wearable connection is provided before the process restart triggered"
                            + " by the previous connection is complete. Discarding the previous"
                            + " connection.");
                if (Flags.enableProvideWearableConnectionApi()) {
                    WearableSensingManagerPerUserService.notifyStatusCallback(
                            mNextSecureConnectionContext.mStatusCallback,
                            WearableSensingManager.STATUS_CHANNEL_ERROR);
                }
                mNextSecureConnectionContext =
                        new SecureWearableConnectionContext(
                                secureWearableConnection, wearableSensingCallback, statusCallback);
                return;
            }
            if (!mSecureConnectionProvided) {
                // no need to kill the process
                provideSecureConnectionInternal(
                        secureWearableConnection, wearableSensingCallback, statusCallback);
                mSecureConnectionProvided = true;
                return;
            }
            mNextSecureConnectionContext =
                    new SecureWearableConnectionContext(
                            secureWearableConnection, wearableSensingCallback, statusCallback);
            // Killing the process causes the binder to die. #binderDied will then be triggered
            killWearableSensingServiceProcess();
        }
    }

    private void provideSecureConnectionInternal(
            ParcelFileDescriptor secureWearableConnection,
            IWearableSensingCallback wearableSensingCallback,
            RemoteCallback statusCallback) {
        Slog.d(TAG, "Providing secure wearable connection.");
        var unused =
                post(
                        service -> {
                            service.provideSecureConnection(
                                    secureWearableConnection,
                                    wearableSensingCallback,
                                    statusCallback);
                            try {
                                // close the local fd after it has been sent to the WSS process
                                secureWearableConnection.close();
                            } catch (IOException ex) {
                                Slog.w(TAG, "Unable to close the local parcelFileDescriptor.", ex);
                            }
                        });
    }

    @Override
    public void binderDied() {
        super.binderDied();
        synchronized (mSecureConnectionLock) {
            if (mNextSecureConnectionContext != null) {
                // This will call #post, which will recreate the process and bind to it
                provideSecureConnectionInternal(
                        mNextSecureConnectionContext.mSecureConnection,
                        mNextSecureConnectionContext.mWearableSensingCallback,
                        mNextSecureConnectionContext.mStatusCallback);
                mNextSecureConnectionContext = null;
            } else {
                mSecureConnectionProvided = false;
                Slog.w(TAG, "Binder died but there is no secure wearable connection to provide.");
            }
        }
    }

    /** Kills the WearableSensingService process. */
    public void killWearableSensingServiceProcess() {
        var unused = post(service -> service.killProcess());
    }

    /**
     * Provides the implementation a data stream to the wearable.
     *
     * @param parcelFileDescriptor The data stream to the wearable
     * @param wearableSensingCallback The callback for requests such as openFile from the
     *     WearableSensingService.
     * @param callback The callback for service status
     */
    public void provideDataStream(
            ParcelFileDescriptor parcelFileDescriptor,
            IWearableSensingCallback wearableSensingCallback,
            RemoteCallback callback) {
        if (DEBUG) {
            Slog.i(TAG, "Providing data stream.");
        }
        var unused =
                post(
                        service -> {
                            service.provideDataStream(
                                    parcelFileDescriptor, wearableSensingCallback, callback);
                            try {
                                // close the local fd after it has been sent to the WSS process
                                parcelFileDescriptor.close();
                            } catch (IOException ex) {
                                Slog.w(TAG, "Unable to close the local parcelFileDescriptor.", ex);
                            }
                        });
    }

    /**
     * Provides the implementation data.
     *
     * @param data Application configuration data to provide to the implementation.
     * @param sharedMemory The unrestricted data blob to provide to the implementation.
     * @param callback The callback for service status
     */
    public void provideData(PersistableBundle data,
            SharedMemory sharedMemory,
            RemoteCallback callback) {
        if (DEBUG) {
            Slog.i(TAG, "Providing data.");
        }
        post(service -> service.provideData(data, sharedMemory, callback));
    }

    /**
     * Registers a data request observer with WearableSensingService.
     *
     * @param dataType The data type to listen to. Values are defined by the application that
     *     implements WearableSensingService.
     * @param dataRequestCallback The observer to send data requests to.
     * @param dataRequestObserverId The unique ID for the data request observer. It will be used for
     *     unregistering the observer.
     * @param packageName The package name of the app that will receive the data requests.
     * @param statusCallback The callback for status of the method call.
     */
    public void registerDataRequestObserver(
            int dataType,
            RemoteCallback dataRequestCallback,
            int dataRequestObserverId,
            String packageName,
            RemoteCallback statusCallback) {
        if (DEBUG) {
            Slog.i(TAG, "Registering data request observer.");
        }
        var unused =
                post(
                        service ->
                                service.registerDataRequestObserver(
                                        dataType,
                                        dataRequestCallback,
                                        dataRequestObserverId,
                                        packageName,
                                        statusCallback));
    }

    /**
     * Unregisters a previously registered data request observer.
     *
     * @param dataType The data type the observer was registered against.
     * @param dataRequestObserverId The unique ID of the observer to unregister.
     * @param packageName The package name of the app that will receive requests sent to the
     *     observer.
     * @param statusCallback The callback for status of the method call.
     */
    public void unregisterDataRequestObserver(
            int dataType,
            int dataRequestObserverId,
            String packageName,
            RemoteCallback statusCallback) {
        if (DEBUG) {
            Slog.i(TAG, "Unregistering data request observer.");
        }
        var unused =
                post(
                        service ->
                                service.unregisterDataRequestObserver(
                                        dataType,
                                        dataRequestObserverId,
                                        packageName,
                                        statusCallback));
    }

    /**
     * Request the wearable to start hotword recognition.
     *
     * @param wearableHotwordCallback The callback to send hotword audio data and format to.
     * @param statusCallback The callback for service status.
     */
    public void startHotwordRecognition(
            RemoteCallback wearableHotwordCallback, RemoteCallback statusCallback) {
        if (DEBUG) {
            Slog.i(TAG, "Starting to listen for hotword.");
        }
        var unused =
                post(
                        service ->
                                service.startHotwordRecognition(
                                        wearableHotwordCallback, statusCallback));
    }

    /**
     * Request the wearable to stop hotword recognition.
     *
     * @param statusCallback The callback for service status.
     */
    public void stopHotwordRecognition(RemoteCallback statusCallback) {
        if (DEBUG) {
            Slog.i(TAG, "Stopping hotword recognition.");
        }
        var unused = post(service -> service.stopHotwordRecognition(statusCallback));
    }

    /**
     * Signals to the {@link WearableSensingService} that hotword audio data is accepted by the
     * {@link android.service.voice.HotwordDetectionService} as valid hotword.
     */
    public void onValidatedByHotwordDetectionService() {
        if (DEBUG) {
            Slog.i(TAG, "Requesting hotword audio data egress.");
        }
        var unused = post(service -> service.onValidatedByHotwordDetectionService());
    }

    /** Stops the active hotword audio stream from the wearable. */
    public void stopActiveHotwordAudio() {
        if (DEBUG) {
            Slog.i(TAG, "Stopping hotword audio.");
        }
        var unused = post(service -> service.stopActiveHotwordAudio());
    }

    private static class SecureWearableConnectionContext {
        final ParcelFileDescriptor mSecureConnection;
        final IWearableSensingCallback mWearableSensingCallback;
        final RemoteCallback mStatusCallback;

        SecureWearableConnectionContext(
                ParcelFileDescriptor secureWearableConnection,
                IWearableSensingCallback wearableSensingCallback,
                RemoteCallback statusCallback) {
            mSecureConnection = secureWearableConnection;
            mWearableSensingCallback = wearableSensingCallback;
            mStatusCallback = statusCallback;
        }
    }
}
