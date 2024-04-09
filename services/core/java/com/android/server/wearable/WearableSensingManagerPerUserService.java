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

import static android.service.wearable.WearableSensingService.HOTWORD_AUDIO_STREAM_BUNDLE_KEY;
import static android.system.OsConstants.F_GETFL;
import static android.system.OsConstants.O_ACCMODE;
import static android.system.OsConstants.O_RDONLY;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.wearable.Flags;
import android.app.wearable.IWearableSensingCallback;
import android.app.wearable.WearableSensingManager;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.HotwordAudioStream;
import android.service.voice.VoiceInteractionManagerInternal;
import android.service.voice.VoiceInteractionManagerInternal.WearableHotwordDetectionCallback;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-user manager service for managing sensing {@link AmbientContextEvent}s on Wearables.
 */
final class WearableSensingManagerPerUserService extends
        AbstractPerUserSystemService<WearableSensingManagerPerUserService,
                WearableSensingManagerService> {
    private static final String TAG = WearableSensingManagerPerUserService.class.getSimpleName();

    private final PackageManagerInternal mPackageManagerInternal;

    @Nullable
    @VisibleForTesting
    RemoteWearableSensingService mRemoteService;

    @Nullable private VoiceInteractionManagerInternal mVoiceInteractionManagerInternal;

    @GuardedBy("mLock")
    private ComponentName mComponentName;

    private final Object mSecureChannelLock = new Object();

    @GuardedBy("mSecureChannelLock")
    private WearableSensingSecureChannel mSecureChannel;

    WearableSensingManagerPerUserService(
            @NonNull WearableSensingManagerService master, Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    public static void notifyStatusCallback(RemoteCallback statusCallback, int statusCode) {
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

    @GuardedBy("mLock")
    private boolean ensureVoiceInteractionManagerInternalInitiated() {
        if (mVoiceInteractionManagerInternal == null) {
            mVoiceInteractionManagerInternal =
                    LocalServices.getService(VoiceInteractionManagerInternal.class);
        }
        return mVoiceInteractionManagerInternal != null;
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
    public void onProvideConnection(
            ParcelFileDescriptor wearableConnection,
            IWearableSensingCallback wearableSensingCallback,
            RemoteCallback statusCallback) {
        Slog.i(TAG, "onProvideConnection in per user service.");
        final IWearableSensingCallback wrappedWearableSensingCallback;
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            wrappedWearableSensingCallback = wrapWearableSensingCallback(wearableSensingCallback);
        }
        synchronized (mSecureChannelLock) {
            if (mSecureChannel != null) {
                mSecureChannel.close();
            }
            try {
                final AtomicReference<WearableSensingSecureChannel> currentSecureChannelRef =
                        new AtomicReference<>();
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
                                            mRemoteService.provideSecureConnection(
                                                    secureTransport,
                                                    wrappedWearableSensingCallback,
                                                    statusCallback);
                                        }
                                    }

                                    @Override
                                    public void onError() {
                                        if (Flags.enableRestartWssProcess()) {
                                            synchronized (mSecureChannelLock) {
                                                if (mSecureChannel != null
                                                        && mSecureChannel
                                                                == currentSecureChannelRef.get()) {
                                                    mRemoteService
                                                            .killWearableSensingServiceProcess();
                                                    mSecureChannel = null;
                                                }
                                            }
                                        }
                                        if (Flags.enableProvideWearableConnectionApi()) {
                                            notifyStatusCallback(
                                                    statusCallback,
                                                    WearableSensingManager.STATUS_CHANNEL_ERROR);
                                        }
                                    }
                                });
                currentSecureChannelRef.set(mSecureChannel);
            } catch (IOException ex) {
                Slog.e(TAG, "Unable to create the secure channel.", ex);
                if (Flags.enableProvideWearableConnectionApi()) {
                    notifyStatusCallback(
                            statusCallback, WearableSensingManager.STATUS_CHANNEL_ERROR);
                }
            }
        }
    }

    /**
     * Handles sending the provided data stream for the wearable to the wearable sensing service.
     */
    public void onProvideDataStream(
            ParcelFileDescriptor parcelFileDescriptor,
            @Nullable IWearableSensingCallback wearableSensingCallback,
            RemoteCallback statusCallback) {
        Slog.i(
                TAG,
                "onProvideDataStream in per user service. Is data stream read-only? "
                        + isReadOnly(parcelFileDescriptor));
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            Slog.i(TAG, "calling over to remote servvice.");
            ensureRemoteServiceInitiated();
            mRemoteService.provideDataStream(
                    parcelFileDescriptor,
                    wrapWearableSensingCallback(wearableSensingCallback),
                    statusCallback);
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

    /** Handles starting hotword listening. */
    public void onStartHotwordRecognition(
            ComponentName targetVisComponentName, RemoteCallback statusCallback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            if (!ensureVoiceInteractionManagerInternalInitiated()) {
                Slog.w(TAG, "Voice interaction manager is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.startHotwordRecognition(
                    createWearableHotwordCallback(targetVisComponentName), statusCallback);
        }
    }

    /** Handles stopping hotword listening. */
    public void onStopHotwordRecognition(RemoteCallback statusCallback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.stopHotwordRecognition(statusCallback);
        }
    }

    private void onValidatedByHotwordDetectionService() {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Wearable sensing service is not available at this moment.");
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.onValidatedByHotwordDetectionService();
        }
    }

    private void stopActiveHotwordAudio() {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Wearable sensing service is not available at this moment.");
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.stopActiveHotwordAudio();
        }
    }

    private RemoteCallback createWearableHotwordCallback(ComponentName targetVisComponentName) {
        return new RemoteCallback(
                result -> {
                    HotwordAudioStream hotwordAudioStream =
                            result.getParcelable(
                                    HOTWORD_AUDIO_STREAM_BUNDLE_KEY, HotwordAudioStream.class);
                    if (hotwordAudioStream == null) {
                        Slog.w(TAG, "No hotword audio stream received, unable to process hotword.");
                        return;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        mVoiceInteractionManagerInternal.startListeningFromWearable(
                                hotwordAudioStream.getAudioStreamParcelFileDescriptor(),
                                hotwordAudioStream.getAudioFormat(),
                                hotwordAudioStream.getMetadata(),
                                targetVisComponentName,
                                getUserId(),
                                createHotwordDetectionCallback());
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                });
    }

    private WearableHotwordDetectionCallback createHotwordDetectionCallback() {
        return new WearableHotwordDetectionCallback() {
            @Override
            public void onDetected() {
                Slog.i(TAG, "hotwordDetectionCallback onDetected.");
                onValidatedByHotwordDetectionService();
            }

            @Override
            public void onRejected() {
                Slog.i(TAG, "hotwordDetectionCallback onRejected.");
                stopActiveHotwordAudio();
            }

            @Override
            public void onError(String errorMessage) {
                Slog.i(TAG, "hotwordDetectionCallback onError. ErrorMessage: " + errorMessage);
                stopActiveHotwordAudio();
            }
        };
    }

    @GuardedBy("mLock")
    private @Nullable IWearableSensingCallback wrapWearableSensingCallback(
            IWearableSensingCallback callbackFromAppProcess) {
        if (callbackFromAppProcess == null) {
            return null;
        }
        if (mComponentName == null) {
            Slog.w(TAG, "Cannot create WearableSensingCallback because mComponentName is null.");
            return null;
        }
        if (Binder.getCallingUid()
                != mPackageManagerInternal.getPackageUid(
                        mComponentName.getPackageName(), /* flags= */ 0, mUserId)) {
            Slog.d(
                    TAG,
                    "Caller does not belong to the package that provides the WearableSensingService"
                            + " implementation. Do not forward WearableSensingCallback to"
                            + " WearableSensingService.");
            return null;
        }
        return new IWearableSensingCallback.Stub() {
            @Override
            public void openFile(
                    String filename,
                    AndroidFuture<ParcelFileDescriptor> futureFromWearableSensingService)
                    throws RemoteException {
                AndroidFuture<ParcelFileDescriptor> futureFromSystemServer =
                        new AndroidFuture<ParcelFileDescriptor>()
                                .whenComplete(
                                        (pfdFromApp, throwable) -> {
                                            if (throwable != null) {
                                                Slog.e(
                                                        TAG,
                                                        "Error when reading file " + filename,
                                                        throwable);
                                                futureFromWearableSensingService.complete(null);
                                                return;
                                            }
                                            if (pfdFromApp == null) {
                                                futureFromWearableSensingService.complete(null);
                                                return;
                                            }
                                            if (isReadOnly(pfdFromApp)) {
                                                futureFromWearableSensingService.complete(
                                                        pfdFromApp);
                                            } else {
                                                Slog.w(
                                                        TAG,
                                                        "Received writable ParcelFileDescriptor"
                                                            + " from app process. To prevent"
                                                            + " arbitrary data egress, sending null"
                                                            + " to WearableSensingService"
                                                            + " instead.");
                                                futureFromWearableSensingService.complete(null);
                                            }
                                        });
                callbackFromAppProcess.openFile(filename, futureFromSystemServer);
            }
        };
    }

    private static boolean isReadOnly(ParcelFileDescriptor parcelFileDescriptor) {
        try {
            int readMode =
                    Os.fcntlInt(parcelFileDescriptor.getFileDescriptor(), F_GETFL, 0) & O_ACCMODE;
            return readMode == O_RDONLY;
        } catch (ErrnoException ex) {
            Slog.w(
                    TAG,
                    "Error encountered when trying to determine if the parcelFileDescriptor is"
                        + " read-only. Treating it as not read-only",
                    ex);
        }
        return false;
    }
}
