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

package com.android.systemui

import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.JUnitCore

@Suppress("JUnitMalformedDeclaration")
@SmallTest
class OnTeardownRuleTest : SysuiTestCase() {
    // None of these inner classes should be run except as part of this utilities-testing test
    class HasTeardown {
        @get:Rule val teardownRule = OnTeardownRule()

        @Before
        fun setUp() {
            teardownWasRun = false
            teardownRule.onTeardown { teardownWasRun = true }
        }

        @Test fun doTest() {}

        companion object {
            var teardownWasRun = false
        }
    }

    @Test
    fun teardownRuns() {
        val result = JUnitCore().run(HasTeardown::class.java)
        assertThat(result.failures).isEmpty()
        assertThat(HasTeardown.teardownWasRun).isTrue()
    }

    class FirstTeardownFails {
        @get:Rule val teardownRule = OnTeardownRule()

        @Before
        fun setUp() {
            teardownWasRun = false
            teardownRule.onTeardown { fail("One fails") }
            teardownRule.onTeardown { teardownWasRun = true }
        }

        @Test fun doTest() {}

        companion object {
            var teardownWasRun = false
        }
    }

    @Test
    fun allTeardownsRun() {
        val result = JUnitCore().run(FirstTeardownFails::class.java)
        assertThat(result.failures.map { it.message }).isEqualTo(listOf("One fails"))
        assertThat(FirstTeardownFails.teardownWasRun).isTrue()
    }

    class ThreeTeardowns {
        @get:Rule val teardownRule = OnTeardownRule()

        @Before
        fun setUp() {
            messages.clear()
        }

        @Test
        fun doTest() {
            teardownRule.onTeardown { messages.add("A") }
            teardownRule.onTeardown { messages.add("B") }
            teardownRule.onTeardown { messages.add("C") }
        }

        companion object {
            val messages = mutableListOf<String>()
        }
    }

    @Test
    fun reverseOrder() {
        val result = JUnitCore().run(ThreeTeardowns::class.java)
        assertThat(result.failures).isEmpty()
        assertThat(ThreeTeardowns.messages).isEqualTo(listOf("C", "B", "A"))
    }

    class TryToDoABadThing {
        @get:Rule val teardownRule = OnTeardownRule()

        @Test
        fun doTest() {
            teardownRule.onTeardown {
                teardownRule.onTeardown {
                    // do nothing
                }
            }
        }
    }

    @Test
    fun prohibitTeardownDuringTeardown() {
        val result = JUnitCore().run(TryToDoABadThing::class.java)
        assertThat(result.failures.map { it.message })
            .isEqualTo(listOf("Cannot add new teardown routines after test complete."))
    }
}
