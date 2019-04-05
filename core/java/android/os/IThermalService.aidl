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

import android.os.CoolingDevice;
import android.os.IThermalEventListener;
import android.os.IThermalStatusListener;
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
    boolean registerThermalEventListener(in IThermalEventListener listener);

    /**
      * Register a listener for thermal events on given temperature type.
      * @param listener the IThermalEventListener to be notified.
      * @param type the temperature type IThermalEventListener to be notified.
      * @return true if registered successfully.
      * {@hide}
      */
    boolean registerThermalEventListenerWithType(in IThermalEventListener listener, in int type);

    /**
      * Unregister a previously-registered listener for thermal events.
      * @param listener the IThermalEventListener to no longer be notified.
      * @return true if unregistered successfully.
      * {@hide}
      */
    boolean unregisterThermalEventListener(in IThermalEventListener listener);

    /**
      * Get current temperature with its throttling status.
      * @return list of {@link android.os.Temperature}.
      * {@hide}
      */
    List<Temperature> getCurrentTemperatures();

    /**
      * Get current temperature with its throttling status on given temperature type.
      * @param type the temperature type to query.
      * @return list of {@link android.os.Temperature}.
      * {@hide}
      */
    List<Temperature> getCurrentTemperaturesWithType(in int type);

    /**
      * Register a listener for thermal status change.
      * @param listener the {@link android.os.IThermalStatusListener} to be notified.
      * @return true if registered successfully.
      * {@hide}
      */
    boolean registerThermalStatusListener(in IThermalStatusListener listener);

    /**
      * Unregister a previously-registered listener for thermal status.
      * @param listener the {@link android.os.IThermalStatusListener} to no longer be notified.
      * @return true if unregistered successfully.
      * {@hide}
      */
    boolean unregisterThermalStatusListener(in IThermalStatusListener listener);

    /**
      * Get current thermal status.
      * @return status defined in {@link android.os.Temperature}.
      * {@hide}
      */
    int getCurrentThermalStatus();

    /**
      * Get current cooling devices.
      * @return list of {@link android.os.CoolingDevice}.
      * {@hide}
      */
    List<CoolingDevice> getCurrentCoolingDevices();

    /**
      * Get current cooling devices on given type.
      * @param type the cooling device type to query.
      * @return list of {@link android.os.CoolingDevice}.
      * {@hide}
      */
    List<CoolingDevice> getCurrentCoolingDevicesWithType(in int type);
}
