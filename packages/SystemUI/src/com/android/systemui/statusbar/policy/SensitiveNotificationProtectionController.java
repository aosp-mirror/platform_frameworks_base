/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * A controller which provides the current sensitive notification protections status as well as
 * to assist in feature usage and exemptions
 */
public interface SensitiveNotificationProtectionController {
    /**
     * Register a runnable that triggers on changes to protection state
     *
     * <p> onSensitiveStateChanged not invoked on registration
     */
    void registerSensitiveStateListener(Runnable onSensitiveStateChanged);

    /** Unregister a previously registered onSensitiveStateChanged runnable */
    void unregisterSensitiveStateListener(Runnable onSensitiveStateChanged);

    /** Return {@code true} if device in state in which notifications should be protected */
    boolean isSensitiveStateActive();

    /** Return {@code true} when notification should be protected */
    boolean shouldProtectNotification(NotificationEntry entry);
}
