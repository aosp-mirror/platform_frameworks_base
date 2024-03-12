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

import com.android.systemui.util.test.TestExceptionDeferrer
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * This rule is used by [com.android.systemui.SysuiTestCase] to fail any test which attempts to
 * start an AndroidX [Animator] without using [androidx.core.animation.AnimatorTestRule].
 */
class AndroidXAnimatorIsolationRule : TestRule {

    private class IsolatingAnimationHandler(ruleThread: Thread) : AnimationHandler(null) {
        private val exceptionDeferrer = TestExceptionDeferrer(TAG, ruleThread)
        override fun addAnimationFrameCallback(callback: AnimationFrameCallback?) = onError()
        override fun removeCallback(callback: AnimationFrameCallback?) = onError()
        override fun onAnimationFrame(frameTime: Long) = onError()
        override fun setFrameDelay(frameDelay: Long) = onError()

        private fun onError() =
            exceptionDeferrer.fail(
                "Test's animations are not isolated! " +
                    "Did you forget to add an AnimatorTestRule as a @Rule?"
            )

        fun throwDeferred() = exceptionDeferrer.throwDeferred()
    }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val isolationHandler = IsolatingAnimationHandler(Thread.currentThread())
                AnimationHandler.setTestHandler(isolationHandler)
                try {
                    base.evaluate()
                } finally {
                    AnimationHandler.setTestHandler(null)
                    // Pass or fail, a deferred exception should be the failure reason
                    isolationHandler.throwDeferred()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "AndroidXAnimatorIsolationRule"
    }
}
