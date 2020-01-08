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
 * Frontend settings for DVBS.
 * @hide
 */
public class DvbsFrontendSettings extends FrontendSettings {
    public int modulation;
    public DvbsCodeRate coderate;
    public int symbolRate;
    public int rolloff;
    public int pilot;
    public int inputStreamId;
    public byte standard;

    DvbsFrontendSettings(int frequency) {
        super(frequency);
    }

    @Override
    public int getType() {
        return TunerConstants.FRONTEND_TYPE_DVBS;
    }
}
