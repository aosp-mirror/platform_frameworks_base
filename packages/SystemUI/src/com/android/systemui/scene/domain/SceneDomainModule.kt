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

package com.android.systemui.scene.domain

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.scene.domain.resolver.SceneResolverModule
import dagger.Module
import dagger.Provides
import javax.inject.Qualifier

@Module(includes = [SceneResolverModule::class])
object SceneDomainModule {

    @JvmStatic
    @Provides
    @SysUISingleton
    @SceneFrameworkTableLog
    fun provideSceneFrameworkTableLogBuffer(factory: TableLogBufferFactory): TableLogBuffer {
        return factory.create("SceneFrameworkTableLog", 100)
    }
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class SceneFrameworkTableLog
