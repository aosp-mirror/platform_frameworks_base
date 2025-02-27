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

package android.hardware.camera2.params;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat.Format;
import android.hardware.DataSpace.NamedDataSpace;
import android.hardware.HardwareBuffer.Usage;
import android.hardware.camera2.params.OutputConfiguration.MirrorMode;
import android.hardware.camera2.params.OutputConfiguration.StreamUseCase;
import android.hardware.camera2.params.OutputConfiguration.TimestampBase;
import android.util.Log;
import android.util.Size;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable class to store the shared session configuration
 * {@link CameraCharacteristics#SHARED_SESSION_CONFIGURATION} to set up
 * {@link android.view.Surface Surfaces} for creating a
 * {@link android.hardware.camera2.CameraSharedCaptureSession capture session} using
 * {@link android.hardware.camera2.CameraDevice#createCaptureSession(SessionConfiguration)} and
 * {@link android.hardware.camera2.params.SessionConfiguration#SESSION_SHARED
 * shared capture session} when camera has been opened in shared mode using
 * {@link #openSharedCamera(String, Executor, StateCallback)}.
 *
 * <p>This is the authoritative list for all output configurations that are supported by a camera
 * device when opened in shared mode.</p>
 *
 * <p>An instance of this object is available from {@link CameraCharacteristics} using
 * the {@link CameraCharacteristics#SHARED_SESSION_CONFIGURATION} key and the
 * {@link CameraCharacteristics#get} method.</p>
 *
 * <pre><code>{@code
 * CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
 * StreamConfigurationMap configs = characteristics.get(
 *         CameraCharacteristics.SHARED_SESSION_CONFIGURATION);
 * }</code></pre>
 *
 * @see CameraCharacteristics#SHARED_SESSION_CONFIGURATION
 * @see CameraDevice#createCaptureSession(SessionConfiguration)
 * @see SessionConfiguration#SESSION_SHARED
 * @see CameraManager#openSharedCamera(String, Executor, StateCallback)
 *
 *  @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
public final class SharedSessionConfiguration {
    private static final String TAG = "SharedSessionConfiguration";
    // Metadata android.info.availableSharedOutputConfigurations has list of shared output
    // configurations. Each output configuration has minimum of 11 entries of size long
    // followed by the physical camera id if present.
    // See android.info.availableSharedOutputConfigurations for details.
    private static final int SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES = 11;
    /**
     * Immutable class to store shared output stream information.
     */
    public static final class SharedOutputConfiguration {
        private final int mSurfaceType;
        private final Size mSize;
        private final int mFormat;
        private final int mDataspace;
        private final long mStreamUseCase;
        private String mPhysicalCameraId;
        private final long mUsage;
        private int mTimestampBase;
        private int mMirrorMode;
        private boolean mReadoutTimestampEnabled;

        /**
         * Create a new {@link SharedOutputConfiguration}.
         *
         * @param surfaceType Surface Type for this output configuration.
         * @param sz Size for this output configuration.
         * @param format {@link android.graphics.ImageFormat#Format} associated with this
         *         {@link OutputConfiguration}.
         * @param mirrorMode  {@link OutputConfiguration#MirrorMode} for this output configuration.
         * @param readoutTimeStampEnabled  Flag indicating whether readout timestamp is enabled
         *         for this output configuration.
         * @param timestampBase {@link OutputConfiguration#TimestampBase} for this output
         *         configuration.
         * @param dataspace {@link Dataspace#NamedDataSpace} for this output configuration.
         * @param usage   {@link HardwareBuffer#Usage} for this output configuration.
         * @param streamUseCase {@link OutputConfiguration#StreamUseCase} for this output
         *         configuration.
         * @param physicalCamId Physical Camera Id for this output configuration.
         *
         * @hide
         */
        public SharedOutputConfiguration(int surfaceType, @NonNull Size sz, @Format int format,
                @MirrorMode int mirrorMode, boolean readoutTimeStampEnabled,
                @TimestampBase int timestampBase, @NamedDataSpace int dataspace, @Usage long usage,
                @StreamUseCase long streamUseCase, @Nullable String physicalCamId) {
            mSurfaceType = surfaceType;
            mSize = sz;
            mFormat = format;
            mMirrorMode = mirrorMode;
            mReadoutTimestampEnabled = readoutTimeStampEnabled;
            mTimestampBase = timestampBase;
            mDataspace = dataspace;
            mUsage = usage;
            mStreamUseCase = streamUseCase;
            mPhysicalCameraId = physicalCamId;
        }

        /**
         * Returns the surface type configured for the shared output configuration.
         * @return  SURFACE_TYPE_UNKNOWN = -1
         *          SURFACE_TYPE_SURFACE_VIEW = 0
         *          SURFACE_TYPE_SURFACE_TEXTURE = 1
         *          SURFACE_TYPE_MEDIA_RECORDER = 2
         *          SURFACE_TYPE_MEDIA_CODEC = 3
         *          SURFACE_TYPE_IMAGE_READER = 4
         */
        public int getSurfaceType() {
            return mSurfaceType;
        }

        /**
         * Returns the format of the shared output configuration.
         * @return format The format of the configured output. This must be one of the
         *     {@link android.graphics.ImageFormat} or {@link android.graphics.PixelFormat}
         *     constants. Note that not all formats are supported by the camera device.
         */
        public @Format int getFormat() {
            return mFormat;
        }

        /**
         * Returns the configured size for the shared output configuration.
         * @return surfaceSize Size for the shared output configuration
         *
         */
        public @NonNull Size getSize() {
            return mSize;
        }

        /**
         * Return datatspace configured for the shared output configuration.
         *
         * @return {@link Dataspace#NamedDataSpace} configured for shared session
         */
        public @NamedDataSpace int getDataspace() {
            return mDataspace;
        }

        /**
         * Get the  mirroring mode configured for the shared output configuration.
         *
         * @return {@link OutputConfiguration#MirrorMode} configured for the shared session
         */
        public @MirrorMode int getMirrorMode() {
            return mMirrorMode;
        }

        /**
         * Get the stream use case configured for the shared output configuration.
         *
         * @return {@link OutputConfiguration#StreamUseCase} configured for the shared session
         */
        public @StreamUseCase long getStreamUseCase() {
            return mStreamUseCase;
        }

        /**
         * Get the timestamp base configured for the shared output configuration.
         *
         * @return {@link OutputConfiguration#TimestampBase} configured for the shared session
         */
        public @TimestampBase int getTimestampBase() {
            return mTimestampBase;
        }

        /** Whether readout timestamp is used for this shared output configuration.
         *
         */
        public boolean isReadoutTimestampEnabled() {
            return mReadoutTimestampEnabled;
        }

        /** Returns the usage if set for this shared output configuration.
         *
         * @return {@link HardwareBuffer#Usage} flags if set for shared output configuration with
         *         the ImageReader output surface.
         */
        public @Usage long getUsage() {
            return mUsage;
        }

        public @Nullable String getPhysicalCameraId() {
            return mPhysicalCameraId;
        }
    }

    /**
     * Create a new {@link SharedSessionConfiguration}.
     *
     * <p>The array parameters ownership is passed to this object after creation; do not
     * write to them after this constructor is invoked.</p>
     *
     * @param sharedColorSpace the colorspace to be used for the shared output configurations.
     * @param sharedOutputConfigurations a non-{@code null} array of metadata
     *                                  android.info.availableSharedOutputConfigurations
     *
     * @hide
     */
    public SharedSessionConfiguration(int sharedColorSpace,
            @NonNull long[] sharedOutputConfigurations) {
        mColorSpace = sharedColorSpace;
        byte physicalCameraIdLen;
        int surfaceType, width, height, format, mirrorMode, timestampBase, dataspace;
        long usage, streamUseCase;
        boolean isReadOutTimestampEnabled;
        // Parse metadata android.info.availableSharedOutputConfigurations which contains
        // list of shared output configurations.
        int numOfEntries = sharedOutputConfigurations.length;
        int i = 0;
        while (numOfEntries >= SharedSessionConfiguration.SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES) {
            surfaceType = (int) sharedOutputConfigurations[i];
            width = (int) sharedOutputConfigurations[i + 1];
            height = (int) sharedOutputConfigurations[i + 2];
            format = (int) sharedOutputConfigurations[i + 3];
            mirrorMode = (int) sharedOutputConfigurations[i + 4];
            isReadOutTimestampEnabled = (sharedOutputConfigurations[i + 5] != 0);
            timestampBase = (int) sharedOutputConfigurations[i + 6];
            dataspace = (int) sharedOutputConfigurations[i + 7];
            usage = sharedOutputConfigurations[i + 8];
            streamUseCase = sharedOutputConfigurations[i + 9];
            physicalCameraIdLen = (byte) sharedOutputConfigurations[i + 10];
            numOfEntries -= SharedSessionConfiguration.SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES;
            i += SharedSessionConfiguration.SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES;
            if (numOfEntries < physicalCameraIdLen) {
                Log.e(TAG, "Number of remaining data in shared configuration is less than"
                        + " physical camera id length . Malformed metadata"
                        + " android.info.availableSharedOutputConfigurations.");
                break;
            }
            StringBuilder physicalCameraId =  new StringBuilder();
            long asciiValue;
            for (int j = 0; j < physicalCameraIdLen; j++) {
                asciiValue = sharedOutputConfigurations[i + j];
                if (asciiValue == 0) { // Check for null terminator
                    break;
                }
                physicalCameraId.append((char) asciiValue);
            }
            SharedOutputConfiguration outputInfo;
            outputInfo = new SharedOutputConfiguration(surfaceType, new Size(width, height),
                    format,  mirrorMode, isReadOutTimestampEnabled, timestampBase,
                    dataspace, usage, streamUseCase, physicalCameraId.toString());
            mOutputStreamConfigurations.add(outputInfo);
            i += physicalCameraIdLen;
            numOfEntries -= physicalCameraIdLen;
        }
        if (numOfEntries != 0) {
            Log.e(TAG, "Unexpected entries left in shared output configuration."
                    + " Malformed metadata android.info.availableSharedOutputConfigurations.");
        }
    }

    /**
     * Return the shared session color space which is configured.
     *
     * @return the shared session color space
     */
    @SuppressLint("MethodNameUnits")
    public @Nullable ColorSpace getColorSpace() {
        if (mColorSpace != ColorSpaceProfiles.UNSPECIFIED) {
            return ColorSpace.get(ColorSpace.Named.values()[mColorSpace]);
        } else {
            return null;
        }
    }
    /**
     * Get information about each shared output configuarion in the shared session.
     *
     * @return Non-modifiable list of output configuration.
     *
     */
    public @NonNull List<SharedOutputConfiguration> getOutputStreamsInformation() {
        return Collections.unmodifiableList(mOutputStreamConfigurations);
    }

    private int mColorSpace;
    private final ArrayList<SharedOutputConfiguration> mOutputStreamConfigurations =
            new ArrayList<SharedOutputConfiguration>();
}

