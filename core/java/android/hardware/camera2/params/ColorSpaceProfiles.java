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

package android.hardware.camera2.params;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraMetadata;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Map;
import java.util.Set;

/**
 * Immutable class with information about supported color space profiles.
 *
 * <p>An instance of this class can be queried by retrieving the value of
 * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES}.
 * </p>
 *
 * <p>All camera devices supporting the
 * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES}
 * capability must advertise the supported color space profiles in
 * {@link #getSupportedColorSpaces}</p>
 *
 * @see SessionConfiguration#setColorSpace
 */
public final class ColorSpaceProfiles {
    /*
     * @hide
     */
    public static final int UNSPECIFIED =
            CameraMetadata.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED;

    private final Map<ColorSpace.Named, Map<Integer, Set<Long>>> mProfileMap = new ArrayMap<>();

    /**
     * Create a new immutable ColorSpaceProfiles instance.
     *
     * <p>This constructor takes over the array; do not write to the array afterwards.</p>
     *
     * <p>Do note that the constructor is available for testing purposes only!
     * Camera clients must always retrieve the value of
     * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES}.
     * for a given camera id in order to retrieve the device capabilities.</p>
     *
     * @param elements
     *          An array of elements describing the map. It contains three elements per entry which
     *          describe the supported color space profile value in the first element, a compatible
     *          image format in the second, and in the third element a bitmap of compatible dynamic
     *          range profiles (see {@link DynamicRangeProfiles#STANDARD} and others for the
     *          individual bitmap components).
     *
     * @throws IllegalArgumentException
     *            if the {@code elements} array length is invalid, not divisible by 3 or contains
     *            invalid element values
     * @throws NullPointerException
     *            if {@code elements} is {@code null}
     *
     */
    public ColorSpaceProfiles(@NonNull final long[] elements) {
        if ((elements.length % 3) != 0) {
            throw new IllegalArgumentException("Color space profile map length "
                    + elements.length + " is not divisible by 3!");
        }

        for (int i = 0; i < elements.length; i += 3) {
            int colorSpace = (int) elements[i];
            checkProfileValue(colorSpace);
            ColorSpace.Named namedColorSpace = ColorSpace.Named.values()[colorSpace];
            int imageFormat = (int) elements[i + 1];
            long dynamicRangeProfileBitmap = elements[i + 2];

            if (!mProfileMap.containsKey(namedColorSpace)) {
                ArrayMap<Integer, Set<Long>> imageFormatMap = new ArrayMap<>();
                mProfileMap.put(namedColorSpace, imageFormatMap);
            }

            if (!mProfileMap.get(namedColorSpace).containsKey(imageFormat)) {
                ArraySet<Long> dynamicRangeProfiles = new ArraySet<>();
                mProfileMap.get(namedColorSpace).put(imageFormat, dynamicRangeProfiles);
            }

            if (dynamicRangeProfileBitmap != 0) {
                for (long dynamicRangeProfile = DynamicRangeProfiles.STANDARD;
                        dynamicRangeProfile < DynamicRangeProfiles.PUBLIC_MAX;
                        dynamicRangeProfile <<= 1) {
                    if ((dynamicRangeProfileBitmap & dynamicRangeProfile) != 0) {
                        mProfileMap.get(namedColorSpace).get(imageFormat).add(dynamicRangeProfile);
                    }
                }
            }
        }
    }

    /**
     * @hide
     */
    public static void checkProfileValue(int colorSpace) {
        boolean found = false;
        for (ColorSpace.Named value : ColorSpace.Named.values()) {
            if (colorSpace == value.ordinal()) {
                found = true;
                break;
            }
        }

        if (!found) {
            throw new IllegalArgumentException("Unknown ColorSpace " + colorSpace);
        }
    }

    /**
     * @hide
     */
    @TestApi
    public @NonNull Map<ColorSpace.Named, Map<Integer, Set<Long>>> getProfileMap() {
        return mProfileMap;
    }

    /**
     * Return a list of color spaces that are compatible with an ImageFormat. If ImageFormat.UNKNOWN
     * is provided, this function will return a set of all unique color spaces supported by the
     * device, regardless of image format.
     *
     * Color spaces which are compatible with ImageFormat.PRIVATE are able to be used with
     * SurfaceView, SurfaceTexture, MediaCodec and MediaRecorder.
     *
     * @return set of color spaces
     * @see SessionConfiguration#setColorSpace
     * @see ColorSpace.Named
     */
    public @NonNull Set<ColorSpace.Named> getSupportedColorSpaces(
            @ImageFormat.Format int imageFormat) {
        ArraySet<ColorSpace.Named> supportedColorSpaceProfiles = new ArraySet<>();
        for (ColorSpace.Named colorSpace : mProfileMap.keySet()) {
            if (imageFormat == ImageFormat.UNKNOWN) {
                supportedColorSpaceProfiles.add(colorSpace);
            } else {
                Map<Integer, Set<Long>> imageFormatMap = mProfileMap.get(colorSpace);
                if (imageFormatMap.containsKey(imageFormat)) {
                    supportedColorSpaceProfiles.add(colorSpace);
                }
            }
        }
        return supportedColorSpaceProfiles;
    }

    /**
     * Return a list of image formats that are compatible with a color space.
     *
     * Color spaces which are compatible with ImageFormat.PRIVATE are able to be used with
     * SurfaceView, SurfaceTexture, MediaCodec and MediaRecorder.
     *
     * @return set of image formats
     * @see SessionConfiguration#setColorSpace
     * @see ColorSpace.Named
     */
    public @NonNull Set<Integer> getSupportedImageFormatsForColorSpace(
            @NonNull ColorSpace.Named colorSpace) {
        Map<Integer, Set<Long>> imageFormatMap = mProfileMap.get(colorSpace);
        if (imageFormatMap == null) {
            return new ArraySet<Integer>();
        }

        return imageFormatMap.keySet();
    }

    /**
     * Return a list of dynamic range profiles that are compatible with a color space and
     * ImageFormat. If ImageFormat.UNKNOWN is provided, this function will return a set of
     * all unique dynamic range profiles supported by the device given a color space,
     * regardless of image format.
     *
     * @return set of dynamic range profiles.
     * @see OutputConfiguration#setDynamicRangeProfile
     * @see SessionConfiguration#setColorSpace
     * @see ColorSpace.Named
     * @see DynamicRangeProfiles.Profile
     */
    public @NonNull Set<Long> getSupportedDynamicRangeProfiles(@NonNull ColorSpace.Named colorSpace,
            @ImageFormat.Format int imageFormat) {
        Map<Integer, Set<Long>> imageFormatMap = mProfileMap.get(colorSpace);
        if (imageFormatMap == null) {
            return new ArraySet<Long>();
        }

        Set<Long> dynamicRangeProfiles = null;
        if (imageFormat == ImageFormat.UNKNOWN) {
            dynamicRangeProfiles = new ArraySet<>();
            for (int supportedImageFormat : imageFormatMap.keySet()) {
                Set<Long> supportedDynamicRangeProfiles = imageFormatMap.get(
                        supportedImageFormat);
                for (Long supportedDynamicRangeProfile : supportedDynamicRangeProfiles) {
                    dynamicRangeProfiles.add(supportedDynamicRangeProfile);
                }
            }
        } else {
            dynamicRangeProfiles = imageFormatMap.get(imageFormat);
            if (dynamicRangeProfiles == null) {
                return new ArraySet<>();
            }
        }

        return dynamicRangeProfiles;
    }

    /**
     * Return a list of color spaces that are compatible with an ImageFormat and a dynamic range
     * profile. If ImageFormat.UNKNOWN is provided, this function will return a set of all unique
     * color spaces compatible with the given dynamic range profile, regardless of image format.
     *
     * @return set of color spaces
     * @see SessionConfiguration#setColorSpace
     * @see OutputConfiguration#setDynamicRangeProfile
     * @see ColorSpace.Named
     * @see DynamicRangeProfiles.Profile
     */
    public @NonNull Set<ColorSpace.Named> getSupportedColorSpacesForDynamicRange(
            @ImageFormat.Format int imageFormat,
            @DynamicRangeProfiles.Profile long dynamicRangeProfile) {
        ArraySet<ColorSpace.Named> supportedColorSpaceProfiles = new ArraySet<>();
        for (ColorSpace.Named colorSpace : mProfileMap.keySet()) {
            Map<Integer, Set<Long>> imageFormatMap = mProfileMap.get(colorSpace);
            if (imageFormat == ImageFormat.UNKNOWN) {
                for (int supportedImageFormat : imageFormatMap.keySet()) {
                    Set<Long> dynamicRangeProfiles = imageFormatMap.get(supportedImageFormat);
                    if (dynamicRangeProfiles.contains(dynamicRangeProfile)) {
                        supportedColorSpaceProfiles.add(colorSpace);
                    }
                }
            } else if (imageFormatMap.containsKey(imageFormat)) {
                Set<Long> dynamicRangeProfiles = imageFormatMap.get(imageFormat);
                if (dynamicRangeProfiles.contains(dynamicRangeProfile)) {
                    supportedColorSpaceProfiles.add(colorSpace);
                }
            }
        }
        return supportedColorSpaceProfiles;
    }
}
