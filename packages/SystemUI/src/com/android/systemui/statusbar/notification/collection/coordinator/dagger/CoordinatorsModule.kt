/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator.dagger

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.coordinator.NotifCoordinators
import com.android.systemui.statusbar.notification.collection.coordinator.NotifCoordinatorsImpl
import com.android.systemui.statusbar.notification.collection.coordinator.SensitiveContentCoordinatorModule
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import javax.inject.Qualifier
import javax.inject.Scope

@Module(subcomponents = [CoordinatorsSubcomponent::class])
object CoordinatorsModule {
    @SysUISingleton
    @JvmStatic
    @Provides
    fun notifCoordinators(factory: CoordinatorsSubcomponent.Factory): NotifCoordinators =
            factory.create().notifCoordinators
}

@CoordinatorScope
@Subcomponent(modules = [InternalCoordinatorsModule::class])
interface CoordinatorsSubcomponent {
    @get:Internal val notifCoordinators: NotifCoordinators

    @Subcomponent.Factory
    interface Factory {
        fun create(): CoordinatorsSubcomponent
    }
}

@Module(includes = [
    SensitiveContentCoordinatorModule::class,
])
private abstract class InternalCoordinatorsModule {
    @Binds
    @Internal
    abstract fun bindNotifCoordinators(impl: NotifCoordinatorsImpl): NotifCoordinators
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
private annotation class Internal

@Scope
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class CoordinatorScope
