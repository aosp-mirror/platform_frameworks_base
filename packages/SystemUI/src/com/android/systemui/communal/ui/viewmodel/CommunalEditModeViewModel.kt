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
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.android.internal.logging.UiEventLogger
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
    private val uiEventLogger: UiEventLogger,
    @CommunalLog logBuffer: LogBuffer,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : BaseCommunalViewModel(communalSceneInteractor, communalInteractor, mediaHost) {

    private val logger = Logger(logBuffer, "CommunalEditModeViewModel")

    override val isEditMode = true

    // Only widgets are editable.
    override val communalContent: Flow<List<CommunalContentModel>> =
        communalInteractor.widgetContent.onEach { models ->
            logger.d({ "Content updated: $str1" }) { str1 = models.joinToString { it.key } }
        }

    private val _reorderingWidgets = MutableStateFlow(false)

    override val reorderingWidgets: StateFlow<Boolean>
        get() = _reorderingWidgets

    override fun onDeleteWidget(id: Int) = communalInteractor.deleteWidget(id)

    override fun onReorderWidgets(widgetIdToPriorityMap: Map<Int, Int>) =
        communalInteractor.updateWidgetOrder(widgetIdToPriorityMap)

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

    val isIdleOnCommunal: StateFlow<Boolean> = communalInteractor.isIdleOnCommunal

    /** Launch the widget picker activity using the given {@link ActivityResultLauncher}. */
    suspend fun onOpenWidgetPicker(
        resources: Resources,
        packageManager: PackageManager,
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
            getWidgetPickerActivityIntent(resources, packageManager, excludeList)?.let {
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
        packageManager: PackageManager,
        excludeList: ArrayList<AppWidgetProviderInfo>
    ): Intent? {
        val packageName =
            getLauncherPackageName(packageManager)
                ?: run {
                    Log.e(TAG, "Couldn't resolve launcher package name")
                    return@getWidgetPickerActivityIntent null
                }

        return Intent(Intent.ACTION_PICK).apply {
            setPackage(packageName)
            putExtra(
                AppWidgetManager.EXTRA_CATEGORY_FILTER,
                communalSettingsInteractor.communalWidgetCategories.value
            )
            putExtra(EXTRA_UI_SURFACE_KEY, EXTRA_UI_SURFACE_VALUE)
            putParcelableArrayListExtra(EXTRA_ADDED_APP_WIDGETS_KEY, excludeList)
        }
    }

    private fun getLauncherPackageName(packageManager: PackageManager): String? {
        return packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).also { it.addCategory(Intent.CATEGORY_HOME) },
                PackageManager.MATCH_DEFAULT_ONLY
            )
            ?.activityInfo
            ?.packageName
    }

    /** Sets whether edit mode is currently open */
    fun setEditModeOpen(isOpen: Boolean) = communalInteractor.setEditModeOpen(isOpen)

    companion object {
        private const val TAG = "CommunalEditModeViewModel"

        private const val EXTRA_UI_SURFACE_KEY = "ui_surface"
        private const val EXTRA_UI_SURFACE_VALUE = "widgets_hub"
        const val EXTRA_ADDED_APP_WIDGETS_KEY = "added_app_widgets"
    }
}
