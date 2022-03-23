/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.trust.test.lib

import android.util.Log
import com.google.common.truth.Truth.assertWithMessage

private const val TAG = "TrustTestUtils"

/**
 * Waits for [conditionFunction] to be true with a failed assertion if it is not after [maxWait]
 * ms.
 *
 * The condition function can perform additional logic (for example, logging or attempting to make
 * the condition become true).
 *
 * @param conditionFunction function which takes the attempt count & returns whether the condition
 *                          is met
 */
internal fun wait(
    description: String? = null,
    maxWait: Long = 1500L,
    rate: Long = 50L,
    conditionFunction: (count: Int) -> Boolean
) {
    var waited = 0L
    var count = 0
    while (!conditionFunction.invoke(count)) {
        assertWithMessage("Condition exceeded maximum wait time of $maxWait ms: $description")
            .that(waited <= maxWait)
            .isTrue()
        waited += rate
        count++
        Log.i(TAG, "Waiting for $description ($waited/$maxWait) #$count")
        Thread.sleep(rate)
    }
}
