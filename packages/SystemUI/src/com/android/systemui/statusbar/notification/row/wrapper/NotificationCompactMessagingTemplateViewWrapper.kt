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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.android.internal.R
import com.android.internal.widget.CachingIconView
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

/** Wraps a notification containing a messaging or conversation template */
class NotificationCompactMessagingTemplateViewWrapper
constructor(ctx: Context, view: View, row: ExpandableNotificationRow) :
    NotificationCompactHeadsUpTemplateViewWrapper(ctx, view, row) {

    private val compactMessagingView: ViewGroup = requireNotNull(view as? ViewGroup)

    private var conversationIconView: CachingIconView? = null
    private var expandBtn: View? = null
    private var titleView: View? = null
    private var headerTextSecondary: View? = null
    private var subText: View? = null
    private var facePileTop: View? = null
    private var facePileBottom: View? = null
    private var facePileBottomBg: View? = null
    override fun onContentUpdated(row: ExpandableNotificationRow?) {
        resolveViews()
        super.onContentUpdated(row)
    }

    private fun resolveViews() {
        conversationIconView = compactMessagingView.requireViewById(R.id.conversation_icon)
        titleView = compactMessagingView.findViewById(R.id.title)
        headerTextSecondary = compactMessagingView.findViewById(R.id.header_text_secondary)
        subText = compactMessagingView.findViewById(R.id.header_text)
        facePileTop = compactMessagingView.findViewById(R.id.conversation_face_pile_top)
        facePileBottom = compactMessagingView.findViewById(R.id.conversation_face_pile_bottom)
        facePileBottomBg =
            compactMessagingView.findViewById(R.id.conversation_face_pile_bottom_background)

        expandBtn = compactMessagingView.requireViewById(R.id.expand_button)
    }

    override fun updateTransformedTypes() {
        super.updateTransformedTypes()

        addViewsTransformingToSimilar(
            conversationIconView,
            titleView,
            headerTextSecondary,
            subText,
            facePileTop,
            facePileBottom,
            facePileBottomBg,
            expandBtn,
        )
    }
}
