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
