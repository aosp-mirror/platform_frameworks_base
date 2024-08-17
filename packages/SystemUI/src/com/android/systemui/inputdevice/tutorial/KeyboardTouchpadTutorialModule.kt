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

package com.android.systemui.inputdevice.tutorial

import android.app.Activity
import com.android.systemui.CoreStartable
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity
import com.android.systemui.touchpad.tutorial.domain.interactor.TouchpadGesturesInteractor
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface KeyboardTouchpadTutorialModule {

    @Binds
    @IntoMap
    @ClassKey(KeyboardTouchpadTutorialCoreStartable::class)
    fun bindKeyboardTouchpadTutorialCoreStartable(
        listener: KeyboardTouchpadTutorialCoreStartable
    ): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(KeyboardTouchpadTutorialActivity::class)
    fun activity(impl: KeyboardTouchpadTutorialActivity): Activity

    // TouchpadModule dependencies below
    // all should be optional to not introduce touchpad dependency in all sysui variants

    @BindsOptionalOf fun touchpadScreensProvider(): TouchpadTutorialScreensProvider

    @BindsOptionalOf fun touchpadGesturesInteractor(): TouchpadGesturesInteractor
}
