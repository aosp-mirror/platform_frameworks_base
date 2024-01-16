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

package com.android.systemui.communal.ui.viewmodel

import android.app.Activity
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.widget.RemoteViews
import com.android.internal.logging.UiEventLogger
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.util.nullableAtomicReference
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** The view model for communal hub in edit mode. */
@SysUISingleton
class CommunalEditModeViewModel
@Inject
constructor(
    private val communalInteractor: CommunalInteractor,
    private val appWidgetHost: AppWidgetHost,
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
    private val uiEventLogger: UiEventLogger,
) : BaseCommunalViewModel(communalInteractor, mediaHost) {

    private companion object {
        private const val KEY_SPLASH_SCREEN_STYLE = "android.activity.splashScreenStyle"
        private const val SPLASH_SCREEN_STYLE_EMPTY = 0
    }

    private val _widgetsToConfigure = MutableSharedFlow<Int>()

    /**
     * Flow emitting ids of widgets which need to be configured. The consumer of this flow should
     * trigger [startConfigurationActivity] to initiate configuration.
     */
    val widgetsToConfigure: Flow<Int> = _widgetsToConfigure

    private var pendingConfiguration: CompletableDeferred<Int>? by nullableAtomicReference()

    override val isEditMode = true

    // Only widgets are editable. The CTA tile comes last in the list and remains visible.
    override val communalContent: Flow<List<CommunalContentModel>> =
        communalInteractor.widgetContent
            // Clear the selected index when the list is updated.
            .onEach { setSelectedIndex(null) }
            .map { widgets -> widgets + listOf(CommunalContentModel.CtaTileInEditMode()) }

    private val _reorderingWidgets = MutableStateFlow(false)

    override val reorderingWidgets: StateFlow<Boolean>
        get() = _reorderingWidgets

    override fun onDeleteWidget(id: Int) = communalInteractor.deleteWidget(id)

    override fun onReorderWidgets(widgetIdToPriorityMap: Map<Int, Int>) =
        communalInteractor.updateWidgetOrder(widgetIdToPriorityMap)

    override fun getInteractionHandler(): RemoteViews.InteractionHandler {
        // Ignore all interactions in edit mode.
        return RemoteViews.InteractionHandler { _, _, _ -> false }
    }

    override fun onAddWidget(componentName: ComponentName, priority: Int) {
        if (pendingConfiguration != null) {
            throw IllegalStateException(
                "Cannot add $componentName widget while widget configuration is pending"
            )
        }
        super.onAddWidget(componentName, priority)
    }

    fun startConfigurationActivity(activity: Activity, widgetId: Int, requestCode: Int) {
        val options =
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        val bundle = options.toBundle()
        bundle.putInt(KEY_SPLASH_SCREEN_STYLE, SPLASH_SCREEN_STYLE_EMPTY)
        try {
            appWidgetHost.startAppWidgetConfigureActivityForResult(
                activity,
                widgetId,
                0,
                // Use the widget id as the request code.
                requestCode,
                bundle
            )
        } catch (e: ActivityNotFoundException) {
            setConfigurationResult(RESULT_CANCELED)
        }
    }

    override suspend fun configureWidget(widgetId: Int): Boolean {
        if (pendingConfiguration != null) {
            throw IllegalStateException(
                "Attempting to configure $widgetId while another configuration is already active"
            )
        }
        pendingConfiguration = CompletableDeferred()
        _widgetsToConfigure.emit(widgetId)
        val resultCode = pendingConfiguration?.await() ?: RESULT_CANCELED
        pendingConfiguration = null
        return resultCode == RESULT_OK
    }

    /** Sets the result of widget configuration. */
    fun setConfigurationResult(resultCode: Int) {
        pendingConfiguration?.complete(resultCode)
            ?: throw IllegalStateException("No widget pending configuration")
    }

    override fun onReorderWidgetStart() {
        // Clear selection status
        setSelectedIndex(null)
        _reorderingWidgets.value = true
        uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_REORDER_WIDGET_START)
    }

    override fun onReorderWidgetEnd() {
        _reorderingWidgets.value = false
        uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_REORDER_WIDGET_FINISH)
    }

    override fun onReorderWidgetCancel() {
        _reorderingWidgets.value = false
        uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_REORDER_WIDGET_CANCEL)
    }
}
