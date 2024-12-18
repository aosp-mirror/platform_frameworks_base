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

package com.android.systemui.shade.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.TransitionKeys
import com.android.systemui.shade.domain.interactor.PrivacyChipInteractor
import com.android.systemui.shade.domain.interactor.ShadeHeaderClockInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Models UI state for the shade header. */
class ShadeHeaderViewModel
@AssistedInject
constructor(
    private val context: Context,
    private val activityStarter: ActivityStarter,
    private val sceneInteractor: SceneInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val mobileIconsInteractor: MobileIconsInteractor,
    val mobileIconsViewModel: MobileIconsViewModel,
    private val privacyChipInteractor: PrivacyChipInteractor,
    private val clockInteractor: ShadeHeaderClockInteractor,
    private val broadcastDispatcher: BroadcastDispatcher,
) : ExclusiveActivatable() {
    /** True if there is exactly one mobile connection. */
    val isSingleCarrier: StateFlow<Boolean> = mobileIconsInteractor.isSingleCarrier

    private val _mobileSubIds = MutableStateFlow(emptyList<Int>())
    /** The list of subscription Ids for current mobile connections. */
    val mobileSubIds: StateFlow<List<Int>> = _mobileSubIds.asStateFlow()

    /** The list of PrivacyItems to be displayed by the privacy chip. */
    val privacyItems: StateFlow<List<PrivacyItem>> = privacyChipInteractor.privacyItems

    /** Whether or not mic & camera indicators are enabled in the device privacy config. */
    val isMicCameraIndicationEnabled: StateFlow<Boolean> =
        privacyChipInteractor.isMicCameraIndicationEnabled

    /** Whether or not location indicators are enabled in the device privacy config. */
    val isLocationIndicationEnabled: StateFlow<Boolean> =
        privacyChipInteractor.isLocationIndicationEnabled

    /** Whether or not the privacy chip should be visible. */
    val isPrivacyChipVisible: StateFlow<Boolean> = privacyChipInteractor.isChipVisible

    /** Whether or not the privacy chip is enabled in the device privacy config. */
    val isPrivacyChipEnabled: StateFlow<Boolean> = privacyChipInteractor.isChipEnabled

    private val _isDisabled = MutableStateFlow(false)
    /** Whether or not the Shade Header should be disabled based on disableFlags. */
    val isDisabled: StateFlow<Boolean> = _isDisabled.asStateFlow()

    private val longerPattern = context.getString(R.string.abbrev_wday_month_day_no_year_alarm)
    private val shorterPattern = context.getString(R.string.abbrev_month_day_no_year)
    private val longerDateFormat = MutableStateFlow(getFormatFromPattern(longerPattern))
    private val shorterDateFormat = MutableStateFlow(getFormatFromPattern(shorterPattern))

    private val _shorterDateText: MutableStateFlow<String> = MutableStateFlow("")
    val shorterDateText: StateFlow<String> = _shorterDateText.asStateFlow()

    private val _longerDateText: MutableStateFlow<String> = MutableStateFlow("")
    val longerDateText: StateFlow<String> = _longerDateText.asStateFlow()

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                broadcastDispatcher
                    .broadcastFlow(
                        filter =
                            IntentFilter().apply {
                                addAction(Intent.ACTION_TIME_TICK)
                                addAction(Intent.ACTION_TIME_CHANGED)
                                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                                addAction(Intent.ACTION_LOCALE_CHANGED)
                            },
                        user = UserHandle.SYSTEM,
                        map = { intent, _ ->
                            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
                                intent.action == Intent.ACTION_LOCALE_CHANGED
                        }
                    )
                    .onEach { invalidateFormats -> updateDateTexts(invalidateFormats) }
                    .launchIn(this)
            }

            launch { updateDateTexts(false) }

            launch {
                mobileIconsInteractor.filteredSubscriptions
                    .map { list -> list.map { it.subscriptionId } }
                    .collect { _mobileSubIds.value = it }
            }

            launch { shadeInteractor.isQsEnabled.map { !it }.collect { _isDisabled.value = it } }

            awaitCancellation()
        }
    }

    /** Notifies that the privacy chip was clicked. */
    fun onPrivacyChipClicked(privacyChip: OngoingPrivacyChip) {
        privacyChipInteractor.onPrivacyChipClicked(privacyChip)
    }

    /** Notifies that the clock was clicked. */
    fun onClockClicked() {
        clockInteractor.launchClockActivity()
    }

    /** Notifies that the system icons container was clicked. */
    fun onSystemIconContainerClicked() {
        sceneInteractor.changeScene(
            SceneFamilies.Home,
            "ShadeHeaderViewModel.onSystemIconContainerClicked",
            TransitionKeys.SlightlyFasterShadeCollapse,
        )
    }

    /** Notifies that the shadeCarrierGroup was clicked. */
    fun onShadeCarrierGroupClicked() {
        activityStarter.postStartActivityDismissingKeyguard(
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            0
        )
    }

    private fun updateDateTexts(invalidateFormats: Boolean) {
        if (invalidateFormats) {
            longerDateFormat.value = getFormatFromPattern(longerPattern)
            shorterDateFormat.value = getFormatFromPattern(shorterPattern)
        }

        val currentTime = Date()

        _longerDateText.value = longerDateFormat.value.format(currentTime)
        _shorterDateText.value = shorterDateFormat.value.format(currentTime)
    }

    private fun getFormatFromPattern(pattern: String?): DateFormat {
        val l = Locale.getDefault()
        val format = DateFormat.getInstanceForSkeleton(pattern, l)
        // The use of CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE instead of
        // CAPITALIZATION_FOR_STANDALONE is to address
        // https://unicode-org.atlassian.net/browse/ICU-21631
        // TODO(b/229287642): Switch back to CAPITALIZATION_FOR_STANDALONE
        format.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
        return format
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShadeHeaderViewModel
    }
}
