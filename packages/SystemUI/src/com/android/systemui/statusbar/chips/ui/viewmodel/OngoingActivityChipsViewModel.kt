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

import android.content.res.Configuration
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModel
import com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel.CastToOtherDeviceChipViewModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.notification.ui.viewmodel.NotifChipsViewModel
import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModelLegacy
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    @Background scope: CoroutineScope,
    screenRecordChipViewModel: ScreenRecordChipViewModel,
    shareToAppChipViewModel: ShareToAppChipViewModel,
    castToOtherDeviceChipViewModel: CastToOtherDeviceChipViewModel,
    callChipViewModel: CallChipViewModel,
    notifChipsViewModel: NotifChipsViewModel,
    displayStateInteractor: DisplayStateInteractor,
    configurationInteractor: ConfigurationInteractor,
    @StatusBarChipsLog private val logger: LogBuffer,
) {
    private val isLandscape: Flow<Boolean> =
        configurationInteractor.configurationValues
            .map { it.isLandscape }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val isScreenReasonablyLarge: Flow<Boolean> =
        combine(isLandscape, displayStateInteractor.isLargeScreen) { isLandscape, isLargeScreen ->
                isLandscape || isLargeScreen
            }
            .distinctUntilChanged()
            .onEach {
                logger.log(
                    TAG,
                    LogLevel.DEBUG,
                    { bool1 = it },
                    { "isScreenReasonablyLarge: $bool1" },
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private enum class ChipType {
        ScreenRecord,
        ShareToApp,
        CastToOtherDevice,
        Call,
        Notification,
    }

    /** Model that helps us internally track the various chip states from each of the types. */
    @Deprecated("Since StatusBarChipsModernization, this isn't used anymore")
    private sealed interface InternalChipModel {
        /**
         * Represents that we've internally decided to show the chip with type [type] with the given
         * [model] information.
         */
        data class Active(val type: ChipType, val model: OngoingActivityChipModel.Active) :
            InternalChipModel

        /**
         * Represents that all chip types would like to be hidden. Each value specifies *how* that
         * chip type should get hidden.
         */
        data class Inactive(
            val screenRecord: OngoingActivityChipModel.Inactive,
            val shareToApp: OngoingActivityChipModel.Inactive,
            val castToOtherDevice: OngoingActivityChipModel.Inactive,
            val call: OngoingActivityChipModel.Inactive,
            val notifs: OngoingActivityChipModel.Inactive,
        ) : InternalChipModel
    }

    private data class ChipBundle(
        val screenRecord: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val shareToApp: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val castToOtherDevice: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val call: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val notifs: List<OngoingActivityChipModel.Active> = emptyList(),
    )

    /** Bundles all the incoming chips into one object to easily pass to various flows. */
    private val incomingChipBundle =
        combine(
                screenRecordChipViewModel.chip,
                shareToAppChipViewModel.chip,
                castToOtherDeviceChipViewModel.chip,
                callChipViewModel.chip,
                notifChipsViewModel.chips,
            ) { screenRecord, shareToApp, castToOtherDevice, call, notifs ->
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
                        // TODO(b/364653005): Log other information for notification chips.
                        str2 = notifs.map { it.logName }.toString()
                    },
                    { "... > Call=$str1 > Notifs=$str2" },
                )
                ChipBundle(
                    screenRecord = screenRecord,
                    shareToApp = shareToApp,
                    castToOtherDevice = castToOtherDevice,
                    call = call,
                    notifs = notifs,
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
            .pairwise(initialValue = DEFAULT_INTERNAL_INACTIVE_MODEL)
            .map { (old, new) -> createOutputModel(old, new) }
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Inactive())

    /**
     * Equivalent to [MultipleOngoingActivityChipsModelLegacy] but using the internal models to do
     * some state tracking before we get the final output.
     */
    @Deprecated("Since StatusBarChipsModernization, this isn't used anymore")
    private data class InternalMultipleOngoingActivityChipsModel(
        val primary: InternalChipModel,
        val secondary: InternalChipModel,
    )

    private val internalChips: Flow<InternalMultipleOngoingActivityChipsModel> =
        combine(incomingChipBundle, isScreenReasonablyLarge) { bundle, isScreenReasonablyLarge ->
            // First: Find the most important chip.
            val primaryChipResult = pickMostImportantChip(bundle)
            when (val primaryChip = primaryChipResult.mostImportantChip) {
                is InternalChipModel.Inactive -> {
                    // If the primary chip is hidden, the secondary chip will also be hidden, so
                    // just pass the same Hidden model for both.
                    InternalMultipleOngoingActivityChipsModel(primaryChip, primaryChip)
                }
                is InternalChipModel.Active -> {
                    // Otherwise: Find the next most important chip.
                    val secondaryChip =
                        pickMostImportantChip(primaryChipResult.remainingChips).mostImportantChip
                    if (
                        secondaryChip is InternalChipModel.Active &&
                            StatusBarNotifChips.isEnabled &&
                            !StatusBarChipsModernization.isEnabled &&
                            !isScreenReasonablyLarge
                    ) {
                        // If we have two showing chips and we don't have a ton of room
                        // (!isScreenReasonablyLarge), then we want to make both of them as small as
                        // possible so that we have the highest chance of showing both chips (as
                        // opposed to showing the primary chip with a lot of text and completely
                        // hiding the secondary chip).
                        // Also: If StatusBarChipsModernization is enabled, then we'll do the
                        // squishing in Compose instead.
                        InternalMultipleOngoingActivityChipsModel(
                            primaryChip.squish(),
                            secondaryChip.squish(),
                        )
                    } else {
                        InternalMultipleOngoingActivityChipsModel(primaryChip, secondaryChip)
                    }
                }
            }
        }

    /** Squishes the chip down to the smallest content possible. */
    private fun InternalChipModel.Active.squish(): InternalChipModel.Active {
        return when (model) {
            // Icon-only is already maximum squished
            is OngoingActivityChipModel.Active.IconOnly -> this
            // Countdown shows just a single digit, so already maximum squished
            is OngoingActivityChipModel.Active.Countdown -> this
            // The other chips have icon+text, so we should hide the text
            is OngoingActivityChipModel.Active.Timer,
            is OngoingActivityChipModel.Active.ShortTimeDelta,
            is OngoingActivityChipModel.Active.Text ->
                InternalChipModel.Active(this.type, this.model.toIconOnly())
        }
    }

    private fun OngoingActivityChipModel.Active.toIconOnly(): OngoingActivityChipModel.Active {
        // If this chip doesn't have an icon, then it only has text and we should continue showing
        // its text. (This is theoretically impossible because
        // [OngoingActivityChipModel.Active.Countdown] is the only chip without an icon, but protect
        // against it just in case.)
        val currentIcon = icon ?: return this
        return OngoingActivityChipModel.Active.IconOnly(
            key,
            currentIcon,
            colors,
            onClickListenerLegacy,
            clickBehavior,
        )
    }

    /**
     * A flow modeling the active and inactive chips as well as which should be shown in the status
     * bar after accounting for possibly multiple ongoing activities and animation requirements.
     */
    val chips: StateFlow<MultipleOngoingActivityChipsModel> =
        if (StatusBarChipsModernization.isEnabled) {
            incomingChipBundle
                .map { bundle -> rankChips(bundle) }
                .stateIn(scope, SharingStarted.Lazily, MultipleOngoingActivityChipsModel())
        } else {
            MutableStateFlow(MultipleOngoingActivityChipsModel()).asStateFlow()
        }

    /**
     * A flow modeling the primary chip that should be shown in the status bar after accounting for
     * possibly multiple ongoing activities and animation requirements.
     *
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment] is responsible for
     * actually displaying the chip.
     *
     * Deprecated: since StatusBarChipsModernization, use the new [chips] instead.
     */
    val chipsLegacy: StateFlow<MultipleOngoingActivityChipsModelLegacy> =
        if (StatusBarChipsModernization.isEnabled) {
            MutableStateFlow(MultipleOngoingActivityChipsModelLegacy()).asStateFlow()
        } else if (!StatusBarNotifChips.isEnabled) {
            // Multiple chips are only allowed with notification chips. If the flag isn't on, use
            // just the primary chip.
            primaryChip
                .map {
                    MultipleOngoingActivityChipsModelLegacy(
                        primary = it,
                        secondary = OngoingActivityChipModel.Inactive(),
                    )
                }
                .stateIn(scope, SharingStarted.Lazily, MultipleOngoingActivityChipsModelLegacy())
        } else {
            internalChips
                .pairwise(initialValue = DEFAULT_MULTIPLE_INTERNAL_INACTIVE_MODEL)
                .map { (old, new) ->
                    val correctPrimary = createOutputModel(old.primary, new.primary)
                    val correctSecondary = createOutputModel(old.secondary, new.secondary)
                    MultipleOngoingActivityChipsModelLegacy(correctPrimary, correctSecondary)
                }
                .stateIn(scope, SharingStarted.Lazily, MultipleOngoingActivityChipsModelLegacy())
        }

    /**
     * Sort the given chip [bundle] in order of priority, and divide the chips between active,
     * overflow, and inactive (see [MultipleOngoingActivityChipsModel] for a description of each).
     */
    private fun rankChips(bundle: ChipBundle): MultipleOngoingActivityChipsModel {
        val activeChips = mutableListOf<OngoingActivityChipModel.Active>()
        val overflowChips = mutableListOf<OngoingActivityChipModel.Active>()
        val inactiveChips = mutableListOf<OngoingActivityChipModel.Inactive>()

        val sortedChips =
            mutableListOf(
                    bundle.screenRecord,
                    bundle.shareToApp,
                    bundle.castToOtherDevice,
                    bundle.call,
                )
                .apply { bundle.notifs.forEach { add(it) } }

        var shownSlotsRemaining = MAX_VISIBLE_CHIPS
        for (chip in sortedChips) {
            when (chip) {
                is OngoingActivityChipModel.Active -> {
                    // Screen recording also activates the media projection APIs, which means that
                    // whenever the screen recording chip is active, the share-to-app chip would
                    // also be active. (Screen recording is a special case of share-to-app, where
                    // the app receiving the share is specifically System UI.)
                    // We want only the screen-recording-specific chip to be shown in this case. If
                    // we did have screen recording as the primary chip, we need to suppress the
                    // share-to-app chip to make sure they don't both show.
                    // See b/296461748.
                    val suppressShareToApp =
                        chip == bundle.shareToApp &&
                            bundle.screenRecord is OngoingActivityChipModel.Active
                    if (shownSlotsRemaining > 0 && !suppressShareToApp) {
                        activeChips.add(chip)
                        if (!chip.isHidden) shownSlotsRemaining--
                    } else {
                        overflowChips.add(chip)
                    }
                }

                is OngoingActivityChipModel.Inactive -> inactiveChips.add(chip)
            }
        }

        return MultipleOngoingActivityChipsModel(activeChips, overflowChips, inactiveChips)
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
            bundle.screenRecord is OngoingActivityChipModel.Active ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Active(ChipType.ScreenRecord, bundle.screenRecord),
                    remainingChips =
                        bundle.copy(
                            screenRecord = OngoingActivityChipModel.Inactive(),
                            // Screen recording also activates the media projection APIs, which
                            // means that whenever the screen recording chip is active, the
                            // share-to-app chip would also be active. (Screen recording is a
                            // special case of share-to-app, where the app receiving the share is
                            // specifically System UI.)
                            // We want only the screen-recording-specific chip to be shown in this
                            // case. If we did have screen recording as the primary chip, we need to
                            // suppress the share-to-app chip to make sure they don't both show.
                            // See b/296461748.
                            shareToApp = OngoingActivityChipModel.Inactive(),
                        ),
                )
            bundle.shareToApp is OngoingActivityChipModel.Active ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Active(ChipType.ShareToApp, bundle.shareToApp),
                    remainingChips = bundle.copy(shareToApp = OngoingActivityChipModel.Inactive()),
                )
            bundle.castToOtherDevice is OngoingActivityChipModel.Active ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Active(
                            ChipType.CastToOtherDevice,
                            bundle.castToOtherDevice,
                        ),
                    remainingChips =
                        bundle.copy(castToOtherDevice = OngoingActivityChipModel.Inactive()),
                )
            bundle.call is OngoingActivityChipModel.Active ->
                MostImportantChipResult(
                    mostImportantChip = InternalChipModel.Active(ChipType.Call, bundle.call),
                    remainingChips = bundle.copy(call = OngoingActivityChipModel.Inactive()),
                )
            bundle.notifs.isNotEmpty() ->
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Active(ChipType.Notification, bundle.notifs.first()),
                    remainingChips =
                        bundle.copy(notifs = bundle.notifs.subList(1, bundle.notifs.size)),
                )
            else -> {
                // We should only get here if all chip types are hidden
                check(bundle.screenRecord is OngoingActivityChipModel.Inactive)
                check(bundle.shareToApp is OngoingActivityChipModel.Inactive)
                check(bundle.castToOtherDevice is OngoingActivityChipModel.Inactive)
                check(bundle.call is OngoingActivityChipModel.Inactive)
                check(bundle.notifs.isEmpty())
                MostImportantChipResult(
                    mostImportantChip =
                        InternalChipModel.Inactive(
                            screenRecord = bundle.screenRecord,
                            shareToApp = bundle.shareToApp,
                            castToOtherDevice = bundle.castToOtherDevice,
                            call = bundle.call,
                            notifs = OngoingActivityChipModel.Inactive(),
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
        return if (old is InternalChipModel.Active && new is InternalChipModel.Inactive) {
            // If we're transitioning from showing the chip to hiding the chip, different
            // chips require different animation behaviors. For example, the screen share
            // chips shouldn't animate if the user stopped the screen share from the dialog
            // (see b/353249803#comment4), but the call chip should always animate.
            //
            // This `when` block makes sure that when we're transitioning from Active to
            // Inactive, we check what chip type was previously showing and we use that chip
            // type's hide animation behavior.
            return when (old.type) {
                ChipType.ScreenRecord -> new.screenRecord
                ChipType.ShareToApp -> new.shareToApp
                ChipType.CastToOtherDevice -> new.castToOtherDevice
                ChipType.Call -> new.call
                ChipType.Notification -> new.notifs
            }
        } else if (new is InternalChipModel.Active) {
            // If we have a chip to show, always show it.
            new.model
        } else {
            // In the Hidden -> Hidden transition, it shouldn't matter which hidden model we
            // choose because no animation should happen regardless.
            OngoingActivityChipModel.Inactive()
        }
    }

    private val Configuration.isLandscape: Boolean
        get() = orientation == Configuration.ORIENTATION_LANDSCAPE

    companion object {
        private val TAG = "ChipsViewModel".pad()

        private val DEFAULT_INTERNAL_INACTIVE_MODEL =
            InternalChipModel.Inactive(
                screenRecord = OngoingActivityChipModel.Inactive(),
                shareToApp = OngoingActivityChipModel.Inactive(),
                castToOtherDevice = OngoingActivityChipModel.Inactive(),
                call = OngoingActivityChipModel.Inactive(),
                notifs = OngoingActivityChipModel.Inactive(),
            )

        private val DEFAULT_MULTIPLE_INTERNAL_INACTIVE_MODEL =
            InternalMultipleOngoingActivityChipsModel(
                primary = DEFAULT_INTERNAL_INACTIVE_MODEL,
                secondary = DEFAULT_INTERNAL_INACTIVE_MODEL,
            )

        // TODO(b/392886257): Support 3 chips if there's space available.
        private const val MAX_VISIBLE_CHIPS = 2
    }
}
