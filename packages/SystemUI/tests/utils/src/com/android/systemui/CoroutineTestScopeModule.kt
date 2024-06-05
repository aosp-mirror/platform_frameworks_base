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
package com.android.systemui

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope

@Module(includes = [CoroutineTestScopeModule.Bindings::class])
class CoroutineTestScopeModule
private constructor(
    @get:Provides val scope: TestScope,
    @get:Provides val dispatcher: TestDispatcher,
    @get:Provides val scheduler: TestCoroutineScheduler = dispatcher.scheduler,
) {

    constructor() : this(TestScope())

    constructor(
        scope: TestScope
    ) : this(scope, scope.coroutineContext[ContinuationInterceptor] as TestDispatcher)

    constructor(context: CoroutineContext) : this(TestScope(context))

    @get:[Provides Application]
    val appScope: CoroutineScope = scope.backgroundScope

    @get:[Provides Background]
    val bgScope: CoroutineScope = scope.backgroundScope

    @Module
    interface Bindings {
        @Binds @Main fun bindMainContext(dispatcher: TestDispatcher): CoroutineContext
        @Binds @Main fun bindMainDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher
        @Binds @Background fun bindBgContext(dispatcher: TestDispatcher): CoroutineContext
        @Binds @Background fun bindBgDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher
    }
}
