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

import com.android.internal.infra.ServiceConnector;

import java.io.IOException;

/** Manages the connection to the remote wearable sensing service. */
final class RemoteWearableSensingService extends ServiceConnector.Impl<IWearableSensingService> {
    private static final String TAG =
            com.android.server.wearable.RemoteWearableSensingService.class.getSimpleName();
    private final static boolean DEBUG = false;

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
     * @param callback The callback for service status
     */
    public void provideSecureWearableConnection(
            ParcelFileDescriptor secureWearableConnection, RemoteCallback callback) {
        if (DEBUG) {
            Slog.i(TAG, "Providing secure wearable connection.");
        }
        var unused = post(
                service -> {
                    service.provideSecureWearableConnection(secureWearableConnection, callback);
                    try {
                        // close the local fd after it has been sent to the WSS process
                        secureWearableConnection.close();
                    } catch (IOException ex) {
                        Slog.w(TAG, "Unable to close the local parcelFileDescriptor.", ex);
                    }
                });
    }

    /**
     * Provides the implementation a data stream to the wearable.
     *
     * @param parcelFileDescriptor The data stream to the wearable
     * @param callback The callback for service status
     */
    public void provideDataStream(ParcelFileDescriptor parcelFileDescriptor,
            RemoteCallback callback) {
        if (DEBUG) {
            Slog.i(TAG, "Providing data stream.");
        }
        var unused = post(
                service -> {
                    service.provideDataStream(parcelFileDescriptor, callback);
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
}
