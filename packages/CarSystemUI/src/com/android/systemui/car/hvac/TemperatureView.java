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

package com.android.systemui.car.hvac;

/**
 * Interface for Views that display temperature HVAC properties
 */
public interface TemperatureView {
    /**
     * Formats the float for display
     *
     * @param temp - The current temp in Celsius or NaN
     */
    void setTemp(float temp);

    /**
     * Render the displayed temperature in Fahrenheit
     *
     * @param displayFahrenheit - True if temperature should be displayed in Fahrenheit
     */
    void setDisplayInFahrenheit(boolean displayFahrenheit);

    /**
     * Convert the given temperature in Celsius into Fahrenheit
     *
     * @param realTemp - The temperature in Celsius
     * @return Temperature in Fahrenheit.
     */
    default float convertToFahrenheit(float realTemp) {
        return (realTemp * 9f / 5f) + 32;
    }

    /**
     * @return propertiyId  Example: CarHvacManager.ID_ZONED_TEMP_SETPOINT (16385)
     */
    int getPropertyId();

    /**
     * @return hvac AreaId - Example: VehicleSeat.SEAT_ROW_1_LEFT (1)
     */
    int getAreaId();
}
