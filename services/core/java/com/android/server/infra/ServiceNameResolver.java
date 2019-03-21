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
import android.annotation.UserIdInt;

import com.android.internal.infra.AbstractRemoteService;

import java.io.PrintWriter;

/**
 * A helper class used to resolve the name of the app-provided service a
 * {@link AbstractRemoteService} binds to.
 *
 * @hide
 */
public interface ServiceNameResolver {

    /**
     * Listener for name changes.
     */
    public interface NameResolverListener {

        /**
         * The name change callback.
         */
        void onNameResolved(@UserIdInt int userId, @Nullable String serviceName);
    }

    /**
     * Sets a callback that is called after the service is
     * {@link #setTemporaryService(int, String, int) set} or
     * {@link #resetTemporaryService(int) reset}.
     *
     * <p>Typically called after the object is constructed.
     */
    default void setOnTemporaryServiceNameChangedCallback(
            @SuppressWarnings("unused") @NonNull NameResolverListener callback) {
        // ignored by default
    }

    /**
     * Gets the default name of the service for the given user.
     *
     * <p>Typically implemented by reading a Settings property or framework resource.
     */
    @Nullable
    String getDefaultServiceName(@UserIdInt int userId);

    /**
     * Gets the current name of the service for the given user
     *
     * @return either the temporary name (set by
     * {@link #setTemporaryService(int, String, int)}, or the
     * {@link #getDefaultServiceName(int) default name}.
     */
    @Nullable
    default String getServiceName(@UserIdInt int userId) {
        return getDefaultServiceName(userId);
    }

    /**
     * Checks whether the current service is temporary for the given user.
     */
    default boolean isTemporary(@SuppressWarnings("unused") @UserIdInt int userId) {
        return false;
    }

    /**
     * Temporarily sets the service implementation for the given user.
     *
     * @param userId user handle
     * @param componentName name of the new component
     * @param durationMs how long the change will be valid (the service will be automatically reset
     *            to the default component after this timeout expires).
     *
     * @throws UnsupportedOperationException if not implemented.
     */
    default void setTemporaryService(@UserIdInt int userId, @NonNull String componentName,
            int durationMs) {
        throw new UnsupportedOperationException("temporary user not supported");
    }

    /**
     * Resets the temporary service implementation to the default component for the given user.
     *
     * @param userId user handle
     *
     * @throws UnsupportedOperationException if not implemented.
     */
    default void resetTemporaryService(@UserIdInt int userId) {
        throw new UnsupportedOperationException("temporary user not supported");
    }

    /**
     * Sets whether the default service should be used when the temporary service is not set.
     *
     * <p>Typically used during CTS tests to make sure only the default service doesn't interfere
     * with the test results.
     *
     * @param userId user handle
     * @param enabled whether the default service should be used when the temporary service is not
     * set. If the service enabled state is already that value, the command is ignored and this
     * method return {@code false}.
     *
     * @return whether the enabled state changed.
     * @throws UnsupportedOperationException if not implemented.
     */
    default boolean setDefaultServiceEnabled(@UserIdInt int userId, boolean enabled) {
        throw new UnsupportedOperationException("changing default service not supported");
    }

    /**
     * Checks whether the default service should be used when the temporary service is not set.
     *
     * <p>Typically used during CTS tests to make sure only the default service doesn't interfere
     * with the test results.
     *
     * @param userId user handle
     *
     * @throws UnsupportedOperationException if not implemented.
     */
    default boolean isDefaultServiceEnabled(@UserIdInt int userId) {
        throw new UnsupportedOperationException("checking default service not supported");
    }

    /**
     * Dumps the generic info in just one line (without calling {@code println}.
     */
    // TODO(b/117779333): support proto
    void dumpShort(@NonNull PrintWriter pw);

    /**
     * Dumps the user-specific info in just one line (without calling {@code println}.
     */
    // TODO(b/117779333): support proto
    void dumpShort(@NonNull PrintWriter pw, @UserIdInt int userId);
}
