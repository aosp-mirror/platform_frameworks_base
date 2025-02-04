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

package com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.Cuj
import com.android.systemui.CoreStartable
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.sharetoapp.ui.view.EndGenericShareToAppDialogDelegate
import com.android.systemui.statusbar.chips.sharetoapp.ui.view.EndShareScreenToAppDialogDelegate
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickCallback
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickListener
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * View model for the share-to-app chip, shown when sharing your phone screen content to another app
 * on the same device. (Triggered from within each individual app.)
 */
@SysUISingleton
class ShareToAppChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val mediaProjectionChipInteractor: MediaProjectionChipInteractor,
    private val systemClock: SystemClock,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    @StatusBarChipsLog private val logger: LogBuffer,
) : OngoingActivityChipViewModel, CoreStartable {

    private val _stopDialogToShow: MutableStateFlow<MediaProjectionStopDialogModel> =
        MutableStateFlow(MediaProjectionStopDialogModel.Hidden)

    /**
     * Represents the current state of the media projection stop dialog. Emits
     * [MediaProjectionStopDialogModel.Shown] when the dialog should be displayed, and
     * [MediaProjectionStopDialogModel.Hidden] when it is dismissed.
     */
    val stopDialogToShow: StateFlow<MediaProjectionStopDialogModel> =
        _stopDialogToShow.asStateFlow()

    /**
     * Emits a [MediaProjectionStopDialogModel] based on the current projection state when a
     * projectionStartedDuringCallAndActivePostCallEvent event is emitted. If projecting, determines
     * the appropriate dialog type to show. Otherwise, emits a hidden dialog state.
     */
    private val stopDialogDueToCallEndedState: StateFlow<MediaProjectionStopDialogModel> =
        mediaProjectionChipInteractor.projectionStartedDuringCallAndActivePostCallEvent
            .sample(mediaProjectionChipInteractor.projection) { _, currentProjection ->
                when (currentProjection) {
                    is ProjectionChipModel.NotProjecting -> MediaProjectionStopDialogModel.Hidden
                    is ProjectionChipModel.Projecting -> {
                        when (currentProjection.receiver) {
                            ProjectionChipModel.Receiver.ShareToApp -> {
                                when (currentProjection.contentType) {
                                    ProjectionChipModel.ContentType.Screen ->
                                        createShareScreenToAppStopDialog(currentProjection)
                                    ProjectionChipModel.ContentType.Audio ->
                                        createGenericShareScreenToAppStopDialog()
                                }
                            }
                            ProjectionChipModel.Receiver.CastToOtherDevice ->
                                MediaProjectionStopDialogModel.Hidden
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), MediaProjectionStopDialogModel.Hidden)

    /**
     * Initializes background flow collector during SysUI startup for events determining the
     * visibility of media projection stop dialogs.
     */
    override fun start() {
        if (com.android.media.projection.flags.Flags.showStopDialogPostCallEnd()) {
            scope.launch {
                stopDialogDueToCallEndedState.collect { event -> _stopDialogToShow.value = event }
            }
        }
    }

    private val internalChip =
        mediaProjectionChipInteractor.projection
            .map { projectionModel ->
                when (projectionModel) {
                    is ProjectionChipModel.NotProjecting -> OngoingActivityChipModel.Hidden()
                    is ProjectionChipModel.Projecting -> {
                        when (projectionModel.receiver) {
                            ProjectionChipModel.Receiver.ShareToApp -> {
                                when (projectionModel.contentType) {
                                    ProjectionChipModel.ContentType.Screen ->
                                        createShareScreenToAppChip(projectionModel)
                                    ProjectionChipModel.ContentType.Audio ->
                                        createIconOnlyShareToAppChip()
                                }
                            }
                            ProjectionChipModel.Receiver.CastToOtherDevice ->
                                OngoingActivityChipModel.Hidden()
                        }
                    }
                }
            }
            // See b/347726238 for [SharingStarted.Lazily] reasoning.
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    private val chipTransitionHelper = ChipTransitionHelper(scope)

    override val chip: StateFlow<OngoingActivityChipModel> =
        combine(chipTransitionHelper.createChipFlow(internalChip), stopDialogToShow) {
                currentChip,
                stopDialog ->
                if (
                    com.android.media.projection.flags.Flags.showStopDialogPostCallEnd() &&
                        stopDialog is MediaProjectionStopDialogModel.Shown
                ) {
                    logger.log(
                        TAG,
                        LogLevel.INFO,
                        {},
                        { "Hiding the chip as stop dialog is being shown" },
                    )
                    OngoingActivityChipModel.Hidden()
                } else {
                    currentChip
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden())

    /**
     * Notifies this class that the user just stopped a screen recording from the dialog that's
     * shown when you tap the recording chip.
     */
    fun onRecordingStoppedFromDialog() {
        // When a screen recording is active, share-to-app is also active (screen recording is just
        // a special case of share-to-app, where the specific app receiving the share is System UI).
        // When a screen recording is stopped, we immediately hide the screen recording chip in
        // [com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel].
        // We *also* need to immediately hide the share-to-app chip so it doesn't briefly show.
        // See b/350891338.
        chipTransitionHelper.onActivityStoppedFromDialog()
    }

    /** Called when the stop dialog is dismissed or cancelled. */
    private fun onStopDialogDismissed() {
        logger.log(TAG, LogLevel.INFO, {}, { "The media projection stop dialog was dismissed" })
        _stopDialogToShow.value = MediaProjectionStopDialogModel.Hidden
    }

    /** Stops the currently active projection. */
    private fun stopProjectingFromDialog() {
        logger.log(TAG, LogLevel.INFO, {}, { "Stop sharing requested from dialog" })
        chipTransitionHelper.onActivityStoppedFromDialog()
        mediaProjectionChipInteractor.stopProjecting()
    }

    private fun createShareScreenToAppStopDialog(
        projectionModel: ProjectionChipModel.Projecting
    ): MediaProjectionStopDialogModel {
        val dialogDelegate = createShareScreenToAppDialogDelegate(projectionModel)
        return MediaProjectionStopDialogModel.Shown(
            dialogDelegate,
            onDismissAction = ::onStopDialogDismissed,
        )
    }

    private fun createGenericShareScreenToAppStopDialog(): MediaProjectionStopDialogModel {
        val dialogDelegate = createGenericShareToAppDialogDelegate()
        return MediaProjectionStopDialogModel.Shown(
            dialogDelegate,
            onDismissAction = ::onStopDialogDismissed,
        )
    }

    private fun createShareScreenToAppChip(
        state: ProjectionChipModel.Projecting
    ): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown.Timer(
            key = KEY,
            icon =
                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                    Icon.Resource(
                        SHARE_TO_APP_ICON,
                        ContentDescription.Resource(R.string.share_to_app_chip_accessibility_label),
                    )
                ),
            colors = ColorsModel.Red,
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            onClickListenerLegacy =
                createDialogLaunchOnClickListener(
                    createShareScreenToAppDialogDelegate(state),
                    dialogTransitionAnimator,
                    DIALOG_CUJ,
                    logger,
                    TAG,
                ),
            clickBehavior =
                OngoingActivityChipModel.ClickBehavior.ExpandAction(
                    onClick =
                        createDialogLaunchOnClickCallback(
                            createShareScreenToAppDialogDelegate(state),
                            dialogTransitionAnimator,
                            DIALOG_CUJ,
                            logger,
                            TAG,
                        )
                ),
        )
    }

    private fun createIconOnlyShareToAppChip(): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown.IconOnly(
            key = KEY,
            icon =
                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                    Icon.Resource(
                        SHARE_TO_APP_ICON,
                        ContentDescription.Resource(
                            R.string.share_to_app_chip_accessibility_label_generic
                        ),
                    )
                ),
            colors = ColorsModel.Red,
            onClickListenerLegacy =
                createDialogLaunchOnClickListener(
                    createGenericShareToAppDialogDelegate(),
                    dialogTransitionAnimator,
                    DIALOG_CUJ_AUDIO_ONLY,
                    logger,
                    TAG,
                ),
            clickBehavior =
                OngoingActivityChipModel.ClickBehavior.ExpandAction(
                    createDialogLaunchOnClickCallback(
                        createGenericShareToAppDialogDelegate(),
                        dialogTransitionAnimator,
                        DIALOG_CUJ_AUDIO_ONLY,
                        logger,
                        TAG,
                    )
                ),
        )
    }

    private fun createShareScreenToAppDialogDelegate(state: ProjectionChipModel.Projecting) =
        EndShareScreenToAppDialogDelegate(
            endMediaProjectionDialogHelper,
            context,
            stopAction = this::stopProjectingFromDialog,
            state,
        )

    private fun createGenericShareToAppDialogDelegate() =
        EndGenericShareToAppDialogDelegate(
            endMediaProjectionDialogHelper,
            context,
            stopAction = this::stopProjectingFromDialog,
        )

    companion object {
        @VisibleForTesting const val KEY = "ShareToApp"
        @DrawableRes val SHARE_TO_APP_ICON = R.drawable.ic_present_to_all
        private val DIALOG_CUJ =
            DialogCuj(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP, tag = "Share to app")
        private val DIALOG_CUJ_AUDIO_ONLY =
            DialogCuj(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP, tag = "Share to app audio only")
        private val TAG = "ShareToAppVM".pad()
    }
}
