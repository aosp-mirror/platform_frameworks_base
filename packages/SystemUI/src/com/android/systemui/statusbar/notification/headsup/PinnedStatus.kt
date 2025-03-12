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

package com.android.systemui.statusbar.notification.headsup

/**
 * A status representing whether and how a notification is pinned.
 *
 * @property isPinned true if a notification should be "pinned", meaning that a notification should
 *   stay on top of the screen.
 */
enum class PinnedStatus(val isPinned: Boolean) {
    /** This notification is not pinned. */
    NotPinned(isPinned = false),
    /**
     * This notification is pinned by the system - likely because when the notification was added or
     * updated, it required pinning.
     */
    PinnedBySystem(isPinned = true),
    /**
     * This notification is pinned because the user did an explicit action to pin it (like tapping
     * the notification chip in the status bar).
     */
    // TODO(b/364653005): Use this status when a user taps the notification chip.
    PinnedByUser(isPinned = true),
}
