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

import java.util.List;

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
      * Register a listener for thermal events on given temperature type.
      * @param listener the IThermalEventListener to be notified.
      * @param type the temperature type IThermalEventListener to be notified.
      * {@hide}
      */
    void registerThermalEventListenerWithType(in IThermalEventListener listener, in int type);
    /**
      * Unregister a previously-registered listener for thermal events.
      * @param listener the IThermalEventListener to no longer be notified.
      * {@hide}
      */
    void unregisterThermalEventListener(in IThermalEventListener listener);
    /**
      * Get current temperature with its throttling status.
      * @return list of android.os.Temperature
      * {@hide}
      */
    List<Temperature> getCurrentTemperatures();
    /**
      * Get current temperature with its throttling status on given temperature type.
      * @param type the temperature type to query.
      * @return list of android.os.Temperature
      * {@hide}
      */
    List<Temperature> getCurrentTemperaturesWithType(in int type);
}
