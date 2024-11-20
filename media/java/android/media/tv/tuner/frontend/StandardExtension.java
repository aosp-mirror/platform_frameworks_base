/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendDvbsStandard;
import android.hardware.tv.tuner.FrontendDvbtStandard;
import android.media.tv.flags.Flags;

/**
 * Standard extension for the standard DVB-T and DVB-S series.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_TUNER_W_APIS)
public final class StandardExtension {
    private final int mDvbsStandardExtension;
    private final int mDvbtStandardExtension;

    /**
     * Private constructor called by JNI only.
     */
    private StandardExtension(int dvbsStandardExtension, int dvbtStandardExtension) {
        mDvbsStandardExtension = dvbsStandardExtension;
        mDvbtStandardExtension = dvbtStandardExtension;
    }

    /**
     * Gets the DVB-S standard extension within the DVB-S standard series.
     *
     * @return An integer representing the standard, such as
     * {@link DvbsFrontendSettings#STANDARD_S}.
     *
     * @see android.media.tv.tuner.frontend.DvbsFrontendSettings
     */
    @DvbsFrontendSettings.Standard
    public int getDvbsStandardExtension() {
        if (mDvbsStandardExtension == FrontendDvbsStandard.UNDEFINED) {
            throw new IllegalStateException("No DVB-S standard transition");
        }
        return mDvbsStandardExtension;
    }

    /**
     * Gets the DVB-T standard extension within the DVB-T standard series.
     *
     * @return An integer representing the standard, such as
     * {@link DvbtFrontendSettings#STANDARD_T}.
     *
     * @see android.media.tv.tuner.frontend.DvbtFrontendSettings
     */
    @DvbtFrontendSettings.Standard
    public int getDvbtStandardExtension() {
        if (mDvbtStandardExtension == FrontendDvbtStandard.UNDEFINED) {
            throw new IllegalStateException("No DVB-T standard transition");
        }
        return mDvbtStandardExtension;
    }
}
