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

package com.android.systemui.display.domain.interactor

import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.shared.model.DisplayWindowProperties
import dagger.Binds
import dagger.Module
import javax.inject.Inject

/** Provides per display instances of [DisplayWindowProperties]. */
interface DisplayWindowPropertiesInteractor {

    /**
     * Returns a [DisplayWindowProperties] instance for a given display id, to be used for the
     * status bar.
     *
     * @throws IllegalArgumentException if no display with the given display id exists.
     */
    fun getForStatusBar(displayId: Int): DisplayWindowProperties
}

@SysUISingleton
class DisplayWindowPropertiesInteractorImpl
@Inject
constructor(private val repo: DisplayWindowPropertiesRepository) :
    DisplayWindowPropertiesInteractor {

    override fun getForStatusBar(displayId: Int): DisplayWindowProperties {
        return repo.get(displayId, TYPE_STATUS_BAR)
    }
}

@Module
interface DisplayWindowPropertiesInteractorModule {

    @Binds
    fun interactor(impl: DisplayWindowPropertiesInteractorImpl): DisplayWindowPropertiesInteractor
}
