/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.dagger

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.MediaProjectionAppSelectorActivity
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorController
import com.android.systemui.mediaprojection.appselector.data.ActivityTaskManagerThumbnailLoader
import com.android.systemui.mediaprojection.appselector.data.AppIconLoader
import com.android.systemui.mediaprojection.appselector.data.IconLoaderLibAppIconLoader
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import com.android.systemui.mediaprojection.appselector.data.RecentTaskThumbnailLoader
import com.android.systemui.mediaprojection.appselector.data.ShellRecentTaskListProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaProjectionAppSelector

@Module
abstract class MediaProjectionModule {

    @Binds
    @IntoMap
    @ClassKey(MediaProjectionAppSelectorActivity::class)
    abstract fun provideMediaProjectionAppSelectorActivity(
        activity: MediaProjectionAppSelectorActivity
    ): Activity

    @Binds
    abstract fun bindRecentTaskThumbnailLoader(
        impl: ActivityTaskManagerThumbnailLoader
    ): RecentTaskThumbnailLoader

    @Binds
    abstract fun bindRecentTaskListProvider(
        impl: ShellRecentTaskListProvider
    ): RecentTaskListProvider

    @Binds
    abstract fun bindAppIconLoader(impl: IconLoaderLibAppIconLoader): AppIconLoader

    companion object {
        @Provides
        fun provideController(
            recentTaskListProvider: RecentTaskListProvider,
            context: Context,
            @MediaProjectionAppSelector scope: CoroutineScope
        ): MediaProjectionAppSelectorController {
            val appSelectorComponentName =
                ComponentName(context, MediaProjectionAppSelectorActivity::class.java)

            return MediaProjectionAppSelectorController(
                recentTaskListProvider,
                scope,
                appSelectorComponentName
            )
        }

        @MediaProjectionAppSelector
        @Provides
        fun provideCoroutineScope(@Application applicationScope: CoroutineScope): CoroutineScope =
            CoroutineScope(applicationScope.coroutineContext + SupervisorJob())
    }
}
