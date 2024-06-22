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

package com.android.systemui.communal.widgets

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.IWindowManager
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.theme.PlatformTheme
import com.android.internal.logging.UiEventLogger
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.ui.compose.CommunalHub
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.communal.util.WidgetPickerIntentUtils.getWidgetExtraFromIntent
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** An Activity for editing the widgets that appear in hub mode. */
class EditWidgetsActivity
@Inject
constructor(
    private val communalViewModel: CommunalEditModeViewModel,
    private var windowManagerService: IWindowManager? = null,
    private val uiEventLogger: UiEventLogger,
    private val widgetConfiguratorFactory: WidgetConfigurationController.Factory,
    @CommunalLog logBuffer: LogBuffer,
) : ComponentActivity() {
    companion object {
        private const val TAG = "EditWidgetsActivity"
        private const val EXTRA_IS_PENDING_WIDGET_DRAG = "is_pending_widget_drag"
        const val EXTRA_PRESELECTED_KEY = "preselected_key"
        const val EXTRA_OPEN_WIDGET_PICKER_ON_START = "open_widget_picker_on_start"
    }

    private val logger = Logger(logBuffer, "EditWidgetsActivity")

    private val widgetConfigurator by lazy { widgetConfiguratorFactory.create(this) }

    private var shouldOpenWidgetPickerOnStart = false

    private val addWidgetActivityLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(StartActivityForResult()) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_WIDGET_PICKER_SHOWN)

                    result.data?.let { intent ->
                        val isPendingWidgetDrag =
                            intent.getBooleanExtra(EXTRA_IS_PENDING_WIDGET_DRAG, false)
                        // Nothing to do when a widget is being dragged & dropped. The drop
                        // target in the communal grid will receive the widget to be added (if
                        // the user drops it over).
                        if (!isPendingWidgetDrag) {
                            val (componentName, user) = getWidgetExtraFromIntent(intent)
                            if (componentName != null && user != null) {
                                communalViewModel.onAddWidget(
                                    componentName,
                                    user,
                                    0,
                                    widgetConfigurator
                                )
                            } else {
                                run { Log.w(TAG, "No AppWidgetProviderInfo found in result.") }
                            }
                        }
                    }
                        ?: run { Log.w(TAG, "No data in result.") }
                }
                else ->
                    Log.w(
                        TAG,
                        "Failed to receive result from widget picker, code=${result.resultCode}"
                    )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listenForTransitionAndChangeScene()

        communalViewModel.setEditModeOpen(true)

        val windowInsetsController = window.decorView.windowInsetsController
        windowInsetsController?.hide(WindowInsets.Type.systemBars())
        window.setDecorFitsSystemWindows(false)

        val preselectedKey = intent.getStringExtra(EXTRA_PRESELECTED_KEY)
        shouldOpenWidgetPickerOnStart =
            intent.getBooleanExtra(EXTRA_OPEN_WIDGET_PICKER_ON_START, false)

        communalViewModel.setSelectedKey(preselectedKey)

        setContent {
            PlatformTheme {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(LocalAndroidColorScheme.current.onSecondaryFixed),
                ) {
                    CommunalHub(
                        viewModel = communalViewModel,
                        onOpenWidgetPicker = ::onOpenWidgetPicker,
                        widgetConfigurator = widgetConfigurator,
                        onEditDone = ::onEditDone,
                    )
                }
            }
        }
    }

    // Handle scene change to show the activity and animate in its content
    private fun listenForTransitionAndChangeScene() {
        lifecycleScope.launch {
            communalViewModel.canShowEditMode.collect {
                communalViewModel.changeScene(
                    CommunalScenes.Blank,
                    CommunalTransitionKeys.ToEditMode
                )
                // wait till transitioned to Blank scene, then animate in communal content in
                // edit mode
                communalViewModel.currentScene.first { it == CommunalScenes.Blank }
                communalViewModel.setEditModeState(EditModeState.SHOWING)
            }
        }
    }

    private fun onOpenWidgetPicker() {
        lifecycleScope.launch {
            communalViewModel.onOpenWidgetPicker(
                resources,
                packageManager,
                addWidgetActivityLauncher
            )
        }
    }

    private fun onEditDone() {
        lifecycleScope.launch {
            communalViewModel.cleanupEditModeState()

            communalViewModel.changeScene(
                CommunalScenes.Communal,
                CommunalTransitionKeys.FromEditMode
            )

            // Wait for the current scene to be idle on communal.
            communalViewModel.isIdleOnCommunal.first { it }

            // Lock to go back to the hub after exiting.
            lockNow()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WidgetConfigurationController.REQUEST_CODE) {
            widgetConfigurator.setConfigurationResult(resultCode)
        }
    }

    override fun onStart() {
        super.onStart()

        if (shouldOpenWidgetPickerOnStart) {
            onOpenWidgetPicker()
            shouldOpenWidgetPickerOnStart = false
        }

        logger.i("Starting the communal widget editor activity")
        uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_EDIT_MODE_SHOWN)
    }

    override fun onStop() {
        super.onStop()

        logger.i("Stopping the communal widget editor activity")
        uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_EDIT_MODE_GONE)
    }

    override fun onDestroy() {
        super.onDestroy()
        communalViewModel.cleanupEditModeState()
        communalViewModel.setEditModeOpen(false)
    }

    private fun lockNow() {
        try {
            checkNotNull(windowManagerService).lockNow(/* options */ null)
        } catch (e: RemoteException) {
            Log.e(TAG, "Couldn't lock the device as WindowManager is dead.")
        }
    }
}
