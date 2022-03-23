/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (149the "License");
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
import android.content.Intent
import android.content.ServiceConnection
import android.os.UserHandle
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsProvider
import android.service.controls.IControlsSubscriber
import android.service.controls.actions.ControlAction
import android.service.controls.actions.ControlActionWrapper
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsProviderLifecycleManagerTest : SysuiTestCase() {

    @Mock
    private lateinit var actionCallbackService: IControlsActionCallback.Stub
    @Mock
    private lateinit var subscriberService: IControlsSubscriber.Stub
    @Mock
    private lateinit var service: IControlsProvider.Stub

    @Captor
    private lateinit var wrapperCaptor: ArgumentCaptor<ControlActionWrapper>

    private val componentName = ComponentName("test.pkg", "test.cls")
    private lateinit var manager: ControlsProviderLifecycleManager
    private lateinit var executor: FakeExecutor

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        context.addMockService(componentName, service)
        executor = FakeExecutor(FakeSystemClock())
        `when`(service.asBinder()).thenCallRealMethod()
        `when`(service.queryLocalInterface(ArgumentMatchers.anyString())).thenReturn(service)

        manager = ControlsProviderLifecycleManager(
                context,
                executor,
                actionCallbackService,
                UserHandle.of(0),
                componentName
        )
    }

    @After
    fun tearDown() {
        manager.unbindService()
    }

    @Test
    fun testBindService() {
        manager.bindService()
        executor.runAllReady()
        assertTrue(context.isBound(componentName))
    }

    @Test
    fun testNullBinding() {
        val mockContext = mock(Context::class.java)
        lateinit var serviceConnection: ServiceConnection
        `when`(mockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer {
            val component = (it.arguments[0] as Intent).component
            if (component == componentName) {
                serviceConnection = it.arguments[1] as ServiceConnection
                serviceConnection.onNullBinding(component)
                true
            } else {
                false
            }
        }

        val nullManager = ControlsProviderLifecycleManager(
                mockContext,
                executor,
                actionCallbackService,
                UserHandle.of(0),
                componentName
        )

        nullManager.bindService()
        executor.runAllReady()

        verify(mockContext).unbindService(serviceConnection)
    }

    @Test
    fun testUnbindService() {
        manager.bindService()
        executor.runAllReady()

        manager.unbindService()
        executor.runAllReady()

        assertFalse(context.isBound(componentName))
    }

    @Test
    fun testMaybeBindAndLoad() {
        manager.maybeBindAndLoad(subscriberService)
        executor.runAllReady()

        verify(service).load(subscriberService)

        assertTrue(context.isBound(componentName))
    }

    @Test
    fun testMaybeUnbind_bindingAndCallback() {
        manager.maybeBindAndLoad(subscriberService)
        executor.runAllReady()

        manager.unbindService()
        executor.runAllReady()
        assertFalse(context.isBound(componentName))
    }

    @Test
    fun testMaybeBindAndLoad_timeout() {
        manager.maybeBindAndLoad(subscriberService)
        executor.runAllReady()

        executor.advanceClockToLast()
        executor.runAllReady()

        verify(subscriberService).onError(any(), anyString())
    }

    @Test
    fun testMaybeBindAndLoad_timeoutCancelled() {
        manager.maybeBindAndLoad(subscriberService)
        executor.runAllReady()

        manager.cancelLoadTimeout()

        executor.advanceClockToLast()
        executor.runAllReady()

        verify(subscriberService, never()).onError(any(), anyString())
    }

    @Test
    fun testMaybeBindAndSubscribe() {
        val list = listOf("TEST_ID")
        manager.maybeBindAndSubscribe(list, subscriberService)
        executor.runAllReady()

        assertTrue(context.isBound(componentName))
        verify(service).subscribe(list, subscriberService)
    }

    @Test
    fun testMaybeBindAndAction() {
        val controlId = "TEST_ID"
        val action = ControlAction.ERROR_ACTION
        manager.maybeBindAndSendAction(controlId, action)
        executor.runAllReady()

        assertTrue(context.isBound(componentName))
        verify(service).action(eq(controlId), capture(wrapperCaptor),
                eq(actionCallbackService))
        assertEquals(action, wrapperCaptor.getValue().getWrappedAction())
    }
}
