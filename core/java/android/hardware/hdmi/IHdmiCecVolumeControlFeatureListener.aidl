/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.hdmi;

/**
 * Listener used to get the status of the HDMI CEC volume control feature (enabled/disabled).
 * @hide
 */
oneway interface IHdmiCecVolumeControlFeatureListener {

    /**
     * Called when the HDMI Control (CEC) volume control feature is enabled/disabled.
     *
     * @param enabled status of HDMI CEC volume control feature
     * @see {@link HdmiControlManager#setHdmiCecVolumeControlEnabled(boolean)} ()}
     **/
    void onHdmiCecVolumeControlFeature(boolean enabled);
}
