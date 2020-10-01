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

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

enum NotificationAppOpsEvent implements UiEventLogger.UiEventEnum {
    @UiEvent(doc = "User opened app ops controls on a notification (for active "
            + "privacy-sensitive permissions usage)")
    NOTIFICATION_APP_OPS_OPEN(597),

    @UiEvent(doc = "User closed app ops controls")
    NOTIFICATION_APP_OPS_CLOSE(598),

    @UiEvent(doc = "User clicked through to settings in app ops controls")
    NOTIFICATION_APP_OPS_SETTINGS_CLICK(599);

    private final int mId;
    NotificationAppOpsEvent(int id) {
        mId = id;
    }
    @Override public int getId() {
        return mId;
    }
}

