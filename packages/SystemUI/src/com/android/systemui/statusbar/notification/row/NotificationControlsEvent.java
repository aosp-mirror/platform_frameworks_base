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

enum NotificationControlsEvent implements UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The user opened the notification inline controls.")
    NOTIFICATION_CONTROLS_OPEN(594),

    @UiEvent(doc = "In notification inline controls, the user saved a notification channel "
            + "importance change.")
    NOTIFICATION_CONTROLS_SAVE_IMPORTANCE(595),

    @UiEvent(doc = "The user closed the notification inline controls.")
    NOTIFICATION_CONTROLS_CLOSE(596);

    private final int mId;
    NotificationControlsEvent(int id) {
        mId = id;
    }
    @Override public int getId() {
        return mId;
    }
}
