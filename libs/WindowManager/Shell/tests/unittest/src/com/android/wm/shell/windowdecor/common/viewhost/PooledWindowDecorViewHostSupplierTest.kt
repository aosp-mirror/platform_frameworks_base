/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.wm.shell.windowdecor.common.viewhost

import android.content.res.Configuration
import android.graphics.Region
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.util.StubTransaction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock

/**
 * Tests for [PooledWindowDecorViewHostSupplier].
 *
 * Build/Install/Run: atest WMShellUnitTests:PooledWindowDecorViewHostSupplierTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class PooledWindowDecorViewHostSupplierTest : ShellTestCase() {

    private lateinit var supplier: PooledWindowDecorViewHostSupplier

    @Test
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun acquire_poolBelowLimit_caches() = runTest {
        supplier = createSupplier(maxPoolSize = 5)

        val viewHost = FakeWindowDecorViewHost()
        supplier.release(viewHost, StubTransaction())

        assertThat(supplier.acquire(context, context.display)).isEqualTo(viewHost)
    }

    @Test
    fun release_poolBelowLimit_doesNotReleaseViewHost() = runTest {
        supplier = createSupplier(maxPoolSize = 5)

        val viewHost = FakeWindowDecorViewHost()
        val mockT = mock<SurfaceControl.Transaction>()
        supplier.release(viewHost, mockT)

        assertThat(viewHost.released).isFalse()
    }

    @Test
    fun release_poolAtLimit_doesNotCache() = runTest {
        supplier = createSupplier(maxPoolSize = 1)
        val viewHost = FakeWindowDecorViewHost()
        supplier.release(viewHost, StubTransaction()) // Maxes pool.

        val viewHost2 = FakeWindowDecorViewHost()
        supplier.release(viewHost2, StubTransaction()) // Beyond limit.

        assertThat(supplier.acquire(context, context.display)).isEqualTo(viewHost)
        // Second one wasn't cached, so the acquired one should've been a new instance.
        assertThat(supplier.acquire(context, context.display)).isNotEqualTo(viewHost2)
    }

    @Test
    fun release_poolAtLimit_releasesViewHost() = runTest {
        supplier = createSupplier(maxPoolSize = 1)
        val viewHost = FakeWindowDecorViewHost()
        supplier.release(viewHost, StubTransaction()) // Maxes pool.

        val viewHost2 = FakeWindowDecorViewHost()
        val mockT = mock<SurfaceControl.Transaction>()
        supplier.release(viewHost2, mockT) // Beyond limit.

        // Second one doesn't fit, so it needs to be released.
        assertThat(viewHost2.released).isTrue()
    }

    private fun CoroutineScope.createSupplier(maxPoolSize: Int) =
        PooledWindowDecorViewHostSupplier(this, maxPoolSize)

    private class FakeWindowDecorViewHost : WindowDecorViewHost {
        var released = false
            private set

        override val surfaceControl: SurfaceControl
            get() = SurfaceControl()

        override fun updateView(
            view: View,
            attrs: WindowManager.LayoutParams,
            configuration: Configuration,
            touchableRegion: Region?,
            onDrawTransaction: SurfaceControl.Transaction?,
        ) {}

        override fun updateViewAsync(
            view: View,
            attrs: WindowManager.LayoutParams,
            configuration: Configuration,
            touchableRegion: Region?,
        ) {}

        override fun release(t: SurfaceControl.Transaction) {
            released = true
        }
    }
}
