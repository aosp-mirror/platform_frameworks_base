/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.Lazy
import javax.inject.Inject

/** Handles start activity logic in SystemUI. */
@SysUISingleton
class ActivityStarterImpl
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    @Main private val mainExecutor: DelayableExecutor,
    legacyActivityStarter: Lazy<LegacyActivityStarterInternalImpl>
) : ActivityStarter {

    private val activityStarterInternal: ActivityStarterInternal = legacyActivityStarter.get()

    override fun startPendingIntentDismissingKeyguard(intent: PendingIntent) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            dismissShade = true
        )
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            dismissShade = true,
        )
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        associatedView: View?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            associatedView = associatedView,
            dismissShade = true,
        )
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityTransitionAnimator.Controller?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
            dismissShade = true,
        )
    }

    override fun startPendingIntentWithoutDismissing(
        intent: PendingIntent,
        dismissShade: Boolean,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityTransitionAnimator.Controller?,
        fillInIntent: Intent?,
        extraOptions: Bundle?
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
            showOverLockscreen = true,
            skipLockscreenChecks = true,
            dismissShade = dismissShade,
            fillInIntent = fillInIntent,
            extraOptions = extraOptions,
        )
    }

    override fun startPendingIntentMaybeDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityTransitionAnimator.Controller?
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
            showOverLockscreen = true,
            dismissShade = true,
        )
    }

    override fun startPendingIntentMaybeDismissingKeyguard(
        intent: PendingIntent,
        dismissShade: Boolean,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityTransitionAnimator.Controller?,
        fillInIntent: Intent?,
        extraOptions: Bundle?,
        customMessage: String?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
            showOverLockscreen = true,
            dismissShade = dismissShade,
            fillInIntent = fillInIntent,
            extraOptions = extraOptions,
            customMessage = customMessage,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(intent: Intent, dismissShade: Boolean) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            dismissShade = dismissShade,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(intent: Intent, onlyProvisioned: Boolean, dismissShade: Boolean) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        callback: ActivityStarter.Callback?,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            dismissShade = dismissShade,
            callback = callback,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(
        intent: Intent,
        onlyProvisioned: Boolean,
        dismissShade: Boolean,
        flags: Int,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
            flags = flags,
        )
    }

    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
    ) {
        activityStarterInternal.startActivity(
            intent = intent,
            dismissShade = dismissShade,
            animationController = animationController,
            showOverLockscreenWhenLocked = showOverLockscreenWhenLocked,
        )
    }

    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
        userHandle: UserHandle?,
    ) {
        activityStarterInternal.startActivity(
            intent = intent,
            dismissShade = dismissShade,
            animationController = animationController,
            showOverLockscreenWhenLocked = showOverLockscreenWhenLocked,
            userHandle = userHandle,
        )
    }

    override fun postStartActivityDismissingKeyguard(intent: PendingIntent) {
        postOnUiThread {
            activityStarterInternal.startPendingIntentDismissingKeyguard(
                intent = intent,
                dismissShade = true,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(
        intent: PendingIntent,
        animationController: ActivityTransitionAnimator.Controller?
    ) {
        postOnUiThread {
            activityStarterInternal.startPendingIntentDismissingKeyguard(
                intent = intent,
                animationController = animationController,
                dismissShade = true,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(intent: Intent, delay: Int) {
        postOnUiThread(delay) {
            activityStarterInternal.startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = true,
                dismissShade = true,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(
        intent: Intent,
        delay: Int,
        animationController: ActivityTransitionAnimator.Controller?,
    ) {
        postOnUiThread(delay) {
            activityStarterInternal.startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = true,
                dismissShade = true,
                animationController = animationController,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(
        intent: Intent,
        delay: Int,
        animationController: ActivityTransitionAnimator.Controller?,
        customMessage: String?,
    ) {
        postOnUiThread(delay) {
            activityStarterInternal.startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = true,
                dismissShade = true,
                animationController = animationController,
                customMessage = customMessage,
            )
        }
    }

    override fun dismissKeyguardThenExecute(
        action: OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
    ) {
        activityStarterInternal.dismissKeyguardThenExecute(
            action = action,
            cancel = cancel,
            afterKeyguardGone = afterKeyguardGone,
        )
    }

    override fun dismissKeyguardThenExecute(
        action: OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
        customMessage: String?,
    ) {
        activityStarterInternal.dismissKeyguardThenExecute(
            action = action,
            cancel = cancel,
            afterKeyguardGone = afterKeyguardGone,
            customMessage = customMessage,
        )
    }

    override fun startActivityDismissingKeyguard(
        intent: Intent,
        onlyProvisioned: Boolean,
        dismissShade: Boolean,
        customMessage: String?,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
            customMessage = customMessage,
        )
    }

    override fun startActivityDismissingKeyguard(
        intent: Intent,
        onlyProvisioned: Boolean,
        dismissShade: Boolean,
        disallowEnterPictureInPictureWhileLaunching: Boolean,
        callback: ActivityStarter.Callback?,
        flags: Int,
        animationController: ActivityTransitionAnimator.Controller?,
        userHandle: UserHandle?,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
            disallowEnterPictureInPictureWhileLaunching =
                disallowEnterPictureInPictureWhileLaunching,
            callback = callback,
            flags = flags,
            animationController = animationController,
            userHandle = userHandle,
        )
    }

    override fun executeRunnableDismissingKeyguard(
        runnable: Runnable?,
        cancelAction: Runnable?,
        dismissShade: Boolean,
        afterKeyguardGone: Boolean,
        deferred: Boolean,
    ) {
        activityStarterInternal.executeRunnableDismissingKeyguard(
            runnable = runnable,
            cancelAction = cancelAction,
            dismissShade = dismissShade,
            afterKeyguardGone = afterKeyguardGone,
            deferred = deferred,
        )
    }

    override fun postQSRunnableDismissingKeyguard(runnable: Runnable?) {
        postOnUiThread {
            statusBarStateController.setLeaveOpenOnKeyguardHide(true)
            activityStarterInternal.executeRunnableDismissingKeyguard(
                runnable = { runnable?.let { postOnUiThread(runnable = it) } },
            )
        }
    }

    override fun shouldAnimateLaunch(isActivityIntent: Boolean): Boolean {
        return activityStarterInternal.shouldAnimateLaunch(isActivityIntent)
    }

    private fun postOnUiThread(delay: Int = 0, runnable: Runnable) {
        mainExecutor.executeDelayed(runnable, delay.toLong())
    }
}
