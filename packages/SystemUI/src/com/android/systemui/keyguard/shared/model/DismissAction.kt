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
 *
 */

package com.android.systemui.keyguard.shared.model

/** DismissAction models */
sealed interface DismissAction {
    val onDismissAction: () -> KeyguardDone
    val onCancelAction: Runnable
    val message: String
    /**
     * True if the dismiss action will run an animation on the keyguard and requires any views that
     * would obscure this animation (ie: the primary bouncer) to immediately hide, so the animation
     * would be visible.
     */
    val willAnimateOnLockscreen: Boolean
    val runAfterKeyguardGone: Boolean

    class RunImmediately(
        override val onDismissAction: () -> KeyguardDone,
        override val onCancelAction: Runnable,
        override val message: String,
        override val willAnimateOnLockscreen: Boolean,
    ) : DismissAction {
        override val runAfterKeyguardGone: Boolean = false
    }

    class RunAfterKeyguardGone(
        val dismissAction: () -> Unit,
        override val onCancelAction: Runnable,
        override val message: String,
        override val willAnimateOnLockscreen: Boolean,
    ) : DismissAction {
        override val onDismissAction: () -> KeyguardDone = {
            dismissAction()
            // no-op, when this dismissAction is run after the keyguard is gone,
            // the keyguard is already done so KeyguardDone timing is irrelevant
            KeyguardDone.IMMEDIATE
        }
        override val runAfterKeyguardGone: Boolean = true
    }

    data object None : DismissAction {
        override val onDismissAction: () -> KeyguardDone = { KeyguardDone.IMMEDIATE }
        override val onCancelAction: Runnable = Runnable {}
        override val message: String = ""
        override val willAnimateOnLockscreen: Boolean = false
        override val runAfterKeyguardGone: Boolean = false
    }
}
