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

import android.content.Context
import android.os.Handler
import android.view.Display
import android.view.IWindowManager
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.fakeDisplayWindowPropertiesRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import org.mockito.Mockito.mock

val Kosmos.mockAutoHideController: AutoHideController by
    Kosmos.Fixture { mock(AutoHideController::class.java) }

val Kosmos.fakeAutoHideControllerFactory by Kosmos.Fixture { FakeAutoHideControllerFactory() }

val Kosmos.multiDisplayAutoHideControllerStore by
    Kosmos.Fixture {
        MultiDisplayAutoHideControllerStore(
            applicationCoroutineScope,
            displayRepository,
            fakeDisplayWindowPropertiesRepository,
            fakeAutoHideControllerFactory,
        )
    }

val Kosmos.fakeAutoHideControllerStore by Kosmos.Fixture { FakeAutoHideControllerStore() }

class FakeAutoHideControllerFactory :
    AutoHideControllerImpl.Factory(mock(Handler::class.java), mock(IWindowManager::class.java)) {

    override fun create(context: Context): AutoHideControllerImpl {
        return mock(AutoHideControllerImpl::class.java)
    }
}

class FakeAutoHideControllerStore : AutoHideControllerStore {

    private val perDisplayMocks = mutableMapOf<Int, AutoHideController>()

    override val defaultDisplay: AutoHideController
        get() = forDisplay(Display.DEFAULT_DISPLAY)

    override fun forDisplay(displayId: Int): AutoHideController {
        return perDisplayMocks.computeIfAbsent(displayId) { mock(AutoHideController::class.java) }
    }
}
