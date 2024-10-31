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

package com.android.systemui.statusbar.data.repository

import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR
import com.android.systemui.CoreStartable
import com.android.systemui.common.ui.GlobalConfig
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.display.data.repository.SingleDisplayStore
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Status bar specific interface to disambiguate from the global [ConfigurationController]. */
interface StatusBarConfigurationController : ConfigurationController

/** Provides per display instances of [ConfigurationController], specifically for the Status Bar. */
interface StatusBarConfigurationControllerStore : PerDisplayStore<StatusBarConfigurationController>

@SysUISingleton
class MultiDisplayStatusBarConfigurationControllerStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    private val configurationControllerFactory: ConfigurationControllerImpl.Factory,
) :
    StatusBarConfigurationControllerStore,
    PerDisplayStoreImpl<StatusBarConfigurationController>(
        backgroundApplicationScope,
        displayRepository,
    ) {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun createInstanceForDisplay(displayId: Int): StatusBarConfigurationController {
        val displayWindowProperties =
            displayWindowPropertiesRepository.get(displayId, TYPE_STATUS_BAR)
        return configurationControllerFactory.create(displayWindowProperties.context)
    }

    override val instanceClass = StatusBarConfigurationController::class.java
}

@SysUISingleton
class SingleDisplayStatusBarConfigurationControllerStore
@Inject
constructor(@GlobalConfig globalConfigurationController: ConfigurationController) :
    StatusBarConfigurationControllerStore,
    PerDisplayStore<StatusBarConfigurationController> by SingleDisplayStore(
        globalConfigurationController as StatusBarConfigurationController
    ) {

    init {
        StatusBarConnectedDisplays.assertInLegacyMode()
    }
}

@Module
object StatusBarConfigurationControllerModule {

    @Provides
    @SysUISingleton
    fun store(
        singleDisplayLazy: Lazy<SingleDisplayStatusBarConfigurationControllerStore>,
        multiDisplayLazy: Lazy<MultiDisplayStatusBarConfigurationControllerStore>,
    ): StatusBarConfigurationControllerStore {
        return if (StatusBarConnectedDisplays.isEnabled) {
            multiDisplayLazy.get()
        } else {
            singleDisplayLazy.get()
        }
    }

    @Provides
    @SysUISingleton
    @IntoMap
    @ClassKey(StatusBarConfigurationControllerStore::class)
    fun storeAsCoreStartable(
        multiDisplayLazy: Lazy<MultiDisplayStatusBarConfigurationControllerStore>
    ): CoreStartable {
        return if (StatusBarConnectedDisplays.isEnabled) {
            multiDisplayLazy.get()
        } else {
            CoreStartable.NOP
        }
    }
}
