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

import android.os.IThermalEventListener;
import android.os.Temperature;

/**
 * {@hide}
 */
interface IThermalService {
    /**
      * Register a listener for thermal events.
      * @param listener the IThermalEventListener to be notified.
      * {@hide}
      */
    void registerThermalEventListener(in IThermalEventListener listener);
    /**
      * Unregister a previously-registered listener for thermal events.
      * @param listener the IThermalEventListener to no longer be notified.
      * {@hide}
      */
    void unregisterThermalEventListener(in IThermalEventListener listener);
    /**
      * Send a thermal throttling start/stop notification to all listeners.
      * @param temperature the temperature at which the event was generated.
      * {@hide}
      */
    oneway void notifyThrottling(
        in boolean isThrottling, in Temperature temperature);
    /**
      * Return whether system performance is currently thermal throttling.
      * @return true if thermal throttling is currently in effect
      * {@hide}
      */
    boolean isThrottling();
}
