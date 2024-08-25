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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.lifecycle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BaseActivatableTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest = FakeActivatable()

    @Test
    fun activate() =
        testScope.runTest {
            assertThat(underTest.isActive).isFalse()
            assertThat(underTest.activationCount).isEqualTo(0)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            underTest.activateIn(testScope)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(underTest.activationCount).isEqualTo(1)
            assertThat(underTest.cancellationCount).isEqualTo(0)
        }

    @Test
    fun activate_andCancel() =
        testScope.runTest {
            assertThat(underTest.isActive).isFalse()
            assertThat(underTest.activationCount).isEqualTo(0)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            val job = Job()
            underTest.activateIn(testScope, context = job)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(underTest.activationCount).isEqualTo(1)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            job.cancel()
            runCurrent()
            assertThat(underTest.isActive).isFalse()
            assertThat(underTest.activationCount).isEqualTo(1)
            assertThat(underTest.cancellationCount).isEqualTo(1)
        }

    @Test
    fun activate_afterCancellation() =
        testScope.runTest {
            assertThat(underTest.isActive).isFalse()
            assertThat(underTest.activationCount).isEqualTo(0)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            val job = Job()
            underTest.activateIn(testScope, context = job)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(underTest.activationCount).isEqualTo(1)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            job.cancel()
            runCurrent()
            assertThat(underTest.isActive).isFalse()
            assertThat(underTest.activationCount).isEqualTo(1)
            assertThat(underTest.cancellationCount).isEqualTo(1)

            underTest.activateIn(testScope)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(underTest.activationCount).isEqualTo(2)
            assertThat(underTest.cancellationCount).isEqualTo(1)
        }

    @Test(expected = IllegalStateException::class)
    fun activate_whileActive_throws() =
        testScope.runTest {
            assertThat(underTest.isActive).isFalse()
            assertThat(underTest.activationCount).isEqualTo(0)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            underTest.activateIn(testScope)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(underTest.activationCount).isEqualTo(1)
            assertThat(underTest.cancellationCount).isEqualTo(0)

            underTest.activateIn(testScope)
            runCurrent()
        }

    @Test
    fun addChild_beforeActive_activatesChildrenOnceActivated() =
        testScope.runTest {
            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            assertThat(underTest.isActive).isFalse()
            underTest.addChild(child1)
            underTest.addChild(child2)
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.activateIn(this)
            runCurrent()

            assertThat(underTest.isActive).isTrue()
            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()
        }

    @Test
    fun addChild_whileActive_activatesChildrenImmediately() =
        testScope.runTest {
            underTest.activateIn(this)
            runCurrent()
            assertThat(underTest.isActive).isTrue()

            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.addChild(child1)
            underTest.addChild(child2)
            runCurrent()

            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()
        }

    @Test
    fun addChild_afterCancellation_doesNotActivateChildren() =
        testScope.runTest {
            val job = Job()
            underTest.activateIn(this, context = job)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            job.cancel()
            runCurrent()
            assertThat(underTest.isActive).isFalse()

            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.addChild(child1)
            underTest.addChild(child2)
            runCurrent()

            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()
        }

    @Test
    fun activate_cancellation_cancelsCurrentChildren() =
        testScope.runTest {
            val job = Job()
            underTest.activateIn(this, context = job)
            runCurrent()
            assertThat(underTest.isActive).isTrue()

            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.addChild(child1)
            underTest.addChild(child2)
            runCurrent()

            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()

            job.cancel()
            runCurrent()
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()
        }

    @Test
    fun activate_afterCancellation_reactivatesCurrentChildren() =
        testScope.runTest {
            val job = Job()
            underTest.activateIn(this, context = job)
            runCurrent()
            assertThat(underTest.isActive).isTrue()

            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.addChild(child1)
            underTest.addChild(child2)
            runCurrent()

            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()

            job.cancel()
            runCurrent()
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.activateIn(this)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()
        }

    @Test
    fun removeChild_beforeActive_neverActivatesChild() =
        testScope.runTest {
            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            assertThat(underTest.isActive).isFalse()
            underTest.addChild(child1)
            underTest.addChild(child2)
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()
        }

    @Test
    fun removeChild_whileActive_cancelsChild() =
        testScope.runTest {
            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            assertThat(underTest.isActive).isFalse()
            underTest.addChild(child1)
            underTest.addChild(child2)
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.activateIn(this)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()

            underTest.removeChild(child1)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isTrue()
        }

    @Test
    fun removeChild_afterCancellation_doesNotReactivateChildren() =
        testScope.runTest {
            val child1 = FakeActivatable()
            val child2 = FakeActivatable()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            assertThat(underTest.isActive).isFalse()
            underTest.addChild(child1)
            underTest.addChild(child2)
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            val job = Job()
            underTest.activateIn(this, context = job)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(child1.isActive).isTrue()
            assertThat(child2.isActive).isTrue()

            job.cancel()
            runCurrent()
            assertThat(underTest.isActive).isFalse()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isFalse()

            underTest.removeChild(child1)
            underTest.activateIn(this)
            runCurrent()
            assertThat(underTest.isActive).isTrue()
            assertThat(child1.isActive).isFalse()
            assertThat(child2.isActive).isTrue()
        }
}
