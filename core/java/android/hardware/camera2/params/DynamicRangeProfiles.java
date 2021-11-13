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

import android.annotation.IntDef;
import android.annotation.NonNull;

import android.hardware.camera2.CameraMetadata;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable class with information about supported 10-bit dynamic range profiles.
 *
 * <p>An instance of this class can be queried by retrieving the value of
 * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES}.
 * </p>
 *
 * <p>All camera devices supporting the
 * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT}
 * capability must advertise the supported 10-bit dynamic range profiles in
 * {@link #getSupportedProfiles}</p>
 *
 * <p>Some devices may not be able to support 8-bit and/or 10-bit output with different dynamic
 * range profiles within the same capture request. Such device specific constraints can be queried
 * by calling {@link #getProfileCaptureRequestConstraints(int)}. Do note that unsupported
 * combinations will result in {@link IllegalArgumentException} when trying to submit a capture
 * request. Capture requests that only reference outputs configured using the same dynamic range
 * profile value will never fail due to such constraints.</p>
 *
 * @see OutputConfiguration#setDynamicRangeProfile(int)
 */
public final class DynamicRangeProfiles {
    /**
     * This the default 8-bit standard profile that will be used in case where camera clients do not
     * explicitly configure a supported dynamic range profile by calling
     * {@link OutputConfiguration#setDynamicRangeProfile(int)}.
     */
    public static final int STANDARD =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_STANDARD;

    /**
     * 10-bit pixel samples encoded using the Hybrid log-gamma transfer function
     *
     * <p>All 10-bit output capable devices are required to support this profile.</p>
     */
    public static final int HLG10  =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_HLG10;

    /**
     * 10-bit pixel samples encoded using the SMPTE ST 2084 transfer function.
     *
     * <p>This profile utilizes internal static metadata to increase the quality
     * of the capture.</p>
     */
    public static final int HDR10  =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_HDR10;

    /**
     * 10-bit pixel samples encoded using the SMPTE ST 2084 transfer function.
     *
     * <p>In contrast to HDR10, this profile uses internal per-frame metadata
     * to further enhance the quality of the capture.</p>
     */
    public static final int HDR10_PLUS =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_HDR10_PLUS;

    /**
     * <p>This is a camera mode for Dolby Vision capture optimized for a more scene
     * accurate capture. This would typically differ from what a specific device
     * might want to tune for a consumer optimized Dolby Vision general capture.</p>
     */
    public static final int DOLBY_VISION_10B_HDR_REF =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_REF;

    /**
     * <p>This is the power optimized mode for 10-bit Dolby Vision HDR Reference Mode.</p>
     */
    public static final int DOLBY_VISION_10B_HDR_REF_PO =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_REF_PO;

    /**
     * <p>This is the camera mode for the default Dolby Vision capture mode for the
     * specific device. This would be tuned by each specific device for consumer
     * pleasing results that resonate with their particular audience. We expect
     * that each specific device would have a different look for their default
     * Dolby Vision capture.</p>
     */
    public static final int DOLBY_VISION_10B_HDR_OEM =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_OEM;

    /**
     * <p>This is the power optimized mode for 10-bit Dolby Vision HDR device specific capture
     * Mode.</p>
     */
    public static final int DOLBY_VISION_10B_HDR_OEM_PO =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_OEM_PO;

    /**
     * <p>This is the 8-bit version of the Dolby Vision reference capture mode optimized
     * for scene accuracy.</p>
     */
    public static final int DOLBY_VISION_8B_HDR_REF =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_REF;

    /**
     * <p>This is the power optimized mode for 8-bit Dolby Vision HDR Reference Mode.</p>
     */
    public static final int DOLBY_VISION_8B_HDR_REF_PO =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_REF_PO;

    /**
     * <p>This is the 8-bit version of device specific tuned and optimized Dolby Vision
     * capture mode.</p>
     */
    public static final int DOLBY_VISION_8B_HDR_OEM =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_OEM;

    /**
     * <p>This is the power optimized mode for 8-bit Dolby Vision HDR device specific
     * capture Mode.</p>
     */
    public static final int DOLBY_VISION_8B_HDR_OEM_PO =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_OEM_PO;

    /*
     * @hide
     */
    public static final int PUBLIC_MAX =
            CameraMetadata.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_MAX;

     /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PROFILE_"}, value =
            {STANDARD,
             HLG10,
             HDR10,
             HDR10_PLUS,
             DOLBY_VISION_10B_HDR_REF,
             DOLBY_VISION_10B_HDR_REF_PO,
             DOLBY_VISION_10B_HDR_OEM,
             DOLBY_VISION_10B_HDR_OEM_PO,
             DOLBY_VISION_8B_HDR_REF,
             DOLBY_VISION_8B_HDR_REF_PO,
             DOLBY_VISION_8B_HDR_OEM,
             DOLBY_VISION_8B_HDR_OEM_PO})
    public @interface Profile {
    }

    private final HashMap<Integer, Set<Integer>> mProfileMap = new HashMap<>();

    /**
     * Create a new immutable DynamicRangeProfiles instance.
     *
     * <p>This constructor takes over the array; do not write to the array afterwards.</p>
     *
     * <p>Do note that the constructor is available for testing purposes only!
     * Camera clients must always retrieve the value of
     * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES}.
     * for a given camera id in order to retrieve the device capabilities.</p>
     *
     * @param elements
     *          An array of elements describing the map. It contains two elements per entry which
     *          describe the supported dynamic range profile value in the first element and in the
     *          second element a bitmap of concurrently supported dynamic range profiles within the
     *          same capture request. Bitmap values of 0 indicate that there are no constraints.
     *
     * @throws IllegalArgumentException
     *            if the {@code elements} array length is invalid, not divisible by 2 or contains
     *            invalid element values
     * @throws NullPointerException
     *            if {@code elements} is {@code null}
     *
     */
    public DynamicRangeProfiles(@NonNull final int[] elements) {
        if ((elements.length % 2) != 0) {
            throw new IllegalArgumentException("Dynamic range profile map length " +
                    elements.length + " is not even!");
        }

        for (int i = 0; i < elements.length; i += 2) {
            checkProfileValue(elements[i]);
            // STANDARD is not expected to be included
            if (elements[i] == STANDARD) {
                throw new IllegalArgumentException("Dynamic range profile map must not include a"
                        + " STANDARD profile entry!");
            }
            HashSet<Integer> profiles = new HashSet<>();

            if (elements[i+1] != 0) {
                for (int profile = STANDARD; profile < PUBLIC_MAX; profile <<= 1) {
                    if ((elements[i+1] & profile) != 0) {
                        profiles.add(profile);
                    }
                }
            }

            mProfileMap.put(elements[i], profiles);
        }

        // Build the STANDARD constraints depending on the advertised 10-bit limitations
        HashSet<Integer> standardConstraints = new HashSet<>();
        standardConstraints.add(STANDARD);
        for(Integer profile : mProfileMap.keySet()) {
            if (mProfileMap.get(profile).isEmpty() || mProfileMap.get(profile).contains(STANDARD)) {
                standardConstraints.add(profile);
            }
        }

        mProfileMap.put(STANDARD, standardConstraints);
    }


    /**
     * @hide
     */
    public static void checkProfileValue(int profile) {
        switch (profile) {
            case STANDARD:
            case HLG10:
            case HDR10:
            case HDR10_PLUS:
            case DOLBY_VISION_10B_HDR_REF:
            case DOLBY_VISION_10B_HDR_REF_PO:
            case DOLBY_VISION_10B_HDR_OEM:
            case DOLBY_VISION_10B_HDR_OEM_PO:
            case DOLBY_VISION_8B_HDR_REF:
            case DOLBY_VISION_8B_HDR_REF_PO:
            case DOLBY_VISION_8B_HDR_OEM:
            case DOLBY_VISION_8B_HDR_OEM_PO:
                //No-op
                break;
            default:
                throw new IllegalArgumentException("Unknown profile " + profile);
        }
    }

    /**
     * Return a set of supported dynamic range profiles.
     *
     * @return non-modifiable set of dynamic range profiles
     */
     public @NonNull Set<Integer> getSupportedProfiles() {
         return Collections.unmodifiableSet(mProfileMap.keySet());
     }

    /**
     * Return a list of supported dynamic range profiles that
     * can be referenced in a single capture request along with a given
     * profile.
     *
     * <p>For example if assume that a particular 10-bit output capable device
     * returns ({@link #STANDARD}, {@link #HLG10}, {@link #HDR10}) as result from calling
     * {@link #getSupportedProfiles()} and {@link #getProfileCaptureRequestConstraints(int)}
     * returns ({@link #STANDARD}, {@link #HLG10}) when given an argument of {@link #STANDARD}.
     * This means that the corresponding camera device will only accept and process capture requests
     * that reference outputs configured using {@link #HDR10} dynamic profile or alternatively
     * some combination of {@link #STANDARD} and {@link #HLG10}. However trying to
     * queue capture requests to outputs that reference both {@link #HDR10} and
     * {@link #STANDARD}/{@link #HLG10} will result in {@link IllegalArgumentException}.</p>
     *
     * <p>The list will be empty in case there are no constraints for the given
     * profile.</p>
     *
     * @return non-modifiable set of dynamic range profiles
     * @throws IllegalArgumentException - If the profile argument is not
     *                                    within the list returned by
     *                                    getSupportedProfiles()
     *
     * @see OutputConfiguration#setDynamicRangeProfile(int)
     */
     public @NonNull Set<Integer> getProfileCaptureRequestConstraints(@Profile int profile) {
         Set<Integer> ret = mProfileMap.get(profile);
         if (ret == null) {
             throw new IllegalArgumentException("Unsupported profile!");
         }

         return Collections.unmodifiableSet(ret);
     }
}
