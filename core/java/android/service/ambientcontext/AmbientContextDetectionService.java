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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.ambientcontext.AmbientContextEventResponse;
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

    /**
     * The key for the bundle the parameter of {@code RemoteCallback#sendResult}. Implementation
     * should set bundle result with this key.
     *
     * @hide
     */
    public static final String RESPONSE_BUNDLE_KEY =
            "android.service.ambientcontext.EventResponseKey";

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IAmbientContextDetectionService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void startDetection(
                        @NonNull AmbientContextEventRequest request, String packageName,
                        RemoteCallback callback) {
                    Objects.requireNonNull(request);
                    Objects.requireNonNull(callback);
                    Consumer<AmbientContextEventResponse> consumer =
                            response -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        AmbientContextDetectionService.RESPONSE_BUNDLE_KEY,
                                        response);
                                callback.sendResult(bundle);
                            };
                    AmbientContextDetectionService.this.onStartDetection(
                            request, packageName, consumer);
                    Slog.d(TAG, "startDetection " + request);
                }

                /** {@inheritDoc} */
                @Override
                public void stopDetection(String packageName) {
                    Objects.requireNonNull(packageName);
                    AmbientContextDetectionService.this.onStopDetection(packageName);
                }
            };
        }
        return null;
    }

    /**
     * Starts detection and provides detected events to the consumer. The ongoing detection will
     * keep running, until onStopDetection is called. If there were previously requested
     * detection from the same package, the previous request will be replaced with the new request.
     * The implementation should keep track of whether the user consented each requested
     * AmbientContextEvent for the app. If not consented, the response should set status
     * STATUS_ACCESS_DENIED and include an action PendingIntent for the app to redirect the user
     * to the consent screen.
     *
     * @param request The request with events to detect, optional detection window and other
     *                options.
     * @param packageName the requesting app's package name
     * @param consumer the consumer for the detected event
     */
    public abstract void onStartDetection(
            @NonNull AmbientContextEventRequest request,
            @NonNull String packageName,
            @NonNull Consumer<AmbientContextEventResponse> consumer);

    /**
     * Stops detection of the events. Events that are not being detected will be ignored.
     *
     * @param packageName stops detection for the given package.
     */
    public abstract void onStopDetection(@NonNull String packageName);
}
