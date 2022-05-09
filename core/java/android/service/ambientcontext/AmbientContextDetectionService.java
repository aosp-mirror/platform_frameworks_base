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

package android.service.ambientcontext;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.util.Slog;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Abstract base class for {@link AmbientContextEvent} detection service.
 *
 * <p> A service that provides requested ambient context events to the system.
 * The system's default AmbientContextDetectionService implementation is configured in
 * {@code config_defaultAmbientContextDetectionService}. If this config has no value, a stub is
 * returned.
 *
 * See: {@code AmbientContextManagerService}.
 *
 * <pre>
 * {@literal
 * <service android:name=".YourAmbientContextDetectionService"
 *          android:permission="android.permission.BIND_AMBIENT_CONTEXT_DETECTION_SERVICE">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
public abstract class AmbientContextDetectionService extends Service {
    private static final String TAG = AmbientContextDetectionService.class.getSimpleName();

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_AMBIENT_CONTEXT_DETECTION_SERVICE}
     * permission so that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.ambientcontext.AmbientContextDetectionService";

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IAmbientContextDetectionService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void startDetection(
                        @NonNull AmbientContextEventRequest request, String packageName,
                        RemoteCallback detectionResultCallback, RemoteCallback statusCallback) {
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
                    AmbientContextDetectionService.this.onStartDetection(
                            request, packageName, detectionResultConsumer, statusConsumer);
                    Slog.d(TAG, "startDetection " + request);
                }

                /** {@inheritDoc} */
                @Override
                public void stopDetection(String packageName) {
                    Objects.requireNonNull(packageName);
                    AmbientContextDetectionService.this.onStopDetection(packageName);
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
                    AmbientContextDetectionService.this.onQueryServiceStatus(
                            eventTypes, packageName, consumer);
                }
            };
        }
        return null;
    }

    /**
     * Called when a client app requests starting detection of the events in the request. The
     * implementation should keep track of whether the user has explicitly consented to detecting
     * the events using on-going ambient sensor (e.g. microphone), and agreed to share the
     * detection results with this client app. If the user has not consented, the detection
     * should not start, and the statusConsumer should get a response with STATUS_ACCESS_DENIED.
     * If the user has made the consent and the underlying services are available, the
     * implementation should start detection and provide detected events to the
     * detectionResultConsumer. If the type of event needs immediate attention, the implementation
     * should send result as soon as detected. Otherwise, the implementation can bulk send response.
     * The ongoing detection will keep running, until onStopDetection is called. If there were
     * previously requested detection from the same package, regardless of the type of events in
     * the request, the previous request will be replaced with the new request.
     *
     * @param request The request with events to detect.
     * @param packageName the requesting app's package name
     * @param detectionResultConsumer the consumer for the detected event
     * @param statusConsumer the consumer for the service status.
     */
    @BinderThread
    public abstract void onStartDetection(
            @NonNull AmbientContextEventRequest request,
            @NonNull String packageName,
            @NonNull Consumer<AmbientContextDetectionResult> detectionResultConsumer,
            @NonNull Consumer<AmbientContextDetectionServiceStatus> statusConsumer);

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
    public abstract void onQueryServiceStatus(
            @NonNull int[] eventTypes,
            @NonNull String packageName,
            @NonNull Consumer<AmbientContextDetectionServiceStatus> consumer);
}
