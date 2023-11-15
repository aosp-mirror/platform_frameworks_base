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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.StringRes;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.view.Surface;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Configuration to create a new {@link VirtualCamera}.
 *
 * <p>Instance of this class are created using the {@link VirtualCameraConfig.Builder}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
public final class VirtualCameraConfig implements Parcelable {

    private final @StringRes int mNameStringRes;
    private final Set<VirtualCameraStreamConfig> mStreamConfigurations;
    private final IVirtualCameraCallback mCallback;

    private VirtualCameraConfig(
            int displayNameStringRes,
            @NonNull Set<VirtualCameraStreamConfig> streamConfigurations,
            @NonNull Executor executor,
            @NonNull VirtualCameraCallback callback) {
        mNameStringRes = displayNameStringRes;
        mStreamConfigurations =
                Set.copyOf(requireNonNull(streamConfigurations, "Missing stream configurations"));
        if (mStreamConfigurations.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one stream configuration is needed to create a virtual camera.");
        }
        mCallback =
                new VirtualCameraCallbackInternal(
                        requireNonNull(callback, "Missing callback"),
                        requireNonNull(executor, "Missing callback executor"));
    }

    private VirtualCameraConfig(@NonNull Parcel in) {
        mNameStringRes = in.readInt();
        mCallback = IVirtualCameraCallback.Stub.asInterface(in.readStrongBinder());
        mStreamConfigurations =
                Set.of(
                        in.readParcelableArray(
                                VirtualCameraStreamConfig.class.getClassLoader(),
                                VirtualCameraStreamConfig.class));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNameStringRes);
        dest.writeStrongInterface(mCallback);
        dest.writeParcelableArray(
                mStreamConfigurations.toArray(new VirtualCameraStreamConfig[0]), flags);
    }

    /**
     * @return The display name of this VirtualCamera
     */
    @StringRes
    public int getDisplayNameStringRes() {
        return mNameStringRes;
    }

    /**
     * Returns an unmodifiable set of the stream configurations added to this {@link
     * VirtualCameraConfig}.
     *
     * @see VirtualCameraConfig.Builder#addStreamConfig(int, int, int)
     */
    @NonNull
    public Set<VirtualCameraStreamConfig> getStreamConfigs() {
        return mStreamConfigurations;
    }

    /**
     * Returns the callback used to communicate from the server to the client.
     *
     * @hide
     */
    @NonNull
    public IVirtualCameraCallback getCallback() {
        return mCallback;
    }

    /**
     * Builder for {@link VirtualCameraConfig}.
     *
     * <p>To build an instance of {@link VirtualCameraConfig} the following conditions must be met:
     * <li>At least one stream must be added with {@link #addStreamConfig(int, int, int)}.
     * <li>A callback must be set with {@link #setVirtualCameraCallback(Executor,
     *     VirtualCameraCallback)}
     * <li>A user readable name can be set with {@link #setDisplayNameStringRes(int)}
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
    public static final class Builder {

        private @StringRes int mDisplayNameStringRes = Resources.ID_NULL;
        private final ArraySet<VirtualCameraStreamConfig> mStreamConfigurations = new ArraySet<>();
        private Executor mCallbackExecutor;
        private VirtualCameraCallback mCallback;

        /**
         * Set the visible name of this camera for the user.
         *
         * <p>Sets the resource to a string representing a user readable name for this virtual
         * camera.
         *
         * @throws IllegalArgumentException if an invalid resource id is passed.
         */
        @NonNull
        public Builder setDisplayNameStringRes(@StringRes int displayNameStringRes) {
            if (displayNameStringRes <= 0) {
                throw new IllegalArgumentException("Invalid resource passed for display name");
            }
            mDisplayNameStringRes = displayNameStringRes;
            return this;
        }

        /**
         * Add an available stream configuration fot this {@link VirtualCamera}.
         *
         * <p>At least one {@link VirtualCameraStreamConfig} must be added.
         *
         * @param width The width of the stream.
         * @param height The height of the stream.
         * @param format The {@link ImageFormat} of the stream.
         *
         * @throws IllegalArgumentException if invalid format or dimensions are passed.
         */
        @NonNull
        public Builder addStreamConfig(int width, int height, @ImageFormat.Format int format) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid dimensions passed for stream config");
            }
            if (!ImageFormat.isPublicFormat(format)) {
                throw new IllegalArgumentException("Invalid format passed for stream config");
            }
            mStreamConfigurations.add(new VirtualCameraStreamConfig(width, height, format));
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
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder") // The configuration is immutable
        public Builder setVirtualCameraCallback(
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
                    mDisplayNameStringRes, mStreamConfigurations, mCallbackExecutor, mCallback);
        }
    }

    private static class VirtualCameraCallbackInternal extends IVirtualCameraCallback.Stub {

        private final VirtualCameraCallback mCallback;
        private final Executor mExecutor;

        private VirtualCameraCallbackInternal(VirtualCameraCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onStreamConfigured(
                int streamId, Surface surface, VirtualCameraStreamConfig streamConfig) {
            mExecutor.execute(() -> mCallback.onStreamConfigured(streamId, surface, streamConfig));
        }

        @Override
        public void onProcessCaptureRequest(
                int streamId, long frameId, VirtualCameraMetadata metadata) {
            mExecutor.execute(() -> mCallback.onProcessCaptureRequest(streamId, frameId, metadata));
        }

        @Override
        public void onStreamClosed(int streamId) {
            mExecutor.execute(() -> mCallback.onStreamClosed(streamId));
        }
    }

    @NonNull
    public static final Parcelable.Creator<VirtualCameraConfig> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public VirtualCameraConfig createFromParcel(Parcel in) {
                    return new VirtualCameraConfig(in);
                }

                @Override
                public VirtualCameraConfig[] newArray(int size) {
                    return new VirtualCameraConfig[size];
                }
            };
}
