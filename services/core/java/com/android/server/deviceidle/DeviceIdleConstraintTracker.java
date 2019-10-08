/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.deviceidle;

/**
 * Current state of an {@link IDeviceIdleConstraint}.
 *
 * If the current doze state is between leastActive and mostActive, then startMonitoring() will
 * be the most recent call. Otherwise, stopMonitoring() is the most recent call.
 */
public class DeviceIdleConstraintTracker {

    /**
     * Appears in "dumpsys deviceidle".
     */
    public final String name;

    /**
     * Whenever a constraint is active, it will keep the device at or above
     * minState (provided the rule is currently in effect).
     *
     */
    public final int minState;

    /**
     * Whether this constraint currently prevents going below {@link #minState}.
     *
     * When the state is set to exactly minState, active is automatically
     * overwritten with {@code true}.
     */
    public boolean active = false;

    /**
     * Internal tracking for whether the {@link IDeviceIdleConstraint} on the other
     * side has been told it needs to send updates.
     */
    public boolean monitoring = false;

    public DeviceIdleConstraintTracker(final String name, int minState) {
        this.name = name;
        this.minState = minState;
    }
}
