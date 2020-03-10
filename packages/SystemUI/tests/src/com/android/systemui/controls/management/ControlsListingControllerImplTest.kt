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

package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.settingslib.applications.ServiceListing
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsListingControllerImplTest : SysuiTestCase() {

    companion object {
        private const val TEST_LABEL = "TEST_LABEL"
        private const val TEST_PERMISSION = "permission"
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> any(): T = Mockito.any<T>()
    }

    @Mock
    private lateinit var mockSL: ServiceListing
    @Mock
    private lateinit var mockCallback: ControlsListingController.ControlsListingCallback
    @Mock
    private lateinit var mockCallbackOther: ControlsListingController.ControlsListingCallback
    @Mock
    private lateinit var serviceInfo: ServiceInfo
    @Mock
    private lateinit var componentName: ComponentName

    private val executor = FakeExecutor(FakeSystemClock())

    private lateinit var controller: ControlsListingControllerImpl

    private var serviceListingCallbackCaptor =
            ArgumentCaptor.forClass(ServiceListing.Callback::class.java)

    private val user = mContext.userId
    private val otherUser = user + 1

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(serviceInfo.componentName).thenReturn(componentName)

        val wrapper = object : ContextWrapper(mContext) {
            override fun createContextAsUser(user: UserHandle, flags: Int): Context {
                return baseContext
            }
        }

        controller = ControlsListingControllerImpl(wrapper, executor, { mockSL })
        verify(mockSL).addCallback(capture(serviceListingCallbackCaptor))
    }

    @After
    fun tearDown() {
        executor.advanceClockToLast()
        executor.runAllReady()
    }

    @Test
    fun testInitialStateListening() {
        verify(mockSL).setListening(true)
        verify(mockSL).reload()
    }

    @Test
    fun testStartsOnUser() {
        assertEquals(user, controller.currentUserId)
    }

    @Test
    fun testCallbackCalledWhenAdded() {
        controller.addCallback(mockCallback)
        executor.runAllReady()
        verify(mockCallback).onServicesUpdated(any())
        reset(mockCallback)

        controller.addCallback(mockCallbackOther)
        executor.runAllReady()
        verify(mockCallbackOther).onServicesUpdated(any())
        verify(mockCallback, never()).onServicesUpdated(any())
    }

    @Test
    fun testCallbackGetsList() {
        val list = listOf(serviceInfo)
        controller.addCallback(mockCallback)
        controller.addCallback(mockCallbackOther)

        @Suppress("unchecked_cast")
        val captor: ArgumentCaptor<List<ControlsServiceInfo>> =
                ArgumentCaptor.forClass(List::class.java)
                        as ArgumentCaptor<List<ControlsServiceInfo>>

        executor.runAllReady()
        reset(mockCallback)
        reset(mockCallbackOther)

        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()
        verify(mockCallback).onServicesUpdated(capture(captor))
        assertEquals(1, captor.value.size)
        assertEquals(componentName.flattenToString(), captor.value[0].key)

        verify(mockCallbackOther).onServicesUpdated(capture(captor))
        assertEquals(1, captor.value.size)
        assertEquals(componentName.flattenToString(), captor.value[0].key)
    }

    @Test
    fun testChangeUser() {
        controller.changeUser(UserHandle.of(otherUser))
        executor.runAllReady()
        assertEquals(otherUser, controller.currentUserId)

        val inOrder = inOrder(mockSL)
        inOrder.verify(mockSL).setListening(false)
        inOrder.verify(mockSL).addCallback(any()) // We add a callback because we replaced the SL
        inOrder.verify(mockSL).setListening(true)
        inOrder.verify(mockSL).reload()
    }
}