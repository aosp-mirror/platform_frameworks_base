/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.data.model

import android.graphics.drawable.Icon
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel

/** Simple ActiveNotificationModel builder for use in tests. */
fun activeNotificationModel(
    key: String,
    groupKey: String? = null,
    isAmbient: Boolean = false,
    isRowDismissed: Boolean = false,
    isSilent: Boolean = false,
    isLastMessageFromReply: Boolean = false,
    isSuppressedFromStatusBar: Boolean = false,
    isPulsing: Boolean = false,
    aodIcon: Icon? = null,
    shelfIcon: Icon? = null,
    statusBarIcon: Icon? = null,
) =
    ActiveNotificationModel(
        key = key,
        groupKey = groupKey,
        isAmbient = isAmbient,
        isRowDismissed = isRowDismissed,
        isSilent = isSilent,
        isLastMessageFromReply = isLastMessageFromReply,
        isSuppressedFromStatusBar = isSuppressedFromStatusBar,
        isPulsing = isPulsing,
        aodIcon = aodIcon,
        shelfIcon = shelfIcon,
        statusBarIcon = statusBarIcon,
    )
