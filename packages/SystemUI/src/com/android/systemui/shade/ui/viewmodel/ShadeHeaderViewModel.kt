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
import com.android.systemui.res.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Models UI state for the shade header. */
@SysUISingleton
class ShadeHeaderViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    context: Context,
    sceneInteractor: SceneInteractor,
    mobileIconsInteractor: MobileIconsInteractor,
    val mobileIconsViewModel: MobileIconsViewModel,
    broadcastDispatcher: BroadcastDispatcher,
) {
    /** True if we are transitioning between Shade and QuickSettings scenes, in either direction. */
    val isTransitioning =
        combine(
                sceneInteractor.transitioning(from = SceneKey.Shade, to = SceneKey.QuickSettings),
                sceneInteractor.transitioning(from = SceneKey.QuickSettings, to = SceneKey.Shade)
            ) { shadeToQuickSettings, quickSettingsToShade ->
                shadeToQuickSettings || quickSettingsToShade
            }
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), false)

    /** True if there is exactly one mobile connection. */
    val isSingleCarrier: StateFlow<Boolean> = mobileIconsInteractor.isSingleCarrier

    /** The list of subscription Ids for current mobile connections. */
    val mobileSubIds =
        mobileIconsInteractor.filteredSubscriptions
            .map { list -> list.map { it.subscriptionId } }
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), emptyList())

    private val longerPattern = context.getString(R.string.abbrev_wday_month_day_no_year_alarm)
    private val shorterPattern = context.getString(R.string.abbrev_month_day_no_year)
    private val longerDateFormat = MutableStateFlow(getFormatFromPattern(longerPattern))
    private val shorterDateFormat = MutableStateFlow(getFormatFromPattern(shorterPattern))

    private val _shorterDateText: MutableStateFlow<String> = MutableStateFlow("")
    val shorterDateText: StateFlow<String> = _shorterDateText.asStateFlow()

    private val _longerDateText: MutableStateFlow<String> = MutableStateFlow("")
    val longerDateText: StateFlow<String> = _longerDateText.asStateFlow()

    init {
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
            .launchIn(applicationScope)

        applicationScope.launch { updateDateTexts(false) }
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
}
