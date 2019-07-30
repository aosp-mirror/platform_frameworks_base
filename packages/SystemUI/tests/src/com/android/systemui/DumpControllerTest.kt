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
 * limitations under the License
 */

package com.android.systemui

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.FileDescriptor
import java.io.PrintWriter

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DumpControllerTest : SysuiTestCase() {

    private lateinit var controller: DumpController
    @Mock private lateinit var callback1: Dumpable
    @Mock private lateinit var callback2: Dumpable
    @Mock private lateinit var callback3: Dumpable
    @Mock private lateinit var fd: FileDescriptor
    @Mock private lateinit var pw: PrintWriter
    private val args = emptyArray<String>()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controller = DumpController()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testListenerOnlyAddedOnce() {
        controller.registerDumpable("cb1", callback1)
        controller.registerDumpable("cb1", callback2)
    }

    @Test
    fun testListenersCalledOnDump() {
        controller.apply {
            registerDumpable("cb1", callback1)
            registerDumpable("cb2", callback2)
        }

        controller.dump(fd, pw, args)

        verify(callback1 /* only once */).dump(fd, pw, args)
        verify(callback2 /* only once */).dump(fd, pw, args)
    }

    @Test
    fun testListenersAreFiltered() {
        controller.apply {
            registerDumpable("cb1", callback1)
            registerDumpable("cb2", callback2)
            registerDumpable("cb3", callback3)
        }

        val args = arrayOf("dependency", "DumpController", "cb3,cb1")
        controller.dump(fd, pw, args)

        verify(callback1 /* only once */).dump(fd, pw, args)
        verify(callback2, never()).dump(fd, pw, args)
        verify(callback3 /* only once */).dump(fd, pw, args)
    }

    @Test
    fun testFiltersAreNotCaseSensitive() {
        controller.apply {
            registerDumpable("cb1", callback1)
            registerDumpable("cb2", callback2)
            registerDumpable("cb3", callback3)
        }

        val args = arrayOf("dependency", "DumpController", "CB3")
        controller.dump(fd, pw, args)

        verify(callback1, never()).dump(fd, pw, args)
        verify(callback2, never()).dump(fd, pw, args)
        verify(callback3 /* only once */).dump(fd, pw, args)
    }

    @Test
    fun testFiltersAreIgnoredIfPrecedingArgsDontMatch() {
        controller.apply {
            registerDumpable("cb1", callback1)
            registerDumpable("cb2", callback2)
            registerDumpable("cb3", callback3)
        }

        val args = arrayOf("", "", "cb2")
        controller.dump(fd, pw, args)

        verify(callback1 /* only once */).dump(fd, pw, args)
        verify(callback2 /* only once */).dump(fd, pw, args)
        verify(callback3 /* only once */).dump(fd, pw, args)
    }

    @Test
    fun testRemoveListener() {
        controller.apply {
            registerDumpable("cb1", callback1)
            registerDumpable("cb2", callback2)
            unregisterDumpable(callback1)
        }

        controller.dump(fd, pw, args)

        verify(callback1, never()).dump(any(), any(), any())
        verify(callback2 /* only once */).dump(fd, pw, args)
    }
}
