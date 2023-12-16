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

package com.android.systemui.statusbar.data.repository

import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.data.model.StatusBarAppearance
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.google.common.truth.Truth.assertThat
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeStatusBarModeRepository @Inject constructor() : StatusBarModeRepositoryStore {

    companion object {
        const val DISPLAY_ID = Display.DEFAULT_DISPLAY
    }

    override val defaultDisplay: FakeStatusBarModePerDisplayRepository =
        FakeStatusBarModePerDisplayRepository()

    override fun forDisplay(displayId: Int): FakeStatusBarModePerDisplayRepository {
        assertThat(displayId).isEqualTo(DISPLAY_ID)
        return defaultDisplay
    }
}

class FakeStatusBarModePerDisplayRepository : StatusBarModePerDisplayRepository {
    override val isTransientShown = MutableStateFlow(false)
    override val isInFullscreenMode = MutableStateFlow(false)
    override val statusBarAppearance = MutableStateFlow<StatusBarAppearance?>(null)
    override val statusBarMode = MutableStateFlow(StatusBarMode.TRANSPARENT)

    override fun showTransient() {
        isTransientShown.value = true
    }
    override fun clearTransient() {
        isTransientShown.value = false
    }
}

@Module
interface FakeStatusBarModeRepositoryModule {
    @Binds fun bindFake(fake: FakeStatusBarModeRepository): StatusBarModeRepositoryStore
}
