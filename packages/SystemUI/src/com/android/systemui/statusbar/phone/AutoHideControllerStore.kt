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

package com.android.systemui.statusbar.phone

import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.display.data.repository.SingleDisplayStore
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [AutoHideController] */
interface AutoHideControllerStore : PerDisplayStore<AutoHideController>

@SysUISingleton
class MultiDisplayAutoHideControllerStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    private val autoHideControllerFactory: AutoHideControllerImpl.Factory,
) :
    AutoHideControllerStore,
    PerDisplayStoreImpl<AutoHideController>(backgroundApplicationScope, displayRepository) {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun createInstanceForDisplay(displayId: Int): AutoHideController {
        val displayWindowProperties =
            displayWindowPropertiesRepository.get(displayId, TYPE_STATUS_BAR)
        return autoHideControllerFactory.create(displayWindowProperties.context)
    }

    override suspend fun onDisplayRemovalAction(instance: AutoHideController) {
        instance.stop()
    }

    override val instanceClass = AutoHideController::class.java
}

@SysUISingleton
class SingleDisplayAutoHideControllerStore
@Inject
constructor(defaultController: AutoHideController) :
    AutoHideControllerStore,
    PerDisplayStore<AutoHideController> by SingleDisplayStore(defaultController) {

    init {
        StatusBarConnectedDisplays.assertInLegacyMode()
    }
}
