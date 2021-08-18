/*
 * Copyright 2015 The Android Open Source Project
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

package android.hardware.camera2.utils;

import static android.system.OsConstants.EINVAL;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Various Surface utilities.
 */
public class SurfaceUtils {

    // Usage flags not yet included in HardwareBuffer
    private static final int USAGE_RENDERSCRIPT = 0x00100000;
    private static final int USAGE_HW_COMPOSER = 0x00000800;

    // Image formats not yet included in PixelFormat
    private static final int BGRA_8888 = 0x5;

    private static final int BAD_VALUE = -EINVAL;

    /**
     * Check if a surface is for preview consumer based on consumer end point Gralloc usage flags.
     *
     * @param surface The surface to be checked.
     * @return true if the surface is for preview consumer, false otherwise.
     */
    public static boolean isSurfaceForPreview(Surface surface) {
        checkNotNull(surface);
        long usageFlags = nativeDetectSurfaceUsageFlags(surface);
        long disallowedFlags = HardwareBuffer.USAGE_VIDEO_ENCODE | USAGE_RENDERSCRIPT
                | HardwareBuffer.USAGE_CPU_READ_OFTEN;
        long allowedFlags = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | USAGE_HW_COMPOSER
                | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT;
        boolean previewConsumer = ((usageFlags & disallowedFlags) == 0
                && (usageFlags & allowedFlags) != 0);
        int surfaceFormat = getSurfaceFormat(surface);

        return previewConsumer;
    }

    /**
     * Check if the surface is for hardware video encoder consumer based on consumer end point
     * Gralloc usage flags.
     *
     * @param surface The surface to be checked.
     * @return true if the surface is for hardware video encoder consumer, false otherwise.
     */
    public static boolean isSurfaceForHwVideoEncoder(Surface surface) {
        checkNotNull(surface);
        long usageFlags = nativeDetectSurfaceUsageFlags(surface);
        long disallowedFlags = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | USAGE_HW_COMPOSER
                | USAGE_RENDERSCRIPT | HardwareBuffer.USAGE_CPU_READ_OFTEN;
        long allowedFlags = HardwareBuffer.USAGE_VIDEO_ENCODE;
        boolean videoEncoderConsumer = ((usageFlags & disallowedFlags) == 0
                && (usageFlags & allowedFlags) != 0);

        int surfaceFormat = getSurfaceFormat(surface);

        return videoEncoderConsumer;
    }

    /**
     * Get the native object id of a surface.
     *
     * @param surface The surface to be checked.
     * @return the native object id of the surface, 0 if surface is not backed by a native object.
     */
    public static long getSurfaceId(Surface surface) {
        checkNotNull(surface);
        try {
            return nativeGetSurfaceId(surface);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Get the surface usage bits.
     *
     * @param surface The surface to be queried for usage.
     * @return the native object id of the surface, 0 if surface is not backed by a native object.
     */
    public static long getSurfaceUsage(Surface surface) {
        checkNotNull(surface);
        try {
            return nativeDetectSurfaceUsageFlags(surface);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
    /**
     * Get the Surface size.
     *
     * @param surface The surface to be queried for size.
     * @return Size of the surface.
     *
     * @throws IllegalArgumentException if the surface is already abandoned.
     */
    @UnsupportedAppUsage
    public static Size getSurfaceSize(Surface surface) {
        checkNotNull(surface);

        int[] dimens = new int[2];
        int errorFlag =  nativeDetectSurfaceDimens(surface, /*out*/dimens);
        if (errorFlag == BAD_VALUE) throw new IllegalArgumentException("Surface was abandoned");

        return new Size(dimens[0], dimens[1]);
    }

    /**
     * Get the Surface format.
     *
     * @param surface The surface to be queried for format.
     * @return format of the surface.
     *
     * @throws IllegalArgumentException if the surface is already abandoned.
     */
    public static int getSurfaceFormat(Surface surface) {
        checkNotNull(surface);
        int surfaceType = nativeDetectSurfaceType(surface);
        if (surfaceType == BAD_VALUE) throw new IllegalArgumentException("Surface was abandoned");

        // TODO: remove this override since the default format should be
        // ImageFormat.PRIVATE. b/9487482
        if ((surfaceType >= PixelFormat.RGBA_8888
                && surfaceType <= BGRA_8888)) {
            surfaceType = ImageFormat.PRIVATE;
        }
        return surfaceType;
    }

    /**
     * Detect and retrieve the Surface format without any
     * additional overrides.
     *
     * @param surface The surface to be queried for format.
     * @return format of the surface.
     *
     * @throws IllegalArgumentException if the surface is already abandoned.
     */
    public static int detectSurfaceFormat(Surface surface) {
        checkNotNull(surface);
        int surfaceType = nativeDetectSurfaceType(surface);
        if (surfaceType == BAD_VALUE) throw new IllegalArgumentException("Surface was abandoned");

        return surfaceType;
    }

    /**
     * Get the Surface dataspace.
     *
     * @param surface The surface to be queried for dataspace.
     * @return dataspace of the surface.
     *
     * @throws IllegalArgumentException if the surface is already abandoned.
     */
    public static int getSurfaceDataspace(Surface surface) {
        checkNotNull(surface);
        int dataSpace = nativeDetectSurfaceDataspace(surface);
        if (dataSpace == BAD_VALUE) throw new IllegalArgumentException("Surface was abandoned");
        return dataSpace;
    }

    /**
     * Return true is the consumer is one of the consumers that can accept
     * producer overrides of the default dimensions and format.
     *
     */
    public static boolean isFlexibleConsumer(Surface output) {
        checkNotNull(output);
        long usageFlags = nativeDetectSurfaceUsageFlags(output);

        // Keep up to date with allowed consumer types in
        // frameworks/av/services/camera/libcameraservice/api2/CameraDeviceClient.cpp
        long disallowedFlags = HardwareBuffer.USAGE_VIDEO_ENCODE | USAGE_RENDERSCRIPT;
        long allowedFlags = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_CPU_READ_OFTEN
                | USAGE_HW_COMPOSER;
        boolean flexibleConsumer = ((usageFlags & disallowedFlags) == 0
                && (usageFlags & allowedFlags) != 0);
        return flexibleConsumer;
    }


    /**
     * A high speed output surface can only be preview or hardware encoder surface.
     *
     * @param surface The high speed output surface to be checked.
     */
    private static void checkHighSpeedSurfaceFormat(Surface surface) {
        int surfaceFormat = SurfaceUtils.getSurfaceFormat(surface);

        if (surfaceFormat != ImageFormat.PRIVATE) {
            throw new IllegalArgumentException("Surface format(" + surfaceFormat + ") is not"
                    + " for preview or hardware video encoding!");
        }
    }

    /**
     * Verify that that the surfaces are valid for high-speed recording mode,
     * and that the FPS range is supported
     *
     * @param surfaces the surfaces to verify as valid in terms of size and format
     * @param fpsRange the target high-speed FPS range to validate
     * @param config The stream configuration map for the device in question
     */
    public static void checkConstrainedHighSpeedSurfaces(Collection<Surface> surfaces,
            Range<Integer> fpsRange, StreamConfigurationMap config) {
        if (surfaces == null || surfaces.size() == 0 || surfaces.size() > 2) {
            throw new IllegalArgumentException("Output target surface list must not be null and"
                    + " the size must be 1 or 2");
        }

        List<Size> highSpeedSizes = null;
        if (fpsRange == null) {
            highSpeedSizes = Arrays.asList(config.getHighSpeedVideoSizes());
        } else {
            // Check the FPS range first if provided
            Range<Integer>[] highSpeedFpsRanges = config.getHighSpeedVideoFpsRanges();
            if(!Arrays.asList(highSpeedFpsRanges).contains(fpsRange)) {
                throw new IllegalArgumentException("Fps range " + fpsRange.toString() + " in the"
                        + " request is not a supported high speed fps range " +
                        Arrays.toString(highSpeedFpsRanges));
            }
            highSpeedSizes = Arrays.asList(config.getHighSpeedVideoSizesFor(fpsRange));
        }

        for (Surface surface : surfaces) {
            checkHighSpeedSurfaceFormat(surface);

            // Surface size must be supported high speed sizes.
            Size surfaceSize = SurfaceUtils.getSurfaceSize(surface);
            if (!highSpeedSizes.contains(surfaceSize)) {
                throw new IllegalArgumentException("Surface size " + surfaceSize.toString() + " is"
                        + " not part of the high speed supported size list " +
                        Arrays.toString(highSpeedSizes.toArray()));
            }
            // Each output surface must be either preview surface or recording surface.
            if (!SurfaceUtils.isSurfaceForPreview(surface) &&
                    !SurfaceUtils.isSurfaceForHwVideoEncoder(surface)) {
                throw new IllegalArgumentException("This output surface is neither preview nor "
                        + "hardware video encoding surface");
            }
            if (SurfaceUtils.isSurfaceForPreview(surface) &&
                    SurfaceUtils.isSurfaceForHwVideoEncoder(surface)) {
                throw new IllegalArgumentException("This output surface can not be both preview"
                        + " and hardware video encoding surface");
            }
        }

        // For 2 output surface case, they shouldn't be same type.
        if (surfaces.size() == 2) {
            // Up to here, each surface can only be either preview or recording.
            Iterator<Surface> iterator = surfaces.iterator();
            boolean isFirstSurfacePreview =
                    SurfaceUtils.isSurfaceForPreview(iterator.next());
            boolean isSecondSurfacePreview =
                    SurfaceUtils.isSurfaceForPreview(iterator.next());
            if (isFirstSurfacePreview == isSecondSurfacePreview) {
                throw new IllegalArgumentException("The 2 output surfaces must have different"
                        + " type");
            }
        }
    }

    private static native int nativeDetectSurfaceType(Surface surface);

    private static native int nativeDetectSurfaceDataspace(Surface surface);

    private static native long nativeDetectSurfaceUsageFlags(Surface surface);

    private static native int nativeDetectSurfaceDimens(Surface surface,
            /*out*/int[/*2*/] dimens);

    private static native long nativeGetSurfaceId(Surface surface);
}
