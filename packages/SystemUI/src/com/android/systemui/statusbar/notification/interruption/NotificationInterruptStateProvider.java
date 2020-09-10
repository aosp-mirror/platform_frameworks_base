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

/**
 * Provides bubble-up and heads-up state for notification entries.
 *
 * When a notification is heads-up when dozing, this is also called "pulsing."
 */
public interface NotificationInterruptStateProvider {
    /**
     * If the device is awake (not dozing):
     *  Whether the notification should peek in from the top and alert the user.
     *
     * If the device is dozing:
     *  Whether the notification should show the ambient view of the notification ("pulse").
     *
     * @param entry the entry to check
     * @return true if the entry should heads up, false otherwise
     */
    boolean shouldHeadsUp(NotificationEntry entry);

    /**
     * Whether the notification should appear as a bubble with a fly-out on top of the screen.
     *
     * @param entry the entry to check
     * @return true if the entry should bubble up, false otherwise
     */
    boolean shouldBubbleUp(NotificationEntry entry);

    /**
     * Whether to launch the entry's full screen intent when the entry is added.
     *
     * @param entry the entry that was added
     * @return {@code true} if we should launch the full screen intent
     */
    boolean shouldLaunchFullScreenIntentWhenAdded(NotificationEntry entry);

    /**
     * Add a component that can suppress visual interruptions.
     */
    void addSuppressor(NotificationInterruptSuppressor suppressor);
}
