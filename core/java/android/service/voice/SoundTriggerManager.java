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

import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;

import java.util.ArrayList;

/**
 * Manager for {@link SoundTrigger} APIs.
 * Currently this just acts as an abstraction over all SoundTrigger API calls.
 * @hide
 */
public class SoundTriggerManager {
    /** The {@link DspInfo} for the system, or null if none exists. */
    public DspInfo dspInfo;

    public SoundTriggerManager() {
        ArrayList <ModuleProperties> modules = new ArrayList<>();
        int status = SoundTrigger.listModules(modules);
        if (status != SoundTrigger.STATUS_OK || modules.size() == 0) {
            // TODO(sansid, elaurent): Figure out how to handle errors in listing the modules here.
            dspInfo = null;
        } else {
            // TODO(sansid, elaurent): Figure out how to determine which module corresponds to the
            // DSP hardware.
            ModuleProperties properties = modules.get(0);
            dspInfo = new DspInfo(properties.uuid, properties.implementor, properties.description,
                    properties.version, properties.powerConsumptionMw);
        }
    }

    /**
     * @return True, if the keyphrase is supported on DSP for the given locale.
     */
    public boolean isKeyphraseSupported(String keyphrase, String locale) {
        // TODO(sansid): We also need to look into a SoundTrigger API that let's us
        // query this. For now just return supported if there's a DSP available.
        return dspInfo != null;
    }

    /**
     * @return True, if the keyphrase is has been enrolled for the given locale.
     */
    public boolean isKeyphraseEnrolled(String keyphrase, String locale) {
        // TODO(sansid, elaurent): Query SoundTrigger to list currently loaded sound models.
        // They have been enrolled.
        return false;
    }

    /**
     * @return True, if a recognition for the keyphrase is active for the given locale.
     */
    public boolean isKeyphraseActive(String keyphrase, String locale) {
        // TODO(sansid, elaurent): Check if the recognition for the keyphrase is currently active.
        return false;
    }
}
