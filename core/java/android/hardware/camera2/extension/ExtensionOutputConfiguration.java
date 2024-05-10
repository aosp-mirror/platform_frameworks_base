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
import android.annotation.Nullable;
import android.annotation.SystemApi;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public class ExtensionOutputConfiguration {
    private final List<CameraOutputSurface> mSurfaces;
    private final String mPhysicalCameraId;
    private final int mOutputConfigId;
    private final int mSurfaceGroupId;

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public ExtensionOutputConfiguration(@NonNull List<CameraOutputSurface> outputs,
            int outputConfigId, @Nullable String physicalCameraId, int surfaceGroupId) {
        mSurfaces = outputs;
        mPhysicalCameraId = physicalCameraId;
        mOutputConfigId = outputConfigId;
        mSurfaceGroupId = surfaceGroupId;
    }

    private void initializeOutputConfig(@NonNull CameraOutputConfig config,
            @NonNull CameraOutputSurface surface) {
        config.surface = surface.getSurface();
        if (surface.getSize() != null) {
            config.size = new Size();
            config.size.width = surface.getSize().getWidth();
            config.size.height = surface.getSize().getHeight();
        }
        config.imageFormat = surface.getImageFormat();
        config.type = CameraOutputConfig.TYPE_SURFACE;
        config.physicalCameraId = mPhysicalCameraId;
        config.outputId = new OutputConfigId();
        config.outputId.id = mOutputConfigId;
        config.surfaceGroupId = mSurfaceGroupId;
    }

    @Nullable CameraOutputConfig getOutputConfig() {
        if (mSurfaces.isEmpty()) {
            return null;
        }

        CameraOutputConfig ret = new CameraOutputConfig();
        initializeOutputConfig(ret, mSurfaces.get(0));
        if (mSurfaces.size() > 1) {
            ret.sharedSurfaceConfigs = new ArrayList<>(mSurfaces.size() - 1);
            for (int i = 1; i < mSurfaces.size(); i++) {
                CameraOutputConfig sharedConfig = new CameraOutputConfig();
                initializeOutputConfig(sharedConfig, mSurfaces.get(i));
                ret.sharedSurfaceConfigs.add(sharedConfig);
            }
        }

        return ret;
    }
}
