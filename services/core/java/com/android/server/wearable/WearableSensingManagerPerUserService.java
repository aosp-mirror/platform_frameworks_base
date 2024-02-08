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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.wearable.Flags;
import android.app.wearable.WearableSensingManager;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.OsConstants;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Per-user manager service for managing sensing {@link AmbientContextEvent}s on Wearables.
 */
final class WearableSensingManagerPerUserService extends
        AbstractPerUserSystemService<WearableSensingManagerPerUserService,
                WearableSensingManagerService> {
    private static final String TAG = WearableSensingManagerPerUserService.class.getSimpleName();

    @Nullable
    @VisibleForTesting
    RemoteWearableSensingService mRemoteService;

    private ComponentName mComponentName;
    private final Object mSecureChannelLock = new Object();

    @GuardedBy("mSecureChannelLock")
    private WearableSensingSecureChannel mSecureChannel;

    WearableSensingManagerPerUserService(
            @NonNull WearableSensingManagerService master, Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
    }

    static void notifyStatusCallback(RemoteCallback statusCallback, int statusCode) {
        Bundle bundle = new Bundle();
        bundle.putInt(
                WearableSensingManager.STATUS_RESPONSE_BUNDLE_KEY, statusCode);
        statusCallback.sendResult(bundle);
    }

    void destroyLocked() {
        Slog.d(TAG, "Trying to cancel the remote request. Reason: Service destroyed.");
        if (mRemoteService != null) {
            synchronized (mLock) {
                mRemoteService.unbind();
                mRemoteService = null;
            }
        }
        synchronized (mSecureChannelLock) {
            if (mSecureChannel != null) {
                mSecureChannel.close();
            }
        }
    }

    @GuardedBy("mLock")
    private void ensureRemoteServiceInitiated() {
        if (mRemoteService == null) {
            mRemoteService = new RemoteWearableSensingService(
                    getContext(), mComponentName, getUserId());
        }
    }

    /**
     * get the currently bound component name.
     */
    @VisibleForTesting
    ComponentName getComponentName() {
        return mComponentName;
    }


    /**
     * Resolves and sets up the service if it had not been done yet. Returns true if the service
     * is available.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean setUpServiceIfNeeded() {
        if (mComponentName == null) {
            mComponentName = updateServiceInfoLocked();
        }
        if (mComponentName == null) {
            return false;
        }

        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                    mComponentName, 0, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while setting up service");
            return false;
        }
        return serviceInfo != null;
    }

    @Override
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    0, mUserId);
            if (serviceInfo != null) {
                final String permission = serviceInfo.permission;
                if (!Manifest.permission.BIND_WEARABLE_SENSING_SERVICE.equals(
                        permission)) {
                    throw new SecurityException(String.format(
                            "Service %s requires %s permission. Found %s permission",
                            serviceInfo.getComponentName(),
                            Manifest.permission.BIND_WEARABLE_SENSING_SERVICE,
                            serviceInfo.permission));
                }
            }
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return serviceInfo;
    }

    @Override
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (super.mLock) {
            super.dumpLocked(prefix, pw);
        }
        if (mRemoteService != null) {
            mRemoteService.dump("", new IndentingPrintWriter(pw, "  "));
        }
    }

    /**
     * Creates a CompanionDeviceManager secure channel and sends a proxy to the wearable sensing
     * service.
     */
    public void onProvideWearableConnection(
            ParcelFileDescriptor wearableConnection, RemoteCallback callback) {
        Slog.i(TAG, "onProvideWearableConnection in per user service.");
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(callback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
        }
        synchronized (mSecureChannelLock) {
            if (mSecureChannel != null) {
                // TODO(b/321012559): Kill the WearableSensingService process if it has not been
                // killed from onError
                mSecureChannel.close();
            }
            try {
                mSecureChannel =
                        WearableSensingSecureChannel.create(
                                getContext().getSystemService(CompanionDeviceManager.class),
                                wearableConnection,
                                new WearableSensingSecureChannel.SecureTransportListener() {
                                    @Override
                                    public void onSecureTransportAvailable(
                                            ParcelFileDescriptor secureTransport) {
                                        Slog.i(TAG, "calling over to remote service.");
                                        synchronized (mLock) {
                                            ensureRemoteServiceInitiated();
                                            mRemoteService.provideSecureWearableConnection(
                                                    secureTransport, callback);
                                        }
                                    }

                                    @Override
                                    public void onError() {
                                        // TODO(b/321012559): Kill the WearableSensingService
                                        // process if mSecureChannel has not been reassigned
                                        if (Flags.enableProvideWearableConnectionApi()) {
                                            notifyStatusCallback(
                                                    callback,
                                                    WearableSensingManager.STATUS_CHANNEL_ERROR);
                                        }
                                    }
                                });
            } catch (IOException ex) {
                Slog.e(TAG, "Unable to create the secure channel.", ex);
                if (Flags.enableProvideWearableConnectionApi()) {
                    notifyStatusCallback(callback, WearableSensingManager.STATUS_CHANNEL_ERROR);
                }
            }
        }
    }

    /**
     * Handles sending the provided data stream for the wearable to the wearable sensing service.
     */
    public void onProvideDataStream(
            ParcelFileDescriptor parcelFileDescriptor,
            RemoteCallback callback) {
        Slog.i(TAG, "onProvideDataStream in per user service.");
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(callback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            Slog.i(TAG, "calling over to remote servvice.");
            ensureRemoteServiceInitiated();
            mRemoteService.provideDataStream(parcelFileDescriptor, callback);
        }
    }

    /**
     * Handles sending the provided data to the wearable sensing service.
     */
    public void onProvidedData(PersistableBundle data,
            SharedMemory sharedMemory,
            RemoteCallback callback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(callback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            if (sharedMemory != null) {
                sharedMemory.setProtect(OsConstants.PROT_READ);
            }
            mRemoteService.provideData(data, sharedMemory, callback);
        }
    }

    /**
     * Handles registering a data request observer.
     *
     * @param dataType The data type to listen to. Values are defined by the application that
     *     implements WearableSensingService.
     * @param dataRequestObserver The observer to register.
     * @param dataRequestObserverId The unique ID for the data request observer. It will be used for
     *     unregistering the observer.
     * @param packageName The package name of the app that will receive the data requests.
     * @param statusCallback The callback for status of the method call.
     */
    public void onRegisterDataRequestObserver(
            int dataType,
            RemoteCallback dataRequestObserver,
            int dataRequestObserverId,
            String packageName,
            RemoteCallback statusCallback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.registerDataRequestObserver(
                    dataType,
                    dataRequestObserver,
                    dataRequestObserverId,
                    packageName,
                    statusCallback);
        }
    }

    /**
     * Handles unregistering a previously registered data request observer.
     *
     * @param dataType The data type the observer was registered against.
     * @param dataRequestObserverId The unique ID of the observer to unregister.
     * @param packageName The package name of the app that will receive requests sent to the
     *     observer.
     * @param statusCallback The callback for status of the method call.
     */
    public void onUnregisterDataRequestObserver(
            int dataType,
            int dataRequestObserverId,
            String packageName,
            RemoteCallback statusCallback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.unregisterDataRequestObserver(
                    dataType, dataRequestObserverId, packageName, statusCallback);
        }
    }
}
