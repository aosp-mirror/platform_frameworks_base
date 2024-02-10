/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.animation

import com.android.internal.jank.InteractionJankMonitor
import org.mockito.Mockito.mock

/** A [DialogTransitionAnimator] to be used in tests. */
@JvmOverloads
fun fakeDialogTransitionAnimator(
    isUnlocked: Boolean = true,
    isShowingAlternateAuthOnUnlock: Boolean = false,
    interactionJankMonitor: InteractionJankMonitor = mock(InteractionJankMonitor::class.java),
    isPredictiveBackQsDialogAnim: Boolean = false,
): DialogTransitionAnimator {
    return DialogTransitionAnimator(
        callback =
            FakeCallback(
                isUnlocked = isUnlocked,
                isShowingAlternateAuthOnUnlock = isShowingAlternateAuthOnUnlock,
            ),
        interactionJankMonitor = interactionJankMonitor,
        featureFlags =
            object : AnimationFeatureFlags {
                override val isPredictiveBackQsDialogAnim = isPredictiveBackQsDialogAnim
            },
        transitionAnimator = fakeTransitionAnimator(),
        isForTesting = true,
    )
}

private class FakeCallback(
    private val isDreaming: Boolean = false,
    private val isUnlocked: Boolean = true,
    private val isShowingAlternateAuthOnUnlock: Boolean = false,
) : DialogTransitionAnimator.Callback {
    override fun isDreaming(): Boolean = isDreaming
    override fun isUnlocked(): Boolean = isUnlocked
    override fun isShowingAlternateAuthOnUnlock() = isShowingAlternateAuthOnUnlock
}
