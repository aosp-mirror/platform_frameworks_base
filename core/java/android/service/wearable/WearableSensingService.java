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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.wearable.WearableSensingManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.SharedMemory;
import android.util.Slog;

import java.util.Objects;
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
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_WEARABLE_SENSING_SERVICE}
     * permission so that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.wearable.WearableSensingService";

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IWearableSensingService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void provideDataStream(
                        ParcelFileDescriptor parcelFileDescriptor,
                        RemoteCallback callback) {
                    Objects.requireNonNull(parcelFileDescriptor);
                    Consumer<Integer> consumer = response -> {
                        Bundle bundle = new Bundle();
                        bundle.putInt(
                                STATUS_RESPONSE_BUNDLE_KEY,
                                response);
                        callback.sendResult(bundle);
                    };
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
                    Consumer<Integer> consumer = response -> {
                        Bundle bundle = new Bundle();
                        bundle.putInt(
                                STATUS_RESPONSE_BUNDLE_KEY,
                                response);
                        callback.sendResult(bundle);
                    };
                    WearableSensingService.this.onDataProvided(data, sharedMemory, consumer);
                }
            };
        }
        Slog.w(TAG, "Incorrect service interface, returning null.");
        return null;
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
     * Called when configurations and read-only data in a {@link PersistableBundle}
     * can be used by the WearableSensingService and sends the result to the {@link Consumer}
     * right after the call. It is dependent on the application to define the type of data to
     * provide. This is used by applications that will also provide an implementation of an isolated
     * WearableSensingService. If the data was provided successfully
     * {@link WearableSensingManager#STATUS_SUCCESS} will be provided.
     *
     * @param data Application configuration data to provide to the {@link WearableSensingService}.
     *             PersistableBundle does not allow any remotable objects or other contents
     *             that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to
     *                     provide to the {@link WearableSensingService}. Use this to provide the
     *                     sensing models data or other such data to the trusted process.
     * @param statusConsumer the consumer for the service status.
     */
    @BinderThread
    public abstract void onDataProvided(
            @NonNull PersistableBundle data,
            @Nullable SharedMemory sharedMemory,
            @NonNull Consumer<Integer> statusConsumer);
}
