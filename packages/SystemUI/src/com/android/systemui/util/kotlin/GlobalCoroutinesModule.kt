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

package com.android.systemui.util.kotlin

import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.Tracing
import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Providers for various application-wide coroutines-related constructs. */
@Module
class GlobalCoroutinesModule {
    @Provides
    @Singleton
    @Application
    fun applicationScope(
        @Main dispatcherContext: CoroutineContext,
    ): CoroutineScope = CoroutineScope(dispatcherContext + createCoroutineTracingContext("ApplicationScope"))

    @Provides
    @Singleton
    @Main
    @Deprecated(
        "Use @Main CoroutineContext instead",
        ReplaceWith("mainCoroutineContext()", "kotlin.coroutines.CoroutineContext")
    )
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    @Main
    fun mainCoroutineContext(): CoroutineContext {
        return Dispatchers.Main.immediate
    }
}
