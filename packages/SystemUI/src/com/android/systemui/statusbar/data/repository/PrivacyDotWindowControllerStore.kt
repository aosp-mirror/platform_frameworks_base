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

import android.view.Display
import android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.events.PrivacyDotWindowController
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Providers per display instances of [PrivacyDotWindowController]. */
interface PrivacyDotWindowControllerStore : PerDisplayStore<PrivacyDotWindowController>

@SysUISingleton
class PrivacyDotWindowControllerStoreImpl
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val windowControllerFactory: PrivacyDotWindowController.Factory,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    private val privacyDotViewControllerStore: PrivacyDotViewControllerStore,
    private val viewCaptureAwareWindowManagerFactory: ViewCaptureAwareWindowManager.Factory,
) :
    PrivacyDotWindowControllerStore,
    PerDisplayStoreImpl<PrivacyDotWindowController>(backgroundApplicationScope, displayRepository) {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun createInstanceForDisplay(displayId: Int): PrivacyDotWindowController {
        if (displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("This class should only be used for connected displays")
        }
        val displayWindowProperties =
            displayWindowPropertiesRepository.get(displayId, TYPE_NAVIGATION_BAR_PANEL)
        return windowControllerFactory.create(
            displayId = displayId,
            privacyDotViewController = privacyDotViewControllerStore.forDisplay(displayId),
            viewCaptureAwareWindowManager =
                viewCaptureAwareWindowManagerFactory.create(displayWindowProperties.windowManager),
            inflater = displayWindowProperties.layoutInflater,
        )
    }

    override val instanceClass = PrivacyDotWindowController::class.java
}

@Module
interface PrivacyDotWindowControllerStoreModule {

    @Binds fun store(impl: PrivacyDotWindowControllerStoreImpl): PrivacyDotWindowControllerStore

    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(PrivacyDotWindowControllerStore::class)
        fun storeAsCoreStartable(
            storeLazy: Lazy<PrivacyDotWindowControllerStoreImpl>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                storeLazy.get()
            } else {
                CoreStartable.NOP
            }
        }
    }
}
