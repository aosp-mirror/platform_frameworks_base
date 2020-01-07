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

package android.media.tv.tuner.frontend;


import android.media.tv.tuner.FrontendSettings;
import android.media.tv.tuner.TunerConstants;

/**
 * Frontend settings for DVBT.
 * @hide
 */
public class DvbtFrontendSettings extends FrontendSettings {
    public int transmissionMode;
    public int bandwidth;
    public int constellation;
    public int hierarchy;
    public int hpCoderate;
    public int lpCoderate;
    public int guardInterval;
    public boolean isHighPriority;
    public byte standard;
    public boolean isMiso;
    public int plpMode;
    public byte plpId;
    public byte plpGroupId;

    DvbtFrontendSettings(int frequency) {
        super(frequency);
    }

    @Override
    public int getType() {
        return TunerConstants.FRONTEND_TYPE_DVBT;
    }
}
