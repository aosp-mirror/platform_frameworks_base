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

import android.os.RemoteException
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsProvider
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
import android.service.controls.actions.ControlAction
import android.service.controls.actions.ControlActionWrapper
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ServiceWrapperTest : SysuiTestCase() {

    @Mock
    private lateinit var service: IControlsProvider

    @Mock
    private lateinit var subscription: IControlsSubscription

    @Mock
    private lateinit var subscriber: IControlsSubscriber

    @Mock
    private lateinit var actionCallback: IControlsActionCallback

    @Captor
    private lateinit var wrapperCaptor: ArgumentCaptor<ControlActionWrapper>

    private val exception = RemoteException()

    private lateinit var wrapper: ServiceWrapper

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        wrapper = ServiceWrapper(service)
    }

    @Test
    fun testLoad_happyPath() {
        val result = wrapper.load(subscriber)

        assertTrue(result)
        verify(service).load(subscriber)
    }

    @Test
    fun testLoad_error() {
        `when`(service.load(any())).thenThrow(exception)
        val result = wrapper.load(subscriber)

        assertFalse(result)
    }

    @Test
    fun testLoadSuggested_happyPath() {
        val result = wrapper.loadSuggested(subscriber)

        assertTrue(result)
        verify(service).loadSuggested(subscriber)
    }

    @Test
    fun testLoadSuggested_error() {
        `when`(service.loadSuggested(any())).thenThrow(exception)
        val result = wrapper.loadSuggested(subscriber)

        assertFalse(result)
    }

    @Test
    fun testSubscribe_happyPath() {
        val list = listOf("TEST_ID")
        val result = wrapper.subscribe(list, subscriber)

        assertTrue(result)
        verify(service).subscribe(list, subscriber)
    }

    @Test
    fun testSubscribe_error() {
        `when`(service.subscribe(any(), any())).thenThrow(exception)

        val list = listOf("TEST_ID")
        val result = wrapper.subscribe(list, subscriber)

        assertFalse(result)
    }

    @Test
    fun testCancel_happyPath() {
        val result = wrapper.cancel(subscription)

        assertTrue(result)
        verify(subscription).cancel()
    }

    @Test
    fun testCancel_error() {
        `when`(subscription.cancel()).thenThrow(exception)
        val result = wrapper.cancel(subscription)

        assertFalse(result)
    }

    @Test
    fun testOnAction_happyPath() {
        val id = "TEST_ID"
        val action = ControlAction.ERROR_ACTION

        val result = wrapper.action(id, action, actionCallback)

        assertTrue(result)
        verify(service).action(eq(id), capture(wrapperCaptor),
                eq(actionCallback))
        assertEquals(action, wrapperCaptor.getValue().getWrappedAction())
    }

    @Test
    fun testOnAction_error() {
        `when`(service.action(any(), any(), any())).thenThrow(exception)

        val id = "TEST_ID"
        val action = ControlAction.ERROR_ACTION

        val result = wrapper.action(id, action, actionCallback)

        assertFalse(result)
    }
}
