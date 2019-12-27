/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.controller

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import dagger.Lazy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsBindingControllerTest : SysuiTestCase() {

    companion object {
        fun <T> any(): T = Mockito.any<T>()
        private val TEST_COMPONENT_NAME_1 = ComponentName("TEST_PKG", "TEST_CLS_1")
        private val TEST_COMPONENT_NAME_2 = ComponentName("TEST_PKG", "TEST_CLS_2")
        private val TEST_COMPONENT_NAME_3 = ComponentName("TEST_PKG", "TEST_CLS_3")
    }

    @Mock
    private lateinit var mockControlsController: ControlsController

    private val executor = FakeExecutor(FakeSystemClock())
    private lateinit var controller: ControlsBindingController
    private val providers = TestableControlsBindingControllerImpl.providers

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controller = TestableControlsBindingControllerImpl(
                mContext, executor, Lazy { mockControlsController })
    }

    @After
    fun tearDown() {
        executor.advanceClockToLast()
        executor.runAllReady()
        providers.clear()
    }

    @Test
    fun testBindAndLoad() {
        val callback: (List<Control>) -> Unit = {}
        controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        assertEquals(1, providers.size)
        val provider = providers.first()
        verify(provider).maybeBindAndLoad(callback)
    }

    @Test
    fun testBindServices() {
        controller.bindServices(listOf(TEST_COMPONENT_NAME_1, TEST_COMPONENT_NAME_2))
        executor.runAllReady()

        assertEquals(2, providers.size)
        assertEquals(setOf(TEST_COMPONENT_NAME_1, TEST_COMPONENT_NAME_2),
                providers.map { it.componentName }.toSet())
        providers.forEach {
            verify(it).bindPermanently()
        }
    }

    @Test
    fun testSubscribe() {
        val controlInfo1 = ControlInfo(TEST_COMPONENT_NAME_1, "id_1", "", DeviceTypes.TYPE_UNKNOWN)
        val controlInfo2 = ControlInfo(TEST_COMPONENT_NAME_2, "id_2", "", DeviceTypes.TYPE_UNKNOWN)
        controller.bindServices(listOf(TEST_COMPONENT_NAME_3))

        controller.subscribe(listOf(controlInfo1, controlInfo2))

        executor.runAllReady()

        assertEquals(3, providers.size)
        val provider1 = providers.first { it.componentName == TEST_COMPONENT_NAME_1 }
        val provider2 = providers.first { it.componentName == TEST_COMPONENT_NAME_2 }
        val provider3 = providers.first { it.componentName == TEST_COMPONENT_NAME_3 }

        verify(provider1).maybeBindAndSubscribe(listOf(controlInfo1.controlId))
        verify(provider2).maybeBindAndSubscribe(listOf(controlInfo2.controlId))
        verify(provider3, never()).maybeBindAndSubscribe(any())
        verify(provider3).unbindService() // Not needed services will be unbound
    }

    @Test
    fun testUnsubscribe_notRefreshing() {
        controller.bindServices(listOf(TEST_COMPONENT_NAME_1, TEST_COMPONENT_NAME_2))
        controller.unsubscribe()

        executor.runAllReady()

        providers.forEach {
            verify(it, never()).unsubscribe()
        }
    }

    @Test
    fun testUnsubscribe_refreshing() {
        val controlInfo1 = ControlInfo(TEST_COMPONENT_NAME_1, "id_1", "", DeviceTypes.TYPE_UNKNOWN)
        val controlInfo2 = ControlInfo(TEST_COMPONENT_NAME_2, "id_2", "", DeviceTypes.TYPE_UNKNOWN)

        controller.subscribe(listOf(controlInfo1, controlInfo2))

        controller.unsubscribe()

        executor.runAllReady()

        providers.forEach {
            verify(it).unsubscribe()
        }
    }
}

class TestableControlsBindingControllerImpl(
    context: Context,
    executor: DelayableExecutor,
    lazyController: Lazy<ControlsController>
) : ControlsBindingControllerImpl(context, executor, lazyController) {

    companion object {
        val providers = mutableSetOf<ControlsProviderLifecycleManager>()
    }

    override fun createProviderManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        val provider = mock(ControlsProviderLifecycleManager::class.java)
        val token = Binder()
        `when`(provider.componentName).thenReturn(component)
        `when`(provider.token).thenReturn(token)
        providers.add(provider)
        return provider
    }
}