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

package com.android.systemui.qs.tiles.base.actions

import android.content.Intent
import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

/**
 * Provides a shortcut to start an activity from [QSTileUserActionInteractor]. It supports keyguard
 * dismissing and tile from-view animations.
 */
@SysUISingleton
class QSTileIntentUserActionHandler
@Inject
constructor(private val activityStarter: ActivityStarter) {

    fun handle(view: View?, intent: Intent) {
        val animationController: ActivityLaunchAnimator.Controller? =
            view?.let {
                ActivityLaunchAnimator.Controller.fromView(
                    it,
                    InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE,
                )
            }
        activityStarter.postStartActivityDismissingKeyguard(intent, 0, animationController)
    }
}
