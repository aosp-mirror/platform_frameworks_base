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

package com.android.systemui.mediaprojection.appselector

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import com.android.launcher3.icons.IconFactory
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.MediaProjectionAppSelectorActivity
import com.android.systemui.mediaprojection.appselector.data.ActivityTaskManagerThumbnailLoader
import com.android.systemui.mediaprojection.appselector.data.AppIconLoader
import com.android.systemui.mediaprojection.appselector.data.IconLoaderLibAppIconLoader
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import com.android.systemui.mediaprojection.appselector.data.RecentTaskThumbnailLoader
import com.android.systemui.mediaprojection.appselector.data.ShellRecentTaskListProvider
import com.android.systemui.mediaprojection.appselector.view.MediaProjectionRecentsViewController
import com.android.systemui.mediaprojection.appselector.view.TaskPreviewSizeProvider
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Qualifier
import javax.inject.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MediaProjectionAppSelector

@Retention(AnnotationRetention.RUNTIME) @Scope annotation class MediaProjectionAppSelectorScope

@Module(subcomponents = [MediaProjectionAppSelectorComponent::class])
interface MediaProjectionModule {
    @Binds
    @IntoMap
    @ClassKey(MediaProjectionAppSelectorActivity::class)
    fun provideMediaProjectionAppSelectorActivity(
        activity: MediaProjectionAppSelectorActivity
    ): Activity
}

/** Scoped values for [MediaProjectionAppSelectorComponent].
 *  We create a scope for the activity so certain dependencies like [TaskPreviewSizeProvider]
 *  could be reused. */
@Module
interface MediaProjectionAppSelectorModule {

    @Binds
    @MediaProjectionAppSelectorScope
    fun bindRecentTaskThumbnailLoader(
        impl: ActivityTaskManagerThumbnailLoader
    ): RecentTaskThumbnailLoader

    @Binds
    @MediaProjectionAppSelectorScope
    fun bindRecentTaskListProvider(impl: ShellRecentTaskListProvider): RecentTaskListProvider

    @Binds
    @MediaProjectionAppSelectorScope
    fun bindAppIconLoader(impl: IconLoaderLibAppIconLoader): AppIconLoader

    companion object {
        @Provides
        @MediaProjectionAppSelector
        @MediaProjectionAppSelectorScope
        fun provideAppSelectorComponentName(context: Context): ComponentName =
                ComponentName(context, MediaProjectionAppSelectorActivity::class.java)

        @Provides
        @MediaProjectionAppSelector
        @MediaProjectionAppSelectorScope
        fun bindConfigurationController(
            activity: MediaProjectionAppSelectorActivity
        ): ConfigurationController = ConfigurationControllerImpl(activity)

        @Provides
        fun bindIconFactory(
            context: Context
        ): IconFactory = IconFactory.obtain(context)

        @Provides
        @MediaProjectionAppSelector
        @MediaProjectionAppSelectorScope
        fun provideCoroutineScope(@Application applicationScope: CoroutineScope): CoroutineScope =
            CoroutineScope(applicationScope.coroutineContext + SupervisorJob())
    }
}

@Subcomponent(modules = [MediaProjectionAppSelectorModule::class])
@MediaProjectionAppSelectorScope
interface MediaProjectionAppSelectorComponent {

    /** Generates [MediaProjectionAppSelectorComponent]. */
    @Subcomponent.Factory
    interface Factory {
        /**
         * Create a factory to inject the activity into the graph
         */
        fun create(
            @BindsInstance activity: MediaProjectionAppSelectorActivity,
            @BindsInstance view: MediaProjectionAppSelectorView,
            @BindsInstance resultHandler: MediaProjectionAppSelectorResultHandler,
        ): MediaProjectionAppSelectorComponent
    }

    val controller: MediaProjectionAppSelectorController
    val recentsViewController: MediaProjectionRecentsViewController

    @MediaProjectionAppSelector val configurationController: ConfigurationController
}
