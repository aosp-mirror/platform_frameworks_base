/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware;

/**
 * SensorPrivacyManager calls for within the system server
 * @hide
 */
public abstract class SensorPrivacyManagerInternal {

    /**
     * A class implementing this interface can register to receive a callback when state changes.
     */
    public interface OnSensorPrivacyChangedListener {
        /**
         * The callback invoked when the state changes.
         */
        void onSensorPrivacyChanged(boolean enabled);
    }

    /**
     * A class implementing this interface can register to receive a callback when state changes for
     * any user.
     */
    public interface OnUserSensorPrivacyChangedListener {
        /**
         * The callback invoked when the state changes.
         */
        void onSensorPrivacyChanged(int userId, boolean enabled);
    }

    /**
     * Get the individual sensor privacy state for a given user.
     */
    public abstract boolean isSensorPrivacyEnabled(int userId, int sensor);

    /**
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     */
    public abstract void addSensorPrivacyListener(int userId, int sensor,
            OnSensorPrivacyChangedListener listener);

    /**
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes for any user.
     */
    public abstract void addSensorPrivacyListenerForAllUsers(int sensor,
            OnUserSensorPrivacyChangedListener listener);
}
