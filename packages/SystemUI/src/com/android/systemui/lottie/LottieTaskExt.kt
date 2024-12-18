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

package com.android.systemui.lottie

import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieListener
import com.airbnb.lottie.LottieTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until [LottieTask] is finished with a result or a failure.
 *
 * @return result of the [LottieTask] when it's successful
 */
suspend fun LottieTask<LottieComposition>.await() =
    suspendCancellableCoroutine<LottieComposition> { continuation ->
        val resultListener =
            LottieListener<LottieComposition> { result ->
                with(continuation) { if (!isCancelled && !isCompleted) resume(result) }
            }
        val failureListener =
            LottieListener<Throwable> { throwable ->
                with(continuation) {
                    if (!isCancelled && !isCompleted) resumeWithException(throwable)
                }
            }
        addListener(resultListener)
        addFailureListener(failureListener)
        continuation.invokeOnCancellation {
            removeListener(resultListener)
            removeFailureListener(failureListener)
        }
    }
