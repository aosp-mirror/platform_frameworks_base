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

package com.android.systemui.statusbar.chips.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModel
import com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel.CastToOtherDeviceChipViewModel
import com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel.DemoRonChipViewModel
import com.android.systemui.statusbar.chips.ron.shared.StatusBarRonChips
import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * View model deciding which ongoing activity chip to show in the status bar.
 *
 * There may be multiple ongoing activities at the same time, but we can only ever show one chip at
 * any one time (for now). This class decides which ongoing activity to show if there are multiple.
 */
@SysUISingleton
class OngoingActivityChipsViewModel
@Inject
constructor(
    @Application scope: CoroutineScope,
    screenRecordChipViewModel: ScreenRecordChipViewModel,
    shareToAppChipViewModel: ShareToAppChipViewModel,
    castToOtherDeviceChipViewModel: CastToOtherDeviceChipViewModel,
    callChipViewModel: CallChipViewModel,
    demoRonChipViewModel: DemoRonChipViewModel,
    @StatusBarChipsLog private val logger: LogBuffer,
) {
    private enum class ChipType {
        ScreenRecord,
        ShareToApp,
        CastToOtherDevice,
        Call,
        /** A demo of a RON chip (rich ongoing notification chip), used just for testing. */
        DemoRon,
    }

    /** Model that helps us internally track the various chip states from each of the types. */
    private sealed interface InternalChipModel {
        /**
         * Represents that we've internally decided to show the chip with type [type] with the given
         * [model] information.
         */
        data class Shown(val type: ChipType, val model: OngoingActivityChipModel.Shown) :
            InternalChipModel

        /**
         * Represents that all chip types would like to be hidden. Each value specifies *how* that
         * chip type should get hidden.
         */
        data class Hidden(
            val screenRecord: OngoingActivityChipModel.Hidden,
            val shareToApp: OngoingActivityChipModel.Hidden,
            val castToOtherDevice: OngoingActivityChipModel.Hidden,
            val call: OngoingActivityChipModel.Hidden,
            val demoRon: OngoingActivityChipModel.Hidden,
        ) : InternalChipModel
    }

    private val internalChip: Flow<InternalChipModel> =
        combine(
            screenRecordChipViewModel.chip,
            shareToAppChipViewModel.chip,
            castToOtherDeviceChipViewModel.chip,
            callChipViewModel.chip,
            demoRonChipViewModel.chip,
        ) { screenRecord, shareToApp, castToOtherDevice, call, demoRon ->
            logger.log(
                TAG,
                LogLevel.INFO,
                {
                    str1 = screenRecord.logName
                    str2 = shareToApp.logName
                    str3 = castToOtherDevice.logName
                },
                { "Chips: ScreenRecord=$str1 > ShareToApp=$str2 > CastToOther=$str3..." },
            )
            logger.log(
                TAG,
                LogLevel.INFO,
                {
                    str1 = call.logName
                    str2 = demoRon.logName
                },
                { "... > Call=$str1 > DemoRon=$str2" }
            )
            // This `when` statement shows the priority order of the chips.
            when {
                // Screen recording also activates the media projection APIs, so whenever the
                // screen recording chip is active, the media projection chip would also be
                // active. We want the screen-recording-specific chip shown in this case, so we
                // give the screen recording chip priority. See b/296461748.
                screenRecord is OngoingActivityChipModel.Shown ->
                    InternalChipModel.Shown(ChipType.ScreenRecord, screenRecord)
                shareToApp is OngoingActivityChipModel.Shown ->
                    InternalChipModel.Shown(ChipType.ShareToApp, shareToApp)
                castToOtherDevice is OngoingActivityChipModel.Shown ->
                    InternalChipModel.Shown(ChipType.CastToOtherDevice, castToOtherDevice)
                call is OngoingActivityChipModel.Shown ->
                    InternalChipModel.Shown(ChipType.Call, call)
                demoRon is OngoingActivityChipModel.Shown -> {
                    StatusBarRonChips.assertInNewMode()
                    InternalChipModel.Shown(ChipType.DemoRon, demoRon)
                }
                else -> {
                    // We should only get here if all chip types are hidden
                    check(screenRecord is OngoingActivityChipModel.Hidden)
                    check(shareToApp is OngoingActivityChipModel.Hidden)
                    check(castToOtherDevice is OngoingActivityChipModel.Hidden)
                    check(call is OngoingActivityChipModel.Hidden)
                    check(demoRon is OngoingActivityChipModel.Hidden)
                    InternalChipModel.Hidden(
                        screenRecord = screenRecord,
                        shareToApp = shareToApp,
                        castToOtherDevice = castToOtherDevice,
                        call = call,
                        demoRon = demoRon,
                    )
                }
            }
        }

    /**
     * A flow modeling the chip that should be shown in the status bar after accounting for possibly
     * multiple ongoing activities and animation requirements.
     *
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment] is responsible for
     * actually displaying the chip.
     */
    val chip: StateFlow<OngoingActivityChipModel> =
        internalChip
            .pairwise(initialValue = DEFAULT_INTERNAL_HIDDEN_MODEL)
            .map { (old, new) ->
                if (old is InternalChipModel.Shown && new is InternalChipModel.Hidden) {
                    // If we're transitioning from showing the chip to hiding the chip, different
                    // chips require different animation behaviors. For example, the screen share
                    // chips shouldn't animate if the user stopped the screen share from the dialog
                    // (see b/353249803#comment4), but the call chip should always animate.
                    //
                    // This `when` block makes sure that when we're transitioning from Shown to
                    // Hidden, we check what chip type was previously showing and we use that chip
                    // type's hide animation behavior.
                    when (old.type) {
                        ChipType.ScreenRecord -> new.screenRecord
                        ChipType.ShareToApp -> new.shareToApp
                        ChipType.CastToOtherDevice -> new.castToOtherDevice
                        ChipType.Call -> new.call
                        ChipType.DemoRon -> new.demoRon
                    }
                } else if (new is InternalChipModel.Shown) {
                    // If we have a chip to show, always show it.
                    new.model
                } else {
                    // In the Hidden -> Hidden transition, it shouldn't matter which hidden model we
                    // choose because no animation should happen regardless.
                    OngoingActivityChipModel.Hidden()
                }
            }
            // Some of the chips could have timers in them and we don't want the start time
            // for those timers to get reset for any reason. So, as soon as any subscriber has
            // requested the chip information, we maintain it forever by using
            // [SharingStarted.Lazily]. See b/347726238.
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    companion object {
        private const val TAG = "ChipsViewModel"

        private val DEFAULT_INTERNAL_HIDDEN_MODEL =
            InternalChipModel.Hidden(
                screenRecord = OngoingActivityChipModel.Hidden(),
                shareToApp = OngoingActivityChipModel.Hidden(),
                castToOtherDevice = OngoingActivityChipModel.Hidden(),
                call = OngoingActivityChipModel.Hidden(),
                demoRon = OngoingActivityChipModel.Hidden(),
            )
    }
}
