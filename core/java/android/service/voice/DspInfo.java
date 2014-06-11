/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.voice;

import java.util.UUID;

/**
 * Properties of the DSP hardware on the device.
 * @hide
 */
public class DspInfo {
    /**
     * Unique voice engine Id (changes with each version).
     */
    public final UUID voiceEngineId;

    /**
     * Human readable voice detection engine implementor.
     */
    public final String voiceEngineImplementor;
    /**
     * Human readable voice detection engine description.
     */
    public final String voiceEngineDescription;
    /**
     * Human readable voice detection engine version
     */
    public final String voiceEngineVersion;
    /**
     * Rated power consumption when detection is active.
     */
    public final int powerConsumptionMw;

    public DspInfo(UUID voiceEngineId, String voiceEngineImplementor,
            String voiceEngineDescription, String voiceEngineVersion, int powerConsumptionMw) {
        this.voiceEngineId = voiceEngineId;
        this.voiceEngineImplementor = voiceEngineImplementor;
        this.voiceEngineDescription = voiceEngineDescription;
        this.voiceEngineVersion = voiceEngineVersion;
        this.powerConsumptionMw = powerConsumptionMw;
    }
}
