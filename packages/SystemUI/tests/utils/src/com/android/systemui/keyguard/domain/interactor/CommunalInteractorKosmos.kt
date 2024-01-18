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

package com.android.systemui.keyguard.domain.interactor

import android.appwidget.AppWidgetHost
import com.android.systemui.communal.data.repository.communalMediaRepository
import com.android.systemui.communal.data.repository.communalPrefsRepository
import com.android.systemui.communal.data.repository.communalRepository
import com.android.systemui.communal.data.repository.communalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.smartspace.data.repository.smartspaceRepository
import org.mockito.Mockito.mock

val Kosmos.communalInteractor by
    Kosmos.Fixture {
        CommunalInteractor(
            communalRepository = communalRepository,
            widgetRepository = communalWidgetRepository,
            mediaRepository = communalMediaRepository,
            communalPrefsRepository = communalPrefsRepository,
            smartspaceRepository = smartspaceRepository,
            keyguardInteractor = keyguardInteractor,
            appWidgetHost = mock(AppWidgetHost::class.java),
            editWidgetsActivityStarter = mock(EditWidgetsActivityStarter::class.java),
        )
    }
