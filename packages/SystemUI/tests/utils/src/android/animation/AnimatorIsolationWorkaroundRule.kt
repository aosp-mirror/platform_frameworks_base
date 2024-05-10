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

import android.os.Looper
import android.util.Log
import com.android.systemui.util.test.TestExceptionDeferrer
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * This rule is intended to be used by System UI tests that are otherwise blocked from using
 * animators because of [PlatformAnimatorIsolationRule]. It is preferred that test authors use
 * [AnimatorTestRule], as that rule allows test authors to step through animations and removes the
 * need for tests to handle multiple threads. However, many System UI tests were written before this
 * was conceivable, so this rule is intended to support those legacy tests.
 */
class AnimatorIsolationWorkaroundRule(
    private val requiredLooper: Looper? = Looper.getMainLooper(),
) : TestRule {
    private inner class IsolationWorkaroundHandler(ruleThread: Thread) : AnimationHandler() {
        private val exceptionDeferrer = TestExceptionDeferrer(TAG, ruleThread)
        private val addedCallbacks = mutableSetOf<AnimationFrameCallback>()

        fun tearDownAndThrowDeferred() {
            addedCallbacks.forEach { super.removeCallback(it) }
            exceptionDeferrer.throwDeferred()
        }

        override fun addAnimationFrameCallback(callback: AnimationFrameCallback?, delay: Long) {
            checkLooper()
            if (callback != null) {
                addedCallbacks.add(callback)
            }
            super.addAnimationFrameCallback(callback, delay)
        }

        override fun addOneShotCommitCallback(callback: AnimationFrameCallback?) {
            checkLooper()
            super.addOneShotCommitCallback(callback)
        }

        override fun removeCallback(callback: AnimationFrameCallback?) {
            super.removeCallback(callback)
        }

        override fun setProvider(provider: AnimationFrameCallbackProvider?) {
            checkLooper()
            super.setProvider(provider)
        }

        override fun autoCancelBasedOn(objectAnimator: ObjectAnimator?) {
            checkLooper()
            super.autoCancelBasedOn(objectAnimator)
        }

        private fun checkLooper() {
            exceptionDeferrer.check(requiredLooper == null || Looper.myLooper() == requiredLooper) {
                "Animations are being registered on a different looper than the expected one!" +
                    " expected=$requiredLooper actual=${Looper.myLooper()}"
            }
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val workaroundHandler = IsolationWorkaroundHandler(Thread.currentThread())
                val prevInstance = AnimationHandler.setTestHandler(workaroundHandler)
                check(PlatformAnimatorIsolationRule.isIsolatingHandler(prevInstance)) {
                    "AnimatorIsolationWorkaroundRule must be used within " +
                        "PlatformAnimatorIsolationRule, but test handler was $prevInstance"
                }
                try {
                    base.evaluate()
                    val count = AnimationHandler.getAnimationCount()
                    if (count > 0) {
                        Log.w(TAG, "Animations still running: $count")
                    }
                } finally {
                    val handlerAtEnd = AnimationHandler.setTestHandler(prevInstance)
                    check(workaroundHandler == handlerAtEnd) {
                        "Test handler was altered: expected=$workaroundHandler actual=$handlerAtEnd"
                    }
                    // Pass or fail, errors caught here should be the reason the test fails
                    workaroundHandler.tearDownAndThrowDeferred()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "AnimatorIsolationWorkaroundRule"
    }
}
