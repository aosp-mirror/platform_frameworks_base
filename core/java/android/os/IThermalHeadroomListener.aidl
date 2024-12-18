/*
** Copyright 2024, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.os;

/**
 * Listener for thermal headroom and threshold changes.
 * This is mainly used by {@link android.os.PowerManager} to serve public thermal headoom related
 * APIs.
 * {@hide}
 */
oneway interface IThermalHeadroomListener {
    /**
     * Called when thermal headroom or thresholds changed.
     */
    void onHeadroomChange(in float headroom, in float forecastHeadroom,
                                 in int forecastSeconds, in float[] thresholds);
}
