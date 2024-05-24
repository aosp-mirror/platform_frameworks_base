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

package com.android.systemui.statusbar.notification.row.ui.viewbinder

import com.android.systemui.statusbar.notification.row.HybridConversationNotificationView
import com.android.systemui.statusbar.notification.row.HybridNotificationView
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleLineViewModel

object SingleLineConversationViewBinder {
    @JvmStatic
    fun bind(viewModel: SingleLineViewModel, view: HybridNotificationView?) {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) return
        if (view !is HybridConversationNotificationView || !viewModel.isConversation()) {
            SingleLineViewBinder.bind(viewModel, view)
            return
        }

        viewModel.conversationData?.avatar?.let { view.setAvatar(it) }
        view.setText(
            viewModel.titleText,
            viewModel.contentText,
            viewModel.conversationData?.conversationSenderName
        )
    }
}
