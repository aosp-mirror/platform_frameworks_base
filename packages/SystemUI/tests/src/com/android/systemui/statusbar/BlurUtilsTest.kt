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

package com.android.systemui.statusbar

import android.content.res.Resources
import android.view.SurfaceControl
import android.view.ViewRootImpl
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class BlurUtilsTest : SysuiTestCase() {

    @Mock lateinit var resources: Resources
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var transaction: SurfaceControl.Transaction
    lateinit var blurUtils: BlurUtils

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        blurUtils = TestableBlurUtils()
    }

    @Test
    fun testApplyBlur_noViewRoot_doesntCrash() {
        blurUtils.applyBlur(null /* viewRootImple */, 10 /* radius */)
    }

    @Test
    fun testApplyBlur_invalidSurfaceControl() {
        val surfaceControl = mock(SurfaceControl::class.java)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        `when`(viewRootImpl.surfaceControl).thenReturn(surfaceControl)
        blurUtils.applyBlur(viewRootImpl, 10 /* radius */)
    }

    @Test
    fun testApplyBlur_success() {
        val radius = 10
        val surfaceControl = mock(SurfaceControl::class.java)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        `when`(viewRootImpl.surfaceControl).thenReturn(surfaceControl)
        `when`(surfaceControl.isValid).thenReturn(true)
        blurUtils.applyBlur(viewRootImpl, radius)
        verify(transaction).setBackgroundBlurRadius(eq(surfaceControl), eq(radius))
        verify(transaction).apply()
    }

    inner class TestableBlurUtils() : BlurUtils(resources, dumpManager) {
        override fun supportsBlursOnWindows(): Boolean {
            return true
        }

        override fun createTransaction(): SurfaceControl.Transaction {
            return transaction
        }
    }
}