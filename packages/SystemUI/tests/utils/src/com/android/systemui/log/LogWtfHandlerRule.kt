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

package com.android.systemui.log

import android.util.Log
import android.util.Log.TerribleFailureHandler
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class LogWtfHandlerRule : TestRule {

    private var started = false
    private var handler = ThrowAndFailAtEnd

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                started = true
                val originalWtfHandler = Log.setWtfHandler(handler)
                var failure: Throwable? = null
                try {
                    base.evaluate()
                } catch (ex: Throwable) {
                    failure = ex.runAndAddSuppressed { handler.onTestFailure(ex) }
                } finally {
                    failure = failure.runAndAddSuppressed { handler.onTestFinished() }
                    Log.setWtfHandler(originalWtfHandler)
                }
                if (failure != null) {
                    throw failure
                }
            }
        }
    }

    fun Throwable?.runAndAddSuppressed(block: () -> Unit): Throwable? {
        try {
            block()
        } catch (t: Throwable) {
            if (this == null) {
                return t
            }
            addSuppressed(t)
        }
        return this
    }

    fun setWtfHandler(handler: TerribleFailureTestHandler) {
        check(!started) { "Should only be called before the test starts" }
        this.handler = handler
    }

    fun interface TerribleFailureTestHandler : TerribleFailureHandler {
        fun onTestFailure(failure: Throwable) {}
        fun onTestFinished() {}
    }

    companion object Handlers {
        val ThrowAndFailAtEnd
            get() =
                object : TerribleFailureTestHandler {
                    val failures = mutableListOf<Log.TerribleFailure>()

                    override fun onTerribleFailure(
                        tag: String,
                        what: Log.TerribleFailure,
                        system: Boolean
                    ) {
                        failures.add(what)
                        throw what
                    }

                    override fun onTestFailure(failure: Throwable) {
                        super.onTestFailure(failure)
                    }

                    override fun onTestFinished() {
                        if (failures.isNotEmpty()) {
                            throw AssertionError("Unexpected Log.wtf calls: $failures", failures[0])
                        }
                    }
                }

        val JustThrow = TerribleFailureTestHandler { _, what, _ -> throw what }

        val JustFailAtEnd
            get() =
                object : TerribleFailureTestHandler {
                    val failures = mutableListOf<Log.TerribleFailure>()

                    override fun onTerribleFailure(
                        tag: String,
                        what: Log.TerribleFailure,
                        system: Boolean
                    ) {
                        failures.add(what)
                    }

                    override fun onTestFinished() {
                        if (failures.isNotEmpty()) {
                            throw AssertionError("Unexpected Log.wtf calls: $failures", failures[0])
                        }
                    }
                }
    }
}
