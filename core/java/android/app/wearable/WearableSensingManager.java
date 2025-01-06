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
import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.compat.CompatChanges;
import android.companion.CompanionDeviceManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.wearable.WearableSensingService;
import android.system.OsConstants;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Allows granted apps to manage the WearableSensingService. Applications are responsible for
 * managing the connection to Wearables. Applications can choose to provide a data stream to the
 * WearableSensingService to use for computing {@link AmbientContextEvent}s. Applications can also
 * optionally provide their own defined data to power the detection of {@link AmbientContextEvent}s.
 * Methods on this class requires the caller to hold and be granted the {@link
 * Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE}.
 *
 * <p>The use of "Wearable" here is not the same as the Android Wear platform and should be treated
 * separately.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.WEARABLE_SENSING_SERVICE)
public class WearableSensingManager {
    /**
     * The bundle key for the service status query result, used in {@code
     * RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String STATUS_RESPONSE_BUNDLE_KEY =
            "android.app.wearable.WearableSensingStatusBundleKey";

    /**
     * The Intent extra key for the data request in the Intent sent to the PendingIntent registered
     * with {@link #registerDataRequestObserver(int, PendingIntent, Executor, Consumer)}.
     *
     * @hide
     */
    public static final String EXTRA_WEARABLE_SENSING_DATA_REQUEST =
            "android.app.wearable.extra.WEARABLE_SENSING_DATA_REQUEST";

    /**
     * An invalid connection ID returned by the system_server when it encounters an error providing
     * a connection to WearableSensingService.
     *
     * @hide
     */
    public static final int CONNECTION_ID_INVALID = -1;

    /** A placeholder connection ID used in an implementation detail. */
    private static final int CONNECTION_ID_PLACEHOLDER = -2;

    /** An unknown status. */
    public static final int STATUS_UNKNOWN = 0;

    /** The value of the status code that indicates success. */
    public static final int STATUS_SUCCESS = 1;

    /**
     * The value of the status code that indicates one or more of the requested events are not
     * supported.
     *
     * @deprecated WearableSensingManager does not deal with events. Use {@link
     *     STATUS_UNSUPPORTED_OPERATION} instead for operations not supported by the implementation
     *     of {@link WearableSensingService}.
     */
    @Deprecated public static final int STATUS_UNSUPPORTED = 2;

    /** The value of the status code that indicates service not available. */
    public static final int STATUS_SERVICE_UNAVAILABLE = 3;

    /** The value of the status code that there's no connection to the wearable. */
    public static final int STATUS_WEARABLE_UNAVAILABLE = 4;

    /** The value of the status code that the app is not granted access. */
    public static final int STATUS_ACCESS_DENIED = 5;

    /**
     * The value of the status code that indicates the method called is not supported by the
     * implementation of {@link WearableSensingService}.
     */
    public static final int STATUS_UNSUPPORTED_OPERATION = 6;

    /**
     * The value of the status code that indicates an error occurred in the encrypted channel backed
     * by the provided connection. See {@link #provideConnection(ParcelFileDescriptor, Executor,
     * Consumer)}.
     */
    public static final int STATUS_CHANNEL_ERROR = 7;

    /** The value of the status code that indicates the provided data type is not supported. */
    public static final int STATUS_UNSUPPORTED_DATA_TYPE = 8;

    /**
     * The value of the status code that indicates the provided connection is rejected because the
     * maximum number of concurrent connections have already been provided. Use {@link
     * #removeConnection(WearableConnection)} or {@link #removeAllConnections()} to remove a
     * connection before providing a new one.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
    public static final int STATUS_MAX_CONCURRENT_CONNECTIONS_EXCEEDED = 9;

    /** @hide */
    @IntDef(
            prefix = {"STATUS_"},
            value = {
                STATUS_UNKNOWN,
                STATUS_SUCCESS,
                STATUS_UNSUPPORTED,
                STATUS_SERVICE_UNAVAILABLE,
                STATUS_WEARABLE_UNAVAILABLE,
                STATUS_ACCESS_DENIED,
                STATUS_UNSUPPORTED_OPERATION,
                STATUS_CHANNEL_ERROR,
                STATUS_UNSUPPORTED_DATA_TYPE,
                STATUS_MAX_CONCURRENT_CONNECTIONS_EXCEEDED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatusCode {}

    /**
     * If the WearableSensingService implementation belongs to the same APK as the caller, calling
     * {@link #provideDataStream(ParcelFileDescriptor, Executor, Consumer)} will allow
     * WearableSensingService to read from the caller's file directory via {@link
     * Context#openFileInput(String)}. The read will be proxied via the caller's process and
     * executed by the {@code executor} provided to this method.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long ALLOW_WEARABLE_SENSING_SERVICE_FILE_READ = 330701114L;

    /**
     * Retrieves a {@link WearableSensingDataRequest} from the Intent sent to the PendingIntent
     * provided to {@link #registerDataRequestObserver(int, PendingIntent, Executor, Consumer)}.
     *
     * @param intent The Intent received from the PendingIntent.
     * @return The WearableSensingDataRequest in the provided Intent, or null if the Intent does not
     *     contain a WearableSensingDataRequest.
     */
    @Nullable
    public static WearableSensingDataRequest getDataRequestFromIntent(@NonNull Intent intent) {
        return intent.getParcelableExtra(
                EXTRA_WEARABLE_SENSING_DATA_REQUEST, WearableSensingDataRequest.class);
    }

    private static final String TAG = WearableSensingManager.class.getSimpleName();
    private final Context mContext;
    private final IWearableSensingManager mService;

    private final Map<WearableConnection, Integer> mWearableConnectionIdMap =
            new ConcurrentHashMap<>();

    /**
     * Creates a WearableSensingManager.
     *
     * @hide
     */
    public WearableSensingManager(Context context, IWearableSensingManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns the remaining number of concurrent connections allowed by {@link
     * #provideConnection(WearableConnection, Executor)}.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
    public int getAvailableConnectionCount() {
        try {
            return mService.getAvailableConnectionCount();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provides a remote wearable device connection to the WearableSensingService and sends the
     * resulting status to the {@code statusConsumer} after the call.
     *
     * <p>This method has the same behavior as {@link #provideConnection(WearableConnection,
     * Executor)} except that concurrent connections are not allowed. Before providing the
     * secureWearableConnection, the system will restart the WearableSensingService process if it
     * has not been restarted since the last secureWearableConnection was provided via this method.
     * Other method calls into WearableSensingService may be dropped during the restart. The caller
     * is responsible for ensuring other method calls are queued until a success status is returned
     * from the {@code statusConsumer}.
     *
     * <p>If an error occurred in the encrypted channel (such as the underlying stream closed), the
     * system will send a status code of {@link STATUS_CHANNEL_ERROR} to the {@code statusConsumer}
     * and kill the WearableSensingService process.
     *
     * @param wearableConnection The connection to provide
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes for providing the connection
     *     and errors in the encrypted channel.
     * @deprecated Use {@link #provideConnection(WearableConnection, Executor)} instead to provide a
     *     remote wearable device connection to the WearableSensingService
     */
    @FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
    @Deprecated
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideConnection(
            @NonNull ParcelFileDescriptor wearableConnection,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        RemoteCallback statusCallback = createStatusCallback(executor, statusConsumer);
        try {
            // The wearableSensingCallback is included in this method call even though it is not
            // semantically related to the connection because we want to avoid race conditions
            // during the process restart triggered by this method call. See
            // com.android.server.wearable.RemoteWearableSensingService for details.
            mService.provideConnection(
                    wearableConnection, createWearableSensingCallback(executor), statusCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provides a remote wearable device connection to the WearableSensingService.
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
     * <p>There is a limit on the number of concurrent connections allowed. Call {@link
     * #getAvailableConnectionCount()} to check the remaining quota. If more connections are
     * provided than allowed, the new connection will be rejected with {@value
     * #STATUS_MAX_CONCURRENT_CONNECTIONS_EXCEEDED}. To reclaim the quota of a previously provided
     * connection that is no longer needed, call {@link #removeConnection(WearableConnection)} with
     * the same WearableConnection instance. Connections provided via {@link
     * #provideConnection(ParcelFileDescriptor, Executor, Consumer)} will not contribute towards the
     * concurrent connection limit.
     *
     * <p>If the {@code wearableConnection} receives an error, either the connection will not be
     * sent to the WearableSensingService, or the connection will be closed by the system. Either
     * way, the concurrent connection quota for this connection will be automatically released.
     *
     * <p>If the WearableSensingService implementation belongs to the same APK as the caller,
     * calling this method will allow WearableSensingService to read from the caller's file
     * directory via {@link Context#openFileInput(String)}. The read will be proxied via the
     * caller's process and executed by the {@code executor} provided to this method.
     *
     * @param wearableConnection The connection to provide
     * @param executor Executor on which to run the callback methods on {@code wearableConnection}
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
    public void provideConnection(
            @NonNull WearableConnection wearableConnection, @NonNull Executor executor) {
        RemoteCallback statusCallback =
                createStatusCallback(
                        executor,
                        statusCode -> {
                            if (!mWearableConnectionIdMap.containsKey(wearableConnection)) {
                                Slog.i(
                                        TAG,
                                        "Surpassed status callback for removed connection "
                                                + wearableConnection);
                                return;
                            }
                            if (statusCode == STATUS_SUCCESS) {
                                wearableConnection.onConnectionAccepted();
                            } else {
                                mWearableConnectionIdMap.remove(wearableConnection);
                                wearableConnection.onError(statusCode);
                            }
                        });
        try {
            // The statusCallback should not invoke callback on a removed connection. To implement
            // this behavior, statusCallback will only invoke the callback if the connection is
            // present in mWearableConnectionIdMap. We need to add the connection to the map before
            // statusCallback is sent to mService in case the system triggers the statusCallback
            // before the connectionId is returned.
            mWearableConnectionIdMap.put(wearableConnection, CONNECTION_ID_PLACEHOLDER);
            int connectionId =
                    mService.provideConcurrentConnection(
                            wearableConnection.getConnection(),
                            wearableConnection.getMetadata(),
                            createWearableSensingCallback(executor),
                            statusCallback);
            mWearableConnectionIdMap.put(wearableConnection, connectionId);
            // For invalid connection IDs, the status callback will remove the connection from
            // mWearableConnectionIdMap
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a connection previously provided via {@link #provideConnection(WearableConnection,
     * Executor)}.
     *
     * <p>The WearableSensingService will no longer be able to use this connection.
     *
     * <p>After this method returns, there will be no new invocation to callback methods in the
     * removed {@link WearableConnection}. Ongoing invocations will continue to run.
     *
     * <p>This method throws a {@link NoSuchElementException} if the provided {@code
     * wearableConnection} does not match any open connection.
     *
     * <p>This method should not be called before the corresponding {@link
     * #provideConnection(WearableConnection, Executor)} invocation returns. Otherwise, the
     * connection may not be removed, and an {@link IllegalStateException} may be thrown.
     *
     * @param wearableConnection The WearableConnection instance previously provided to {@link
     *     #provideConnection(WearableConnection, Executor)}.
     * @throws NoSuchElementException if the connection was never provided or was already removed.
     * @throws IllegalStateException if the {@link #provideConnection(WearableConnection, Executor)}
     *     invocation for the given connection has not returned.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
    public void removeConnection(@NonNull WearableConnection wearableConnection) {
        Integer connectionId = mWearableConnectionIdMap.remove(wearableConnection);
        if (connectionId == null || connectionId == CONNECTION_ID_INVALID) {
            throw new NoSuchElementException(
                    "The provided connection was never provided or was already removed.");
        }
        if (connectionId == CONNECTION_ID_PLACEHOLDER) {
            throw new IllegalStateException(
                    "Attempt to remove connection before provideConnection returns. The connection"
                            + " will not be removed.");
        }
        try {
            if (!mService.removeConnection(connectionId)) {
                throw new NoSuchElementException(
                        "The provided connection was never provided or was already removed.");
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all connections previously provided via {@link #provideConnection(WearableConnection,
     * Executor)}.
     *
     * <p>The connection provided via {@link #provideConnection(ParcelFileDescriptor, Executor,
     * Consumer)}, if exists, will not be affected by this method.
     *
     * <p>The WearableSensingService will no longer be able to use any of the removed connections.
     *
     * <p>After this method returns, there will be no new invocation to callback methods in the
     * removed {@link WearableConnection}s. Ongoing invocations will continue to run.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
    public void removeAllConnections() {
        mWearableConnectionIdMap.clear();
        try {
            mService.removeAllConnections();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provides a read-only {@link ParcelFileDescriptor} to the WearableSensingService.
     *
     * <p>This is used by the application that will also provide an implementation of the isolated
     * WearableSensingService. If the {@link ParcelFileDescriptor} was provided successfully, {@link
     * WearableSensingManager#STATUS_SUCCESS} will be sent to the {@code statusConsumer}.
     *
     * @param parcelFileDescriptor The read-only {@link ParcelFileDescriptor} to provide
     * @param metadata Metadata used to identify the {@code parcelFileDescriptor}
     * @param executor Executor on which to run the {@code statusConsumer}
     * @param statusConsumer A consumer that handles the status codes
     * @throws IllegalArgumentException when the {@code parcelFileDescriptor} is not read-only
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_PROVIDE_READ_ONLY_PFD)
    public void provideReadOnlyParcelFileDescriptor(
            @NonNull ParcelFileDescriptor parcelFileDescriptor,
            @NonNull PersistableBundle metadata,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        RemoteCallback statusCallback = createStatusCallback(executor, statusConsumer);
        try {
            mService.provideReadOnlyParcelFileDescriptor(
                    parcelFileDescriptor, metadata, statusCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provides a data stream to the WearableSensingService that's backed by the
     * parcelFileDescriptor, and sends the result to the {@link Consumer} right after the call. This
     * is used by applications that will also provide an implementation of an isolated
     * WearableSensingService. If the data stream was provided successfully {@link
     * WearableSensingManager#STATUS_SUCCESS} will be provided.
     *
     * <p>Starting from target SDK level 35, if the WearableSensingService implementation belongs to
     * the same APK as the caller, calling this method will allow WearableSensingService to read
     * from the caller's file directory via {@link Context#openFileInput(String)}. The read will be
     * proxied via the caller's process and executed by the {@code executor} provided to this
     * method.
     *
     * @param parcelFileDescriptor The data stream to provide
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes, which is returned right after
     *     the call.
     * @deprecated Use {@link #provideConnection(WearableConnection, Executor)} instead to provide a
     *     remote wearable device connection to the WearableSensingService
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideDataStream(
            @NonNull ParcelFileDescriptor parcelFileDescriptor,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        RemoteCallback statusCallback = createStatusCallback(executor, statusConsumer);
        IWearableSensingCallback wearableSensingCallback = null;
        if (CompatChanges.isChangeEnabled(ALLOW_WEARABLE_SENSING_SERVICE_FILE_READ)) {
            wearableSensingCallback = createWearableSensingCallback(executor);
        }
        try {
            mService.provideDataStream(
                    parcelFileDescriptor, wearableSensingCallback, statusCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets configuration and provides read-only data in a {@link PersistableBundle} that may be
     * used by the WearableSensingService, and sends the result to the {@link Consumer} right after
     * the call. It is dependent on the application to define the type of data to provide. This is
     * used by applications that will also provide an implementation of an isolated
     * WearableSensingService. If the data was provided successfully {@link
     * WearableSensingManager#STATUS_SUCCESS} will be povided.
     *
     * @param data Application configuration data to provide to the {@link WearableSensingService}.
     *     PersistableBundle does not allow any remotable objects or other contents that can be used
     *     to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the {@link
     *     WearableSensingService}. Use this to provide the sensing models data or other such data
     *     to the trusted process. The sharedMemory must be read only and protected with {@link
     *     OsConstants.PROT_READ}. Other operations will be removed by the system.
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes, which is returned right after
     *     the call
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideData(
            @NonNull PersistableBundle data,
            @Nullable SharedMemory sharedMemory,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = createStatusCallback(executor, statusConsumer);
            mService.provideData(data, sharedMemory, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a data request observer for the provided data type.
     *
     * <p>When data is requested, the provided {@code dataRequestPendingIntent} will be invoked. A
     * {@link WearableSensingDataRequest} can be extracted from the Intent sent to {@code
     * dataRequestPendingIntent} by calling {@link #getDataRequestFromIntent(Intent)}. The observer
     * can then provide the requested data via {@link #provideData(PersistableBundle, SharedMemory,
     * Executor, Consumer)}.
     *
     * <p>There is no limit to the number of observers registered for a data type. How they are
     * handled depends on the implementation of WearableSensingService.
     *
     * <p>When the observer is no longer needed, {@link #unregisterDataRequestObserver(int,
     * PendingIntent, Executor, Consumer)} should be called with the same {@code
     * dataRequestPendingIntent}. It should be done regardless of the status code returned from
     * {@code statusConsumer} in order to clean up housekeeping data for the {@code
     * dataRequestPendingIntent} maintained by the system.
     *
     * <p>Example:
     *
     * <pre>{@code
     * // Create a PendingIntent for MyDataRequestBroadcastReceiver
     * Intent intent =
     *         new Intent(actionString).setClass(context, MyDataRequestBroadcastReceiver.class);
     * PendingIntent pendingIntent = PendingIntent.getBroadcast(
     *         context, 0, intent, PendingIntent.FLAG_MUTABLE);
     *
     * // Register the PendingIntent as a data request observer
     * wearableSensingManager.registerDataRequestObserver(
     *         dataType, pendingIntent, executor, statusConsumer);
     *
     * // Within MyDataRequestBroadcastReceiver, receive the broadcast Intent and extract the
     * // WearableSensingDataRequest
     * {@literal @}Override
     * public void onReceive(Context context, Intent intent) {
     *     WearableSensingDataRequest dataRequest =
     *             WearableSensingManager.getDataRequestFromIntent(intent);
     *     // After parsing the dataRequest, provide the data
     *     wearableSensingManager.provideData(data, sharedMemory, executor, statusConsumer);
     * }
     * }</pre>
     *
     * @param dataType The data type to listen to. Values are defined by the application that
     *     implements {@link WearableSensingService}.
     * @param dataRequestPendingIntent A mutable {@link PendingIntent} that will be invoked when
     *     data is requested. See {@link #getDataRequestFromIntent(Intent)}. Activities are not
     *     allowed to be launched using this PendingIntent.
     * @param executor Executor on which to run the consumer callback.
     * @param statusConsumer A consumer that handles the status code for the observer registration.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void registerDataRequestObserver(
            int dataType,
            @NonNull PendingIntent dataRequestPendingIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback statusCallback = createStatusCallback(executor, statusConsumer);
            mService.registerDataRequestObserver(
                    dataType, dataRequestPendingIntent, statusCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a previously registered data request observer. If the provided {@link
     * PendingIntent} was not registered, or is already unregistered, the {@link
     * WearableSensingService} will not be notified.
     *
     * @param dataType The data type the observer is for.
     * @param dataRequestPendingIntent The observer to unregister.
     * @param executor Executor on which to run the consumer callback.
     * @param statusConsumer A consumer that handles the status code for the observer
     *     unregistration.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void unregisterDataRequestObserver(
            int dataType,
            @NonNull PendingIntent dataRequestPendingIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback statusCallback = createStatusCallback(executor, statusConsumer);
            mService.unregisterDataRequestObserver(
                    dataType, dataRequestPendingIntent, statusCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the wearable to start hotword recognition.
     *
     * <p>When this method is called, the system will attempt to provide a {@code
     * Consumer<android.service.voice.HotwordAudioStream>} to {@link WearableSensingService}. After
     * first-stage hotword is detected on a wearable, {@link WearableSensingService} should send the
     * hotword audio to the {@code Consumer<android.service.voice.HotwordAudioStream>}, which will
     * forward the data to the {@link android.service.voice.HotwordDetectionService} for
     * second-stage hotword validation. If hotword is detected there, the audio data will be
     * forwarded to the {@link android.service.voice.VoiceInteractionService}.
     *
     * <p>If the {@code targetVisComponentName} provided here is not null, when {@link
     * WearableSensingService} sends hotword audio to the {@code
     * Consumer<android.service.voice.HotwordAudioStream>}, the system will check whether the {@link
     * android.service.voice.VoiceInteractionService} at that time is {@code
     * targetVisComponentName}. If not, the system will call {@link
     * WearableSensingService#onStopHotwordAudioStream()} and will not forward the audio data to the
     * current {@link android.service.voice.HotwordDetectionService} nor {@link
     * android.service.voice.VoiceInteractionService}. The system will not send a status code to
     * {@code statusConsumer} regarding the {@code targetVisComponentName} check. The caller is
     * responsible for determining whether the system's {@link
     * android.service.voice.VoiceInteractionService} is the same as {@code targetVisComponentName}.
     * The check here is just a protection against race conditions.
     *
     * <p>Calling this method again will send a new {@code
     * Consumer<android.service.voice.HotwordAudioStream>} to {@link WearableSensingService}. For
     * audio data sent to the new consumer, the system will perform the above check using the newly
     * provided {@code targetVisComponentName}. The {@link WearableSensingService} should not
     * continue to use the previous consumers after receiving a new one.
     *
     * <p>If the {@code statusConsumer} returns {@link STATUS_SUCCESS}, the caller should call
     * {@link #stopHotwordRecognition(Executor, Consumer)} when it wants the wearable to stop
     * listening for hotword. If the {@code statusConsumer} returns any other status code, a failure
     * has occurred and calling {@link #stopHotwordRecognition(Executor, Consumer)} is not required.
     * The system will not retry listening automatically. The caller should call this method again
     * if they want to retry.
     *
     * <p>If a failure occurred after the {@link statusConsumer} returns {@link STATUS_SUCCESS},
     * {@link statusConsumer} will be invoked again with a status code other than {@link
     * STATUS_SUCCESS}.
     *
     * @param targetVisComponentName The ComponentName of the target VoiceInteractionService.
     * @param executor Executor on which to run the consumer callback.
     * @param statusConsumer A consumer that handles the status codes.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_HOTWORD_WEARABLE_SENSING_API)
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void startHotwordRecognition(
            @Nullable ComponentName targetVisComponentName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            mService.startHotwordRecognition(
                    targetVisComponentName, createStatusCallback(executor, statusConsumer));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the wearable to stop hotword recognition.
     *
     * @param executor Executor on which to run the consumer callback.
     * @param statusConsumer A consumer that handles the status codes.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_HOTWORD_WEARABLE_SENSING_API)
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void stopHotwordRecognition(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            mService.stopHotwordRecognition(createStatusCallback(executor, statusConsumer));
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

    private IWearableSensingCallback createWearableSensingCallback(Executor executor) {
        return new IWearableSensingCallback.Stub() {

            @Override
            public void openFile(String filename, AndroidFuture<ParcelFileDescriptor> future) {
                Slog.d(TAG, "IWearableSensingCallback#openFile " + filename);
                Binder.withCleanCallingIdentity(
                        () ->
                                executor.execute(
                                        () -> {
                                            File file = new File(mContext.getFilesDir(), filename);
                                            ParcelFileDescriptor pfd = null;
                                            try {
                                                pfd =
                                                        ParcelFileDescriptor.open(
                                                                file,
                                                                ParcelFileDescriptor
                                                                        .MODE_READ_ONLY);
                                                Slog.d(
                                                        TAG,
                                                        "Successfully opened a file with"
                                                                + " ParcelFileDescriptor.");
                                            } catch (FileNotFoundException e) {
                                                Slog.e(TAG, "Cannot open file.", e);
                                            } finally {
                                                future.complete(pfd);
                                                if (pfd != null) {
                                                    try {
                                                        pfd.close();
                                                    } catch (IOException ex) {
                                                        Slog.e(
                                                                TAG,
                                                                "Error closing"
                                                                        + " ParcelFileDescriptor.",
                                                                ex);
                                                    }
                                                }
                                            }
                                        }));
            }
        };
    }
}
