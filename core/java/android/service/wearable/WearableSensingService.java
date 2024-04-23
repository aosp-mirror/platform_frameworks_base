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

package android.service.wearable;

import android.annotation.BinderThread;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.wearable.Flags;
import android.app.wearable.IWearableSensingCallback;
import android.app.wearable.WearableSensingDataRequest;
import android.app.wearable.WearableSensingManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.ambientcontext.AmbientContextDetectionResult;
import android.service.ambientcontext.AmbientContextDetectionServiceStatus;
import android.service.voice.HotwordAudioStream;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.infra.AndroidFuture;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Abstract base class for sensing with wearable devices. An example of this is {@link
 *AmbientContextEvent} detection.
 *
 * <p> A service that provides requested sensing events to the system, such as a {@link
 *AmbientContextEvent}. The system's default WearableSensingService implementation is configured in
 * {@code config_defaultWearableSensingService}. If this config has no value, a stub is
 * returned.
 *
 * <p> An implementation of a WearableSensingService should be an isolated service. Using the
 * "isolatedProcess=true" attribute in the service's configurations. </p>
 **
 * <pre>
 * {@literal
 * <service android:name=".YourWearableSensingService"
 *          android:permission="android.permission.BIND_WEARABLE_SENSING_SERVICE"
 *          android:isolatedProcess="true">
 * </service>}
 * </pre>
 *
 * <p>The use of "Wearable" here is not the same as the Android Wear platform and should be treated
 * separately. </p>
 *
 * @hide
 */
@SystemApi
public abstract class WearableSensingService extends Service {
    private static final String TAG = WearableSensingService.class.getSimpleName();

    /**
     * The bundle key for this class of object, used in {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String STATUS_RESPONSE_BUNDLE_KEY =
            "android.app.wearable.WearableSensingStatusBundleKey";

    /**
     * The bundle key for hotword audio stream, used in {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String HOTWORD_AUDIO_STREAM_BUNDLE_KEY =
            "android.app.wearable.HotwordAudioStreamBundleKey";

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_WEARABLE_SENSING_SERVICE}
     * permission so that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.wearable.WearableSensingService";

    // Timeout to prevent thread from waiting on the openFile future indefinitely.
    private static final Duration OPEN_FILE_TIMEOUT = Duration.ofSeconds(5);

    private final SparseArray<WearableSensingDataRequester> mDataRequestObserverIdToRequesterMap =
            new SparseArray<>();

    private IWearableSensingCallback mWearableSensingCallback;

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IWearableSensingService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void provideSecureConnection(
                        ParcelFileDescriptor secureWearableConnection,
                        IWearableSensingCallback wearableSensingCallback,
                        RemoteCallback callback) {
                    Objects.requireNonNull(secureWearableConnection);
                    if (wearableSensingCallback != null) {
                        mWearableSensingCallback = wearableSensingCallback;
                    }
                    Consumer<Integer> consumer = createWearableStatusConsumer(callback);
                    WearableSensingService.this.onSecureConnectionProvided(
                            secureWearableConnection, consumer);
                }

                /** {@inheritDoc} */
                @Override
                public void provideDataStream(
                        ParcelFileDescriptor parcelFileDescriptor,
                        IWearableSensingCallback wearableSensingCallback,
                        RemoteCallback callback) {
                    Objects.requireNonNull(parcelFileDescriptor);
                    if (wearableSensingCallback != null) {
                        mWearableSensingCallback = wearableSensingCallback;
                    }
                    Consumer<Integer> consumer = createWearableStatusConsumer(callback);
                    WearableSensingService.this.onDataStreamProvided(
                            parcelFileDescriptor, consumer);
                }

                /** {@inheritDoc} */
                @Override
                public void provideData(
                        PersistableBundle data,
                        SharedMemory sharedMemory,
                        RemoteCallback callback) {
                    Objects.requireNonNull(data);
                    Consumer<Integer> consumer = createWearableStatusConsumer(callback);
                    WearableSensingService.this.onDataProvided(data, sharedMemory, consumer);
                }

                /** {@inheritDoc} */
                @Override
                public void registerDataRequestObserver(
                        int dataType,
                        RemoteCallback dataRequestCallback,
                        int dataRequestObserverId,
                        String packageName,
                        RemoteCallback statusCallback) {
                    Objects.requireNonNull(dataRequestCallback);
                    Objects.requireNonNull(statusCallback);
                    WearableSensingDataRequester dataRequester;
                    synchronized (mDataRequestObserverIdToRequesterMap) {
                        dataRequester =
                                mDataRequestObserverIdToRequesterMap.get(dataRequestObserverId);
                        if (dataRequester == null) {
                            dataRequester = createDataRequester(dataRequestCallback);
                            mDataRequestObserverIdToRequesterMap.put(
                                    dataRequestObserverId, dataRequester);
                        }
                    }
                    Consumer<Integer> statusConsumer = createWearableStatusConsumer(statusCallback);
                    WearableSensingService.this.onDataRequestObserverRegistered(
                            dataType, packageName, dataRequester, statusConsumer);
                }

                @Override
                public void unregisterDataRequestObserver(
                        int dataType,
                        int dataRequestObserverId,
                        String packageName,
                        RemoteCallback statusCallback) {
                    WearableSensingDataRequester dataRequester;
                    synchronized (mDataRequestObserverIdToRequesterMap) {
                        dataRequester =
                                mDataRequestObserverIdToRequesterMap.get(dataRequestObserverId);
                        if (dataRequester == null) {
                            Slog.w(
                                    TAG,
                                    "dataRequestObserverId not found, cannot unregister data"
                                            + " request observer.");
                            return;
                        }
                        mDataRequestObserverIdToRequesterMap.remove(dataRequestObserverId);
                    }
                    Consumer<Integer> statusConsumer = createWearableStatusConsumer(statusCallback);
                    WearableSensingService.this.onDataRequestObserverUnregistered(
                            dataType, packageName, dataRequester, statusConsumer);
                }

                @Override
                public void startHotwordRecognition(
                        RemoteCallback wearableHotwordCallback, RemoteCallback statusCallback) {
                    Consumer<HotwordAudioStream> hotwordAudioConsumer =
                            (hotwordAudioStream) -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        HOTWORD_AUDIO_STREAM_BUNDLE_KEY, hotwordAudioStream);
                                wearableHotwordCallback.sendResult(bundle);
                            };
                    Consumer<Integer> statusConsumer =
                            response -> {
                                Bundle bundle = new Bundle();
                                bundle.putInt(STATUS_RESPONSE_BUNDLE_KEY, response);
                                statusCallback.sendResult(bundle);
                            };
                    WearableSensingService.this.onStartHotwordRecognition(
                            hotwordAudioConsumer, statusConsumer);
                }

                /** {@inheritDoc} */
                @Override
                public void stopHotwordRecognition(RemoteCallback statusCallback) {
                    Consumer<Integer> statusConsumer =
                            response -> {
                                Bundle bundle = new Bundle();
                                bundle.putInt(STATUS_RESPONSE_BUNDLE_KEY, response);
                                statusCallback.sendResult(bundle);
                            };
                    WearableSensingService.this.onStopHotwordRecognition(statusConsumer);
                }

                /** {@inheritDoc} */
                @Override
                public void onValidatedByHotwordDetectionService() {
                    WearableSensingService.this.onValidatedByHotwordDetectionService();
                }

                /** {@inheritDoc} */
                @Override
                public void stopActiveHotwordAudio() {
                    WearableSensingService.this.onStopHotwordAudioStream();
                }

                /** {@inheritDoc} */
                @Override
                public void startDetection(
                        @NonNull AmbientContextEventRequest request,
                        String packageName,
                        RemoteCallback detectionResultCallback,
                        RemoteCallback statusCallback) {
                    Objects.requireNonNull(request);
                    Objects.requireNonNull(packageName);
                    Objects.requireNonNull(detectionResultCallback);
                    Objects.requireNonNull(statusCallback);
                    Consumer<AmbientContextDetectionResult> detectionResultConsumer =
                            result -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        AmbientContextDetectionResult.RESULT_RESPONSE_BUNDLE_KEY,
                                        result);
                                detectionResultCallback.sendResult(bundle);
                            };
                    Consumer<AmbientContextDetectionServiceStatus> statusConsumer =
                            status -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        AmbientContextDetectionServiceStatus
                                                .STATUS_RESPONSE_BUNDLE_KEY,
                                        status);
                                statusCallback.sendResult(bundle);
                            };
                    WearableSensingService.this.onStartDetection(
                            request, packageName, statusConsumer, detectionResultConsumer);
                    Slog.d(TAG, "startDetection " + request);
                }

                /** {@inheritDoc} */
                @Override
                public void stopDetection(String packageName) {
                    Objects.requireNonNull(packageName);
                    WearableSensingService.this.onStopDetection(packageName);
                }

                /** {@inheritDoc} */
                @Override
                public void queryServiceStatus(
                        @AmbientContextEvent.EventCode int[] eventTypes,
                        String packageName,
                        RemoteCallback callback) {
                    Objects.requireNonNull(eventTypes);
                    Objects.requireNonNull(packageName);
                    Objects.requireNonNull(callback);
                    Consumer<AmbientContextDetectionServiceStatus> consumer =
                            response -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        AmbientContextDetectionServiceStatus
                                                .STATUS_RESPONSE_BUNDLE_KEY,
                                        response);
                                callback.sendResult(bundle);
                            };
                    Integer[] events = intArrayToIntegerArray(eventTypes);
                    WearableSensingService.this.onQueryServiceStatus(
                            new HashSet<>(Arrays.asList(events)), packageName, consumer);
                }

                /** {@inheritDoc} */
                @Override
                public void killProcess() {
                    Slog.d(TAG, "#killProcess");
                    Process.killProcess(Process.myPid());
                }
            };
        }
        Slog.w(TAG, "Incorrect service interface, returning null.");
        return null;
    }

    /**
     * Called when a secure connection to the wearable is available. See {@link
     * WearableSensingManager#provideConnection(ParcelFileDescriptor, Executor, Consumer)}
     * for details about the secure connection.
     *
     * <p>When the {@code secureWearableConnection} is closed, the system will send a {@link
     * WearableSensingManager#STATUS_CHANNEL_ERROR} status code to the status consumer provided by
     * the caller of {@link WearableSensingManager#provideConnection(ParcelFileDescriptor,
     * Executor, Consumer)}.
     *
     * <p>The implementing class should override this method. It should return an appropriate status
     * code via {@code statusConsumer} after receiving the {@code secureWearableConnection}.
     *
     * @param secureWearableConnection The secure connection to the wearable.
     * @param statusConsumer The consumer for the service status.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PROVIDE_WEARABLE_CONNECTION_API)
    @BinderThread
    public void onSecureConnectionProvided(
            @NonNull ParcelFileDescriptor secureWearableConnection,
            @NonNull Consumer<Integer> statusConsumer) {
        statusConsumer.accept(WearableSensingManager.STATUS_UNSUPPORTED_OPERATION);
    }

    /**
     * Called when a data stream to the wearable is provided. This data stream can be used to obtain
     * data from a wearable device. It is up to the implementation to maintain the data stream and
     * close the data stream when it is finished.
     *
     * @param parcelFileDescriptor The data stream to the wearable
     * @param statusConsumer the consumer for the service status.
     */
    @BinderThread
    public abstract void onDataStreamProvided(@NonNull ParcelFileDescriptor parcelFileDescriptor,
            @NonNull Consumer<Integer> statusConsumer);

    /**
     * Called when configurations and read-only data in a {@link PersistableBundle} can be used by
     * the WearableSensingService and sends the result to the {@link Consumer} right after the call.
     * It is dependent on the application to define the type of data to provide. This is used by
     * applications that will also provide an implementation of an isolated WearableSensingService.
     * If the data was provided successfully {@link WearableSensingManager#STATUS_SUCCESS} will be
     * provided.
     *
     * @param data Application configuration data to provide to the {@link WearableSensingService}.
     *     PersistableBundle does not allow any remotable objects or other contents that can be used
     *     to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the {@link
     *     WearableSensingService}. Use this to provide the sensing models data or other such data
     *     to the trusted process.
     * @param statusConsumer the consumer for the service status.
     */
    @BinderThread
    public abstract void onDataProvided(
            @NonNull PersistableBundle data,
            @Nullable SharedMemory sharedMemory,
            @NonNull Consumer<Integer> statusConsumer);

    /**
     * Called when a data request observer is registered. Each request must not be larger than
     * {@link WearableSensingDataRequest#getMaxRequestSize()}. In addition, at most {@link
     * WearableSensingDataRequester#getRateLimit()} requests can be sent every rolling {@link
     * WearableSensingDataRequester#getRateLimitWindowSize()}. Requests that are too large or too
     * frequent will be dropped by the system. See {@link
     * WearableSensingDataRequester#requestData(WearableSensingDataRequest, Consumer)} for details
     * about the status code returned for each request.
     *
     * <p>The implementing class should override this method. After the data requester is received,
     * it should send a {@link WearableSensingManager#STATUS_SUCCESS} status code to the {@code
     * statusConsumer} unless it encounters an error condition described by a status code listed in
     * {@link WearableSensingManager}, such as {@link
     * WearableSensingManager#STATUS_WEARABLE_UNAVAILABLE}, in which case it should return the
     * corresponding status code.
     *
     * @param dataType The data type the observer is registered for. Values are defined by the
     *     application that implements this class.
     * @param packageName The package name of the app that will receive the requests.
     * @param dataRequester A handle to the observer registered. It can be used to request data of
     *     the specified data type.
     * @param statusConsumer the consumer for the status of the data request observer registration.
     *     This is different from the status for each data request.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
    @BinderThread
    public void onDataRequestObserverRegistered(
            int dataType,
            @NonNull String packageName,
            @NonNull WearableSensingDataRequester dataRequester,
            @NonNull Consumer<Integer> statusConsumer) {
        statusConsumer.accept(WearableSensingManager.STATUS_UNSUPPORTED_OPERATION);
    }

    /**
     * Called when a data request observer is unregistered.
     *
     * <p>The implementing class should override this method. It should send a {@link
     * WearableSensingManager#STATUS_SUCCESS} status code to the {@code statusConsumer} unless it
     * encounters an error condition described by a status code listed in {@link
     * WearableSensingManager}, such as {@link WearableSensingManager#STATUS_WEARABLE_UNAVAILABLE},
     * in which case it should return the corresponding status code.
     *
     * @param dataType The data type the observer is for.
     * @param packageName The package name of the app that will receive the requests sent to the
     *     dataRequester.
     * @param dataRequester A handle to the observer to be unregistered. It is the exact same
     *     instance provided in a previous {@link #onDataRequestConsumerRegistered(int, String,
     *     WearableSensingDataRequester, Consumer)} invocation.
     * @param statusConsumer the consumer for the status of the data request observer
     *     unregistration. This is different from the status for each data request.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
    @BinderThread
    public void onDataRequestObserverUnregistered(
            int dataType,
            @NonNull String packageName,
            @NonNull WearableSensingDataRequester dataRequester,
            @NonNull Consumer<Integer> statusConsumer) {
        statusConsumer.accept(WearableSensingManager.STATUS_UNSUPPORTED_OPERATION);
    }

    /**
     * Called when the wearable is requested to start hotword recognition.
     *
     * <p>This method is expected to be overridden by a derived class. The implementation should
     * store the {@code hotwordAudioConsumer} and send it the audio data when first-stage hotword is
     * detected from the wearable. It should also send a {@link
     * WearableSensingManager#STATUS_SUCCESS} status code to the {@code statusConsumer} unless it
     * encounters an error condition described by a status code listed in {@link
     * WearableSensingManager}, such as {@link WearableSensingManager#STATUS_WEARABLE_UNAVAILABLE},
     * in which case it should return the corresponding status code.
     *
     * <p>The implementation should also store the {@code statusConsumer}. If the wearable stops
     * listening for hotword for any reason other than {@link #onStopListeningForHotword(Consumer)}
     * being invoked, it should send an appropriate status code listed in {@link
     * WearableSensingManager} to {@code statusConsumer}. If the error condition cannot be described
     * by any of those status codes, it should send a {@link WearableSensingManager#STATUS_UNKNOWN}.
     *
     * <p>If this method is called again, the implementation should use the new {@code
     * hotwordAudioConsumer} and discard any previous ones it received.
     *
     * <p>At this time, the {@code timestamp} field in the {@link HotwordAudioStream} is not used
     * and will be discarded by the system.
     *
     * @param hotwordAudioConsumer The consumer for the wearable hotword audio data.
     * @param statusConsumer The consumer for the service status.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_HOTWORD_WEARABLE_SENSING_API)
    @BinderThread
    public void onStartHotwordRecognition(
            @NonNull Consumer<HotwordAudioStream> hotwordAudioConsumer,
            @NonNull Consumer<Integer> statusConsumer) {
        if (Flags.enableUnsupportedOperationStatusCode()) {
            statusConsumer.accept(WearableSensingManager.STATUS_UNSUPPORTED_OPERATION);
        }
    }

    /**
     * Called when the wearable is requested to stop hotword recognition.
     *
     * <p>This method is expected to be overridden by a derived class. It should send a {@link
     * WearableSensingManager#STATUS_SUCCESS} status code to the {@code statusConsumer} unless it
     * encounters an error condition described by a status code listed in {@link
     * WearableSensingManager}, such as {@link WearableSensingManager#STATUS_WEARABLE_UNAVAILABLE},
     * in which case it should return the corresponding status code.
     *
     * @param statusConsumer The consumer for the service status.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_HOTWORD_WEARABLE_SENSING_API)
    @BinderThread
    public void onStopHotwordRecognition(@NonNull Consumer<Integer> statusConsumer) {
        if (Flags.enableUnsupportedOperationStatusCode()) {
            statusConsumer.accept(WearableSensingManager.STATUS_UNSUPPORTED_OPERATION);
        }
    }

    /**
     * Called when hotword audio data sent to the {@code hotwordAudioConsumer} in {@link
     * #onStartListeningForHotword(Consumer, Consumer)} is accepted by the
     * {@link android.service.voice.HotwordDetectionService} as valid hotword.
     *
     * <p>After the implementation of this class sends the hotword audio data to the {@code
     * hotwordAudioConsumer} in {@link #onStartListeningForHotword(Consumer,
     * Consumer)}, the system will forward the data into {@link
     * android.service.voice.HotwordDetectionService} (which runs in an isolated process) for
     * second-stage hotword detection. If accepted as valid hotword there, this method will be
     * called, and then the system will send the data to the currently active {@link
     * android.service.voice.AlwaysOnHotwordDetector} (which may not run in an isolated process).
     *
     * <p>This method is expected to be overridden by a derived class. The implementation must
     * request the wearable to turn on the microphone indicator to notify the user that audio data
     * is being used outside of the isolated environment.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_HOTWORD_WEARABLE_SENSING_API)
    @BinderThread
    public void onValidatedByHotwordDetectionService() {}

    /**
     * Called when the currently active hotword audio stream is no longer needed.
     *
     * <p>This method can be called as a result of hotword rejection by {@link
     * android.service.voice.HotwordDetectionService}, or the {@link
     * android.service.voice.AlwaysOnHotwordDetector} closing the data stream it received, or a
     * non-recoverable error occurred before the data reaches the {@link
     * android.service.voice.HotwordDetectionService} or the {@link
     * android.service.voice.AlwaysOnHotwordDetector}.
     *
     * <p>This method is expected to be overridden by a derived class. The implementation should
     * stop sending hotword audio data to the {@code hotwordAudioConsumer} in {@link
     * #onStartListeningForHotword(Consumer, Consumer)}
     */
    @FlaggedApi(Flags.FLAG_ENABLE_HOTWORD_WEARABLE_SENSING_API)
    @BinderThread
    public void onStopHotwordAudioStream() {}

    /**
     * Called when a client app requests starting detection of the events in the request. The
     * implementation should keep track of whether the user has explicitly consented to detecting
     * the events using on-going ambient sensor (e.g. microphone), and agreed to share the
     * detection results with this client app. If the user has not consented, the detection
     * should not start, and the statusConsumer should get a response with STATUS_ACCESS_DENIED.
     * If the user has made the consent and the underlying services are available, the
     * implementation should start detection and provide detected events to the
     * detectionResultConsumer. If the type of event needs immediate attention, the implementation
     * should send result as soon as detected. Otherwise, the implementation can batch response.
     * The ongoing detection will keep running, until onStopDetection is called. If there were
     * previously requested detections from the same package, regardless of the type of events in
     * the request, the previous request will be replaced with the new request and pending events
     * are discarded.
     *
     * @param request The request with events to detect.
     * @param packageName the requesting app's package name
     * @param statusConsumer the consumer for the service status.
     * @param detectionResultConsumer the consumer for the detected event
     */
    @BinderThread
    public abstract void onStartDetection(@NonNull AmbientContextEventRequest request,
            @NonNull String packageName,
            @NonNull Consumer<AmbientContextDetectionServiceStatus> statusConsumer,
            @NonNull Consumer<AmbientContextDetectionResult> detectionResultConsumer);

    /**
     * Stops detection of the events. Events that are not being detected will be ignored.
     *
     * @param packageName stops detection for the given package.
     */
    public abstract void onStopDetection(@NonNull String packageName);

    /**
     * Called when a query for the detection status occurs. The implementation should check
     * the detection status of the requested events for the package, and provide results in a
     * {@link AmbientContextDetectionServiceStatus} for the consumer.
     *
     * @param eventTypes The events to check for status.
     * @param packageName the requesting app's package name
     * @param consumer the consumer for the query results
     */
    @BinderThread
    public abstract void onQueryServiceStatus(@NonNull Set<Integer> eventTypes,
            @NonNull String packageName,
            @NonNull Consumer<AmbientContextDetectionServiceStatus> consumer);

    /**
     * Overrides {@link Context#openFileInput} to read files with the given {@code fileName} under
     * the internal app storage of the APK providing the implementation for this class. {@link
     * Context#getFilesDir()} will be added as a prefix to the provided {@code fileName}.
     *
     * <p>This method is only functional after {@link
     * #onSecureConnectionProvided(ParcelFileDescriptor, Consumer)} or {@link
     * #onDataStreamProvided(ParcelFileDescriptor, Consumer)} has been called as a result of a
     * process owned by the same APK calling {@link
     * WearableSensingManager#provideConnection(ParcelFileDescriptor, Executor, Consumer)} or {@link
     * WearableSensingManager#provideDataStream(ParcelFileDescriptor, Executor, Consumer)}.
     * Otherwise, it will throw an {@link IllegalStateException}. This is because this method
     * proxies the file read via that process. Also, the APK needs to have a targetSdkVersion of 35
     * or newer.
     *
     * @param fileName Relative path of a file under {@link Context#getFilesDir()}.
     * @throws IllegalStateException if the above condition is not satisfied.
     * @throws FileNotFoundException if the file does not exist or cannot be opened, or an error
     *     occurred during the RPC to proxy the file read via a non-isolated process.
     */
    // SuppressLint is needed because the parent Context class does not specify the nullability of
    // the parameter filename. If we remove the @NonNull annotation, the linter will complain about
    // MissingNullability
    @Override
    public @NonNull FileInputStream openFileInput(
            @SuppressLint("InvalidNullabilityOverride") @NonNull String fileName)
            throws FileNotFoundException {
        if (fileName == null) {
            throw new IllegalArgumentException("filename cannot be null");
        }
        try {
            if (mWearableSensingCallback == null) {
                throw new IllegalStateException(
                        "Cannot open file from WearableSensingService. WearableSensingCallback is"
                                + " not available.");
            }
            AndroidFuture<ParcelFileDescriptor> future = new AndroidFuture<>();
            mWearableSensingCallback.openFile(fileName, future);
            ParcelFileDescriptor pfd =
                    future.get(OPEN_FILE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (pfd == null) {
                throw new FileNotFoundException(
                        TextUtils.formatSimple(
                                "File %s not found or unable to be opened in read-only mode.",
                                fileName));
            }
            return new FileInputStream(pfd.getFileDescriptor());
        } catch (RemoteException | ExecutionException | TimeoutException e) {
            throw (FileNotFoundException)
                    new FileNotFoundException("Cannot open file due to remote service failure")
                            .initCause(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw (FileNotFoundException)
                    new FileNotFoundException("Interrupted when opening a file.").initCause(e);
        }
    }

    @NonNull
    private static Integer[] intArrayToIntegerArray(@NonNull int[] integerSet) {
        Integer[] intArray = new Integer[integerSet.length];
        int i = 0;
        for (Integer type : integerSet) {
            intArray[i++] = type;
        }
        return intArray;
    }

    private static WearableSensingDataRequester createDataRequester(
            RemoteCallback dataRequestCallback) {
        return (request, requestStatusConsumer) -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(WearableSensingDataRequest.REQUEST_BUNDLE_KEY, request);
            RemoteCallback requestStatusCallback =
                    new RemoteCallback(
                            requestStatusBundle -> {
                                requestStatusConsumer.accept(
                                        requestStatusBundle.getInt(
                                                WearableSensingManager.STATUS_RESPONSE_BUNDLE_KEY));
                            });
            bundle.putParcelable(
                    WearableSensingDataRequest.REQUEST_STATUS_CALLBACK_BUNDLE_KEY,
                    requestStatusCallback);
            dataRequestCallback.sendResult(bundle);
        };
    }

    @NonNull
    private static Consumer<Integer> createWearableStatusConsumer(RemoteCallback statusCallback) {
        return response -> {
            Bundle bundle = new Bundle();
            bundle.putInt(STATUS_RESPONSE_BUNDLE_KEY, response);
            statusCallback.sendResult(bundle);
        };
    }


}
