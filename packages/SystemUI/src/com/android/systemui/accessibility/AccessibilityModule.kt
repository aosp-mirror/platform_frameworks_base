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

package com.android.systemui.accessibility

import com.android.systemui.accessibility.data.repository.AccessibilityQsShortcutsRepository
import com.android.systemui.accessibility.data.repository.AccessibilityQsShortcutsRepositoryImpl
import com.android.systemui.accessibility.data.repository.ColorCorrectionRepository
import com.android.systemui.accessibility.data.repository.ColorCorrectionRepositoryImpl
import com.android.systemui.accessibility.data.repository.ColorInversionRepository
import com.android.systemui.accessibility.data.repository.ColorInversionRepositoryImpl
import com.android.systemui.accessibility.data.repository.OneHandedModeRepository
import com.android.systemui.accessibility.data.repository.OneHandedModeRepositoryImpl
import com.android.systemui.accessibility.qs.QSAccessibilityModule
import dagger.Binds
import dagger.Module

@Module(includes = [QSAccessibilityModule::class])
interface AccessibilityModule {
    @Binds
    fun colorCorrectionRepository(impl: ColorCorrectionRepositoryImpl): ColorCorrectionRepository

    @Binds
    fun colorInversionRepository(impl: ColorInversionRepositoryImpl): ColorInversionRepository

    @Binds fun oneHandedModeRepository(impl: OneHandedModeRepositoryImpl): OneHandedModeRepository

    @Binds
    fun accessibilityQsShortcutsRepository(
        impl: AccessibilityQsShortcutsRepositoryImpl
    ): AccessibilityQsShortcutsRepository

    @Binds
    fun magnification(impl: MagnificationImpl): Magnification
}
