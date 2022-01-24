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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * Allows granted apps to register for particular pre-defined {@link AmbientContextEvent}s.
 * After successful registration, the app receives a callback on the provided {@link PendingIntent}
 * when the requested event is detected.
 * <p />
 *
 * Example:
 *
 * <pre><code>
 *     // Create request
 *     AmbientContextEventRequest request = new AmbientContextEventRequest.Builder()
 *         .addEventType(AmbientContextEvent.EVENT_COUGH)
 *         .addEventTYpe(AmbientContextEvent.EVENT_SNORE)
 *         .build();
 *     // Create PendingIntent
 *     Intent intent = new Intent(actionString, null, context, MyBroadcastReceiver.class)
 *         .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
 *     PendingIntent pendingIntent = PendingIntents.getBroadcastMutable(context, 0, intent, 0);
 *     // Register for events
 *     AmbientContextManager ambientContextManager =
 *         context.getSystemService(AmbientContextManager.class);
 *    ambientContextManager.registerObserver(request, pendingIntent);
 *
 *    // Handle the callback intent in your receiver
 *    {@literal @}Override
 *    protected void onReceive(Context context, Intent intent) {
 *      AmbientContextEventResponse response =
 *          AmbientContextManager.getResponseFromIntent(intent);
 *      if (response != null) {
 *        if (response.getStatusCode() == AmbientContextEventResponse.STATUS_SUCCESS) {
 *          // Do something useful with response.getEvent()
 *        } else if (response.getStatusCode() == AmbientContextEventResponse.STATUS_ACCESS_DENIED) {
 *          // Redirect users to grant access
 *          PendingIntent callbackPendingIntent = response.getCallbackPendingIntent();
 *          if (callbackPendingIntent != null) {
 *            callbackPendingIntent.send();
 *          }
 *        } else ...
 *      }
 *    }
 * </code></pre>
 *
 * @hide
 */
@SystemApi
@SystemService(Context.AMBIENT_CONTEXT_SERVICE)
public final class AmbientContextManager {

    /**
     * The key of an Intent extra indicating the response.
     */
    public static final String EXTRA_AMBIENT_CONTEXT_EVENT_RESPONSE =
            "android.app.ambientcontext.extra.AMBIENT_CONTEXT_EVENT_RESPONSE";

    /**
     * Allows clients to retrieve the response from the intent.
     * @param intent received from the PendingIntent callback
     *
     * @return the AmbientContextEventResponse, or null if not present
     */
    @Nullable
    public static AmbientContextEventResponse getResponseFromIntent(
            @NonNull Intent intent) {
        if (intent.hasExtra(AmbientContextManager.EXTRA_AMBIENT_CONTEXT_EVENT_RESPONSE)) {
            return intent.getParcelableExtra(EXTRA_AMBIENT_CONTEXT_EVENT_RESPONSE);
        } else {
            return null;
        }
    }

    private final Context mContext;
    private final IAmbientContextEventObserver mService;

    /**
     * {@hide}
     */
    public AmbientContextManager(Context context, IAmbientContextEventObserver service) {
        mContext = context;
        mService = service;
    }

    /**
     * Allows app to register as a {@link AmbientContextEvent} observer. The
     * observer receives a callback on the provided {@link PendingIntent} when the requested
     * event is detected. Registering another observer from the same package that has already been
     * registered will override the previous observer.
     *
     * @param request The request with events to observe.
     * @param pendingIntent A mutable {@link PendingIntent} that will be dispatched when any
     *                     requested event is detected.
     */
    @RequiresPermission(Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT)
    public void registerObserver(
            @NonNull AmbientContextEventRequest request,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(!pendingIntent.isImmutable());
        try {
            mService.registerObserver(request, pendingIntent);
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
