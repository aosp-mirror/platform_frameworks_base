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

package com.android.systemui.communal.domain.interactor

import com.android.systemui.communal.data.repository.CommunalRepository
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.shared.CommunalAppWidgetInfo
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Encapsulates business-logic related to communal mode. */
@SysUISingleton
class CommunalInteractor
@Inject
constructor(
    communalRepository: CommunalRepository,
    widgetRepository: CommunalWidgetRepository,
) {
    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean = communalRepository.isCommunalEnabled

    /** A flow of info about the widget to be displayed, or null if widget is unavailable. */
    val appWidgetInfo: Flow<CommunalAppWidgetInfo?> = widgetRepository.stopwatchAppWidgetInfo
}
