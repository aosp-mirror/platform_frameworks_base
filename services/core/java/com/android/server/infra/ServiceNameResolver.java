/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.infra;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.PrintWriter;

/**
 * A helper class used to resolve the name of the app-provided service a
 * {@link AbstractRemoteService} binds to.
 *
 * @hide
 */
public interface ServiceNameResolver {

    /**
     * Sets a callback that is called after the service is
     * {@link #setTemporaryServiceLocked(String, int) set} or
     * {@link #resetTemporaryServiceLocked() reset}.
     *
     * <p>Typically called after the object is constructed.
     */
    default void setOnTemporaryServiceNameChangedCallback(
            @SuppressWarnings("unused") @NonNull Runnable callback) {
        // ignored by default
    }

    /**
     * Gets the default name of the service.
     *
     * <p>Typically implemented by reading a Settings property or framework resource.
     */
    @Nullable
    String getDefaultServiceName();

    /**
     * Gets the current name of the service.
     *
     * <p>Must be called after acquiring this object's lock.
     *
     * @return either the temporary name (set by {@link #setTemporaryServiceLocked(String, int)}, or
     * the {@link #getDefaultServiceName() default name}.
     */
    @Nullable
    default String getServiceNameLocked() {
        return getDefaultServiceName();
    }

    /**
     * Checks whether the current service is temporary.
     *
     * <p>Must be called after acquiring this object's lock.
     */
    default boolean isTemporaryLocked() {
        return false;
    }

    /**
     * Temporarily sets the service implementation.
     *
     * <p>Must be called after acquiring this object's lock.
     *
     * @param componentName name of the new component
     * @param durationMs how long the change will be valid (the service will be automatically reset
     *            to the default component after this timeout expires).
     *
     * @throws UnsupportedOperationException if not implemented.
     */
    default void setTemporaryServiceLocked(@NonNull String componentName, int durationMs) {
        throw new UnsupportedOperationException("temporary user not supported");
    }

    /**
     * Resets the temporary service implementation to the default component.
     *
     * <p>Must be called after acquiring this object's lock.
     *
     * @throws UnsupportedOperationException if not implemented.
     */
    default void resetTemporaryServiceLocked() {
        throw new UnsupportedOperationException("temporary user not supported");
    }

    /**
     * Dump this object in just one line (without calling {@code println}.
     *
     * <p>Must be called after acquiring this object's lock.
     */
    // TODO(b/117779333): support proto
    void dumpShortLocked(@NonNull PrintWriter pw);
}
