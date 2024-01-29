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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.utils.SurfaceUtils;
import android.util.Size;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;


/**
 * Helper method used to describe a single camera output
 * {@link Surface}.
 *
 * <p>Instances of this class can be used as arguments when
 * initializing {@link ExtensionOutputConfiguration}.</p>
 *
 * @see ExtensionConfiguration
 * @see ExtensionOutputConfiguration
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public final class CameraOutputSurface {
    private final OutputSurface mOutputSurface;

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    CameraOutputSurface(@NonNull OutputSurface surface) {
       mOutputSurface = surface;
    }

    /**
     * Initialize a camera output surface instance
     *
     * @param surface      Output {@link Surface} to be
     *                     configured as camera output
     * @param size         Requested size of the camera
     *                     output
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public CameraOutputSurface(@NonNull Surface surface,
            @NonNull Size size) {
        mOutputSurface = new OutputSurface();
        mOutputSurface.surface = surface;
        mOutputSurface.imageFormat = SurfaceUtils.getSurfaceFormat(surface);
        mOutputSurface.size = new android.hardware.camera2.extension.Size();
        mOutputSurface.size.width = size.getWidth();
        mOutputSurface.size.height = size.getHeight();
    }

    /**
     * Return the current output {@link Surface}
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @NonNull
    public Surface getSurface() {
        return mOutputSurface.surface;
    }

    /**
     * Return the current requested output size
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @NonNull
    public android.util.Size getSize() {
        if (mOutputSurface.size != null) {
            return new Size(mOutputSurface.size.width, mOutputSurface.size.height);
        }
        return null;
    }

    /**
     * Return the current surface output {@link android.graphics.ImageFormat}
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public @ImageFormat.Format int getImageFormat() {
        return mOutputSurface.imageFormat;
    }
}
