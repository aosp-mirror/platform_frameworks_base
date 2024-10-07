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

import android.os.Handler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Tracing
import com.android.systemui.dagger.qualifiers.UiBackground
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.plus
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext

private const val LIMIT_BACKGROUND_DISPATCHER_THREADS = true

/** Providers for various SystemUI-specific coroutines-related constructs. */
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
     * Default Coroutine dispatcher for background operations.
     *
     * Note that this is explicitly limiting the number of threads. In the past, we used
     * [Dispatchers.IO]. This caused >40 threads to be spawned, and a lot of thread list lock
     * contention between then, eventually causing jank.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Provides
    @SysUISingleton
    @Background
    @Deprecated(
        "Use @Background CoroutineContext instead",
        ReplaceWith("bgCoroutineContext()", "kotlin.coroutines.CoroutineContext")
    )
    fun bgDispatcher(): CoroutineDispatcher {
        return if (LIMIT_BACKGROUND_DISPATCHER_THREADS) {
            // Why a new ThreadPool instead of just using Dispatchers.IO with
            // CoroutineDispatcher.limitedParallelism? Because, if we were to use Dispatchers.IO, we
            // would share those threads with other dependencies using Dispatchers.IO.
            // Using a dedicated thread pool we have guarantees only SystemUI is able to schedule
            // code on those.
            newFixedThreadPoolContext(
                nThreads = Runtime.getRuntime().availableProcessors(),
                name = "SystemUIBg"
            )
        } else {
            Dispatchers.IO
        }
    }

    @Provides
    @SysUISingleton
    @SettingsSingleThreadBackground
    fun settingsBgDispatcher(@Background bgHandler: Handler): CoroutineDispatcher {
        // Handlers are guaranteed to be sequential so we use that one for now.
        return bgHandler.asCoroutineDispatcher("SettingsBg")
    }

    @Provides
    @Background
    @SysUISingleton
    fun bgCoroutineContext(
        @Background bgCoroutineDispatcher: CoroutineDispatcher,
    ): CoroutineContext {
        return bgCoroutineDispatcher
    }

    /** Coroutine dispatcher for background operations on for UI. */
    @Provides
    @SysUISingleton
    @UiBackground
    @Deprecated(
        "Use @UiBackground CoroutineContext instead",
        ReplaceWith("uiBgCoroutineContext()", "kotlin.coroutines.CoroutineContext")
    )
    fun uiBgDispatcher(@UiBackground uiBgExecutor: Executor): CoroutineDispatcher =
        uiBgExecutor.asCoroutineDispatcher()

    @Provides
    @UiBackground
    @SysUISingleton
    fun uiBgCoroutineContext(
        @UiBackground uiBgCoroutineDispatcher: CoroutineDispatcher,
    ): CoroutineContext {
        return uiBgCoroutineDispatcher
    }
}
