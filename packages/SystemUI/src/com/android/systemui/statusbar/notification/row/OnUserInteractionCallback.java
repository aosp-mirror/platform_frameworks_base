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

package com.android.systemui.statusbar.notification.row;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Callbacks for when a user interacts with an {@link ExpandableNotificationRow}.
 */
public interface OnUserInteractionCallback {

    /**
     * Triggered after a user has changed the importance of the notification via its
     * {@link NotificationGuts}.
     */
    void onImportanceChanged(NotificationEntry entry);

    /**
     * Called once it is known that a dismissal will take place for the given reason.
     * This returns a Runnable which MUST be invoked when the dismissal is ready to be completed.
     *
     * Registering for future dismissal is typically done before notifying the NMS that a
     * notification was clicked or dismissed, but the local dismissal may happen later.
     *
     * @param entry              the entry being cancelled
     * @param cancellationReason the reason for the cancellation
     * @return the runnable to call when the dismissal can happen
     */
    @NonNull
    Runnable registerFutureDismissal(@NonNull NotificationEntry entry,
            @CancellationReason int cancellationReason);
}
