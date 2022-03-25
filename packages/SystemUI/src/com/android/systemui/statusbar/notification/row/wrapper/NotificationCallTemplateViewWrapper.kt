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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper

import android.content.Context
import android.view.View
import com.android.internal.widget.CachingIconView
import com.android.internal.widget.CallLayout
import com.android.systemui.R
import com.android.systemui.statusbar.notification.NotificationFadeAware
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

/**
 * Wraps a notification containing a call template
 */
class NotificationCallTemplateViewWrapper constructor(
    ctx: Context,
    view: View,
    row: ExpandableNotificationRow
) : NotificationTemplateViewWrapper(ctx, view, row) {

    private val minHeightWithActions: Int =
            NotificationUtils.getFontScaledHeight(ctx, R.dimen.notification_max_height)
    private val callLayout: CallLayout = view as CallLayout

    private lateinit var conversationIconContainer: View
    private lateinit var conversationIconView: CachingIconView
    private lateinit var conversationBadgeBg: View
    private lateinit var expandBtn: View
    private lateinit var appName: View
    private lateinit var conversationTitleView: View

    private fun resolveViews() {
        with(callLayout) {
            conversationIconContainer =
                    requireViewById(com.android.internal.R.id.conversation_icon_container)
            conversationIconView = requireViewById(com.android.internal.R.id.conversation_icon)
            conversationBadgeBg =
                    requireViewById(com.android.internal.R.id.conversation_icon_badge_bg)
            expandBtn = requireViewById(com.android.internal.R.id.expand_button)
            appName = requireViewById(com.android.internal.R.id.app_name_text)
            conversationTitleView = requireViewById(com.android.internal.R.id.conversation_text)
        }
    }

    override fun onContentUpdated(row: ExpandableNotificationRow) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveViews()
        super.onContentUpdated(row)
    }

    override fun updateTransformedTypes() {
        // This also clears the existing types
        super.updateTransformedTypes()
        addTransformedViews(
                appName,
                conversationTitleView
        )
        addViewsTransformingToSimilar(
                conversationIconView,
                conversationBadgeBg,
                expandBtn
        )
    }

    override fun disallowSingleClick(x: Float, y: Float): Boolean {
        val isOnExpandButton = expandBtn.visibility == View.VISIBLE &&
                isOnView(expandBtn, x, y)
        return isOnExpandButton || super.disallowSingleClick(x, y)
    }

    override fun getMinLayoutHeight(): Int = minHeightWithActions

    /**
     * Apply the faded state as a layer type change to the face pile view which needs to have
     * overlapping contents render precisely.
     */
    override fun setNotificationFaded(faded: Boolean) {
        // Do not call super
        NotificationFadeAware.setLayerTypeForFaded(expandBtn, faded)
        NotificationFadeAware.setLayerTypeForFaded(conversationIconContainer, faded)
    }
}
