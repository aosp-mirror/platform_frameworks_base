/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.location.util.listeners;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.location.util.listeners.AbstractListenerManager.Registration;

import java.util.concurrent.Executor;

/**
 * A listener manager for client side implementations, where there is no need to deal with any
 * complications other than muxing listeners. Listeners without associated requests are supported
 * without any further work, and listeners with requests must produce muxed requests of the same
 * type.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 * @hide
 */
public abstract class ClientListenerManager<TRequest, TListener> extends
        AbstractListenerManager<Object, TRequest, TListener, Registration<TRequest, TListener>,
                TRequest> {

    /**
     * Adds a new listener with no request.
     */
    public void addListener(@NonNull TListener listener, @NonNull Executor executor) {
        addListener(listener, null, listener, executor);
    }

    /**
     * Adds a new listener with the given request.
     */
    public void addListener(@Nullable TRequest request, @NonNull TListener listener,
            @NonNull Executor executor) {
        addListener(listener, request, listener, executor);
    }

    /**
     * Adds a new listener with the given request using a custom key, rather than using the listener
     * as the key.
     */
    protected void addListener(@NonNull Object key, @Nullable TRequest request,
            @NonNull TListener listener, @NonNull Executor executor) {
        addRegistration(key, new Registration<>(request, executor, listener));
    }

    /**
     * Removes the listener with the given key.
     */
    public void removeListener(@NonNull Object key) {
        removeRegistration(key);
    }
}
