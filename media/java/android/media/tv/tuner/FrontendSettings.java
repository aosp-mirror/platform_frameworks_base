/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

import android.annotation.SystemApi;
import android.media.tv.tuner.TunerConstants.FrontendSettingsType;

/**
 * Frontend settings for tune and scan operations.
 * @hide
 */
@SystemApi
public abstract class FrontendSettings {
    private final int mFrequency;

    /** @hide */
    public FrontendSettings(int frequency) {
        mFrequency = frequency;
    }

    /**
     * Returns the frontend type.
     */
    @FrontendSettingsType
    public abstract int getType();

    /**
     * Gets the frequency setting.
     *
     * @return the frequency in Hz.
     */
    public final int getFrequency() {
        return mFrequency;
    }

}
