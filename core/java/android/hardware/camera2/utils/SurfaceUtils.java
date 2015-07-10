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

import android.graphics.ImageFormat;
import android.hardware.camera2.legacy.LegacyCameraDevice;
import android.hardware.camera2.legacy.LegacyExceptionUtils.BufferQueueAbandonedException;
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

    /**
     * Check if a surface is for preview consumer based on consumer end point Gralloc usage flags.
     *
     * @param surface The surface to be checked.
     * @return true if the surface is for preview consumer, false otherwise.
     */
    public static boolean isSurfaceForPreview(Surface surface) {
        return LegacyCameraDevice.isPreviewConsumer(surface);
    }

    /**
     * Check if the surface is for hardware video encoder consumer based on consumer end point
     * Gralloc usage flags.
     *
     * @param surface The surface to be checked.
     * @return true if the surface is for hardware video encoder consumer, false otherwise.
     */
    public static boolean isSurfaceForHwVideoEncoder(Surface surface) {
        return LegacyCameraDevice.isVideoEncoderConsumer(surface);
    }

    /**
     * Get the Surface size.
     *
     * @param surface The surface to be queried for size.
     * @return Size of the surface.
     *
     * @throws IllegalArgumentException if the surface is already abandoned.
     */
    public static Size getSurfaceSize(Surface surface) {
        try {
            return LegacyCameraDevice.getSurfaceSize(surface);
        } catch (BufferQueueAbandonedException e) {
            throw new IllegalArgumentException("Surface was abandoned", e);
        }
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
        try {
            return LegacyCameraDevice.detectSurfaceType(surface);
        } catch (BufferQueueAbandonedException e) {
            throw new IllegalArgumentException("Surface was abandoned", e);
        }
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
        try {
            return LegacyCameraDevice.detectSurfaceDataspace(surface);
        } catch (BufferQueueAbandonedException e) {
            throw new IllegalArgumentException("Surface was abandoned", e);
        }
    }

    /**
     * Return true is the consumer is one of the consumers that can accept
     * producer overrides of the default dimensions and format.
     *
     */
    public static boolean isFlexibleConsumer(Surface output) {
        return LegacyCameraDevice.isFlexibleConsumer(output);
    }

    /**
     * A high speed output surface can only be preview or hardware encoder surface.
     *
     * @param surface The high speed output surface to be checked.
     */
    private static void checkHighSpeedSurfaceFormat(Surface surface) {
        // TODO: remove this override since the default format should be
        // ImageFormat.PRIVATE. b/9487482
        final int HAL_FORMAT_RGB_START = 1; // HAL_PIXEL_FORMAT_RGBA_8888 from graphics.h
        final int HAL_FORMAT_RGB_END = 5; // HAL_PIXEL_FORMAT_BGRA_8888 from graphics.h
        int surfaceFormat = SurfaceUtils.getSurfaceFormat(surface);
        if (surfaceFormat >= HAL_FORMAT_RGB_START &&
                surfaceFormat <= HAL_FORMAT_RGB_END) {
            surfaceFormat = ImageFormat.PRIVATE;
        }

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

}
