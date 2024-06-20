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

import android.os.Looper
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.dagger.GlobalRootComponent
import com.android.systemui.dagger.SysUIComponent
import com.android.systemui.dump.dumpManager
import com.android.systemui.flags.systemPropertiesHelper
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.process.processWrapper
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@RunWithLooper
class SystemUIApplicationTest : SysuiTestCase() {

    private val app: SystemUIApplication = SystemUIApplication()
    private lateinit var contextAvailableCallback:
        SystemUIAppComponentFactoryBase.ContextAvailableCallback

    @get:Rule val setFlagsRule = SetFlagsRule(SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT)

    val kosmos = Kosmos()
    @Mock private lateinit var initializer: SystemUIInitializer
    @Mock private lateinit var rootComponent: GlobalRootComponent
    @Mock private lateinit var sysuiComponent: SysUIComponent
    @Mock private lateinit var bootCompleteCache: BootCompleteCacheImpl
    @Mock private lateinit var initController: InitController

    class StartableA : TestableStartable()
    class StartableB : TestableStartable()
    class StartableC : TestableStartable()
    class StartableD : TestableStartable()
    class StartableE : TestableStartable()

    val dependencyMap: Map<Class<*>, Set<Class<out CoreStartable>>> =
        mapOf(
            StartableC::class.java to setOf(StartableA::class.java),
            StartableD::class.java to setOf(StartableA::class.java, StartableB::class.java),
            StartableE::class.java to setOf(StartableD::class.java, StartableB::class.java),
        )

    private val startableA = StartableA()
    private val startableB = StartableB()
    private val startableC = StartableC()
    private val startableD = StartableD()
    private val startableE = StartableE()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        app.attachBaseContext(context)
        contextAvailableCallback =
            SystemUIAppComponentFactoryBase.ContextAvailableCallback { initializer }
        whenever(initializer.rootComponent).thenReturn(rootComponent)
        whenever(initializer.sysUIComponent).thenReturn(sysuiComponent)
        whenever(rootComponent.mainLooper).thenReturn(Looper.myLooper())
        whenever(rootComponent.systemPropertiesHelper).thenReturn(kosmos.systemPropertiesHelper)
        whenever(rootComponent.processWrapper).thenReturn(kosmos.processWrapper)
        whenever(sysuiComponent.provideBootCacheImpl()).thenReturn(bootCompleteCache)
        whenever(sysuiComponent.createDumpManager()).thenReturn(kosmos.dumpManager)
        whenever(sysuiComponent.initController).thenReturn(initController)
        whenever(sysuiComponent.startableDependencies).thenReturn(dependencyMap)
        kosmos.processWrapper.systemUser = true

        app.setContextAvailableCallback(contextAvailableCallback)
    }

    @Test
    fun testAppOnCreate() {
        app.onCreate()
    }

    @Test
    fun testStartServices_singleService() {
        whenever(sysuiComponent.startables)
            .thenReturn(mutableMapOf(StartableA::class.java to Provider { startableA }))
        app.onCreate()
        app.startSystemUserServicesIfNeeded()
        assertThat(startableA.started).isTrue()
    }

    @Test
    fun testStartServices_twoServices() {
        whenever(sysuiComponent.startables)
            .thenReturn(
                mutableMapOf(
                    StartableA::class.java to Provider { startableA },
                    StartableB::class.java to Provider { startableB }
                )
            )
        app.onCreate()
        app.startSystemUserServicesIfNeeded()
        assertThat(startableA.started).isTrue()
        assertThat(startableB.started).isTrue()
    }

    @Test
    fun testStartServices_simpleDependency() {
        whenever(sysuiComponent.startables)
            .thenReturn(
                mutableMapOf(
                    StartableC::class.java to Provider { startableC },
                    StartableA::class.java to Provider { startableA },
                    StartableB::class.java to Provider { startableB }
                )
            )
        app.onCreate()
        app.startSystemUserServicesIfNeeded()
        assertThat(startableA.started).isTrue()
        assertThat(startableB.started).isTrue()
        assertThat(startableC.started).isTrue()
        assertThat(startableC.order).isGreaterThan(startableA.order)
    }

    @Test
    fun testStartServices_complexDependency() {
        whenever(sysuiComponent.startables)
            .thenReturn(
                mutableMapOf(
                    StartableE::class.java to Provider { startableE },
                    StartableC::class.java to Provider { startableC },
                    StartableD::class.java to Provider { startableD },
                    StartableA::class.java to Provider { startableA },
                    StartableB::class.java to Provider { startableB }
                )
            )
        app.onCreate()
        app.startSystemUserServicesIfNeeded()
        assertThat(startableA.started).isTrue()
        assertThat(startableB.started).isTrue()
        assertThat(startableC.started).isTrue()
        assertThat(startableD.started).isTrue()
        assertThat(startableE.started).isTrue()
        assertThat(startableC.order).isGreaterThan(startableA.order)
        assertThat(startableD.order).isGreaterThan(startableA.order)
        assertThat(startableD.order).isGreaterThan(startableB.order)
        assertThat(startableE.order).isGreaterThan(startableB.order)
        assertThat(startableE.order).isGreaterThan(startableD.order)
    }

    open class TestableStartable : CoreStartable {
        companion object {
            var startOrder = 0
        }

        var started = false
        var order = -1

        override fun start() {
            started = true
            order = startOrder
            startOrder++
        }
    }
}
