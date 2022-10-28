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

import android.annotation.NonNull;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Callback for listening to Ambient Context events and status changes. See {@link
 * AmbientContextManager#registerObserver(AmbientContextEventRequest, AmbientContextCallback,
 * Executor)}
 *
 * @hide
 */
public interface AmbientContextCallback {
    /**
     * Called when AmbientContextManager service detects events.
     *
     * @param events a list of detected events.
     */
    void onEvents(@NonNull List<AmbientContextEvent> events);

    /**
     * Called with a statusCode when
     * {@link AmbientContextManager#registerObserver(AmbientContextEventRequest,
     * Executor, AmbientContextCallback)} completes, to indicate if the registration is successful
     *
     * @param statusCode the status of the service.
     */
    void onRegistrationComplete(@NonNull @AmbientContextManager.StatusCode int statusCode);
}
