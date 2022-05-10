/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.util

import com.google.common.truth.Expect
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Wrapper for [Expect] which supports ignoring any failures. This should be used with caution, but
 * it allows a base test to be written which doesn't switch success/failure in the test itself,
 * preventing any logic errors from causing the test to accidentally succeed.
 */
internal class IgnoreableExpect : TestRule {

    val expect = Expect.create()

    private var ignore = false

    override fun apply(base: Statement?, description: Description?): Statement {
        return object : Statement() {
            override fun evaluate() {
                ignore = false
                try {
                    expect.apply(base, description).evaluate()
                } catch (t: Throwable) {
                    if (!ignore) {
                        throw t
                    }
                }
            }
        }
    }

    fun ignore() {
        ignore = true
    }
}
