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

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.testing.TestableContext
import android.testing.TestableResources
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.data.repository.FaceWakeUpTriggersConfigModule
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.SystemUIDeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.DefaultBlueprintModule
import com.android.systemui.scene.SceneContainerFrameworkModule
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSource
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.shade.domain.interactor.BaseShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@Module(
    includes =
        [
            TestMocksModule::class,
            CoroutineTestScopeModule::class,
            FakeSystemUiModule::class,
            DefaultBlueprintModule::class,
            SceneContainerFrameworkModule::class,
            FaceWakeUpTriggersConfigModule::class,
        ]
)
interface SysUITestModule {

    @Binds fun bindTestableContext(sysuiTestableContext: SysuiTestableContext): TestableContext
    @Binds fun bindContext(testableContext: TestableContext): Context
    @Binds @Application fun bindAppContext(context: Context): Context
    @Binds @Application fun bindAppResources(resources: Resources): Resources
    @Binds @Main fun bindMainResources(resources: Resources): Resources
    @Binds fun bindBroadcastDispatcher(fake: FakeBroadcastDispatcher): BroadcastDispatcher
    @Binds @SysUISingleton fun bindsShadeInteractor(sii: ShadeInteractorImpl): ShadeInteractor

    @Binds
    @SysUISingleton
    fun bindSceneDataSource(delegator: SceneDataSourceDelegator): SceneDataSource

    @Binds
    fun provideFaceAuthInteractor(
        sysUIFaceAuthInteractor: SystemUIDeviceEntryFaceAuthInteractor
    ): DeviceEntryFaceAuthInteractor

    companion object {
        @Provides
        fun provideSysuiTestableContext(test: SysuiTestCase): SysuiTestableContext = test.context

        @Provides
        fun provideTestableResources(context: TestableContext): TestableResources =
            context.getOrCreateTestableResources()

        @Provides
        fun provideResources(testableResources: TestableResources): Resources =
            testableResources.resources

        @Provides
        fun provideFakeBroadcastDispatcher(test: SysuiTestCase): FakeBroadcastDispatcher =
            test.fakeBroadcastDispatcher

        @Provides
        fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

        @Provides
        fun provideBaseShadeInteractor(
            sceneContainerOn: Provider<ShadeInteractorSceneContainerImpl>,
            sceneContainerOff: Provider<ShadeInteractorLegacyImpl>
        ): BaseShadeInteractor {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun providesSceneDataSourceDelegator(
            @Application applicationScope: CoroutineScope,
            config: SceneContainerConfig,
        ): SceneDataSourceDelegator {
            return SceneDataSourceDelegator(applicationScope, config)
        }
    }
}

interface SysUITestComponent<out T> {
    val testScope: TestScope
    val underTest: T
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T : SysUITestComponent<*>> T.runTest(block: suspend T.() -> Unit): Unit =
    testScope.runTest {
        // Access underTest immediately to force Dagger to instantiate it prior to the test running
        underTest
        runCurrent()
        block()
    }

@OptIn(ExperimentalCoroutinesApi::class)
fun SysUITestComponent<*>.runCurrent() = testScope.runCurrent()

fun <T> SysUITestComponent<*>.collectLastValue(
    flow: Flow<T>,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
) = testScope.collectLastValue(flow, context, start)

fun <T> SysUITestComponent<*>.collectValues(
    flow: Flow<T>,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
) = testScope.collectValues(flow, context, start)

val SysUITestComponent<*>.backgroundScope
    get() = testScope.backgroundScope
