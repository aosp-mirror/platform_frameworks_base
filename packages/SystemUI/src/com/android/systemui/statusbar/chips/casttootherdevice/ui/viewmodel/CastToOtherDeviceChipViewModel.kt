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

package com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel

import android.content.Context
import androidx.annotation.DrawableRes
import com.android.internal.jank.Cuj
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.casttootherdevice.domain.interactor.MediaRouterChipInteractor
import com.android.systemui.statusbar.chips.casttootherdevice.domain.model.MediaRouterCastModel
import com.android.systemui.statusbar.chips.casttootherdevice.ui.view.EndCastScreenToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.casttootherdevice.ui.view.EndGenericCastToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickListener
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * View model for the cast-to-other-device chip, shown when a user is sharing content to a different
 * device. (Triggered from the Quick Settings Cast tile or from the Settings app.) The content could
 * either be the user's screen, or just the user's audio.
 */
@SysUISingleton
class CastToOtherDeviceChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val mediaProjectionChipInteractor: MediaProjectionChipInteractor,
    private val mediaRouterChipInteractor: MediaRouterChipInteractor,
    private val systemClock: SystemClock,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    @StatusBarChipsLog private val logger: LogBuffer,
) : OngoingActivityChipViewModel {
    /**
     * The cast chip to show, based only on MediaProjection API events.
     *
     * This chip will only be [OngoingActivityChipModel.Shown] when the user is casting their
     * *screen*. If the user is only casting audio, this chip will be
     * [OngoingActivityChipModel.Hidden].
     */
    private val projectionChip: StateFlow<OngoingActivityChipModel> =
        mediaProjectionChipInteractor.projection
            .map { projectionModel ->
                when (projectionModel) {
                    is ProjectionChipModel.NotProjecting -> OngoingActivityChipModel.Hidden()
                    is ProjectionChipModel.Projecting -> {
                        if (projectionModel.type != ProjectionChipModel.Type.CAST_TO_OTHER_DEVICE) {
                            OngoingActivityChipModel.Hidden()
                        } else {
                            createCastScreenToOtherDeviceChip(projectionModel)
                        }
                    }
                }
            }
            // See b/347726238 for [SharingStarted.Lazily] reasoning.
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    /**
     * The cast chip to show, based only on MediaRouter API events.
     *
     * This chip will be [OngoingActivityChipModel.Shown] when the user is casting their screen *or*
     * their audio.
     *
     * The MediaProjection APIs are not invoked for casting *only audio* to another device because
     * MediaProjection is only concerned with *screen* sharing (see b/342169876). We listen to
     * MediaRouter APIs here to cover audio-only casting.
     *
     * Note that this means we will start showing the cast chip before the casting actually starts,
     * for **both** audio-only casting and screen casting. MediaRouter is aware of all
     * cast-to-other-device events, and MediaRouter immediately marks a device as "connecting" once
     * a user selects what device they'd like to cast to, even if they haven't hit "Start casting"
     * yet. All of SysUI considers "connecting" devices to be casting (see
     * [com.android.systemui.statusbar.policy.CastDevice.isCasting]), so the chip will follow the
     * same convention and start showing once a device is selected. See b/269975671.
     */
    private val routerChip =
        mediaRouterChipInteractor.mediaRouterCastingState
            .map { routerModel ->
                when (routerModel) {
                    is MediaRouterCastModel.DoingNothing -> OngoingActivityChipModel.Hidden()
                    is MediaRouterCastModel.Casting -> {
                        // A consequence of b/269975671 is that MediaRouter will mark a device as
                        // casting before casting has actually started. To alleviate this bug a bit,
                        // we won't show a timer for MediaRouter events. That way, we won't show a
                        // timer if cast hasn't actually started.
                        //
                        // This does mean that the audio-only casting chip will *never* show a
                        // timer, because audio-only casting never activates the MediaProjection
                        // APIs and those are the only cast APIs that show a timer.
                        createIconOnlyCastChip(routerModel.deviceName)
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden())

    private val internalChip: StateFlow<OngoingActivityChipModel> =
        combine(projectionChip, routerChip) { projection, router ->
                logger.log(
                    TAG,
                    LogLevel.INFO,
                    {
                        str1 = projection.logName
                        str2 = router.logName
                    },
                    { "projectionChip=$str1 > routerChip=$str2" }
                )

                // A consequence of b/269975671 is that MediaRouter and MediaProjection APIs fire at
                // different times when *screen* casting:
                //
                // 1. When the user chooses what device to cast to, the MediaRouter APIs mark the
                // device as casting (even though casting hasn't actually started yet). At this
                // point, `routerChip` is [OngoingActivityChipModel.Shown] but `projectionChip` is
                // [OngoingActivityChipModel.Hidden], and we'll show the router chip.
                //
                // 2. Once casting has actually started, the MediaProjection APIs become aware of
                // the device. At this point, both `routerChip` and `projectionChip` are
                // [OngoingActivityChipModel.Shown].
                //
                // Because the MediaProjection APIs have activated, we know that the user is screen
                // casting (not audio casting). We need to switch to using `projectionChip` because
                // that chip will show information specific to screen casting. The `projectionChip`
                // will also show a timer, as opposed to `routerChip`'s icon-only display.
                if (projection is OngoingActivityChipModel.Shown) {
                    projection
                } else {
                    router
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden())

    private val hideChipDuringDialogTransitionHelper = ChipTransitionHelper(scope)

    override val chip: StateFlow<OngoingActivityChipModel> =
        hideChipDuringDialogTransitionHelper.createChipFlow(internalChip)

    /** Stops the currently active projection. */
    private fun stopProjectingFromDialog() {
        logger.log(TAG, LogLevel.INFO, {}, { "Stop casting requested from dialog (projection)" })
        hideChipDuringDialogTransitionHelper.onActivityStoppedFromDialog()
        mediaProjectionChipInteractor.stopProjecting()
    }

    /** Stops the currently active media route. */
    private fun stopMediaRouterCastingFromDialog() {
        logger.log(TAG, LogLevel.INFO, {}, { "Stop casting requested from dialog (router)" })
        hideChipDuringDialogTransitionHelper.onActivityStoppedFromDialog()
        mediaRouterChipInteractor.stopCasting()
    }

    private fun createCastScreenToOtherDeviceChip(
        state: ProjectionChipModel.Projecting,
    ): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown.Timer(
            icon =
                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                    Icon.Resource(
                        CAST_TO_OTHER_DEVICE_ICON,
                        // This string is "Casting screen"
                        ContentDescription.Resource(
                            R.string.cast_screen_to_other_device_chip_accessibility_label,
                        ),
                    )
                ),
            colors = ColorsModel.Red,
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            createDialogLaunchOnClickListener(
                createCastScreenToOtherDeviceDialogDelegate(state),
                dialogTransitionAnimator,
                DialogCuj(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP, tag = "Cast to other device"),
                logger,
                TAG,
            ),
        )
    }

    private fun createIconOnlyCastChip(deviceName: String?): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown.IconOnly(
            icon =
                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                    Icon.Resource(
                        CAST_TO_OTHER_DEVICE_ICON,
                        // This string is just "Casting"
                        ContentDescription.Resource(R.string.accessibility_casting),
                    )
                ),
            colors = ColorsModel.Red,
            createDialogLaunchOnClickListener(
                createGenericCastToOtherDeviceDialogDelegate(deviceName),
                dialogTransitionAnimator,
                DialogCuj(
                    Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP,
                    tag = "Cast to other device audio only",
                ),
                logger,
                TAG,
            ),
        )
    }

    private fun createCastScreenToOtherDeviceDialogDelegate(
        state: ProjectionChipModel.Projecting,
    ) =
        EndCastScreenToOtherDeviceDialogDelegate(
            endMediaProjectionDialogHelper,
            context,
            stopAction = this::stopProjectingFromDialog,
            state,
        )

    private fun createGenericCastToOtherDeviceDialogDelegate(deviceName: String?) =
        EndGenericCastToOtherDeviceDialogDelegate(
            endMediaProjectionDialogHelper,
            context,
            deviceName,
            stopAction = this::stopMediaRouterCastingFromDialog,
        )

    companion object {
        @DrawableRes val CAST_TO_OTHER_DEVICE_ICON = R.drawable.ic_cast_connected
        private const val TAG = "CastToOtherVM"
    }
}
