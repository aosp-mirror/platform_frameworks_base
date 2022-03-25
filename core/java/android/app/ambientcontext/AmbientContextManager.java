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

package android.app.ambientcontext;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.RemoteCallback;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Allows granted apps to register for event types defined in {@link AmbientContextEvent}.
 * After registration, the app receives a Consumer callback of the service status.
 * If it is {@link STATUS_SUCCESSFUL}, when the requested events are detected, the provided
 * {@link PendingIntent} callback will receive the list of detected {@link AmbientContextEvent}s.
 * If it is {@link STATUS_ACCESS_DENIED}, the app can call {@link #startConsentActivity}
 * to load the consent screen.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.AMBIENT_CONTEXT_SERVICE)
public final class AmbientContextManager {
    /**
     * The bundle key for the service status query result, used in
     * {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String STATUS_RESPONSE_BUNDLE_KEY =
            "android.app.ambientcontext.AmbientContextStatusBundleKey";

    /**
     * The key of an intent extra indicating a list of detected {@link AmbientContextEvent}s.
     * The intent is sent to the app in the app's registered {@link PendingIntent}.
     */
    public static final String EXTRA_AMBIENT_CONTEXT_EVENTS =
            "android.app.ambientcontext.extra.AMBIENT_CONTEXT_EVENTS";

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
    public static final int STATUS_NOT_SUPPORTED = 2;

    /**
     * The value of the status code that indicates service not available.
     */
    public static final int STATUS_SERVICE_UNAVAILABLE = 3;

    /**
     * The value of the status code that microphone is disabled.
     */
    public static final int STATUS_MICROPHONE_DISABLED = 4;

    /**
     * The value of the status code that the app is not granted access.
     */
    public static final int STATUS_ACCESS_DENIED = 5;

    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN,
            STATUS_SUCCESS,
            STATUS_NOT_SUPPORTED,
            STATUS_SERVICE_UNAVAILABLE,
            STATUS_MICROPHONE_DISABLED,
            STATUS_ACCESS_DENIED
    }) public @interface StatusCode {}

    /**
     * Allows clients to retrieve the list of {@link AmbientContextEvent}s from the intent.
     *
     * @param intent received from the PendingIntent callback
     *
     * @return the list of events, or an empty list if the intent doesn't have such events.
     */
    @NonNull public static List<AmbientContextEvent> getEventsFromIntent(@NonNull Intent intent) {
        if (intent.hasExtra(AmbientContextManager.EXTRA_AMBIENT_CONTEXT_EVENTS)) {
            return intent.getParcelableArrayListExtra(EXTRA_AMBIENT_CONTEXT_EVENTS);
        } else {
            return new ArrayList<>();
        }
    }

    private final Context mContext;
    private final IAmbientContextManager mService;

    /**
     * {@hide}
     */
    public AmbientContextManager(Context context, IAmbientContextManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Queries the {@link AmbientContextEvent} service status for the calling package, and
     * sends the result to the {@link Consumer} right after the call. This is used by foreground
     * apps to check whether the requested events are enabled for detection on the device.
     * If all events are enabled for detection, the response has
     * {@link AmbientContextManager#STATUS_SUCCESS}.
     * If any of the events are not consented by user, the response has
     * {@link AmbientContextManager#STATUS_ACCESS_DENIED}, and the app can
     * call {@link #startConsentActivity} to redirect the user to the consent screen.
     * <p />
     *
     * Example:
     *
     * <pre><code>
     *   Set<Integer> eventTypes = new HashSet<>();
     *   eventTypes.add(AmbientContextEvent.EVENT_COUGH);
     *   eventTypes.add(AmbientContextEvent.EVENT_SNORE);
     *
     *   // Create Consumer
     *   Consumer<Integer> statusConsumer = response -> {
     *     int status = status.getStatusCode();
     *     if (status == AmbientContextManager.STATUS_SUCCESS) {
     *       // Show user it's enabled
     *     } else if (status == AmbientContextManager.STATUS_ACCESS_DENIED) {
     *       // Send user to grant access
     *       startConsentActivity(eventTypes);
     *     }
     *   };
     *
     *   // Query status
     *   AmbientContextManager ambientContextManager =
     *       context.getSystemService(AmbientContextManager.class);
     *   ambientContextManager.queryAmbientContextStatus(eventTypes, executor, statusConsumer);
     * </code></pre>
     *
     * @param eventTypes The set of event codes to check status on.
     * @param executor Executor on which to run the consumer callback.
     * @param consumer The consumer that handles the status code.
     */
    @RequiresPermission(Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT)
    public void queryAmbientContextServiceStatus(
            @NonNull @AmbientContextEvent.EventCode Set<Integer> eventTypes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> consumer) {
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                int status = result.getInt(STATUS_RESPONSE_BUNDLE_KEY);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> consumer.accept(status));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            });
            mService.queryServiceStatus(integerSetToIntArray(eventTypes),
                    mContext.getOpPackageName(), callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the consent data host to open an activity that allows users to modify consent.
     *
     * @param eventTypes The set of event codes to be consented.
     */
    @RequiresPermission(Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT)
    public void startConsentActivity(
            @NonNull @AmbientContextEvent.EventCode Set<Integer> eventTypes) {
        try {
            mService.startConsentActivity(
                    integerSetToIntArray(eventTypes), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    private static int[] integerSetToIntArray(@NonNull Set<Integer> integerSet) {
        int[] intArray = new int[integerSet.size()];
        int i = 0;
        for (Integer type : integerSet) {
            intArray[i++] = type;
        }
        return intArray;
    }

    /**
     * Allows app to register as a {@link AmbientContextEvent} observer. The
     * observer receives a callback on the provided {@link PendingIntent} when the requested
     * event is detected. Registering another observer from the same package that has already been
     * registered will override the previous observer.
     * <p />
     *
     * Example:
     *
     * <pre><code>
     *   // Create request
     *   AmbientContextEventRequest request = new AmbientContextEventRequest.Builder()
     *       .addEventType(AmbientContextEvent.EVENT_COUGH)
     *       .addEventType(AmbientContextEvent.EVENT_SNORE)
     *       .build();
     *
     *   // Create PendingIntent for delivering detection results to my receiver
     *   Intent intent = new Intent(actionString, null, context, MyBroadcastReceiver.class)
     *       .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
     *   PendingIntent pendingIntent =
     *       PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
     *
     *   // Create Consumer of service status
     *   Consumer<Integer> statusConsumer = status -> {
     *       if (status == AmbientContextManager.STATUS_ACCESS_DENIED) {
     *         // User did not consent event detection. See #queryAmbientContextServiceStatus and
     *         // #startConsentActivity
     *       }
     *   };
     *
     *   // Register as observer
     *   AmbientContextManager ambientContextManager =
     *       context.getSystemService(AmbientContextManager.class);
     *   ambientContextManager.registerObserver(request, pendingIntent, executor, statusConsumer);
     *
     *   // Handle the list of {@link AmbientContextEvent}s in your receiver
     *   {@literal @}Override
     *   protected void onReceive(Context context, Intent intent) {
     *     List<AmbientContextEvent> events = AmbientContextManager.getEventsFromIntent(intent);
     *     if (!events.isEmpty()) {
     *       // Do something useful with the events.
     *     }
     *   }
     * </code></pre>
     *
     * @param request The request with events to observe.
     * @param resultPendingIntent A mutable {@link PendingIntent} that will be dispatched after the
     *                            requested events are detected.
     * @param executor Executor on which to run the consumer callback.
     * @param statusConsumer A consumer that handles the status code, which is returned
     *                      right after the call.
     */
    @RequiresPermission(Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT)
    public void registerObserver(
            @NonNull AmbientContextEventRequest request,
            @NonNull PendingIntent resultPendingIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        Preconditions.checkArgument(!resultPendingIntent.isImmutable());
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                int statusCode =  result.getInt(STATUS_RESPONSE_BUNDLE_KEY);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> statusConsumer.accept(statusCode));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            });
            mService.registerObserver(request, resultPendingIntent, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters the requesting app as an {@code AmbientContextEvent} observer. Unregistering an
     * observer that was already unregistered or never registered will have no effect.
     */
    @RequiresPermission(Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT)
    public void unregisterObserver() {
        try {
            mService.unregisterObserver(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
