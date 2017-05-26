/*
** Copyright 2017, The Android Open Source Project
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

import android.os.Temperature;

/**
 * Listener for thermal events.
 * {@hide}
 */
oneway interface IThermalEventListener {
    /**
     * Called when a thermal throttling start/stop event is received.
     * @param temperature the temperature at which the event was generated.
     */
    void notifyThrottling(
        in boolean isThrottling, in Temperature temperature);
}
