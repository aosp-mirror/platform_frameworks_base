/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.shared

import com.google.common.truth.Correspondence

val byKey: Correspondence<ActiveNotificationModel, String> =
    Correspondence.transforming({ it?.key }, "has a key of")
val byIsAmbient: Correspondence<ActiveNotificationModel, Boolean> =
    Correspondence.transforming({ it?.isAmbient }, "has an isAmbient value of")
val byIsSuppressedFromStatusBar: Correspondence<ActiveNotificationModel, Boolean> =
    Correspondence.transforming(
        { it?.isSuppressedFromStatusBar },
        "has an isSuppressedFromStatusBar value of",
    )
val byIsSilent: Correspondence<ActiveNotificationModel, Boolean> =
    Correspondence.transforming({ it?.isSilent }, "has an isSilent value of")
val byIsRowDismissed: Correspondence<ActiveNotificationModel, Boolean> =
    Correspondence.transforming({ it?.isRowDismissed }, "has an isRowDismissed value of")
val byIsLastMessageFromReply: Correspondence<ActiveNotificationModel, Boolean> =
    Correspondence.transforming(
        { it?.isLastMessageFromReply },
        "has an isLastMessageFromReply value of"
    )
val byIsPulsing: Correspondence<ActiveNotificationModel, Boolean> =
    Correspondence.transforming({ it?.isPulsing }, "has an isPulsing value of")
