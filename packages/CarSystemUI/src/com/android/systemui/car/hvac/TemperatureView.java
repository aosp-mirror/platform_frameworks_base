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
 * Interface for Views that display temperature HVAC properties.
 */
public interface TemperatureView {

    /**
     * Sets the {@link HvacController} to handle changes to HVAC properties. The View is only
     * responsible for the UI to display temperature. It should not contain logic that makes direct
     * changes to HVAC properties and instead use this {@link HvacController}.
     */
    void setHvacController(HvacController controller);

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
     * @return hvac AreaId - Example: VehicleAreaSeat.SEAT_ROW_1_LEFT (1)
     */
    int getAreaId();
}
