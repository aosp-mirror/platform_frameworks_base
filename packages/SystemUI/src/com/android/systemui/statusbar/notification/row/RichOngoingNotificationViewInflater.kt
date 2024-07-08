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

package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.shared.RichOngoingContentModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.statusbar.notification.row.shared.StopwatchContentModel
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel
import com.android.systemui.statusbar.notification.row.ui.view.TimerView
import com.android.systemui.statusbar.notification.row.ui.viewbinder.TimerViewBinder
import com.android.systemui.statusbar.notification.row.ui.viewmodel.RichOngoingViewModelComponent
import com.android.systemui.statusbar.notification.row.ui.viewmodel.TimerViewModel
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

fun interface DeferredContentViewBinder {
    fun setupContentViewBinder(): DisposableHandle
}

class InflatedContentViewHolder(val view: View, val binder: DeferredContentViewBinder)

/**
 * Interface which provides a [RichOngoingContentModel] for a given [Notification] when one is
 * applicable to the given style.
 */
interface RichOngoingNotificationViewInflater {
    fun inflateView(
        contentModel: RichOngoingContentModel,
        existingView: View?,
        entry: NotificationEntry,
        systemUiContext: Context,
        parentView: ViewGroup,
    ): InflatedContentViewHolder?
}

@SysUISingleton
class RichOngoingNotificationViewInflaterImpl
@Inject
constructor(
    private val viewModelComponentFactory: RichOngoingViewModelComponent.Factory,
) : RichOngoingNotificationViewInflater {

    override fun inflateView(
        contentModel: RichOngoingContentModel,
        existingView: View?,
        entry: NotificationEntry,
        systemUiContext: Context,
        parentView: ViewGroup,
    ): InflatedContentViewHolder? {
        if (RichOngoingNotificationFlag.isUnexpectedlyInLegacyMode()) return null
        val component = viewModelComponentFactory.create(entry)
        return when (contentModel) {
            is TimerContentModel ->
                inflateTimerView(
                    existingView,
                    component::createTimerViewModel,
                    systemUiContext,
                    parentView
                )
            is StopwatchContentModel -> TODO("Not yet implemented")
        }
    }

    private fun inflateTimerView(
        existingView: View?,
        createViewModel: () -> TimerViewModel,
        systemUiContext: Context,
        parentView: ViewGroup,
    ): InflatedContentViewHolder? {
        if (existingView is TimerView && !existingView.isReinflateNeeded()) return null
        val newView =
            LayoutInflater.from(systemUiContext)
                .inflate(
                    R.layout.rich_ongoing_timer_notification,
                    parentView,
                    /* attachToRoot= */ false
                ) as TimerView
        return InflatedContentViewHolder(newView) {
            TimerViewBinder.bindWhileAttached(newView, createViewModel())
        }
    }
}
