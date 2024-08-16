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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ControlsProviderLifecycleManagerTest : SysuiTestCase() {

    @Mock
    private lateinit var actionCallbackService: IControlsActionCallback.Stub
    @Mock
    private lateinit var subscriberService: IControlsSubscriber.Stub
    @Mock
    private lateinit var service: IControlsProvider.Stub
    @Mock
    private lateinit var packageUpdateMonitor: PackageUpdateMonitor
    @Captor
    private lateinit var wrapperCaptor: ArgumentCaptor<ControlActionWrapper>

    private lateinit var packageUpdateMonitorFactory: FakePackageUpdateMonitorFactory

    private val componentName = ComponentName("test.pkg", "test.cls")
    private lateinit var manager: ControlsProviderLifecycleManager
    private lateinit var executor: FakeExecutor
    private lateinit var fakeSystemClock: FakeSystemClock

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        private val USER = UserHandle.of(0)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        context.addMockService(componentName, service)
        fakeSystemClock = FakeSystemClock()
        executor = FakeExecutor(fakeSystemClock)
        `when`(service.asBinder()).thenCallRealMethod()
        `when`(service.queryLocalInterface(anyString())).thenReturn(service)

        packageUpdateMonitorFactory = FakePackageUpdateMonitorFactory(packageUpdateMonitor)

        manager = ControlsProviderLifecycleManager(
                context,
                executor,
                actionCallbackService,
                USER,
                componentName,
                packageUpdateMonitorFactory,
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
    fun testBindForPanel() {
        manager.bindServiceForPanel()
        executor.runAllReady()
        assertTrue(context.isBound(componentName))
    }

    @Test
    fun testUnbindPanelIsUnbound() {
        manager.bindServiceForPanel()
        executor.runAllReady()
        manager.unbindService()
        executor.runAllReady()
        assertFalse(context.isBound(componentName))
    }

    @Test
    fun testNullBinding() {
        val mockContext = mock<Context>()
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
                USER,
                componentName,
                packageUpdateMonitorFactory,
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

    @Test
    fun testFalseBindCallsUnbind() {
        val falseContext = mock<Context>()
        `when`(falseContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(false)
        val manager = ControlsProviderLifecycleManager(
            falseContext,
            executor,
            actionCallbackService,
            USER,
            componentName,
            packageUpdateMonitorFactory,
        )
        manager.bindService()
        executor.runAllReady()

        val captor = ArgumentCaptor.forClass(
            ServiceConnection::class.java
        )
        verify(falseContext).bindServiceAsUser(any(), captor.capture(), anyInt(), any())
        verify(falseContext).unbindService(captor.value)
    }

    @Test
    fun testPackageUpdateMonitor_createdWithCorrectValues() {
        assertEquals(USER, packageUpdateMonitorFactory.lastUser)
        assertEquals(componentName.packageName, packageUpdateMonitorFactory.lastPackage)
    }

    @Test
    fun testBound_packageMonitorStartsMonitoring() {
        manager.bindService()
        executor.runAllReady()

        // Service will connect and monitoring should start
        verify(packageUpdateMonitor).startMonitoring()
    }

    @Test
    fun testOnPackageUpdateWhileBound_unbound_thenBindAgain() {
        val mockContext = mock<Context> {
            `when`(bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true)
        }

        val manager = ControlsProviderLifecycleManager(
            mockContext,
            executor,
            actionCallbackService,
            USER,
            componentName,
            packageUpdateMonitorFactory,
        )

        manager.bindService()
        executor.runAllReady()
        clearInvocations(mockContext)

        packageUpdateMonitorFactory.lastCallback?.run()
        executor.runAllReady()

        val inOrder = inOrder(mockContext)
        inOrder.verify(mockContext).unbindService(any())
        inOrder.verify(mockContext).bindServiceAsUser(any(), any(), anyInt(), any())
    }

    @Test
    fun testOnPackageUpdateWhenNotBound_nothingHappens() {
        val mockContext = mock<Context> {
            `when`(bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true)
        }

        ControlsProviderLifecycleManager(
            mockContext,
            executor,
            actionCallbackService,
            USER,
            componentName,
            packageUpdateMonitorFactory,
        )

        packageUpdateMonitorFactory.lastCallback?.run()
        verifyNoMoreInteractions(mockContext)
    }

    @Test
    fun testUnbindService_stopsTracking() {
        manager.bindService()
        manager.unbindService()
        executor.runAllReady()

        verify(packageUpdateMonitor).stopMonitoring()
    }

    @Test
    fun testRebindForPanelWithSameFlags() {
        val mockContext = mock<Context> {
            `when`(bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true)
        }

        val manager = ControlsProviderLifecycleManager(
            mockContext,
            executor,
            actionCallbackService,
            USER,
            componentName,
            packageUpdateMonitorFactory,
        )

        manager.bindServiceForPanel()
        executor.runAllReady()

        val flagsCaptor = argumentCaptor<Int>()
        verify(mockContext).bindServiceAsUser(any(), any(), capture(flagsCaptor), any())
        clearInvocations(mockContext)

        packageUpdateMonitorFactory.lastCallback?.run()
        executor.runAllReady()

        verify(mockContext).bindServiceAsUser(any(), any(), eq(flagsCaptor.value), any())
    }

    @Test
    fun testBindAfterSecurityExceptionWorks() {
        val mockContext = mock<Context> {
            `when`(bindServiceAsUser(any(), any(), anyInt(), any()))
                .thenThrow(SecurityException("exception"))
        }

        val manager = ControlsProviderLifecycleManager(
            mockContext,
            executor,
            actionCallbackService,
            USER,
            componentName,
            packageUpdateMonitorFactory,
        )

        manager.bindServiceForPanel()
        executor.runAllReady()

        `when`(mockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true)

        manager.bindServiceForPanel()
        executor.runAllReady()

        verify(mockContext, times(2)).bindServiceAsUser(any(), any(), anyInt(), any())
    }

    private class FakePackageUpdateMonitorFactory(
        private val monitor: PackageUpdateMonitor
    ) : PackageUpdateMonitor.Factory {

        var lastUser: UserHandle? = null
        var lastPackage: String? = null
        var lastCallback: Runnable? = null

        override fun create(
            user: UserHandle,
            packageName: String,
            callback: Runnable
        ): PackageUpdateMonitor {
            lastUser = user
            lastPackage = packageName
            lastCallback = callback
            return monitor
        }
    }
}

