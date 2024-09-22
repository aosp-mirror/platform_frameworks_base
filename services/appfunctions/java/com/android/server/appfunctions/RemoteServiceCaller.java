/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.appfunctions;

import android.annotation.NonNull;
import android.content.Intent;
import android.os.UserHandle;

/**
 * Defines a contract for establishing temporary connections to services and executing operations
 * within a specified timeout. Implementations of this interface provide mechanisms to ensure that
 * services are properly unbound after the operation completes or a timeout occurs.
 *
 * @param <T> Class of wrapped service.
 */
public interface RemoteServiceCaller<T> {

    /**
     * Initiates service binding and executes a provided method when the service connects. Unbinds
     * the service after execution or upon timeout. Returns the result of the bindService API.
     *
     * <p>When the service connection was made successfully, it's the caller responsibility to
     * report the usage is completed and can be unbound by calling {@link
     * ServiceUsageCompleteListener#onCompleted()}.
     *
     * <p>This method includes a timeout mechanism to prevent the system from being stuck in a state
     * where a service is bound indefinitely (for example, if the binder method never returns). This
     * helps ensure that the calling app does not remain alive unnecessarily.
     *
     * @param intent An Intent object that describes the service that should be bound.
     * @param bindFlags Flags used to control the binding process See {@link
     *     android.content.Context#bindService}.
     * @param userHandle The UserHandle of the user for which the service should be bound.
     * @param callback A callback to be invoked for various events. See {@link
     *     RunServiceCallCallback}.
     */
    boolean runServiceCall(
            @NonNull Intent intent,
            int bindFlags,
            @NonNull UserHandle userHandle,
            @NonNull RunServiceCallCallback<T> callback);

    /** An interface for clients to signal that they have finished using a bound service. */
    interface ServiceUsageCompleteListener {
        /**
         * Called when a client has finished using a bound service. This indicates that the service
         * can be safely unbound.
         */
        void onCompleted();
    }

    interface RunServiceCallCallback<T> {
        /**
         * Called when the service connection has been established. Uses {@code
         * serviceUsageCompleteListener} to report finish using the connected service.
         */
        void onServiceConnected(
                @NonNull T service,
                @NonNull ServiceUsageCompleteListener serviceUsageCompleteListener);

        /** Called when the service connection was failed to establish. */
        void onFailedToConnect();
    }
}
