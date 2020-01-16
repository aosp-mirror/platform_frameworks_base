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
import android.service.controls.Control
import android.service.controls.IControlsProvider
import android.service.controls.IControlsProviderCallback
import android.service.controls.actions.ControlAction
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsProviderLifecycleManagerTest : SysuiTestCase() {

    @Mock
    private lateinit var serviceCallback: IControlsProviderCallback.Stub
    @Mock
    private lateinit var service: IControlsProvider.Stub

    private val componentName = ComponentName("test.pkg", "test.cls")
    private lateinit var manager: ControlsProviderLifecycleManager
    private lateinit var executor: DelayableExecutor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mContext.addMockService(componentName, service)
        executor = FakeExecutor(FakeSystemClock())
        `when`(service.asBinder()).thenCallRealMethod()
        `when`(service.queryLocalInterface(ArgumentMatchers.anyString())).thenReturn(service)

        manager = ControlsProviderLifecycleManager(
                context,
                executor,
                serviceCallback,
                componentName
        )
    }

    @After
    fun tearDown() {
        manager.unbindService()
    }

    @Test
    fun testBindService() {
        manager.bindPermanently()
        assertTrue(mContext.isBound(componentName))
    }

    @Test
    fun testUnbindService() {
        manager.bindPermanently()
        manager.unbindService()
        assertFalse(mContext.isBound(componentName))
    }

    @Test
    fun testMaybeBindAndLoad() {
        val callback: (List<Control>) -> Unit = {}
        manager.maybeBindAndLoad(callback)

        verify(service).load()

        assertTrue(mContext.isBound(componentName))
        assertEquals(callback, manager.lastLoadCallback)
    }

    @Test
    fun testMaybeUnbind_bindingAndCallback() {
        manager.maybeBindAndLoad {}

        manager.maybeUnbindAndRemoveCallback()
        assertFalse(mContext.isBound(componentName))
        assertNull(manager.lastLoadCallback)
    }

    @Test
    fun testUnsubscribe() {
        manager.bindPermanently()
        manager.unsubscribe()

        verify(service).unsubscribe()
    }

    @Test
    fun testMaybeBindAndSubscribe() {
        val list = listOf("TEST_ID")
        manager.maybeBindAndSubscribe(list)

        assertTrue(mContext.isBound(componentName))
        verify(service).subscribe(list)
    }

    @Test
    fun testMaybeBindAndAction() {
        val controlId = "TEST_ID"
        val action = ControlAction.UNKNOWN_ACTION
        manager.maybeBindAndSendAction(controlId, action)

        assertTrue(mContext.isBound(componentName))
        verify(service).onAction(controlId, action)
    }
}