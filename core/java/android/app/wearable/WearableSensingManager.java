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
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
     * The Intent extra key for the data request in the Intent sent to the PendingIntent registered
     * with {@link #registerDataRequestObserver(int, PendingIntent, Executor, Consumer)}.
     *
     * @hide
     */
    public static final String EXTRA_WEARABLE_SENSING_DATA_REQUEST =
            "android.app.wearable.extra.WEARABLE_SENSING_DATA_REQUEST";

    /**
     * An unknown status.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * The value of the status code that indicates success.
     */
    public static final int STATUS_SUCCESS = 1;

    /**
     * The value of the status code that indicates one or more of the requested events are not
     * supported.
     *
     * @deprecated WearableSensingManager does not deal with events. Use {@link
     * STATUS_UNSUPPORTED_OPERATION} instead for operations not supported by the implementation of
     * {@link WearableSensingService}.
     */
    @Deprecated
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
     * by the provided connection. See {@link #provideConnection(ParcelFileDescriptor,
     * Executor, Consumer)}.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PROVIDE_WEARABLE_CONNECTION_API)
    public static final int STATUS_CHANNEL_ERROR = 7;

    /** The value of the status code that indicates the provided data type is not supported. */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
    public static final int STATUS_UNSUPPORTED_DATA_TYPE = 8;

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
                STATUS_UNSUPPORTED_DATA_TYPE
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatusCode {}

    /**
     * Retrieves a {@link WearableSensingDataRequest} from the Intent sent to the PendingIntent
     * provided to {@link #registerDataRequestObserver(int, PendingIntent, Executor, Consumer)}.
     *
     * @param intent The Intent received from the PendingIntent.
     * @return The WearableSensingDataRequest in the provided Intent, or null if the Intent does not
     *     contain a WearableSensingDataRequest.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
    @Nullable
    public static WearableSensingDataRequest getDataRequestFromIntent(@NonNull Intent intent) {
        return intent.getParcelableExtra(
                EXTRA_WEARABLE_SENSING_DATA_REQUEST, WearableSensingDataRequest.class);
    }

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
     * WearableSensingService process if it has not been restarted since the last
     * secureWearableConnection was provided. Other method calls into WearableSensingService may be
     * dropped during the restart. The caller is responsible for ensuring other method calls are
     * queued until a success status is returned from the {@code statusConsumer}.
     *
     * @param wearableConnection The connection to provide
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes for providing the connection
     *     and errors in the encrypted channel.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_PROVIDE_WEARABLE_CONNECTION_API)
    public void provideConnection(
            @NonNull ParcelFileDescriptor wearableConnection,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = createStatusCallback(executor, statusConsumer);
            mService.provideConnection(wearableConnection, callback);
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
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
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
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
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
     * <p>When this method is called, the system will attempt to provide a {@link
     * android.service.wearable.WearableHotwordAudioConsumer} to {@link WearableSensingService}.
     * After first-stage hotword is detected on a wearable, {@link WearableSensingService} should
     * send the hotword audio to the {@link android.service.wearable.WearableHotwordAudioConsumer},
     * which will forward the data to the {@link android.service.voice.HotwordDetectionService} for
     * second-stage hotword validation. If hotword is detected there, the audio data will be
     * forwarded to the {@link android.service.voice.VoiceInteractionService}.
     *
     * <p>If the {@code targetVisComponentName} provided here is not null, when {@link
     * WearableSensingService} sends hotword audio to the {@link
     * android.service.wearable.WearableHotwordAudioConsumer}, the system will check whether the
     * {@link android.service.voice.VoiceInteractionService} at that time is {@code
     * targetVisComponentName}. If not, the system will call {@link
     * WearableSensingService#onActiveHotwordAudioStopRequested()} and will not forward the audio
     * data to the current {@link android.service.voice.HotwordDetectionService} nor {@link
     * android.service.voice.VoiceInteractionService}. The system will not send a status code to
     * {@code statusConsumer} regarding the {@code targetVisComponentName} check. The caller is
     * responsible for determining whether the system's {@link
     * android.service.voice.VoiceInteractionService} is the same as {@code targetVisComponentName}.
     * The check here is just a protection against race conditions.
     *
     * <p>Calling this method again will send a new {@link
     * android.service.wearable.WearableHotwordAudioConsumer} to {@link WearableSensingService}. For
     * audio data sent to the new consumer, the system will perform the above check using the newly
     * provided {@code targetVisComponentName}. The {@link WearableSensingService} should not
     * continue to use the previous consumers after receiving a new one.
     *
     * <p>If the {@code statusConsumer} returns {@link STATUS_SUCCESS}, the caller should call
     * {@link #stopListeningForHotword(Executor, Consumer)} when it wants the wearable to stop
     * listening for hotword. If the {@code statusConsumer} returns any other status code, a failure
     * has occurred and calling {@link #stopListeningForHotword(Executor, Consumer)} is not
     * required. The system will not retry listening automatically. The caller should call this
     * method again if they want to retry.
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
}
