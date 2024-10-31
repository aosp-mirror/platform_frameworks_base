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

import android.content.Context
import android.os.Handler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.decor.FaceScanningProviderFactory
import com.android.systemui.decor.FaceScanningProviderFactoryImpl
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.ThreadFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import java.util.concurrent.Executor
import javax.inject.Qualifier

@Qualifier annotation class ScreenDecorationsThread

@Module
interface ScreenDecorationsModule {
    /** Start ScreenDecorations. */
    @Binds
    @IntoMap
    @ClassKey(ScreenDecorations::class)
    fun bindScreenDecorationsCoreStartable(impl: ScreenDecorations): CoreStartable

    /** Listen to config changes for ScreenDecorations. */
    @Binds
    @IntoSet
    fun bindScreenDecorationsConfigListener(impl: ScreenDecorations): ConfigurationListener

    @Binds
    @ScreenDecorationsThread
    fun screenDecorationsExecutor(
        @ScreenDecorationsThread delayableExecutor: DelayableExecutor
    ): Executor

    companion object {
        @Provides
        @SysUISingleton
        fun faceScanningProviderFactory(
            creator: FaceScanningProviderFactoryImpl.Creator,
            context: Context,
        ): FaceScanningProviderFactory {
            return creator.create(context)
        }

        @Provides
        @SysUISingleton
        @ScreenDecorationsThread
        fun screenDecorationsHandler(threadFactory: ThreadFactory): Handler {
            return threadFactory.buildHandlerOnNewThread("ScreenDecorations")
        }

        @Provides
        @SysUISingleton
        @ScreenDecorationsThread
        fun screenDecorationsDelayableExecutor(
            @ScreenDecorationsThread handler: Handler,
            threadFactory: ThreadFactory,
        ): DelayableExecutor {
            return threadFactory.buildDelayableExecutorOnHandler(handler)
        }
    }
}
