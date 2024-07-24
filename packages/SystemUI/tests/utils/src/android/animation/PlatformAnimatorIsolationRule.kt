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

package android.animation

import com.android.systemui.util.test.TestExceptionDeferrer
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * This rule is used by [com.android.systemui.SysuiTestCase] to fail any test which attempts to
 * start a Platform [Animator] without using [android.animation.AnimatorTestRule].
 *
 * TODO(b/291645410): enable this; currently this causes hundreds of test failures.
 */
class PlatformAnimatorIsolationRule : TestRule {

    private class IsolatingAnimationHandler(ruleThread: Thread) : AnimationHandler() {
        private val exceptionDeferrer = TestExceptionDeferrer(TAG, ruleThread)
        override fun addOneShotCommitCallback(callback: AnimationFrameCallback?) = onError()
        override fun removeCallback(callback: AnimationFrameCallback?) = onError()
        override fun setProvider(provider: AnimationFrameCallbackProvider?) = onError()
        override fun autoCancelBasedOn(objectAnimator: ObjectAnimator?) = onError()
        override fun addAnimationFrameCallback(callback: AnimationFrameCallback?, delay: Long) =
            onError()

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
                val originalHandler = AnimationHandler.setTestHandler(isolationHandler)
                try {
                    base.evaluate()
                } finally {
                    val handlerAtEnd = AnimationHandler.setTestHandler(originalHandler)
                    check(isolationHandler == handlerAtEnd) {
                        "Test handler was altered: expected=$isolationHandler actual=$handlerAtEnd"
                    }
                    // Pass or fail, a deferred exception should be the failure reason
                    isolationHandler.throwDeferred()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PlatformAnimatorIsolationRule"

        fun isIsolatingHandler(handler: AnimationHandler?): Boolean =
            handler is IsolatingAnimationHandler
    }
}
