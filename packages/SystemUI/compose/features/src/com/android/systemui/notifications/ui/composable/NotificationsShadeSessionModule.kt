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

package com.android.systemui.notifications.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.session.shared.SessionStorage
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.session.ui.composable.Session
import dagger.Module
import dagger.Provides

@Module
object NotificationsShadeSessionModule {
    @Provides @SysUISingleton fun provideShadeSessionStorage(): SessionStorage = SessionStorage()

    @Provides
    fun provideShadeSession(storage: SessionStorage): SaveableSession =
        object : SaveableSession, Session by Session(storage) {
            @Composable
            override fun <T : Any> rememberSaveableSession(
                vararg inputs: Any?,
                saver: Saver<T, out Any>,
                key: String?,
                init: () -> T
            ): T = rememberSession(key, inputs = inputs, init = init)
        }
}
