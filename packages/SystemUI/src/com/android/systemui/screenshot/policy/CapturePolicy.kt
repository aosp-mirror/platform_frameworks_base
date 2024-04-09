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

package com.android.systemui.screenshot.policy

import com.android.systemui.screenshot.data.model.DisplayContentModel

/** Contains logic to determine when and how an adjust to screenshot behavior applies. */
fun interface CapturePolicy {
    /**
     * Test the policy against the current display task state. If the policy applies, Returns a
     * [PolicyResult.Matched] containing [CaptureParameters] used to alter the request.
     */
    suspend fun check(content: DisplayContentModel): PolicyResult

    /** The result of a screen capture policy check. */
    sealed interface PolicyResult {
        /** The policy rules matched the given display content and will be applied. */
        data class Matched(
            /** The name of the policy rule which matched. */
            val policy: String,
            /** Why the policy matched. */
            val reason: String,
            /** Details on how to modify the screen capture request. */
            val parameters: CaptureParameters,
        ) : PolicyResult

        /** The policy rules do not match the given display content and do not apply. */
        data class NotMatched(
            /** The name of the policy rule which matched. */
            val policy: String,
            /** Why the policy did not match. */
            val reason: String
        ) : PolicyResult
    }
}
