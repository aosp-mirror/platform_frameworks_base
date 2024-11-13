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

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.graphics.drawable.Icon
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.row.domain.interactor.NotificationRowInteractor
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.util.kotlin.FlowDumperImpl
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** A view model for EnRoute notifications. */
class EnRouteViewModel
@Inject
constructor(
    dumpManager: DumpManager,
    rowInteractor: NotificationRowInteractor,
) : FlowDumperImpl(dumpManager) {
    init {
        /* check if */ RichOngoingNotificationFlag.isUnexpectedlyInLegacyMode()
    }

    val icon: Flow<Icon?> = rowInteractor.enRouteContentModel.mapNotNull { it.smallIcon.icon }

    val title: Flow<CharSequence?> = rowInteractor.enRouteContentModel.map { it.title }

    val text: Flow<CharSequence?> = rowInteractor.enRouteContentModel.map { it.text }
}
