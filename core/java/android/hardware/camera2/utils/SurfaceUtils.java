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
import android.util.Size;
import android.view.Surface;

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

}
