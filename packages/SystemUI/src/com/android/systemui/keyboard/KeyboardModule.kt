/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyboard

import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.keyboard.data.repository.KeyboardRepositoryImpl
import com.android.systemui.keyboard.shortcut.ShortcutHelperModule
import com.android.systemui.keyboard.stickykeys.data.repository.StickyKeysRepository
import com.android.systemui.keyboard.stickykeys.data.repository.StickyKeysRepositoryImpl
import dagger.Binds
import dagger.Module

@Module(includes = [ShortcutHelperModule::class])
abstract class KeyboardModule {

    @Binds
    abstract fun bindKeyboardRepository(repository: KeyboardRepositoryImpl): KeyboardRepository

    @Binds
    abstract fun bindStickyKeysRepository(
        repository: StickyKeysRepositoryImpl
    ): StickyKeysRepository
}
