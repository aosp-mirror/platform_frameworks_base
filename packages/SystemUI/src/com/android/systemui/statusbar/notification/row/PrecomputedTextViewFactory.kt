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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.android.internal.widget.ConversationLayout
import com.android.internal.widget.ImageFloatingTextView
import com.android.internal.widget.MessagingLayout
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import javax.inject.Inject

class PrecomputedTextViewFactory @Inject constructor() : NotifRemoteViewsFactory {
    override fun instantiate(
        row: ExpandableNotificationRow,
        @InflationFlag layoutType: Int,
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        return when (name) {
            ImageFloatingTextView::class.java.name ->
                PrecomputedImageFloatingTextView(context, attrs)
            MessagingLayout::class.java.name ->
                MessagingLayout(context, attrs).apply { setPrecomputedTextEnabled(true) }
            ConversationLayout::class.java.name ->
                ConversationLayout(context, attrs).apply { setPrecomputedTextEnabled(true) }
            else -> null
        }
    }
}
