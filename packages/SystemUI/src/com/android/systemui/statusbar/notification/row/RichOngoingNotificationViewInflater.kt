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
import com.android.systemui.statusbar.notification.row.ContentViewInflationResult.InflatedContentViewHolder
import com.android.systemui.statusbar.notification.row.ContentViewInflationResult.KeepExistingView
import com.android.systemui.statusbar.notification.row.ContentViewInflationResult.NullContentView
import com.android.systemui.statusbar.notification.row.shared.EnRouteContentModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingContentModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel
import com.android.systemui.statusbar.notification.row.ui.view.EnRouteView
import com.android.systemui.statusbar.notification.row.ui.view.TimerView
import com.android.systemui.statusbar.notification.row.ui.viewbinder.EnRouteViewBinder
import com.android.systemui.statusbar.notification.row.ui.viewbinder.TimerViewBinder
import com.android.systemui.statusbar.notification.row.ui.viewmodel.EnRouteViewModel
import com.android.systemui.statusbar.notification.row.ui.viewmodel.RichOngoingViewModelComponent
import com.android.systemui.statusbar.notification.row.ui.viewmodel.TimerViewModel
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

fun interface DeferredContentViewBinder {
    fun setupContentViewBinder(): DisposableHandle
}

enum class RichOngoingNotificationViewType {
    Contracted,
    Expanded,
    HeadsUp,
}

/**
 * * Supertype of the 3 different possible result types of
 *   [RichOngoingNotificationViewInflater.inflateView].
 */
sealed interface ContentViewInflationResult {

    /** Indicates that the content view should be removed if present. */
    data object NullContentView : ContentViewInflationResult

    /**
     * Indicates that the content view (which *must be* present) should be unmodified during this
     * inflation.
     */
    data object KeepExistingView : ContentViewInflationResult

    /**
     * Contains the new view and binder that should replace any existing content view for this slot.
     */
    data class InflatedContentViewHolder(val view: View, val binder: DeferredContentViewBinder) :
        ContentViewInflationResult
}

fun ContentViewInflationResult?.shouldDisposeViewBinder() = this !is KeepExistingView

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
        viewType: RichOngoingNotificationViewType,
    ): ContentViewInflationResult

    fun canKeepView(
        contentModel: RichOngoingContentModel,
        existingView: View?,
        viewType: RichOngoingNotificationViewType
    ): Boolean
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
        viewType: RichOngoingNotificationViewType,
    ): ContentViewInflationResult {
        if (RichOngoingNotificationFlag.isUnexpectedlyInLegacyMode()) return NullContentView
        val component = viewModelComponentFactory.create(entry)
        return when (contentModel) {
            is TimerContentModel ->
                inflateTimerView(
                    existingView,
                    component::createTimerViewModel,
                    systemUiContext,
                    parentView,
                    viewType
                )
            is EnRouteContentModel ->
                inflateEnRouteView(
                    existingView,
                    component::createEnRouteViewModel,
                    systemUiContext,
                    parentView,
                    viewType
                )
            else -> TODO("Not yet implemented")
        }
    }

    override fun canKeepView(
        contentModel: RichOngoingContentModel,
        existingView: View?,
        viewType: RichOngoingNotificationViewType
    ): Boolean {
        if (RichOngoingNotificationFlag.isUnexpectedlyInLegacyMode()) return false
        return when (contentModel) {
            is TimerContentModel -> canKeepTimerView(contentModel, existingView, viewType)
            is EnRouteContentModel -> canKeepEnRouteView(contentModel, existingView, viewType)
            else -> TODO("Not yet implemented")
        }
    }

    private fun inflateTimerView(
        existingView: View?,
        createViewModel: () -> TimerViewModel,
        systemUiContext: Context,
        parentView: ViewGroup,
        viewType: RichOngoingNotificationViewType,
    ): ContentViewInflationResult {
        if (existingView is TimerView && !existingView.isReinflateNeeded()) return KeepExistingView

        return when (viewType) {
            RichOngoingNotificationViewType.Contracted -> {
                val newView =
                    LayoutInflater.from(systemUiContext)
                        .inflate(
                            R.layout.rich_ongoing_timer_notification,
                            parentView,
                            /* attachToRoot= */ false
                        ) as TimerView
                InflatedContentViewHolder(newView) {
                    TimerViewBinder.bindWhileAttached(newView, createViewModel())
                }
            }
            RichOngoingNotificationViewType.Expanded,
            RichOngoingNotificationViewType.HeadsUp -> NullContentView
        }
    }

    private fun canKeepTimerView(
        contentModel: TimerContentModel,
        existingView: View?,
        viewType: RichOngoingNotificationViewType
    ): Boolean = true

    private fun inflateEnRouteView(
        existingView: View?,
        createViewModel: () -> EnRouteViewModel,
        systemUiContext: Context,
        parentView: ViewGroup,
        viewType: RichOngoingNotificationViewType,
    ): ContentViewInflationResult {
        if (existingView is EnRouteView && !existingView.isReinflateNeeded())
            return KeepExistingView
        return when (viewType) {
            RichOngoingNotificationViewType.Contracted -> {
                val newView =
                    LayoutInflater.from(systemUiContext)
                        .inflate(
                            R.layout.notification_template_en_route_contracted,
                            parentView,
                            /* attachToRoot= */ false
                        ) as EnRouteView
                InflatedContentViewHolder(newView) {
                    EnRouteViewBinder.bindWhileAttached(newView, createViewModel())
                }
            }
            RichOngoingNotificationViewType.Expanded -> {
                val newView =
                    LayoutInflater.from(systemUiContext)
                        .inflate(
                            R.layout.notification_template_en_route_expanded,
                            parentView,
                            /* attachToRoot= */ false
                        ) as EnRouteView
                InflatedContentViewHolder(newView) {
                    EnRouteViewBinder.bindWhileAttached(newView, createViewModel())
                }
            }
            RichOngoingNotificationViewType.HeadsUp -> NullContentView
        }
    }

    private fun canKeepEnRouteView(
        contentModel: EnRouteContentModel,
        existingView: View?,
        viewType: RichOngoingNotificationViewType
    ): Boolean = true
}
