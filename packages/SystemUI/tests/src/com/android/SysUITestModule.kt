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
package com.android

import android.content.Context
import com.android.systemui.FakeSystemUiModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import dagger.Module
import dagger.Provides

@Module(
    includes =
        [
            TestMocksModule::class,
            CoroutineTestScopeModule::class,
            FakeSystemUiModule::class,
        ]
)
class SysUITestModule {
    @Provides fun provideContext(test: SysuiTestCase): Context = test.context

    @Provides @Application fun provideAppContext(test: SysuiTestCase): Context = test.context

    @Provides
    fun provideBroadcastDispatcher(test: SysuiTestCase): BroadcastDispatcher =
        test.fakeBroadcastDispatcher
}
