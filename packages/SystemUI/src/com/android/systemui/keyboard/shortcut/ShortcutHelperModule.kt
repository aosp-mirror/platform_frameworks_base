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

package com.android.systemui.keyboard.shortcut

import android.app.Activity
import com.android.systemui.CoreStartable
import com.android.systemui.Flags.keyboardShortcutHelperRewrite
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperStateRepository
import com.android.systemui.keyboard.shortcut.data.source.AppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.CurrentAppShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.InputShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.MultitaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.SystemShortcutsSource
import com.android.systemui.keyboard.shortcut.qualifiers.AppCategoriesShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.CurrentAppShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.InputShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.MultitaskingShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.SystemShortcuts
import com.android.systemui.keyboard.shortcut.ui.ShortcutHelperActivityStarter
import com.android.systemui.keyboard.shortcut.ui.view.ShortcutHelperActivity
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface ShortcutHelperModule {

    @Binds
    @IntoMap
    @ClassKey(ShortcutHelperActivity::class)
    fun activity(impl: ShortcutHelperActivity): Activity

    @Binds
    @SystemShortcuts
    fun systemShortcutsSource(impl: SystemShortcutsSource): KeyboardShortcutGroupsSource

    @Binds
    @MultitaskingShortcuts
    fun multitaskingShortcutsSource(impl: MultitaskingShortcutsSource): KeyboardShortcutGroupsSource

    @Binds
    @CurrentAppShortcuts
    fun currentAppShortcutsSource(impl: CurrentAppShortcutsSource): KeyboardShortcutGroupsSource

    @Binds
    @InputShortcuts
    fun inputShortcutsSources(impl: InputShortcutsSource): KeyboardShortcutGroupsSource

    @Binds
    @AppCategoriesShortcuts
    fun appCategoriesShortcutsSource(
        impl: AppCategoriesShortcutsSource
    ): KeyboardShortcutGroupsSource

    companion object {
        @Provides
        @IntoMap
        @ClassKey(ShortcutHelperActivityStarter::class)
        fun starter(implLazy: Lazy<ShortcutHelperActivityStarter>): CoreStartable {
            return if (keyboardShortcutHelperRewrite()) {
                implLazy.get()
            } else {
                // No-op implementation when the flag is disabled.
                NoOpStartable
            }
        }

        @Provides
        @IntoMap
        @ClassKey(ShortcutHelperStateRepository::class)
        fun repo(implLazy: Lazy<ShortcutHelperStateRepository>): CoreStartable {
            return if (keyboardShortcutHelperRewrite()) {
                implLazy.get()
            } else {
                // No-op implementation when the flag is disabled.
                NoOpStartable
            }
        }
    }
}

private object NoOpStartable : CoreStartable {
    override fun start() {}
}
