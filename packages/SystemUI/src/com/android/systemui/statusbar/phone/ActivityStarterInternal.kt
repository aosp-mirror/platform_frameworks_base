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

package com.android.systemui.statusbar.phone

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.plugins.ActivityStarter

interface ActivityStarterInternal {
    /**
     * Starts a pending intent after dismissing keyguard.
     *
     * This can be called in a background thread (to prevent calls in [ActivityIntentHelper] in the
     * main thread).
     */
    fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        dismissShade: Boolean,
        intentSentUiThreadCallback: Runnable? = null,
        associatedView: View? = null,
        animationController: ActivityTransitionAnimator.Controller? = null,
        showOverLockscreen: Boolean = false,
        skipLockscreenChecks: Boolean = false,
        fillInIntent: Intent? = null,
        extraOptions: Bundle? = null,
    )

    /** Starts an activity after dismissing keyguard. */
    fun startActivityDismissingKeyguard(
        intent: Intent,
        dismissShade: Boolean,
        onlyProvisioned: Boolean = false,
        callback: ActivityStarter.Callback? = null,
        flags: Int = 0,
        animationController: ActivityTransitionAnimator.Controller? = null,
        customMessage: String? = null,
        disallowEnterPictureInPictureWhileLaunching: Boolean = false,
        userHandle: UserHandle? = null,
    )

    /** Starts an Activity. */
    fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
        userHandle: UserHandle? = null,
    )

    /** Executes an action after dismissing keyguard. */
    fun dismissKeyguardThenExecute(
        action: ActivityStarter.OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
        customMessage: String? = null,
    )

    /** Executes an action after dismissing keyguard. */
    fun executeRunnableDismissingKeyguard(
        runnable: Runnable?,
        cancelAction: Runnable? = null,
        dismissShade: Boolean = false,
        afterKeyguardGone: Boolean = false,
        deferred: Boolean = false,
        willAnimateOnKeyguard: Boolean = false,
        customMessage: String? = null,
    )

    /** Whether we should animate an activity launch. */
    fun shouldAnimateLaunch(isActivityIntent: Boolean): Boolean
}
