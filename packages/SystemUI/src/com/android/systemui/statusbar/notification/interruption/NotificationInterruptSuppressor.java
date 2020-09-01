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

package com.android.systemui.statusbar.notification.interruption;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/** A component which can suppress visual interruptions of notifications such as heads-up and
 *  bubble-up.
 */
public interface NotificationInterruptSuppressor {
    /**
     * A unique name to identify this suppressor.
     */
    default String getName() {
        return this.getClass().getName();
    }

    /**
     * Returns true if the provided notification is, when the device is awake, ineligible for
     * heads-up according to this component.
     *
     * @param entry entry of the notification that might heads-up
     * @return true if the heads up interruption should be suppressed when the device is awake
     */
    default boolean suppressAwakeHeadsUp(NotificationEntry entry) {
        return false;
    }

    /**
     * Returns true if the provided notification is, when the device is awake, ineligible for
     * heads-up or bubble-up according to this component.
     *
     * @param entry entry of the notification that might heads-up or bubble-up
     * @return true if interruptions should be suppressed when the device is awake
     */
    default boolean suppressAwakeInterruptions(NotificationEntry entry) {
        return false;
    }

    /**
     * Returns true if the provided notification is, regardless of awake/dozing state,
     * ineligible for heads-up or bubble-up according to this component.
     *
     * @param entry entry of the notification that might heads-up or bubble-up
     * @return true if interruptions should be suppressed
     */
    default boolean suppressInterruptions(NotificationEntry entry) {
        return false;
    }
}
