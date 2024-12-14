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

import com.android.systemui.Flags
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
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
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

    private data class ChipBundle(
        val screenRecord: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
        val shareToApp: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
        val castToOtherDevice: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
        val call: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
        val demoRon: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
    )

    /** Bundles all the incoming chips into one object to easily pass to various flows. */
    private val incomingChipBundle =
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
                ChipBundle(
                    screenRecord = screenRecord,
                    shareToApp = shareToApp,
                    castToOtherDevice = castToOtherDevice,
                    call = call,
                    demoRon = demoRon,
                )
            }
            // Some of the chips could have timers in them and we don't want the start time
            // for those timers to get reset for any reason. So, as soon as any subscriber has
            // requested the chip information, we maintain it forever by using
            // [SharingStarted.Lazily]. See b/347726238.
            .stateIn(scope, SharingStarted.Lazily, ChipBundle())

    private val internalChip: Flow<InternalChipModel> =
        incomingChipBundle.map { bundle -> pickMostImportantChip(bundle).mostImportantChip }

    /**
     * A flow modeling the primary chip that should be shown in the status bar after accounting for
     * possibly multiple ongoing activities and animation requirements.
     *
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment] is responsible for
     * actually displaying the chip.
     */
    val primaryChip: StateFlow<OngoingActivityChipModel> =
        internalChip
            .pairwise(initialValue = DEFAULT_INTERNAL_HIDDEN_MODEL)
            .map { (old, new) -> createOutputModel(old, new) }
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    /**
     * Equivalent to [MultipleOngoingActivityChipsModel] but using the internal models to do some
     * state tracking before we get the final output.
     */
    private data class InternalMultipleOngoingActivityChipsModel(
        val primary: InternalChipModel,
        val secondary: InternalChipModel,
    )

    private val internalChips: Flow<InternalMultipleOngoingActivityChipsModel> =
        incomingChipBundle.map { bundle ->
            // First: Find the most important chip.
            val primaryChipResult = pickMostImportantChip(bundle)
            val primaryChip = primaryChipResult.mostImportantChip
            if (primaryChip is InternalChipModel.Hidden) {
                // If the primary chip is hidden, the secondary chip will also be hidden, so just
                // pass the same Hidden model for both.
                InternalMultipleOngoingActivityChipsModel(primaryChip, primaryChip)
            } else {
                // Then: Find the next most important chip.
                val secondaryChip =
                    pickMostImportantChip(primaryChipResult.remainingChips).mostImportantChip
                InternalMultipleOngoingActivityChipsModel(primaryChip, secondaryChip)
            }
        }

    /**
     * A flow modeling the primary chip that should be shown in the status bar after accounting for
     * possibly multiple ongoing activities and animation requirements.
     *
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment] is responsible for
     * actually displaying the chip.
     */
    val chips: StateFlow<MultipleOngoingActivityChipsModel> =
        if (!Flags.statusBarRonChips()) {
            // Multiple chips are only allowed with RONs. If the flag isn't on, use just the
            // primary chip.
            primaryChip
                .map {
                    MultipleOngoingActivityChipsModel(
                        primary = it,
                        secondary = OngoingActivityChipModel.Hidden(),
                    )
                }
                .stateIn(
                    scope,
                    SharingStarted.Lazily,
                    MultipleOngoingActivityChipsModel(),
                )
        } else {
            internalChips
                .pairwise(initialValue = DEFAULT_MULTIPLE_INTERNAL_HIDDEN_MODEL)
                .map { (old, new) ->
                    val correctPrimary = createOutputModel(old.primary, new.primary)
                    val correctSecondary = createOutputModel(old.secondary, new.secondary)
                    MultipleOngoingActivityChipsModel(correctPrimary, correctSecondary)
                }
                .stateIn(
                    scope,
                    SharingStarted.Lazily,
                    MultipleOngoingActivityChipsModel(),
                )
        }

    /** A data class representing the return result of [pickMostImportantChip]. */
    private data class MostImportantChipResult(
        val mostImportantChip: InternalChipModel,
        val remainingChips: ChipBundle,
    )

    /**
     * Finds the most important chip from the given [bundle].
     *
     * This function returns that most important chip, and it also returns any remaining chips that
     * still want to be shown after filtering out the most important chip.
     */
    private fun pickMostImportantChip(bundle: ChipBundle): MostImportantChipResult {
        // This `when` statement shows the priority order of the chips.
        return when {
            bundle.screenRecord is OngoingActivityChipModel.Shown ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Shown(ChipType.ScreenRecord, bundle.screenRecord),
                    remainingChips =
                        bundle.copy(
                            screenRecord = OngoingActivityChipModel.Hidden(),
                            // Screen recording also activates the media projection APIs, which
                            // means that whenever the screen recording chip is active, the
                            // share-to-app chip would also be active. (Screen recording is a
                            // special case of share-to-app, where the app receiving the share is
                            // specifically System UI.)
                            // We want only the screen-recording-specific chip to be shown in this
                            // case. If we did have screen recording as the primary chip, we need to
                            // suppress the share-to-app chip to make sure they don't both show.
                            // See b/296461748.
                            shareToApp = OngoingActivityChipModel.Hidden(),
                        )
                )
            bundle.shareToApp is OngoingActivityChipModel.Shown ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Shown(ChipType.ShareToApp, bundle.shareToApp),
                    remainingChips = bundle.copy(shareToApp = OngoingActivityChipModel.Hidden()),
                )
            bundle.castToOtherDevice is OngoingActivityChipModel.Shown ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Shown(
                            ChipType.CastToOtherDevice,
                            bundle.castToOtherDevice,
                        ),
                    remainingChips =
                        bundle.copy(castToOtherDevice = OngoingActivityChipModel.Hidden()),
                )
            bundle.call is OngoingActivityChipModel.Shown ->
                MostImportantChipResult(
                    mostImportantChip = InternalChipModel.Shown(ChipType.Call, bundle.call),
                    remainingChips = bundle.copy(call = OngoingActivityChipModel.Hidden()),
                )
            bundle.demoRon is OngoingActivityChipModel.Shown -> {
                StatusBarRonChips.assertInNewMode()
                MostImportantChipResult(
                    mostImportantChip = InternalChipModel.Shown(ChipType.DemoRon, bundle.demoRon),
                    remainingChips = bundle.copy(demoRon = OngoingActivityChipModel.Hidden()),
                )
            }
            else -> {
                // We should only get here if all chip types are hidden
                check(bundle.screenRecord is OngoingActivityChipModel.Hidden)
                check(bundle.shareToApp is OngoingActivityChipModel.Hidden)
                check(bundle.castToOtherDevice is OngoingActivityChipModel.Hidden)
                check(bundle.call is OngoingActivityChipModel.Hidden)
                check(bundle.demoRon is OngoingActivityChipModel.Hidden)
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Hidden(
                            screenRecord = bundle.screenRecord,
                            shareToApp = bundle.shareToApp,
                            castToOtherDevice = bundle.castToOtherDevice,
                            call = bundle.call,
                            demoRon = bundle.demoRon,
                        ),
                    // All the chips are already hidden, so no need to filter anything out of the
                    // bundle.
                    remainingChips = bundle,
                )
            }
        }
    }

    private fun createOutputModel(
        old: InternalChipModel,
        new: InternalChipModel,
    ): OngoingActivityChipModel {
        return if (old is InternalChipModel.Shown && new is InternalChipModel.Hidden) {
            // If we're transitioning from showing the chip to hiding the chip, different
            // chips require different animation behaviors. For example, the screen share
            // chips shouldn't animate if the user stopped the screen share from the dialog
            // (see b/353249803#comment4), but the call chip should always animate.
            //
            // This `when` block makes sure that when we're transitioning from Shown to
            // Hidden, we check what chip type was previously showing and we use that chip
            // type's hide animation behavior.
            return when (old.type) {
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

        private val DEFAULT_MULTIPLE_INTERNAL_HIDDEN_MODEL =
            InternalMultipleOngoingActivityChipsModel(
                primary = DEFAULT_INTERNAL_HIDDEN_MODEL,
                secondary = DEFAULT_INTERNAL_HIDDEN_MODEL,
            )
    }
}
