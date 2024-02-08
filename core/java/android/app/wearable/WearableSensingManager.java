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

package android.app.wearable;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.ambientcontext.AmbientContextEvent;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.wearable.WearableSensingService;
import android.system.OsConstants;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Allows granted apps to manage the WearableSensingService.
 * Applications are responsible for managing the connection to Wearables. Applications can choose
 * to provide a data stream to the WearableSensingService to use for
 * computing {@link AmbientContextEvent}s. Applications can also optionally provide their own
 * defined data to power the detection of {@link AmbientContextEvent}s.
 * Methods on this class requires the caller to hold and be granted the
 * {@link Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE}.
 *
 * <p>The use of "Wearable" here is not the same as the Android Wear platform and should be treated
 * separately. </p>
 *
 * @hide
 */

@SystemApi
@SystemService(Context.WEARABLE_SENSING_SERVICE)
public class WearableSensingManager {
    /**
     * The bundle key for the service status query result, used in
     * {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String STATUS_RESPONSE_BUNDLE_KEY =
            "android.app.wearable.WearableSensingStatusBundleKey";


    /**
     * An unknown status.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * The value of the status code that indicates success.
     */
    public static final int STATUS_SUCCESS = 1;

    /**
     * The value of the status code that indicates one or more of the
     * requested events are not supported.
     */
    public static final int STATUS_UNSUPPORTED = 2;

    /**
     * The value of the status code that indicates service not available.
     */
    public static final int STATUS_SERVICE_UNAVAILABLE = 3;

    /**
     * The value of the status code that there's no connection to the wearable.
     */
    public static final int STATUS_WEARABLE_UNAVAILABLE = 4;

    /**
     * The value of the status code that the app is not granted access.
     */
    public static final int STATUS_ACCESS_DENIED = 5;

    /**
     * The value of the status code that indicates the method called is not supported by the
     * implementation of {@link WearableSensingService}.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_UNSUPPORTED_OPERATION_STATUS_CODE)
    public static final int STATUS_UNSUPPORTED_OPERATION = 6;

    /**
     * The value of the status code that indicates an error occurred in the encrypted channel backed
     * by the provided connection. See {@link #provideWearableConnection(ParcelFileDescriptor,
     * Executor, Consumer)}.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PROVIDE_WEARABLE_CONNECTION_API)
    public static final int STATUS_CHANNEL_ERROR = 7;

    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN,
            STATUS_SUCCESS,
            STATUS_UNSUPPORTED,
            STATUS_SERVICE_UNAVAILABLE,
            STATUS_WEARABLE_UNAVAILABLE,
            STATUS_ACCESS_DENIED,
            STATUS_UNSUPPORTED_OPERATION,
            STATUS_CHANNEL_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatusCode {}

    private final Context mContext;
    private final IWearableSensingManager mService;

    /**
     * {@hide}
     */
    public WearableSensingManager(Context context, IWearableSensingManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Provides a remote wearable device connection to the WearableSensingService and sends the
     * resulting status to the {@code statusConsumer} after the call.
     *
     * <p>This is used by applications that will also provide an implementation of the isolated
     * WearableSensingService.
     *
     * <p>The provided {@code wearableConnection} is expected to be a connection to a remotely
     * connected wearable device. This {@code wearableConnection} will be attached to
     * CompanionDeviceManager via {@link CompanionDeviceManager#attachSystemDataTransport(int,
     * InputStream, OutputStream)}, which will create an encrypted channel using {@code
     * wearableConnection} as the raw underlying connection. The wearable device is expected to
     * attach its side of the raw connection to its CompanionDeviceManager via the same method so
     * that the two CompanionDeviceManagers on the two devices can perform attestation and set up
     * the encrypted channel. Attestation requirements are listed in
     * com.android.server.security.AttestationVerificationPeerDeviceVerifier
     *
     * <p>A proxy to the encrypted channel will be provided to the WearableSensingService, which is
     * referred to as the secureWearableConnection in WearableSensingService. Any data written to
     * secureWearableConnection will be encrypted by CompanionDeviceManager and sent over the raw
     * {@code wearableConnection} to the remote wearable device, which is expected to use its
     * CompanionDeviceManager to decrypt the data. Encrypted data arriving at the raw {@code
     * wearableConnection} will be decrypted by CompanionDeviceManager and be readable as plain text
     * from secureWearableConnection. The raw {@code wearableConnection} provided to this method
     * will not be directly available to the WearableSensingService.
     *
     * <p>If an error occurred in the encrypted channel (such as the underlying stream closed), the
     * system will send a status code of {@link STATUS_CHANNEL_ERROR} to the {@code statusConsumer}
     * and kill the WearableSensingService process.
     *
     * <p>Before providing the secureWearableConnection, the system will restart the
     * WearableSensingService process. Other method calls into WearableSensingService may be dropped
     * during the restart. The caller is responsible for ensuring other method calls are queued
     * until a success status is returned from the {@code statusConsumer}.
     *
     * @param wearableConnection The connection to provide
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes for providing the connection
     *     and errors in the encrypted channel.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_PROVIDE_WEARABLE_CONNECTION_API)
    public void provideWearableConnection(
            @NonNull ParcelFileDescriptor wearableConnection,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = createStatusCallback(executor, statusConsumer);
            mService.provideWearableConnection(wearableConnection, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provides a data stream to the WearableSensingService that's backed by the
     * parcelFileDescriptor, and sends the result to the {@link Consumer} right after the call.
     * This is used by applications that will also provide an implementation of
     * an isolated WearableSensingService. If the data stream was provided successfully
     * {@link WearableSensingManager#STATUS_SUCCESS} will be provided.
     *
     * @param parcelFileDescriptor The data stream to provide
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes, which is returned
     *                 right after the call.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideDataStream(
            @NonNull ParcelFileDescriptor parcelFileDescriptor,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = createStatusCallback(executor, statusConsumer);
            mService.provideDataStream(parcelFileDescriptor, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets configuration and provides read-only data in a {@link PersistableBundle} that may be
     * used by the WearableSensingService, and sends the result to the {@link Consumer}
     * right after the call. It is dependent on the application to
     * define the type of data to provide. This is used by applications that will also
     * provide an implementation of an isolated WearableSensingService. If the data was
     * provided successfully {@link WearableSensingManager#STATUS_SUCCESS} will be povided.
     *
     * @param data Application configuration data to provide to the {@link WearableSensingService}.
     *             PersistableBundle does not allow any remotable objects or other contents
     *             that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to
     *                     provide to the {@link WearableSensingService}. Use this to provide the
     *                     sensing models data or other such data to the trusted process.
     *                     The sharedMemory must be read only and protected with
     *                     {@link OsConstants.PROT_READ}.
     *                     Other operations will be removed by the system.
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes, which is returned
     *                     right after the call
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideData(
            @NonNull PersistableBundle data, @Nullable SharedMemory sharedMemory,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = createStatusCallback(executor, statusConsumer);
            mService.provideData(data, sharedMemory, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static RemoteCallback createStatusCallback(
            Executor executor, Consumer<Integer> statusConsumer) {
        return new RemoteCallback(
                result -> {
                    int status = result.getInt(STATUS_RESPONSE_BUNDLE_KEY);
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> statusConsumer.accept(status));
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                });
    }
}
