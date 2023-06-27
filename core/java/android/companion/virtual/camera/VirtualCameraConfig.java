/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.companion.virtual.camera;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.graphics.ImageFormat;
import android.util.ArraySet;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Configuration to create a new {@link VirtualCamera}.
 *
 * <p>Instance of this class are created using the {@link VirtualCameraConfig.Builder}.
 *
 * @hide
 */
public final class VirtualCameraConfig {

    private final String mDisplayName;
    private final Set<VirtualCameraStreamConfig> mStreamConfigurations;
    private final VirtualCameraCallback mCallback;
    private final Executor mCallbackExecutor;

    /**
     * Builder for {@link VirtualCameraConfig}.
     *
     * <p>To build an instance of {@link VirtualCameraConfig} the following conditions must be met:
     * <li>At least one stream must be added wit {@link #addStreamConfiguration(int, int, int)}.
     * <li>A name must be set with {@link #setDisplayName(String)}
     * <li>A callback must be set wit {@link #setCallback(Executor, VirtualCameraCallback)}
     */
    public static final class Builder {

        private String mDisplayName;
        private final ArraySet<VirtualCameraStreamConfig> mStreamConfiguration = new ArraySet<>();
        private Executor mCallbackExecutor;
        private VirtualCameraCallback mCallback;

        /** Set the visible name of this camera for the user. */
        // TODO: b/290172356 - Take a resource id instead of displayName
        @NonNull
        public Builder setDisplayName(@NonNull String displayName) {
            mDisplayName = requireNonNull(displayName);
            return this;
        }

        /**
         * Add an available stream configuration fot this {@link VirtualCamera}.
         *
         * <p>At least one {@link VirtualCameraStreamConfig} must be added.
         *
         * @param width The width of the stream
         * @param height The height of the stream
         * @param format The {@link ImageFormat} of the stream
         */
        @NonNull
        public Builder addStreamConfiguration(
                int width, int height, @ImageFormat.Format int format) {
            VirtualCameraStreamConfig streamConfig = new VirtualCameraStreamConfig();
            streamConfig.width = width;
            streamConfig.height = height;
            streamConfig.format = format;
            mStreamConfiguration.add(streamConfig);
            return this;
        }

        /**
         * Sets the {@link VirtualCameraCallback} used by the framework to communicate with the
         * {@link VirtualCamera} owner.
         *
         * <p>Setting a callback is mandatory.
         *
         * @param executor The executor onto which the callback methods will be called
         * @param callback The instance of the callback to be added. Subsequent call to this method
         *     will replace the callback set.
         */
        public Builder setCallback(
                @NonNull Executor executor, @NonNull VirtualCameraCallback callback) {
            mCallbackExecutor = requireNonNull(executor);
            mCallback = requireNonNull(callback);
            return this;
        }

        /**
         * Builds a new instance of {@link VirtualCameraConfig}
         *
         * @throws NullPointerException if some required parameters are missing.
         */
        @NonNull
        public VirtualCameraConfig build() {
            return new VirtualCameraConfig(
                    mDisplayName, mStreamConfiguration, mCallbackExecutor, mCallback);
        }
    }

    private VirtualCameraConfig(
            @NonNull String displayName,
            @NonNull Set<VirtualCameraStreamConfig> streamConfigurations,
            @NonNull Executor executor,
            @NonNull VirtualCameraCallback callback) {
        mDisplayName = requireNonNull(displayName, "Missing display name");
        mStreamConfigurations =
                Collections.unmodifiableSet(
                        requireNonNull(streamConfigurations, "Missing stream configuration"));
        if (mStreamConfigurations.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one StreamConfiguration is needed to create a virtual camera.");
        }
        mCallback = requireNonNull(callback, "Missing callback");
        mCallbackExecutor = requireNonNull(executor, "Missing callback executor");
    }

    /**
     * @return The display name of this VirtualCamera
     */
    @NonNull
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Returns an unmodifiable set of the stream configurations added to this {@link
     * VirtualCameraConfig}.
     *
     * @see VirtualCameraConfig.Builder#addStreamConfiguration(int, int, int)
     */
    @NonNull
    public Set<VirtualCameraStreamConfig> getStreamConfigs() {
        return mStreamConfigurations;
    }

    /** Returns the callback used to communicate from the server to the client. */
    @NonNull
    public VirtualCameraCallback getCallback() {
        return mCallback;
    }

    /** Returns the executor onto which the callback should be run. */
    @NonNull
    public Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    /**
     * Returns a new instance of {@link VirtualCameraHalConfig} initialized with data from this
     * {@link VirtualCameraConfig}
     */
    @NonNull
    public VirtualCameraHalConfig getHalConfig() {
        VirtualCameraHalConfig halConfig = new VirtualCameraHalConfig();
        halConfig.displayName = mDisplayName;
        halConfig.streamConfigs = mStreamConfigurations.toArray(new VirtualCameraStreamConfig[0]);
        return halConfig;
    }
}
