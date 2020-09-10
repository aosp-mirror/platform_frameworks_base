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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Implemented by OEM and/or Form Factor. System ones are built into the
 * image regardless of build flavour but may still be switched off at run time.
 * Individual feature flags at build time control which are used. We may
 * also explore a local override for quick testing.
 */
public interface IDeviceIdleConstraint {

    /**
     * A state for this constraint to block descent from.
     *
     * <p>These states are a subset of the states in DeviceIdleController that make sense for
     * constraints to be able to block on. For example, {@link #SENSING_OR_ABOVE} clearly has
     * defined "above" and "below" states. However, a hypothetical {@code QUICK_DOZE_OR_ABOVE}
     * state would not have clear semantics as to what transitions should be blocked and which
     * should be allowed.
     */
    @IntDef(flag = false, value = {
            ACTIVE,
            SENSING_OR_ABOVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MinimumState {}

    int ACTIVE = 0;
    int SENSING_OR_ABOVE = 1;

    /**
     * Begin tracking events for this constraint.
     *
     * <p>The device idle controller has reached a point where it is waiting for the all-clear
     * from this tracker (possibly among others) in order to continue with progression into
     * idle state. It will not proceed until one of the following happens:
     * <ul>
     *   <li>The constraint reports inactive with {@code .setActive(false)}.</li>
     *   <li>The constraint is unregistered with {@code .unregisterDeviceIdleConstraint(this)}.</li>
     *   <li>A transition timeout in DeviceIdleController fires.
     * </ul>
     */
    void startMonitoring();

    /** Stop checking for new events and do not call into LocalService with updates any more. */
    void stopMonitoring();
}
