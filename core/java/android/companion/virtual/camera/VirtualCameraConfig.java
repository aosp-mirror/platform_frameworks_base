/*
 * Copyright 2023 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.flags.Flags;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraMetadata;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    private static final int LENS_FACING_UNKNOWN = -1;

    /**
     * Sensor orientation of {@code 0} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_0 = 0;
    /**
     * Sensor orientation of {@code 90} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_90 = 90;
    /**
     * Sensor orientation of {@code 180} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_180 = 180;
    /**
     * Sensor orientation of {@code 270} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_270 = 270;
    /** @hide */
    @IntDef(prefix = {"SENSOR_ORIENTATION_"}, value = {
            SENSOR_ORIENTATION_0,
            SENSOR_ORIENTATION_90,
            SENSOR_ORIENTATION_180,
            SENSOR_ORIENTATION_270
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorOrientation {}

    private final String mName;
    private final Set<VirtualCameraStreamConfig> mStreamConfigurations;
    private final IVirtualCameraCallback mCallback;
    @SensorOrientation
    private final int mSensorOrientation;
    private final int mLensFacing;

    private VirtualCameraConfig(
            @NonNull String name,
            @NonNull Set<VirtualCameraStreamConfig> streamConfigurations,
            @NonNull Executor executor,
            @NonNull VirtualCameraCallback callback,
            @SensorOrientation int sensorOrientation,
            int lensFacing) {
        mName = requireNonNull(name, "Missing name");
        if (lensFacing == LENS_FACING_UNKNOWN) {
            throw new IllegalArgumentException("Lens facing must be set");
        }
        mLensFacing = lensFacing;
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
        mSensorOrientation = sensorOrientation;
    }

    private VirtualCameraConfig(@NonNull Parcel in) {
        mName = in.readString8();
        mCallback = IVirtualCameraCallback.Stub.asInterface(in.readStrongBinder());
        mStreamConfigurations =
                Set.of(
                        in.readParcelableArray(
                                VirtualCameraStreamConfig.class.getClassLoader(),
                                VirtualCameraStreamConfig.class));
        mSensorOrientation = in.readInt();
        mLensFacing = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mName);
        dest.writeStrongInterface(mCallback);
        dest.writeParcelableArray(
                mStreamConfigurations.toArray(new VirtualCameraStreamConfig[0]), flags);
        dest.writeInt(mSensorOrientation);
        dest.writeInt(mLensFacing);
    }

    /**
     * @return The name of this VirtualCamera
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns an unmodifiable set of the stream configurations added to this {@link
     * VirtualCameraConfig}.
     *
     * @see VirtualCameraConfig.Builder#addStreamConfig(int, int, int, int)
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
     * Returns the sensor orientation of this stream, which represents the clockwise angle (in
     * degrees) through which the output image needs to be rotated to be upright on the device
     * screen in its native orientation. Returns {@link #SENSOR_ORIENTATION_0} if omitted.
     */
    @SensorOrientation
    public int getSensorOrientation() {
        return mSensorOrientation;
    }

    /**
     * Returns the direction that the virtual camera faces relative to the virtual device's screen.
     *
     * @see Builder#setLensFacing(int)
     */
    public int getLensFacing() {
        return mLensFacing;
    }

    /**
     * Builder for {@link VirtualCameraConfig}.
     *
     * <p>To build an instance of {@link VirtualCameraConfig} the following conditions must be met:
     * <li>At least one stream must be added with {@link #addStreamConfig(int, int, int, int)}.
     * <li>A callback must be set with {@link #setVirtualCameraCallback(Executor,
     *     VirtualCameraCallback)}
     * <li>A lens facing must be set with {@link #setLensFacing(int)}
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
    public static final class Builder {

        private final String mName;
        private final ArraySet<VirtualCameraStreamConfig> mStreamConfigurations = new ArraySet<>();
        private Executor mCallbackExecutor;
        private VirtualCameraCallback mCallback;
        private int mSensorOrientation = SENSOR_ORIENTATION_0;
        private int mLensFacing = LENS_FACING_UNKNOWN;

        /**
         * Creates a new instance of {@link Builder}.
         *
         * @param name The name of the {@link VirtualCamera}.
         */
        public Builder(@NonNull String name) {
            mName = requireNonNull(name, "Name cannot be null");
        }

        /**
         * Adds a supported input stream configuration for this {@link VirtualCamera}.
         *
         * <p>At least one {@link VirtualCameraStreamConfig} must be added.
         *
         * @param width The width of the stream.
         * @param height The height of the stream.
         * @param format The input format of the stream. Supported formats are
         *               {@link ImageFormat#YUV_420_888} and {@link PixelFormat#RGBA_8888}.
         * @param maximumFramesPerSecond The maximum frame rate (in frames per second) for the
         *                               stream.
         *
         * @throws IllegalArgumentException if invalid dimensions, format or frame rate are passed.
         */
        @NonNull
        public Builder addStreamConfig(
                @IntRange(from = 1) int width,
                @IntRange(from = 1) int height,
                @ImageFormat.Format int format,
                @IntRange(from = 1) int maximumFramesPerSecond) {
            if (width <= 0) {
                throw new IllegalArgumentException(
                        "Invalid width passed for stream config: " + width
                                + ", must be greater than 0");
            }
            if (height <= 0) {
                throw new IllegalArgumentException(
                        "Invalid height passed for stream config: " + height
                                + ", must be greater than 0");
            }
            if (!isFormatSupported(format)) {
                throw new IllegalArgumentException(
                        "Invalid format passed for stream config: " + format);
            }
            if (maximumFramesPerSecond <= 0
                    || maximumFramesPerSecond > VirtualCameraStreamConfig.MAX_FPS_UPPER_LIMIT) {
                throw new IllegalArgumentException(
                        "Invalid maximumFramesPerSecond, must be greater than 0 and less than "
                                + VirtualCameraStreamConfig.MAX_FPS_UPPER_LIMIT);
            }
            mStreamConfigurations.add(new VirtualCameraStreamConfig(width, height, format,
                    maximumFramesPerSecond));
            return this;
        }

        /**
         * Sets the sensor orientation of the virtual camera. This field is optional and can be
         * omitted (defaults to {@link #SENSOR_ORIENTATION_0}).
         *
         * @param sensorOrientation The sensor orientation of the camera, which represents the
         *                          clockwise angle (in degrees) through which the output image
         *                          needs to be rotated to be upright on the device screen in its
         *                          native orientation.
         */
        @NonNull
        public Builder setSensorOrientation(@SensorOrientation int sensorOrientation) {
            if (sensorOrientation != SENSOR_ORIENTATION_0
                    && sensorOrientation != SENSOR_ORIENTATION_90
                    && sensorOrientation != SENSOR_ORIENTATION_180
                    && sensorOrientation != SENSOR_ORIENTATION_270) {
                throw new IllegalArgumentException(
                        "Invalid sensor orientation: " + sensorOrientation);
            }
            mSensorOrientation = sensorOrientation;
            return this;
        }

        /**
         * Sets the lens facing direction of the virtual camera, can be either
         * {@link CameraMetadata#LENS_FACING_FRONT} or {@link CameraMetadata#LENS_FACING_BACK}.
         *
         * <p>A {@link VirtualDevice} can have at most one {@link VirtualCamera} with
         * {@link CameraMetadata#LENS_FACING_FRONT} and at most one {@link VirtualCamera} with
         * {@link CameraMetadata#LENS_FACING_BACK}.
         *
         * @param lensFacing The direction that the virtual camera faces relative to the device's
         *                   screen.
         */
        @NonNull
        public Builder setLensFacing(int lensFacing) {
            if (lensFacing != CameraMetadata.LENS_FACING_BACK
                    && lensFacing != CameraMetadata.LENS_FACING_FRONT) {
                throw new IllegalArgumentException("Unsupported lens facing: " + lensFacing);
            }
            mLensFacing = lensFacing;
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
         * @throws IllegalArgumentException if any parameter is invalid.
         */
        @NonNull
        public VirtualCameraConfig build() {
            return new VirtualCameraConfig(
                    mName, mStreamConfigurations, mCallbackExecutor, mCallback, mSensorOrientation,
                    mLensFacing);
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
        public void onStreamConfigured(int streamId, Surface surface, int width, int height,
                int format) {
            mExecutor.execute(() -> mCallback.onStreamConfigured(streamId, surface, width, height,
                    format));
        }

        @Override
        public void onProcessCaptureRequest(int streamId, long frameId) {
            mExecutor.execute(() -> mCallback.onProcessCaptureRequest(streamId, frameId));
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

    private static boolean isFormatSupported(@ImageFormat.Format int format) {
        return switch (format) {
            case ImageFormat.YUV_420_888, PixelFormat.RGBA_8888 -> true;
            default -> false;
        };
    }
}
