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

package android.os;

/**
 * Battery manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class BatteryManagerInternal {
    /**
     * Returns true if the device is plugged into any of the specified plug types.
     */
    public abstract boolean isPowered(int plugTypeSet);

    /**
     * Returns the current plug type.
     */
    public abstract int getPlugType();

    /**
     * Returns battery level as a percentage.
     */
    public abstract int getBatteryLevel();

    /**
     * Returns whether we currently consider the battery level to be low.
     */
    public abstract boolean getBatteryLevelLow();

    /**
     * Returns a non-zero value if an unsupported charger is attached.
     */
    public abstract int getInvalidCharger();
}
