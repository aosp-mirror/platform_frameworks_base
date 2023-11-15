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

package android.hardware.camera2.impl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class CameraExtensionUtils {
    private static final String TAG = "CameraExtensionUtils";

    public final static int JPEG_DEFAULT_QUALITY = 100;
    public final static int JPEG_DEFAULT_ROTATION = 0;

    public static final int[] SUPPORTED_CAPTURE_OUTPUT_FORMATS = {
            CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT,
            ImageFormat.JPEG,
            ImageFormat.JPEG_R
    };

    public static class SurfaceInfo {
        public int mWidth = 0;
        public int mHeight = 0;
        public int mFormat = PixelFormat.RGBA_8888;
        public long mUsage = 0;
    }

    public static final class HandlerExecutor implements Executor {
        private final Handler mHandler;

        public HandlerExecutor(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void execute(Runnable runCmd) {
            try {
                mHandler.post(runCmd);
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Handler thread unavailable, skipping message!");
            }
        }
    }

    public static @NonNull SurfaceInfo querySurface(@NonNull Surface s) {
        ImageWriter writer = null;
        Image img = null;
        SurfaceInfo surfaceInfo = new SurfaceInfo();
        int nativeFormat = SurfaceUtils.detectSurfaceFormat(s);
        int dataspace = SurfaceUtils.getSurfaceDataspace(s);
        Size surfaceSize = SurfaceUtils.getSurfaceSize(s);
        surfaceInfo.mFormat = nativeFormat;
        surfaceInfo.mWidth = surfaceSize.getWidth();
        surfaceInfo.mHeight = surfaceSize.getHeight();
        surfaceInfo.mUsage = SurfaceUtils.getSurfaceUsage(s);
        // Jpeg surfaces cannot be queried for their usage and other parameters
        // in the usual way below. A buffer can only be de-queued after the
        // producer overrides the surface dimensions to (width*height) x 1.
        if ((nativeFormat == StreamConfigurationMap.HAL_PIXEL_FORMAT_BLOB) &&
                (dataspace == StreamConfigurationMap.HAL_DATASPACE_V0_JFIF)) {
            surfaceInfo.mFormat = ImageFormat.JPEG;
            return surfaceInfo;
        } else if ((nativeFormat == StreamConfigurationMap.HAL_PIXEL_FORMAT_BLOB)
                && (dataspace == StreamConfigurationMap.HAL_DATASPACE_JPEG_R)) {
            surfaceInfo.mFormat = ImageFormat.JPEG_R;
            return surfaceInfo;
        }

        return surfaceInfo;
    }

    public static @Nullable Surface getPostviewSurface(
            @Nullable OutputConfiguration outputConfig,
            @NonNull HashMap<Integer, List<Size>> supportedPostviewSizes,
            @NonNull int captureFormat) {
        if (outputConfig == null) return null;

        SurfaceInfo surfaceInfo = querySurface(outputConfig.getSurface());
        if (surfaceInfo.mFormat == captureFormat) {
            if (supportedPostviewSizes.containsKey(captureFormat)) {
                Size postviewSize = new Size(surfaceInfo.mWidth, surfaceInfo.mHeight);
                if (supportedPostviewSizes.get(surfaceInfo.mFormat)
                        .contains(postviewSize)) {
                    return outputConfig.getSurface();
                } else {
                    throw new IllegalArgumentException("Postview size not supported!");
                }
            }
        } else {
            throw new IllegalArgumentException("Postview format should be equivalent to " +
                    " the capture format!");
        }

        return null;
    }

    public static Surface getBurstCaptureSurface(
            @NonNull List<OutputConfiguration> outputConfigs,
            @NonNull HashMap<Integer, List<Size>> supportedCaptureSizes) {
        for (OutputConfiguration config : outputConfigs) {
            SurfaceInfo surfaceInfo = querySurface(config.getSurface());
            for (int supportedFormat : SUPPORTED_CAPTURE_OUTPUT_FORMATS) {
                if (surfaceInfo.mFormat == supportedFormat) {
                    Size captureSize = new Size(surfaceInfo.mWidth, surfaceInfo.mHeight);
                    if (supportedCaptureSizes.containsKey(supportedFormat)) {
                        if (supportedCaptureSizes.get(surfaceInfo.mFormat).contains(captureSize)) {
                            return config.getSurface();
                        } else {
                            throw new IllegalArgumentException("Capture size not supported!");
                        }
                    }
                    return config.getSurface();
                }
            }
        }

        return null;
    }

    public static @Nullable Surface getRepeatingRequestSurface(
            @NonNull List<OutputConfiguration> outputConfigs,
            @Nullable List<Size> supportedPreviewSizes) {
        for (OutputConfiguration config : outputConfigs) {
            SurfaceInfo surfaceInfo = querySurface(config.getSurface());
            if ((surfaceInfo.mFormat ==
                    CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT) ||
                    ((surfaceInfo.mUsage & HardwareBuffer.USAGE_COMPOSER_OVERLAY) != 0) ||
                    // The default RGBA_8888 is also implicitly supported because camera will
                    // internally override it to
                    // 'CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT'
                    (surfaceInfo.mFormat == PixelFormat.RGBA_8888)) {
                Size repeatingRequestSurfaceSize = new Size(surfaceInfo.mWidth,
                        surfaceInfo.mHeight);
                if ((supportedPreviewSizes == null) ||
                        (!supportedPreviewSizes.contains(repeatingRequestSurfaceSize))) {
                    throw new IllegalArgumentException("Repeating request surface size " +
                            repeatingRequestSurfaceSize + " not supported!");
                }

                return config.getSurface();
            }
        }

        return null;
    }

    public static Map<String, CameraMetadataNative> getCharacteristicsMapNative(
            Map<String, CameraCharacteristics> charsMap) {
        HashMap<String, CameraMetadataNative> ret = new HashMap<>();
        for (Map.Entry<String, CameraCharacteristics> entry : charsMap.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().getNativeMetadata());
        }
        return ret;
    }
}
