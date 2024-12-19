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

package com.android.systemui.statusbar.notification.shelf

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.VisibleForTesting
import com.android.systemui.statusbar.notification.row.NotificationBackgroundView
import com.android.systemui.statusbar.notification.shared.NotificationMinimalism

/** The background view for the NotificationShelf. */
class NotificationShelfBackgroundView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    NotificationBackgroundView(context, attrs) {

    /** Whether the notification shelf is aligned to end, need to keep persistent with the shelf. */
    var alignToEnd = false

    /** @return whether the alignment of the notification shelf is right. */
    @VisibleForTesting
    public override fun isAlignedToRight(): Boolean {
        if (!NotificationMinimalism.isEnabled) {
            return super.isAlignedToRight()
        }
        return alignToEnd xor isLayoutRtl
    }

    override fun toDumpString(): String {
        return super.toDumpString() + " alignToEnd=" + alignToEnd
    }
}
