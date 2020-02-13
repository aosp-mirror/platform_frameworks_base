/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.dump.DumpManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class BootCompleteCacheTest : SysuiTestCase() {

    private lateinit var bootCompleteCache: BootCompleteCacheImpl
    @Mock
    private lateinit var bootCompleteListener: BootCompleteCache.BootCompleteListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        bootCompleteCache = BootCompleteCacheImpl(mock(DumpManager::class.java))
    }

    @Test
    fun testFlagChange() {
        assertFalse(bootCompleteCache.isBootComplete())

        bootCompleteCache.setBootComplete()

        assertTrue(bootCompleteCache.isBootComplete())
    }

    @Test
    fun testDoubleSetIsNoOp() {
        assertFalse(bootCompleteCache.isBootComplete())

        bootCompleteCache.setBootComplete()
        bootCompleteCache.setBootComplete()

        assertTrue(bootCompleteCache.isBootComplete())
    }

    @Test
    fun testAddListenerGivesCurrentState_false() {
        val boot = bootCompleteCache.addListener(bootCompleteListener)
        assertFalse(boot)
    }

    @Test
    fun testAddListenerGivesCurrentState_true() {
        bootCompleteCache.setBootComplete()
        val boot = bootCompleteCache.addListener(bootCompleteListener)
        assertTrue(boot)
    }

    @Test
    fun testListenerCalledOnBootComplete() {
        bootCompleteCache.addListener(bootCompleteListener)

        bootCompleteCache.setBootComplete()
        verify(bootCompleteListener).onBootComplete()
    }

    @Test
    fun testListenerCalledOnBootComplete_onlyOnce() {
        bootCompleteCache.addListener(bootCompleteListener)

        bootCompleteCache.setBootComplete()
        bootCompleteCache.setBootComplete()

        verify(bootCompleteListener).onBootComplete()
    }

    @Test
    fun testListenerNotCalledIfBootIsAlreadyComplete() {
        bootCompleteCache.setBootComplete()

        bootCompleteCache.addListener(bootCompleteListener)

        bootCompleteCache.setBootComplete()

        verify(bootCompleteListener, never()).onBootComplete()
    }

    @Test
    fun testListenerRemovedNotCalled() {
        bootCompleteCache.addListener(bootCompleteListener)

        bootCompleteCache.removeListener(bootCompleteListener)

        bootCompleteCache.setBootComplete()

        verify(bootCompleteListener, never()).onBootComplete()
    }
}