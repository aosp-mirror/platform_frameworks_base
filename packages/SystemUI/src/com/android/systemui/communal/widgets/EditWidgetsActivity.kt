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

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.content.IntentSender
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
import com.android.systemui.Flags.communalEditWidgetsActivityFinishFix
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.ui.compose.CommunalHub
import com.android.systemui.communal.ui.view.layout.sections.CommunalAppWidgetSection
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.communal.util.WidgetPickerIntentUtils.getWidgetExtraFromIntent
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.scene.shared.flag.SceneContainerFlag
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
    private val widgetSection: CommunalAppWidgetSection,
    @CommunalLog logBuffer: LogBuffer,
) : ComponentActivity() {
    companion object {
        private const val TAG = "EditWidgetsActivity"
        private const val EXTRA_IS_PENDING_WIDGET_DRAG = "is_pending_widget_drag"
        const val EXTRA_OPEN_WIDGET_PICKER_ON_START = "open_widget_picker_on_start"
    }

    /**
     * [ActivityController] handles closing the activity in the case it is backgrounded without
     * waiting for an activity result
     */
    interface ActivityController {
        /**
         * Invoked when waiting for an activity result changes, either initiating such wait or
         * finishing due to the return of a result.
         */
        fun onWaitingForResult(waitingForResult: Boolean) {}

        /** Set the visibility of the activity under control. */
        fun setActivityFullyVisible(fullyVisible: Boolean) {}
    }

    /**
     * A nop ActivityController to be use when the communalEditWidgetsActivityFinishFix flag is
     * false.
     */
    class NopActivityController : ActivityController

    /**
     * A functional ActivityController to be used when the communalEditWidgetsActivityFinishFix flag
     * is true.
     */
    class ActivityControllerImpl(activity: Activity) : ActivityController {
        companion object {
            private const val STATE_EXTRA_IS_WAITING_FOR_RESULT = "extra_is_waiting_for_result"
        }

        private var waitingForResult = false
        private var activityFullyVisible = false

        init {
            activity.registerActivityLifecycleCallbacks(
                object : ActivityLifecycleCallbacks {
                    override fun onActivityCreated(
                        activity: Activity,
                        savedInstanceState: Bundle?
                    ) {
                        waitingForResult =
                            savedInstanceState?.getBoolean(STATE_EXTRA_IS_WAITING_FOR_RESULT)
                                ?: false
                    }

                    override fun onActivityStarted(activity: Activity) {
                        // Nothing to implement.
                    }

                    override fun onActivityResumed(activity: Activity) {
                        // Nothing to implement.
                    }

                    override fun onActivityPaused(activity: Activity) {
                        // Nothing to implement.
                    }

                    override fun onActivityStopped(activity: Activity) {
                        // If we're not backgrounded due to waiting for a result (either widget
                        // selection or configuration), and we are fully visible, then finish the
                        // activity.
                        if (
                            !waitingForResult &&
                                activityFullyVisible &&
                                !activity.isChangingConfigurations
                        ) {
                            activity.finish()
                        }
                    }

                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                        outState.putBoolean(STATE_EXTRA_IS_WAITING_FOR_RESULT, waitingForResult)
                    }

                    override fun onActivityDestroyed(activity: Activity) {
                        // Nothing to implement.
                    }
                }
            )
        }

        override fun onWaitingForResult(waitingForResult: Boolean) {
            this.waitingForResult = waitingForResult
        }

        override fun setActivityFullyVisible(fullyVisible: Boolean) {
            activityFullyVisible = fullyVisible
        }
    }

    private val logger = Logger(logBuffer, "EditWidgetsActivity")

    private val widgetConfigurator by lazy { widgetConfiguratorFactory.create(this) }

    private var shouldOpenWidgetPickerOnStart = false

    private val activityController: ActivityController =
        if (communalEditWidgetsActivityFinishFix()) ActivityControllerImpl(this)
        else NopActivityController()

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
                                // Add widget at the end.
                                communalViewModel.onAddWidget(
                                    componentName,
                                    user,
                                    configurator = widgetConfigurator,
                                )
                            } else {
                                run { Log.w(TAG, "No AppWidgetProviderInfo found in result.") }
                            }
                        }
                    } ?: run { Log.w(TAG, "No data in result.") }
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

        activityController.setActivityFullyVisible(false)
        communalViewModel.setEditModeOpen(true)

        val windowInsetsController = window.decorView.windowInsetsController
        windowInsetsController?.hide(WindowInsets.Type.systemBars())
        window.setDecorFitsSystemWindows(false)

        shouldOpenWidgetPickerOnStart =
            intent.getBooleanExtra(EXTRA_OPEN_WIDGET_PICKER_ON_START, false)

        setContent {
            PlatformTheme {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(LocalAndroidColorScheme.current.surfaceDim),
                ) {
                    CommunalHub(
                        viewModel = communalViewModel,
                        onOpenWidgetPicker = ::onOpenWidgetPicker,
                        widgetConfigurator = widgetConfigurator,
                        onEditDone = ::onEditDone,
                        widgetSection = widgetSection,
                    )
                }
            }
        }
    }

    // Handle scene change to show the activity and animate in its content
    private fun listenForTransitionAndChangeScene() {
        lifecycleScope.launch {
            communalViewModel.canShowEditMode.collect {
                if (!SceneContainerFlag.isEnabled) {
                    communalViewModel.changeScene(
                        scene = CommunalScenes.Blank,
                        loggingReason = "edit mode opening",
                        transitionKey = CommunalTransitionKeys.ToEditMode,
                        keyguardState = KeyguardState.GONE,
                    )
                    // wait till transitioned to Blank scene, then animate in communal content in
                    // edit mode
                    communalViewModel.currentScene.first { it == CommunalScenes.Blank }
                }

                communalViewModel.setEditModeState(EditModeState.SHOWING)

                // Inform the ActivityController that we are now fully visible.
                activityController.setActivityFullyVisible(true)

                // Show the widget picker, if necessary, after the edit activity has animated in.
                // Waiting until after the activity has appeared avoids transitions issues.
                if (shouldOpenWidgetPickerOnStart) {
                    onOpenWidgetPicker()
                    shouldOpenWidgetPickerOnStart = false
                }
            }
        }
    }

    private fun onOpenWidgetPicker() {
        lifecycleScope.launch {
            communalViewModel.onOpenWidgetPicker(resources, addWidgetActivityLauncher)
        }
    }

    private fun onEditDone() {
        lifecycleScope.launch {
            communalViewModel.cleanupEditModeState()

            communalViewModel.changeScene(
                scene = CommunalScenes.Communal,
                loggingReason = "edit mode closing",
                transitionKey = CommunalTransitionKeys.FromEditMode
            )

            // Wait for the current scene to be idle on communal.
            communalViewModel.isIdleOnCommunal.first { it }

            // Lock to go back to the hub after exiting.
            lockNow()
            finish()
        }
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        activityController.onWaitingForResult(true)
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startIntentSenderForResult(
        intent: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ) {
        activityController.onWaitingForResult(true)
        super.startIntentSenderForResult(
            intent,
            requestCode,
            fillInIntent,
            flagsMask,
            flagsValues,
            extraFlags,
            options
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        activityController.onWaitingForResult(false)
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WidgetConfigurationController.REQUEST_CODE) {
            widgetConfigurator.setConfigurationResult(resultCode)
        }
    }

    override fun onStart() {
        super.onStart()

        communalViewModel.setEditActivityShowing(true)

        logger.i("Starting the communal widget editor activity")
        uiEventLogger.log(CommunalUiEvent.COMMUNAL_HUB_EDIT_MODE_SHOWN)
    }

    override fun onStop() {
        super.onStop()
        communalViewModel.setEditActivityShowing(false)

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
