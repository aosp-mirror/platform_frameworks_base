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
import android.service.controls.IControlsProvider
import android.service.controls.actions.ControlAction
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsProviderServiceWrapperTest : SysuiTestCase() {

    @Mock
    private lateinit var service: IControlsProvider

    private val exception = RemoteException()

    private lateinit var wrapper: ControlsProviderServiceWrapper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        wrapper = ControlsProviderServiceWrapper(service)
    }

    @Test
    fun testLoad_happyPath() {
        val result = wrapper.load()

        assertTrue(result)
        verify(service).load()
    }

    @Test
    fun testLoad_error() {
        `when`(service.load()).thenThrow(exception)
        val result = wrapper.load()

        assertFalse(result)
    }

    @Test
    fun testSubscribe_happyPath() {
        val list = listOf("TEST_ID")
        val result = wrapper.subscribe(list)

        assertTrue(result)
        verify(service).subscribe(list)
    }

    @Test
    fun testSubscribe_error() {
        `when`(service.subscribe(any())).thenThrow(exception)

        val list = listOf("TEST_ID")
        val result = wrapper.subscribe(list)

        assertFalse(result)
    }

    @Test
    fun testUnsubscribe_happyPath() {
        val result = wrapper.unsubscribe()

        assertTrue(result)
        verify(service).unsubscribe()
    }

    @Test
    fun testUnsubscribe_error() {
        `when`(service.unsubscribe()).thenThrow(exception)
        val result = wrapper.unsubscribe()

        assertFalse(result)
    }

    @Test
    fun testOnAction_happyPath() {
        val id = "TEST_ID"
        val action = ControlAction.UNKNOWN_ACTION

        val result = wrapper.onAction(id, action)

        assertTrue(result)
        verify(service).onAction(id, action)
    }

    @Test
    fun testOnAction_error() {
        `when`(service.onAction(any(), any())).thenThrow(exception)

        val id = "TEST_ID"
        val action = ControlAction.UNKNOWN_ACTION

        val result = wrapper.onAction(id, action)

        assertFalse(result)
    }
}