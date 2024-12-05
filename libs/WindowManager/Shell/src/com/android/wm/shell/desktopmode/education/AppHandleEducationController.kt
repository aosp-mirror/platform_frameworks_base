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

package com.android.wm.shell.desktopmode.education

import android.annotation.DimenRes
import android.annotation.StringRes
import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.os.SystemProperties
import android.util.Slog
import androidx.core.content.withStyledAttributes
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.desktopmode.CaptionState
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository
import com.android.wm.shell.desktopmode.education.data.AppHandleEducationDatastoreRepository
import com.android.wm.shell.shared.annotations.ShellBackgroundThread
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus.canEnterDesktopMode
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.common.Theme
import com.android.wm.shell.windowdecor.education.DesktopWindowingEducationTooltipController
import com.android.wm.shell.windowdecor.education.DesktopWindowingEducationTooltipController.TooltipColorScheme
import com.android.wm.shell.windowdecor.education.DesktopWindowingEducationTooltipController.TooltipEducationViewConfig
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch

/**
 * Controls app handle education end to end.
 *
 * Listen to the user trigger for app handle education, calls an api to check if the education
 * should be shown and controls education UI.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class AppHandleEducationController(
    private val context: Context,
    private val appHandleEducationFilter: AppHandleEducationFilter,
    private val appHandleEducationDatastoreRepository: AppHandleEducationDatastoreRepository,
    private val windowDecorCaptionHandleRepository: WindowDecorCaptionHandleRepository,
    private val windowingEducationViewController: DesktopWindowingEducationTooltipController,
    @ShellMainThread private val applicationCoroutineScope: CoroutineScope,
    @ShellBackgroundThread private val backgroundDispatcher: MainCoroutineDispatcher,
) {
    private val decorThemeUtil = DecorThemeUtil(context)
    private lateinit var openHandleMenuCallback: (Int) -> Unit
    private lateinit var toDesktopModeCallback: (Int, DesktopModeTransitionSource) -> Unit

    init {
        runIfEducationFeatureEnabled {
            applicationCoroutineScope.launch {
                // Central block handling the app handle's educational flow end-to-end.
                isAppHandleHintViewedFlow()
                    .flatMapLatest { isAppHandleHintViewed ->
                        if (isAppHandleHintViewed) {
                            // If the education is viewed then return emptyFlow() that completes
                            // immediately.
                            // This will help us to not listen to [captionHandleStateFlow] after the
                            // education
                            // has been viewed already.
                            emptyFlow()
                        } else {
                            // Listen for changes to window decor's caption handle.
                            windowDecorCaptionHandleRepository.captionStateFlow
                                // Wait for few seconds before emitting the latest state.
                                .debounce(APP_HANDLE_EDUCATION_DELAY_MILLIS)
                                .filter { captionState ->
                                    captionState is CaptionState.AppHandle &&
                                        appHandleEducationFilter.shouldShowAppHandleEducation(
                                            captionState
                                        )
                                }
                        }
                    }
                    .flowOn(backgroundDispatcher)
                    .collectLatest { captionState ->
                        val tooltipColorScheme = tooltipColorScheme(captionState)

                        showEducation(captionState, tooltipColorScheme)
                        // After showing first tooltip, mark education as viewed
                        appHandleEducationDatastoreRepository
                            .updateAppHandleHintViewedTimestampMillis(true)
                    }
            }

            applicationCoroutineScope.launch {
                if (isAppHandleHintUsed()) return@launch
                windowDecorCaptionHandleRepository.captionStateFlow
                    .filter { captionState ->
                        captionState is CaptionState.AppHandle && captionState.isHandleMenuExpanded
                    }
                    .take(1)
                    .flowOn(backgroundDispatcher)
                    .collect {
                        // If user expands app handle, mark user has used the app handle hint
                        appHandleEducationDatastoreRepository
                            .updateAppHandleHintUsedTimestampMillis(true)
                    }
            }
        }
    }

    private inline fun runIfEducationFeatureEnabled(block: () -> Unit) {
        if (canEnterDesktopMode(context) && Flags.enableDesktopWindowingAppHandleEducation())
            block()
    }

    private fun showEducation(captionState: CaptionState, tooltipColorScheme: TooltipColorScheme) {
        val appHandleBounds = (captionState as CaptionState.AppHandle).globalAppHandleBounds
        val tooltipGlobalCoordinates =
            Point(appHandleBounds.left + appHandleBounds.width() / 2, appHandleBounds.bottom)
        // TODO: b/370546801 - Differentiate between user dismissing the tooltip vs following the
        // cue.
        // Populate information important to inflate app handle education tooltip.
        val appHandleTooltipConfig =
            TooltipEducationViewConfig(
                tooltipViewLayout = R.layout.desktop_windowing_education_top_arrow_tooltip,
                tooltipColorScheme = tooltipColorScheme,
                tooltipViewGlobalCoordinates = tooltipGlobalCoordinates,
                tooltipText = getString(R.string.windowing_app_handle_education_tooltip),
                arrowDirection =
                    DesktopWindowingEducationTooltipController.TooltipArrowDirection.UP,
                onEducationClickAction = {
                    launchWithExceptionHandling {
                        showWindowingImageButtonTooltip(tooltipColorScheme)
                    }
                    openHandleMenuCallback(captionState.runningTaskInfo.taskId)
                },
                onDismissAction = {
                    launchWithExceptionHandling {
                        showWindowingImageButtonTooltip(tooltipColorScheme)
                    }
                },
            )

        windowingEducationViewController.showEducationTooltip(
            tooltipViewConfig = appHandleTooltipConfig,
            taskId = captionState.runningTaskInfo.taskId,
        )
    }

    /** Show tooltip that points to windowing image button in app handle menu */
    private suspend fun showWindowingImageButtonTooltip(tooltipColorScheme: TooltipColorScheme) {
        val appInfoPillHeight = getSize(R.dimen.desktop_mode_handle_menu_app_info_pill_height)
        val windowingOptionPillHeight =
            getSize(R.dimen.desktop_mode_handle_menu_windowing_pill_height)
        val appHandleMenuWidth =
            getSize(R.dimen.desktop_mode_handle_menu_width) +
                getSize(R.dimen.desktop_mode_handle_menu_pill_spacing_margin)
        val appHandleMenuMargins =
            getSize(R.dimen.desktop_mode_handle_menu_margin_top) +
                getSize(R.dimen.desktop_mode_handle_menu_pill_spacing_margin)

        windowDecorCaptionHandleRepository.captionStateFlow
            // After the first tooltip was dismissed, wait for 400 ms and see if the app handle menu
            // has been expanded.
            .timeout(APP_HANDLE_EDUCATION_TIMEOUT_MILLIS.milliseconds)
            .catchTimeoutAndLog {
                // TODO: b/341320146 - Log previous tooltip was dismissed
            }
            // Wait for few milliseconds before emitting the latest state.
            .debounce(APP_HANDLE_EDUCATION_DELAY_MILLIS)
            .filter { captionState ->
                // Filter out states when app handle is not visible or not expanded.
                captionState is CaptionState.AppHandle && captionState.isHandleMenuExpanded
            }
            // Before showing this tooltip, stop listening to further emissions to avoid
            // accidentally
            // showing the same tooltip on future emissions.
            .take(1)
            .flowOn(backgroundDispatcher)
            .collectLatest { captionState ->
                captionState as CaptionState.AppHandle
                val appHandleBounds = captionState.globalAppHandleBounds
                val tooltipGlobalCoordinates =
                    Point(
                        appHandleBounds.left + appHandleBounds.width() / 2 + appHandleMenuWidth / 2,
                        appHandleBounds.top +
                            appHandleMenuMargins +
                            appInfoPillHeight +
                            windowingOptionPillHeight / 2,
                    )
                // Populate information important to inflate windowing image button education
                // tooltip.
                val windowingImageButtonTooltipConfig =
                    TooltipEducationViewConfig(
                        tooltipViewLayout = R.layout.desktop_windowing_education_left_arrow_tooltip,
                        tooltipColorScheme = tooltipColorScheme,
                        tooltipViewGlobalCoordinates = tooltipGlobalCoordinates,
                        tooltipText =
                            getString(
                                R.string.windowing_desktop_mode_image_button_education_tooltip
                            ),
                        arrowDirection =
                            DesktopWindowingEducationTooltipController.TooltipArrowDirection.LEFT,
                        onEducationClickAction = {
                            launchWithExceptionHandling {
                                showExitWindowingTooltip(tooltipColorScheme)
                            }
                            toDesktopModeCallback(
                                captionState.runningTaskInfo.taskId,
                                DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON,
                            )
                        },
                        onDismissAction = {
                            launchWithExceptionHandling {
                                showExitWindowingTooltip(tooltipColorScheme)
                            }
                        },
                    )

                windowingEducationViewController.showEducationTooltip(
                    taskId = captionState.runningTaskInfo.taskId,
                    tooltipViewConfig = windowingImageButtonTooltipConfig,
                )
            }
    }

    /** Show tooltip that points to app chip button and educates user on how to exit desktop mode */
    private suspend fun showExitWindowingTooltip(tooltipColorScheme: TooltipColorScheme) {
        windowDecorCaptionHandleRepository.captionStateFlow
            // After the previous tooltip was dismissed, wait for 400 ms and see if the user entered
            // desktop mode.
            .timeout(APP_HANDLE_EDUCATION_TIMEOUT_MILLIS.milliseconds)
            .catchTimeoutAndLog {
                // TODO: b/341320146 - Log previous tooltip was dismissed
            }
            // Wait for few milliseconds before emitting the latest state.
            .debounce(APP_HANDLE_EDUCATION_DELAY_MILLIS)
            .filter { captionState ->
                // Filter out states when app header is not visible or expanded.
                captionState is CaptionState.AppHeader && !captionState.isHeaderMenuExpanded
            }
            // Before showing this tooltip, stop listening to further emissions to avoid
            // accidentally
            // showing the same tooltip on future emissions.
            .take(1)
            .flowOn(backgroundDispatcher)
            .collectLatest { captionState ->
                captionState as CaptionState.AppHeader
                val globalAppChipBounds = captionState.globalAppChipBounds
                val tooltipGlobalCoordinates =
                    Point(
                        globalAppChipBounds.right,
                        globalAppChipBounds.top + globalAppChipBounds.height() / 2,
                    )
                // Populate information important to inflate exit desktop mode education tooltip.
                val exitWindowingTooltipConfig =
                    TooltipEducationViewConfig(
                        tooltipViewLayout = R.layout.desktop_windowing_education_left_arrow_tooltip,
                        tooltipColorScheme = tooltipColorScheme,
                        tooltipViewGlobalCoordinates = tooltipGlobalCoordinates,
                        tooltipText =
                            getString(R.string.windowing_desktop_mode_exit_education_tooltip),
                        arrowDirection =
                            DesktopWindowingEducationTooltipController.TooltipArrowDirection.LEFT,
                        onDismissAction = {},
                        onEducationClickAction = {
                            openHandleMenuCallback(captionState.runningTaskInfo.taskId)
                        },
                    )
                windowingEducationViewController.showEducationTooltip(
                    taskId = captionState.runningTaskInfo.taskId,
                    tooltipViewConfig = exitWindowingTooltipConfig,
                )
            }
    }

    private fun tooltipColorScheme(captionState: CaptionState): TooltipColorScheme {
        context.withStyledAttributes(
            set = null,
            attrs =
                intArrayOf(
                    com.android.internal.R.attr.materialColorOnTertiaryFixed,
                    com.android.internal.R.attr.materialColorTertiaryFixed,
                    com.android.internal.R.attr.materialColorTertiaryFixedDim,
                ),
            defStyleAttr = 0,
            defStyleRes = 0,
        ) {
            val onTertiaryFixed = getColor(/* index= */ 0, /* defValue= */ 0)
            val tertiaryFixed = getColor(/* index= */ 1, /* defValue= */ 0)
            val tertiaryFixedDim = getColor(/* index= */ 2, /* defValue= */ 0)
            val taskInfo = (captionState as CaptionState.AppHandle).runningTaskInfo

            val tooltipContainerColor =
                if (decorThemeUtil.getAppTheme(taskInfo) == Theme.LIGHT) {
                    tertiaryFixed
                } else {
                    tertiaryFixedDim
                }
            return TooltipColorScheme(tooltipContainerColor, onTertiaryFixed, onTertiaryFixed)
        }
        return TooltipColorScheme(0, 0, 0)
    }

    /**
     * Setup callbacks for app handle education tooltips.
     *
     * @param openHandleMenuCallback callback invoked to open app handle menu or app chip menu.
     * @param toDesktopModeCallback callback invoked to move task into desktop mode.
     */
    fun setAppHandleEducationTooltipCallbacks(
        openHandleMenuCallback: (taskId: Int) -> Unit,
        toDesktopModeCallback: (taskId: Int, DesktopModeTransitionSource) -> Unit,
    ) {
        this.openHandleMenuCallback = openHandleMenuCallback
        this.toDesktopModeCallback = toDesktopModeCallback
    }

    private inline fun <T> Flow<T>.catchTimeoutAndLog(crossinline block: () -> Unit) =
        catch { exception ->
            if (exception is TimeoutCancellationException) block() else throw exception
        }

    private fun launchWithExceptionHandling(block: suspend () -> Unit) =
        applicationCoroutineScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                Slog.e(TAG, "Error: ", e)
            }
        }

    /**
     * Listens to the changes to [WindowingEducationProto#hasAppHandleHintViewedTimestampMillis()]
     * in datastore proto object.
     *
     * If [SHOULD_OVERRIDE_EDUCATION_CONDITIONS] is true, this flow will always emit false. That
     * means it will always emit app handle hint has not been viewed yet.
     */
    private fun isAppHandleHintViewedFlow(): Flow<Boolean> =
        appHandleEducationDatastoreRepository.dataStoreFlow
            .map { preferences ->
                preferences.hasAppHandleHintViewedTimestampMillis() &&
                    !SHOULD_OVERRIDE_EDUCATION_CONDITIONS
            }
            .distinctUntilChanged()

    /**
     * Listens to the changes to [WindowingEducationProto#hasAppHandleHintUsedTimestampMillis()] in
     * datastore proto object.
     */
    private suspend fun isAppHandleHintUsed(): Boolean =
        appHandleEducationDatastoreRepository.dataStoreFlow
            .first()
            .hasAppHandleHintUsedTimestampMillis()

    private fun getSize(@DimenRes resourceId: Int): Int {
        if (resourceId == Resources.ID_NULL) return 0
        return context.resources.getDimensionPixelSize(resourceId)
    }

    private fun getString(@StringRes resId: Int): String = context.resources.getString(resId)

    companion object {
        const val TAG = "AppHandleEducationController"
        val APP_HANDLE_EDUCATION_DELAY_MILLIS: Long
            get() = SystemProperties.getLong("persist.windowing_app_handle_education_delay", 3000L)

        val APP_HANDLE_EDUCATION_TIMEOUT_MILLIS: Long
            get() = SystemProperties.getLong("persist.windowing_app_handle_education_timeout", 400L)

        val SHOULD_OVERRIDE_EDUCATION_CONDITIONS: Boolean
            get() =
                SystemProperties.getBoolean(
                    "persist.desktop_windowing_app_handle_education_override_conditions",
                    false,
                )
    }
}
