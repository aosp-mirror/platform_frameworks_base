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
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

/**
 * Encapsulates the activity logic for activity starter when flexiglass is enabled.
 *
 * TODO: b/308819693
 */
@SysUISingleton
class ActivityStarterInternalImpl @Inject constructor() : ActivityStarterInternal {
    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        dismissShade: Boolean,
        intentSentUiThreadCallback: Runnable?,
        associatedView: View?,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreen: Boolean,
        skipLockscreenChecks: Boolean,
        fillInIntent: Intent?,
        extraOptions: Bundle?
    ) {
        TODO("Not yet implemented b/308819693")
    }

    override fun startActivityDismissingKeyguard(
        intent: Intent,
        dismissShade: Boolean,
        onlyProvisioned: Boolean,
        callback: ActivityStarter.Callback?,
        flags: Int,
        animationController: ActivityTransitionAnimator.Controller?,
        customMessage: String?,
        disallowEnterPictureInPictureWhileLaunching: Boolean,
        userHandle: UserHandle?
    ) {
        TODO("Not yet implemented b/308819693")
    }

    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
        userHandle: UserHandle?
    ) {
        TODO("Not yet implemented b/308819693")
    }

    override fun dismissKeyguardThenExecute(
        action: ActivityStarter.OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
        customMessage: String?
    ) {
        TODO("Not yet implemented b/308819693")
    }

    override fun executeRunnableDismissingKeyguard(
        runnable: Runnable?,
        cancelAction: Runnable?,
        dismissShade: Boolean,
        afterKeyguardGone: Boolean,
        deferred: Boolean,
        willAnimateOnKeyguard: Boolean,
        customMessage: String?
    ) {
        TODO("Not yet implemented b/308819693")
    }

    override fun shouldAnimateLaunch(isActivityIntent: Boolean): Boolean {
        TODO("Not yet implemented b/308819693")
    }
}
