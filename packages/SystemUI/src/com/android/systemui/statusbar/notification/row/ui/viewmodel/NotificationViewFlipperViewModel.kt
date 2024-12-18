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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.shared.NotificationViewFlipperPausing
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import javax.inject.Inject

/** A model which represents whether ViewFlippers inside notifications should be paused. */
@SysUISingleton
class NotificationViewFlipperViewModel
@Inject
constructor(
    dumpManager: DumpManager,
    stackInteractor: NotificationStackInteractor,
) : FlowDumperImpl(dumpManager) {
    init {
        /* check if */ NotificationViewFlipperPausing.isUnexpectedlyInLegacyMode()
    }

    val isPaused = stackInteractor.isShowingOnLockscreen.dumpWhileCollecting("isPaused")
}
