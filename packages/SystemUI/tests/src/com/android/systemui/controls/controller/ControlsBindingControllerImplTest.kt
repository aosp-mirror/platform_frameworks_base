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
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
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
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsBindingControllerImplTest : SysuiTestCase() {

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> any(): T = Mockito.any<T>()
        private val TEST_COMPONENT_NAME_1 = ComponentName("TEST_PKG", "TEST_CLS_1")
        private val TEST_COMPONENT_NAME_2 = ComponentName("TEST_PKG", "TEST_CLS_2")
        private val TEST_COMPONENT_NAME_3 = ComponentName("TEST_PKG", "TEST_CLS_3")
    }

    @Mock
    private lateinit var mockControlsController: ControlsController

    @Captor
    private lateinit var subscriberCaptor: ArgumentCaptor<IControlsSubscriber.Stub>

    @Captor
    private lateinit var loadSubscriberCaptor: ArgumentCaptor<IControlsSubscriber.Stub>

    @Captor
    private lateinit var listStringCaptor: ArgumentCaptor<List<String>>

    private val user = UserHandle.of(mContext.userId)
    private val otherUser = UserHandle.of(user.identifier + 1)

    private val executor = FakeExecutor(FakeSystemClock())
    private lateinit var controller: ControlsBindingController
    private val providers = TestableControlsBindingControllerImpl.providers

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        providers.clear()

        controller = TestableControlsBindingControllerImpl(
                mContext, executor, Lazy { mockControlsController })
    }

    @After
    fun tearDown() {
        executor.advanceClockToLast()
        executor.runAllReady()
    }

    @Test
    fun testStartOnUser() {
        assertEquals(user.identifier, controller.currentUserId)
    }

    @Test
    fun testBindAndLoad() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoad(any())
    }

    @Test
    fun testBindAndLoad_cancel() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        val canceller = controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoad(capture(loadSubscriberCaptor))
        loadSubscriberCaptor.value.onSubscribe(Binder(), subscription)

        canceller.run()
        verify(providers[0]).cancelSubscription(subscription)
    }

    @Test
    fun testBindAndLoad_noCancelAfterOnComplete() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        val canceller = controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoad(capture(loadSubscriberCaptor))
        val b = Binder()
        loadSubscriberCaptor.value.onSubscribe(b, subscription)

        loadSubscriberCaptor.value.onComplete(b)
        canceller.run()
        verify(providers[0], never()).cancelSubscription(subscription)
    }

    @Test
    fun testLoad_onCompleteRemovesTimeout() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        val canceller = controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoad(capture(subscriberCaptor))
        val b = Binder()
        subscriberCaptor.value.onSubscribe(b, subscription)

        subscriberCaptor.value.onComplete(b)
        verify(providers[0]).cancelLoadTimeout()
    }

    @Test
    fun testLoad_onErrorRemovesTimeout() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        val canceller = controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoad(capture(subscriberCaptor))
        val b = Binder()
        subscriberCaptor.value.onSubscribe(b, subscription)

        subscriberCaptor.value.onError(b, "")
        verify(providers[0]).cancelLoadTimeout()
    }

    @Test
    fun testBindAndLoad_noCancelAfterOnError() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        val canceller = controller.bindAndLoad(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoad(capture(loadSubscriberCaptor))
        val b = Binder()
        loadSubscriberCaptor.value.onSubscribe(b, subscription)

        loadSubscriberCaptor.value.onError(b, "")
        canceller.run()
        verify(providers[0], never()).cancelSubscription(subscription)
    }

    @Test
    fun testBindAndLoadSuggested() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        controller.bindAndLoadSuggested(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoadSuggested(any())
    }

    @Test
    fun testLoadSuggested_onCompleteRemovesTimeout() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        controller.bindAndLoadSuggested(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoadSuggested(capture(subscriberCaptor))
        val b = Binder()
        subscriberCaptor.value.onSubscribe(b, subscription)

        subscriberCaptor.value.onComplete(b)
        verify(providers[0]).cancelLoadTimeout()
    }

    @Test
    fun testLoadSuggested_onErrorRemovesTimeout() {
        val callback = object : ControlsBindingController.LoadCallback {
            override fun error(message: String) {}

            override fun accept(t: List<Control>) {}
        }
        val subscription = mock(IControlsSubscription::class.java)

        controller.bindAndLoadSuggested(TEST_COMPONENT_NAME_1, callback)

        verify(providers[0]).maybeBindAndLoadSuggested(capture(subscriberCaptor))
        val b = Binder()
        subscriberCaptor.value.onSubscribe(b, subscription)

        subscriberCaptor.value.onError(b, "")
        verify(providers[0]).cancelLoadTimeout()
    }

    @Test
    fun testBindService() {
        controller.bindService(TEST_COMPONENT_NAME_1)
        executor.runAllReady()

        verify(providers[0]).bindService()
    }

    @Test
    fun testSubscribe() {
        val controlInfo1 = ControlInfo("id_1", "", "", DeviceTypes.TYPE_UNKNOWN)
        val controlInfo2 = ControlInfo("id_2", "", "", DeviceTypes.TYPE_UNKNOWN)
        val structure =
            StructureInfo(TEST_COMPONENT_NAME_1, "Home", listOf(controlInfo1, controlInfo2))

        controller.subscribe(structure)

        executor.runAllReady()

        val subs = mock(IControlsSubscription::class.java)
        verify(providers[0]).maybeBindAndSubscribe(
            capture(listStringCaptor), capture(subscriberCaptor))
        assertEquals(listStringCaptor.value,
            listOf(controlInfo1.controlId, controlInfo2.controlId))

        subscriberCaptor.value.onSubscribe(providers[0].token, subs)
    }

    @Test
    fun testUnsubscribe_notRefreshing() {
        controller.bindService(TEST_COMPONENT_NAME_2)
        controller.unsubscribe()

        executor.runAllReady()

        verify(providers[0], never()).cancelSubscription(any())
    }

    @Test
    fun testUnsubscribe_refreshing() {
        val controlInfo1 = ControlInfo("id_1", "", "", DeviceTypes.TYPE_UNKNOWN)
        val controlInfo2 = ControlInfo("id_2", "", "", DeviceTypes.TYPE_UNKNOWN)
        val structure =
            StructureInfo(TEST_COMPONENT_NAME_1, "Home", listOf(controlInfo1, controlInfo2))

        controller.subscribe(structure)
        executor.runAllReady()

        val subs = mock(IControlsSubscription::class.java)
        verify(providers[0]).maybeBindAndSubscribe(
            capture(listStringCaptor), capture(subscriberCaptor))
        assertEquals(listStringCaptor.value,
            listOf(controlInfo1.controlId, controlInfo2.controlId))

        subscriberCaptor.value.onSubscribe(providers[0].token, subs)
        executor.runAllReady()

        controller.unsubscribe()
        executor.runAllReady()

        verify(providers[0]).cancelSubscription(subs)
    }

    @Test
    fun testCurrentUserId() {
        controller.changeUser(otherUser)
        assertEquals(otherUser.identifier, controller.currentUserId)
    }

    @Test
    fun testChangeUsers_providersHaveCorrectUser() {
        controller.bindService(TEST_COMPONENT_NAME_1)
        assertEquals(user, providers[0].user)

        controller.changeUser(otherUser)

        controller.bindService(TEST_COMPONENT_NAME_2)
        assertEquals(otherUser, providers[0].user)
    }

    @Test
    fun testChangeUsers_providersUnbound() {
        controller.bindService(TEST_COMPONENT_NAME_1)
        controller.changeUser(otherUser)

        verify(providers[0]).unbindService()

        controller.bindService(TEST_COMPONENT_NAME_2)
        controller.changeUser(user)

        verify(providers[0]).unbindService()
    }

    @Test
    fun testComponentRemoved_existingIsUnbound() {
        controller.bindService(TEST_COMPONENT_NAME_1)

        controller.onComponentRemoved(TEST_COMPONENT_NAME_1)

        executor.runAllReady()

        verify(providers[0], times(1)).unbindService()
    }
}

class TestableControlsBindingControllerImpl(
    context: Context,
    executor: DelayableExecutor,
    lazyController: Lazy<ControlsController>
) : ControlsBindingControllerImpl(context, executor, lazyController) {

    companion object {
        val providers = mutableListOf<ControlsProviderLifecycleManager>()
    }

    // Replaces the real provider with a mock and puts the mock in a visible set.
    // The mock has the same componentName and user as the real one would have
    override fun createProviderManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        val realProvider = super.createProviderManager(component)
        val provider = mock(ControlsProviderLifecycleManager::class.java)
        val token = Binder()
        `when`(provider.componentName).thenReturn(realProvider.componentName)
        `when`(provider.token).thenReturn(token)
        `when`(provider.user).thenReturn(realProvider.user)

        providers.clear()
        providers.add(provider)

        return provider
    }
}
