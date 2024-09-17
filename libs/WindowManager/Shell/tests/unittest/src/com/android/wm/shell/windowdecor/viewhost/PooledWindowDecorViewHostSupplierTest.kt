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
package com.android.wm.shell.windowdecor.viewhost

import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.sysui.ShellInit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [PooledWindowDecorViewHostSupplier].
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:PooledWindowDecorViewHostSupplierTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class PooledWindowDecorViewHostSupplierTest : ShellTestCase() {

    private val testExecutor = TestShellExecutor()
    private val testShellInit = ShellInit(testExecutor)
    @Mock
    private lateinit var mockViewHostFactory: ReusableWindowDecorViewHost.Factory

    private lateinit var supplier: PooledWindowDecorViewHostSupplier

    @Test
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun onInit_warmsAndPoolsViewHosts() = runTest {
        supplier = createSupplier(maxPoolSize = 5, preWarmSize = 2)
        val mockViewHost1 = mock<ReusableWindowDecorViewHost>()
        val mockViewHost2 = mock<ReusableWindowDecorViewHost>()
        whenever(mockViewHostFactory
            .create(context, this, context.display, id = 0))
            .thenReturn(mockViewHost1)
        whenever(mockViewHostFactory
            .create(context, this, context.display, id = 1))
            .thenReturn(mockViewHost2)

        testExecutor.flushAll()
        advanceUntilIdle()

        // Both were warmed up.
        verify(mockViewHost1).warmUp()
        verify(mockViewHost2).warmUp()
        // Both were released, so re-acquiring them provides the same instance.
        assertThat(mockViewHost2)
            .isEqualTo(supplier.acquire(context, context.display))
        assertThat(mockViewHost1)
            .isEqualTo(supplier.acquire(context, context.display))
    }

    @Test(expected = Throwable::class)
    fun onInit_warmUpSizeExceedsPoolSize_throws() = runTest {
        createSupplier(maxPoolSize = 3, preWarmSize = 4)
    }

    @Test
    fun acquire_poolHasInstances_reuses() = runTest {
        supplier = createSupplier(maxPoolSize = 5, preWarmSize = 0)

        // Prepare the pool with one instance.
        val mockViewHost = mock<ReusableWindowDecorViewHost>()
        supplier.release(mockViewHost, SurfaceControl.Transaction())

        assertThat(mockViewHost)
            .isEqualTo(supplier.acquire(context, context.display))
        verify(mockViewHostFactory, never()).create(any(), any(), any(), any())
    }

    @Test
    fun acquire_pooledHasZeroInstances_creates() = runTest {
        supplier = createSupplier(maxPoolSize = 5, preWarmSize = 0)

        supplier.acquire(context, context.display)

        verify(mockViewHostFactory).create(context, this, context.display, id = 0)
    }

    @Test
    fun release_poolBelowLimit_caches() = runTest {
        supplier = createSupplier(maxPoolSize = 5, preWarmSize = 0)

        val mockViewHost = mock<ReusableWindowDecorViewHost>()
        val mockT = mock<SurfaceControl.Transaction>()
        supplier.release(mockViewHost, mockT)

        assertThat(mockViewHost)
            .isEqualTo(supplier.acquire(context, context.display))
    }

    @Test
    fun release_poolBelowLimit_doesNotReleaseViewHost() = runTest {
        supplier = createSupplier(maxPoolSize = 5, preWarmSize = 0)

        val mockViewHost = mock<ReusableWindowDecorViewHost>()
        val mockT = mock<SurfaceControl.Transaction>()
        supplier.release(mockViewHost, mockT)

        verify(mockViewHost, never()).release(mockT)
    }

    @Test
    fun release_poolAtLimit_doesNotCache() = runTest {
        supplier = createSupplier(maxPoolSize = 1, preWarmSize = 0)
        val mockT = mock<SurfaceControl.Transaction>()
        val mockViewHost = mock<ReusableWindowDecorViewHost>()
        supplier.release(mockViewHost, mockT) // Maxes pool.

        val mockViewHost2 = mock<ReusableWindowDecorViewHost>()
        supplier.release(mockViewHost2, mockT) // Beyond limit.

        assertThat(mockViewHost)
            .isEqualTo(supplier.acquire(context, context.display))
        // Second one wasn't cached, so the acquired one should've been a new instance.
        assertThat(mockViewHost2)
            .isNotEqualTo(supplier.acquire(context, context.display))
    }

    @Test
    fun release_poolAtLimit_releasesViewHost() = runTest {
        supplier = createSupplier(maxPoolSize = 1, preWarmSize = 0)
        val mockT = mock<SurfaceControl.Transaction>()
        val mockViewHost = mock<ReusableWindowDecorViewHost>()
        supplier.release(mockViewHost, mockT) // Maxes pool.

        val mockViewHost2 = mock<ReusableWindowDecorViewHost>()
        supplier.release(mockViewHost2, mockT) // Beyond limit.

        // Second one doesn't fit, so it needs to be released.
        verify(mockViewHost2).release(mockT)
    }

    private fun CoroutineScope.createSupplier(
        maxPoolSize: Int,
        preWarmSize: Int
    ) = PooledWindowDecorViewHostSupplier(
        context,
        this,
        testShellInit,
        mockViewHostFactory,
        maxPoolSize,
        preWarmSize
    ).also {
        testShellInit.init()
    }
}
