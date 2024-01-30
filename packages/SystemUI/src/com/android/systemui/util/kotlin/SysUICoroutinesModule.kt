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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Tracing
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

/** Providers for various SystemIU specific coroutines-related constructs. */
@Module
class SysUICoroutinesModule {
    @Provides
    @SysUISingleton
    @Background
    fun bgApplicationScope(
        @Application applicationScope: CoroutineScope,
        @Background coroutineContext: CoroutineContext,
    ): CoroutineScope = applicationScope.plus(coroutineContext)

    /**
     * Provide a [CoroutineDispatcher] backed by a thread pool containing at most X threads, where X
     * is the number of CPU cores available.
     *
     * Because there are multiple threads at play, there is no serialization order guarantee. You
     * should use a [kotlinx.coroutines.channels.Channel] for serialization if necessary.
     *
     * @see Dispatchers.Default
     */
    @Provides
    @SysUISingleton
    @Background
    @Deprecated(
        "Use @Background CoroutineContext instead",
        ReplaceWith("bgCoroutineContext()", "kotlin.coroutines.CoroutineContext")
    )
    fun bgDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Background
    @SysUISingleton
    fun bgCoroutineContext(@Tracing tracingCoroutineContext: CoroutineContext): CoroutineContext {
        return Dispatchers.IO + tracingCoroutineContext
    }
}
