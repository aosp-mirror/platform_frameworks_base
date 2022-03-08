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

package com.android.systemui.statusbar.notification.collection.notifcollection;

import android.service.notification.StatusBarNotification;

/**
 * An object that allows Coordinators to update notifications internally to SystemUI.
 * This is used when part of the UI involves updating the underlying appearance of a notification
 * on behalf of an app, such as to add a spinner or remote input history.
 */
public interface InternalNotifUpdater {
    /**
     * Called when an already-existing notification needs to be updated to a new temporary
     * appearance.
     * This update is local to the SystemUI process.
     * This has no effect if no notification with the given key exists in the pipeline.
     *
     * @param sbn a notification to update
     * @param reason a debug reason for the update
     */
    void onInternalNotificationUpdate(StatusBarNotification sbn, String reason);
}
