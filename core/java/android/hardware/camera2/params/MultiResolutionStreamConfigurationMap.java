/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.graphics.PixelFormat;
import android.hardware.camera2.params.MultiResolutionStreamInfo;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.HashCodeHelpers;

import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static com.android.internal.util.Preconditions.*;

/**
 * Immutable class to store the information of the multi-resolution streams supported by
 * the camera device.
 *
 * <p>For a {@link
 * android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
 * logical multi-camera} or an ultra high resolution sensor camera, the maximum resolution of images
 * produced by the camera device may be variable. For example, for a logical multi-camera, depending
 * on factors such as current zoom ratio, the camera device may be backed by different physical
 * cameras. If the physical cameras are of different resolutions, the application may intend to
 * consume the variable full resolution images from the physical cameras. For an ultra high
 * resolution sensor camera, the same use case exists where depending on lighting conditions, the
 * camera device may deem it better to run in default mode and maximum resolution mode.
 * </p>
 *
 * <p>For the use cases described above, multi-resolution output streams can be used by
 * {@link android.hardware.camera2.MultiResolutionImageReader} to allow the
 * camera device to output variable size maximum-resolution images.</p>
 *
 * <p>Similarly, multi-resolution input streams can be used for reprocessing of variable size
 * images. In order to reprocess input images of different sizes, the {@link InputConfiguration}
 * used for creating reprocessable session can be initialized using the group of input stream
 * configurations returned by {@link #getInputInfo}.</p>
 */
public final class MultiResolutionStreamConfigurationMap {
    /**
     * Create a new {@link MultiResolutionStreamConfigurationMap}.
     *
     * @param configurations a non-{@code null} array of multi-resolution stream
     *        configurations supported by this camera device
     * @hide
     */
    public MultiResolutionStreamConfigurationMap(
            @NonNull Map<String, StreamConfiguration[]> configurations) {
        checkNotNull(configurations, "multi-resolution configurations must not be null");
        if (configurations.size() == 0) {
            throw new IllegalArgumentException("multi-resolution configurations must not be empty");
        }

        mConfigurations = configurations;

        // For each multi-resolution stream configuration, track how many formats and sizes there
        // are available to configure
        for (Map.Entry<String, StreamConfiguration[]> entry :
                mConfigurations.entrySet()) {
            String cameraId = entry.getKey();
            StreamConfiguration[] configs = entry.getValue();

            for (int i = 0; i < configs.length; i++) {
                StreamConfiguration config = configs[i];
                int format = config.getFormat();

                MultiResolutionStreamInfo multiResolutionStreamInfo = new MultiResolutionStreamInfo(
                        config.getWidth(), config.getHeight(), cameraId);
                Map<Integer, List<MultiResolutionStreamInfo>> destMap;
                if (config.isInput()) {
                    destMap = mMultiResolutionInputConfigs;
                } else {
                    destMap = mMultiResolutionOutputConfigs;
                }

                if (!destMap.containsKey(format)) {
                    List<MultiResolutionStreamInfo> multiResolutionStreamInfoList =
                            new ArrayList<MultiResolutionStreamInfo>();
                    destMap.put(format, multiResolutionStreamInfoList);
                }
                destMap.get(format).add(multiResolutionStreamInfo);
            }
        }
    }

    /**
     * Size comparator that compares the number of pixels two MultiResolutionStreamInfo size covers.
     *
     * <p>If two the areas of two sizes are same, compare the widths.</p>
     *
     * @hide
     */
    public static class SizeComparator implements Comparator<MultiResolutionStreamInfo> {
        @Override
        public int compare(@NonNull MultiResolutionStreamInfo lhs,
                @NonNull MultiResolutionStreamInfo rhs) {
            return StreamConfigurationMap.compareSizes(
                    lhs.getWidth(), lhs.getHeight(), rhs.getWidth(), rhs.getHeight());
        }
    }

    /**
     * Get the output formats in this multi-resolution stream configuration.
     *
     * <p>A logical multi-camera or an ultra high resolution sensor camera may support
     * {@link android.hardware.camera2.MultiResolutionImageReader} to dynamically output maximum
     * resolutions of different sizes (when switching between physical cameras, or between different
     * modes of an ultra high resolution sensor camera). This function returns the formats
     * supported for such case.</p>
     *
     * <p>All image formats returned by this function will be defined in either {@link ImageFormat}
     * or in {@link PixelFormat} (and there is no possibility of collision).</p>
     *
     * @return an array of integer format, or empty array if multi-resolution output is not
     *         supported
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see android.hardware.camera2.MultiResolutionImageReader
     */
    public @NonNull @Format int[] getOutputFormats() {
        return getPublicImageFormats(/*output*/true);
    }

    /**
     * Get the input formats in this multi-resolution stream configuration.
     *
     * <p>A logical multi-camera or ultra high resolution sensor camera may support reprocessing
     * images of different resolutions when switching between physical cameras, or between
     * different modes of the ultra high resolution sensor camera. This function returns the
     * formats supported for such case.</p>
     *
     * <p>The supported output format for an input format can be queried by calling the camera
     * device's {@link StreamConfigurationMap#getValidOutputFormatsForInput}.</p>
     *
     * <p>All image formats returned by this function will be defined in either {@link ImageFormat}
     * or in {@link PixelFormat} (and there is no possibility of collision).</p>
     *
     * @return an array of integer format, or empty array if no multi-resolution reprocessing is
     *         supported
     *
     * @see ImageFormat
     * @see PixelFormat
     */
    public @NonNull @Format int[] getInputFormats() {
        return getPublicImageFormats(/*output*/false);
    }

    // Get the list of publicly visible multi-resolution input/output stream formats
    private int[] getPublicImageFormats(boolean output) {
        Map<Integer, List<MultiResolutionStreamInfo>> multiResolutionConfigs =
                output ? mMultiResolutionOutputConfigs : mMultiResolutionInputConfigs;
        int formatCount = multiResolutionConfigs.size();

        int[] formats = new int[formatCount];
        int i = 0;
        for (Integer format : multiResolutionConfigs.keySet()) {
            formats[i++] = StreamConfigurationMap.imageFormatToPublic(format);
        }

        return formats;
    }

    /**
     * Get a group of {@code MultiResolutionStreamInfo} with the requested output image
     * {@code format}
     *
     * <p>The {@code format} should be a supported format (one of the formats returned by
     * {@link #getOutputFormats}).</p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @return
     *          a group of supported {@link MultiResolutionStreamInfo}. If the {@code format} is not
     *          a supported multi-resolution output, an empty group is returned.
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see #getOutputFormats
     */
    public @NonNull Collection<MultiResolutionStreamInfo> getOutputInfo(@Format int format) {
        return getInfo(format, /*false*/ true);
    }

    /**
     * Get a group of {@code MultiResolutionStreamInfo} with the requested input image {@code format}
     *
     * <p>The {@code format} should be a supported format (one of the formats returned by
     * {@link #getInputFormats}).</p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @return
     *          a group of supported {@link MultiResolutionStreamInfo}. If the {@code format} is not
     *          a supported multi-resolution input, an empty group is returned.
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see #getInputFormats
     */
    public @NonNull Collection<MultiResolutionStreamInfo> getInputInfo(@Format int format) {
        return getInfo(format, /*false*/ false);
    }

    // Get multi-resolution stream info for a particular format
    private @NonNull Collection<MultiResolutionStreamInfo> getInfo(int format, boolean output) {
        int internalFormat = StreamConfigurationMap.imageFormatToInternal(format);
        Map<Integer, List<MultiResolutionStreamInfo>> multiResolutionConfigs =
                output ? mMultiResolutionOutputConfigs : mMultiResolutionInputConfigs;
        if (multiResolutionConfigs.containsKey(internalFormat)) {
            return Collections.unmodifiableCollection(multiResolutionConfigs.get(internalFormat));
        } else {
            return Collections.emptyList();
        }
    }

    private void appendConfigurationsString(StringBuilder sb, boolean output) {
        sb.append(output ? "Outputs(" : "Inputs(");
        int[] formats = getPublicImageFormats(output);
        if (formats != null) {
            for (int format : formats) {
                Collection<MultiResolutionStreamInfo> streamInfoList =
                        getInfo(format, output);
                sb.append("[" + StreamConfigurationMap.formatToString(format) + ":");
                for (MultiResolutionStreamInfo streamInfo : streamInfoList) {
                    sb.append(String.format("[w:%d, h:%d, id:%s], ",
                            streamInfo.getWidth(), streamInfo.getHeight(),
                            streamInfo.getPhysicalCameraId()));
                }
                // Remove the pending ", "
                if (sb.charAt(sb.length() - 1) == ' ') {
                    sb.delete(sb.length() - 2, sb.length());
                }
                sb.append("]");
            }
        }
        sb.append(")");
    }

    /**
     * Check if this {@link MultiResolutionStreamConfigurationMap} is equal to another
     * {@link MultiResolutionStreamConfigurationMap}.
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof MultiResolutionStreamConfigurationMap) {
            final MultiResolutionStreamConfigurationMap other =
                    (MultiResolutionStreamConfigurationMap) obj;
            if (!mConfigurations.keySet().equals(other.mConfigurations.keySet())) {
                return false;
            }

            for (String id : mConfigurations.keySet()) {
                if (!Arrays.equals(mConfigurations.get(id), other.mConfigurations.get(id))) {
                    return false;
                }
            }

            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCodeGeneric(
                mConfigurations, mMultiResolutionOutputConfigs, mMultiResolutionInputConfigs);
    }

    /**
     * Return this {@link MultiResolutionStreamConfigurationMap} as a string representation.
     *
     * <p>{@code "MultiResolutionStreamConfigurationMap(Outputs([format1: [w:%d, h:%d, id:%s], ...
     * ... [w:%d, h:%d, id:%s]), [format2: [w:%d, h:%d, id:%s], ... [w:%d, h:%d, id:%s]], ...),
     * Inputs([format1: [w:%d, h:%d, id:%s], ... [w:%d, h:%d, id:%s], ...).</p>
     *
     * @return string representation of {@link MultiResolutionStreamConfigurationMap}
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MultiResolutionStreamConfigurationMap(");
        appendConfigurationsString(sb, /*output*/ true);
        sb.append(",");
        appendConfigurationsString(sb, /*output*/ false);
        sb.append(")");

        return sb.toString();
    }


    private final Map<String, StreamConfiguration[]> mConfigurations;

    /** Format -> list of MultiResolutionStreamInfo used to create MultiResolutionImageReader */
    private final Map<Integer, List<MultiResolutionStreamInfo>> mMultiResolutionOutputConfigs
            = new HashMap<Integer, List<MultiResolutionStreamInfo>>();
    /** Format -> list of MultiResolutionStreamInfo used for multi-resolution reprocessing */
    private final Map<Integer, List<MultiResolutionStreamInfo>> mMultiResolutionInputConfigs
            = new HashMap<Integer, List<MultiResolutionStreamInfo>>();
}
