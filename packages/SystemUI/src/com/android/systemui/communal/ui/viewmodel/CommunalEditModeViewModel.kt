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

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.UserHandle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.ActivityResultLauncher
import com.android.internal.logging.UiEventLogger
import com.android.systemui.communal.dagger.CommunalModule.Companion.LAUNCHER_PACKAGE
import com.android.systemui.communal.data.model.CommunalWidgetCategories
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** The view model for communal hub in edit mode. */
@SysUISingleton
class CommunalEditModeViewModel
@Inject
constructor(
    communalSceneInteractor: CommunalSceneInteractor,
    private val communalInteractor: CommunalInteractor,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
    private val uiEventLogger: UiEventLogger,
    @CommunalLog logBuffer: LogBuffer,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val metricsLogger: CommunalMetricsLogger,
    @Application private val context: Context,
    private val accessibilityManager: AccessibilityManager,
    private val packageManager: PackageManager,
    @Named(LAUNCHER_PACKAGE) private val launcherPackage: String,
) : BaseCommunalViewModel(communalSceneInteractor, communalInteractor, mediaHost) {

    private val logger = Logger(logBuffer, "CommunalEditModeViewModel")

    override val isEditMode = true

    override val isCommunalContentVisible: Flow<Boolean> =
        communalSceneInteractor.editModeState.map { it == EditModeState.SHOWING }

    val showDisclaimer: Flow<Boolean> =
        allOf(isCommunalContentVisible, not(communalInteractor.isDisclaimerDismissed))

    fun onDisclaimerDismissed() {
        communalInteractor.setDisclaimerDismissed()
    }

    /**
     * Emits when edit mode activity can show, after we've transitioned to [KeyguardState.GONE] and
     * edit mode is open.
     */
    val canShowEditMode =
        allOf(
                keyguardTransitionInteractor.isFinishedIn(
                    scene = Scenes.Gone,
                    stateWithoutSceneContainer = KeyguardState.GONE
                ),
                communalInteractor.editModeOpen
            )
            .filter { it }

    // Only widgets are editable.
    override val communalContent: Flow<List<CommunalContentModel>> =
        communalInteractor.widgetContent.onEach { models ->
            logger.d({ "Content updated: $str1" }) { str1 = models.joinToString { it.key } }
        }

    private val _reorderingWidgets = MutableStateFlow(false)

    override val reorderingWidgets: StateFlow<Boolean>
        get() = _reorderingWidgets

    override fun onAddWidget(
        componentName: ComponentName,
        user: UserHandle,
        rank: Int?,
        configurator: WidgetConfigurator?
    ) {
        communalInteractor.addWidget(componentName, user, rank, configurator)
        metricsLogger.logAddWidget(componentName.flattenToString(), rank)
    }

    override fun onDeleteWidget(
        id: Int,
        componentName: ComponentName,
        rank: Int,
    ) {
        communalInteractor.deleteWidget(id)
        metricsLogger.logRemoveWidget(componentName.flattenToString(), rank)
    }

    override fun onReorderWidgets(widgetIdToRankMap: Map<Int, Int>) =
        communalInteractor.updateWidgetOrder(widgetIdToRankMap)

    override fun onReorderWidgetStart() {
        // Clear selection status
        setSelectedKey(null)
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

    override fun onNewWidgetAdded(provider: AppWidgetProviderInfo) {
        if (!accessibilityManager.isEnabled) {
            return
        }

        // Send an accessibility announcement for the newly added widget
        val widgetLabel = provider.loadLabel(packageManager)
        val announcementText =
            context.getString(
                R.string.accessibility_announcement_communal_widget_added,
                widgetLabel
            )
        accessibilityManager.sendAccessibilityEvent(
            AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                contentDescription = announcementText
            }
        )
    }

    val isIdleOnCommunal: StateFlow<Boolean> = communalInteractor.isIdleOnCommunal

    /** Launch the widget picker activity using the given {@link ActivityResultLauncher}. */
    suspend fun onOpenWidgetPicker(
        resources: Resources,
        activityLauncher: ActivityResultLauncher<Intent>
    ): Boolean =
        withContext(backgroundDispatcher) {
            val widgets = communalInteractor.widgetContent.first()
            val excludeList =
                widgets.filterIsInstance<CommunalContentModel.WidgetContent.Widget>().mapTo(
                    ArrayList()
                ) {
                    it.providerInfo
                }
            getWidgetPickerActivityIntent(resources, excludeList)?.let {
                try {
                    activityLauncher.launch(it)
                    return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch widget picker activity", e)
                }
            }
            false
        }

    private fun getWidgetPickerActivityIntent(
        resources: Resources,
        excludeList: ArrayList<AppWidgetProviderInfo>
    ): Intent? {
        return Intent(Intent.ACTION_PICK).apply {
            setPackage(launcherPackage)
            putExtra(
                EXTRA_DESIRED_WIDGET_WIDTH,
                resources.getDimensionPixelSize(R.dimen.communal_widget_picker_desired_width)
            )
            putExtra(
                EXTRA_DESIRED_WIDGET_HEIGHT,
                resources.getDimensionPixelSize(R.dimen.communal_widget_picker_desired_height)
            )
            putExtra(
                AppWidgetManager.EXTRA_CATEGORY_FILTER,
                CommunalWidgetCategories.defaultCategories
            )

            communalSettingsInteractor.workProfileUserDisallowedByDevicePolicy.value?.let {
                putExtra(EXTRA_USER_ID_FILTER, arrayListOf(it.id))
            }
            putExtra(EXTRA_UI_SURFACE_KEY, EXTRA_UI_SURFACE_VALUE)
            putExtra(EXTRA_PICKER_TITLE, resources.getString(R.string.communal_widget_picker_title))
            putExtra(
                EXTRA_PICKER_DESCRIPTION,
                resources.getString(R.string.communal_widget_picker_description)
            )
            putParcelableArrayListExtra(EXTRA_ADDED_APP_WIDGETS_KEY, excludeList)
        }
    }

    /** Sets whether edit mode is currently open */
    fun setEditModeOpen(isOpen: Boolean) = communalInteractor.setEditModeOpen(isOpen)

    /**
     * Sets whether the edit mode activity is currently showing.
     *
     * See [CommunalInteractor.editActivityShowing] for more info.
     */
    fun setEditActivityShowing(showing: Boolean) =
        communalInteractor.setEditActivityShowing(showing)

    /** Called when exiting the edit mode, before transitioning back to the communal scene. */
    fun cleanupEditModeState() {
        communalSceneInteractor.setEditModeState(null)

        // Set the scroll position of the glanceable hub to match where we are now.
        persistScrollPosition()
    }

    companion object {
        private const val TAG = "CommunalEditModeViewModel"

        private const val EXTRA_DESIRED_WIDGET_WIDTH = "desired_widget_width"
        private const val EXTRA_DESIRED_WIDGET_HEIGHT = "desired_widget_height"
        private const val EXTRA_PICKER_TITLE = "picker_title"
        private const val EXTRA_PICKER_DESCRIPTION = "picker_description"
        private const val EXTRA_UI_SURFACE_KEY = "ui_surface"
        private const val EXTRA_UI_SURFACE_VALUE = "widgets_hub"
        private const val EXTRA_USER_ID_FILTER = "filtered_user_ids"
        const val EXTRA_ADDED_APP_WIDGETS_KEY = "added_app_widgets"
    }
}
