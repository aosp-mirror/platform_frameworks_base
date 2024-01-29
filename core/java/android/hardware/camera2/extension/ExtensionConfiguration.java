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
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.IBinder;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class used to guide the camera framework when
 * initializing the internal camera capture session.
 * It contains all required internal outputs, parameters,
 * modes and settings.
 *
 * <p>Extension must decide the final set of output surfaces
 * and pass an instance of ExtensionConfiguration as part
 * of the result during calls to {@link SessionProcessor#initSession}.</p>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public class ExtensionConfiguration {
    private final int mSessionType;
    private final int mSessionTemplateId;
    private final List<ExtensionOutputConfiguration> mOutputs;
    private final CaptureRequest mSessionParameters;

    /**
     * Initialize an extension configuration instance
     *
     * @param sessionType       The type of camera capture session
     *                          operating mode to be used
     * @param sessionTemplateId The request template id to be used
     *                          for generating the session parameter
     *                          capture request
     * @param outputs           List of {@link ExtensionOutputConfiguration}
     *                          camera outputs to be configured
     *                          as part of the capture session
     * @param sessionParams     An optional set of camera capture
     *                          session parameter values
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public ExtensionConfiguration(@CameraDevice.SessionOperatingMode int sessionType,
            @CameraDevice.RequestTemplate int sessionTemplateId,
            @NonNull List<ExtensionOutputConfiguration> outputs,
            @Nullable CaptureRequest sessionParams) {
        mSessionType = sessionType;
        mSessionTemplateId = sessionTemplateId;
        mOutputs = outputs;
        mSessionParameters = sessionParams;
    }

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    CameraSessionConfig getCameraSessionConfig() {
        if (mOutputs.isEmpty()) {
            return null;
        }

        CameraSessionConfig ret = new CameraSessionConfig();
        ret.sessionTemplateId = mSessionTemplateId;
        ret.sessionType = mSessionType;
        ret.outputConfigs = new ArrayList<>(mOutputs.size());
        for (ExtensionOutputConfiguration outputConfig : mOutputs) {
            ret.outputConfigs.add(outputConfig.getOutputConfig());
        }
        if (mSessionParameters != null) {
            ret.sessionParameter = mSessionParameters.getNativeCopy();
        }

        return ret;
    }
}
