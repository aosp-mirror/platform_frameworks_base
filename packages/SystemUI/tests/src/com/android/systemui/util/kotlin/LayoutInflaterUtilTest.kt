/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.util.kotlin

import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.view.reinflateAndBindLatest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class LayoutInflaterUtilTest : SysuiTestCase() {
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    private var inflationCount = 0
    private var callbackCount = 0
    @Mock private lateinit var disposableHandle: DisposableHandle

    inner class TestLayoutInflater : LayoutInflater(context) {
        override fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
            inflationCount++
            return View(context)
        }

        override fun cloneInContext(p0: Context?): LayoutInflater {
            // not needed for this test
            return this
        }
    }

    val underTest = TestLayoutInflater()

    @After
    fun cleanUp() {
        inflationCount = 0
        callbackCount = 0
    }

    @Test
    fun testReinflateAndBindLatest_inflatesWithoutEmission() = runTest {
        backgroundScope.launch {
            underTest.reinflateAndBindLatest(
                resource = 0,
                root = null,
                attachToRoot = false,
                emptyFlow<Unit>()
            ) {
                callbackCount++
                null
            }
        }

        // Inflates without an emission
        runCurrent()
        assertThat(inflationCount).isEqualTo(1)
        assertThat(callbackCount).isEqualTo(1)
    }

    @Test
    fun testReinflateAndBindLatest_reinflatesOnEmission() = runTest {
        val observable = MutableSharedFlow<Unit>()
        val flow = observable.asSharedFlow()
        backgroundScope.launch {
            underTest.reinflateAndBindLatest(
                resource = 0,
                root = null,
                attachToRoot = false,
                flow
            ) {
                callbackCount++
                null
            }
        }

        listOf(1, 2, 3).forEach { count ->
            runCurrent()
            assertThat(inflationCount).isEqualTo(count)
            assertThat(callbackCount).isEqualTo(count)
            observable.emit(Unit)
        }
    }

    @Test
    fun testReinflateAndBindLatest_disposesOnCancel() = runTest {
        val job = launch {
            underTest.reinflateAndBindLatest(
                resource = 0,
                root = null,
                attachToRoot = false,
                emptyFlow()
            ) {
                callbackCount++
                disposableHandle
            }
        }

        runCurrent()
        job.cancelAndJoin()
        verify(disposableHandle).dispose()
    }
}
