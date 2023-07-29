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

package androidx.core.animation

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class AndroidXAnimatorIsolationRule : TestRule {

    private class TestAnimationHandler : AnimationHandler(null) {
        override fun addAnimationFrameCallback(callback: AnimationFrameCallback?) = doFail()
        override fun removeCallback(callback: AnimationFrameCallback?) = doFail()
        override fun onAnimationFrame(frameTime: Long) = doFail()
        override fun setFrameDelay(frameDelay: Long) = doFail()
        override fun getFrameDelay(): Long = doFail()
    }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                AnimationHandler.setTestHandler(testHandler)
                try {
                    base.evaluate()
                } finally {
                    AnimationHandler.setTestHandler(null)
                }
            }
        }
    }

    companion object {
        private val testHandler = TestAnimationHandler()
        private fun doFail(): Nothing =
            error(
                "Test's animations are not isolated! " +
                    "Did you forget to add an AnimatorTestRule to your test class?"
            )
    }
}
