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

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException

/**
 * Rule that allows teardown steps to be added right next to the places where it becomes clear they
 * are needed. This can avoid the need for complicated or conditional logic in a single teardown
 * method. Examples:
 * ```
 * @get:Rule teardownRule = OnTeardownRule()
 *
 * // setup and teardown right next to each other
 * @Before
 * fun setUp() {
 *   val oldTimeout = getGlobalTimeout()
 *   teardownRule.onTeardown { setGlobalTimeout(oldTimeout) }
 *   overrideGlobalTimeout(5000)
 * }
 *
 * // add teardown logic for fixtures that aren't used in every test
 * fun addCustomer() {
 *   val id = globalDatabase.addCustomer(TEST_NAME, TEST_ADDRESS, ...)
 *   teardownRule.onTeardown { globalDatabase.deleteCustomer(id) }
 * }
 * ```
 */
class OnTeardownRule : TestWatcher() {
    private var canAdd = true
    private val teardowns = mutableListOf<() -> Unit>()

    fun onTeardown(teardownRunnable: () -> Unit) {
        if (!canAdd) {
            throw IllegalStateException("Cannot add new teardown routines after test complete.")
        }
        teardowns.add(teardownRunnable)
    }

    fun onTeardown(teardownRunnable: Runnable) {
        if (!canAdd) {
            throw IllegalStateException("Cannot add new teardown routines after test complete.")
        }
        teardowns.add { teardownRunnable.run() }
    }

    override fun finished(description: Description?) {
        canAdd = false
        val errors = mutableListOf<Throwable>()
        teardowns.reversed().forEach {
            try {
                it()
            } catch (e: Throwable) {
                errors.add(e)
            }
        }
        MultipleFailureException.assertEmpty(errors)
    }
}
