package com.android.systemui.shade

import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Empty implementation of ShadeController for variants of Android without shades. */
@SysUISingleton
open class ShadeControllerEmptyImpl @Inject constructor() : ShadeController {
    override fun start() {}
    override fun instantExpandShade() {}
    override fun instantCollapseShade() {}
    override fun animateCollapseShade(
        flags: Int,
        force: Boolean,
        delayed: Boolean,
        speedUpFactor: Float
    ) {}
    override fun animateExpandShade() {}
    override fun animateExpandQs() {}
    override fun postAnimateCollapseShade() {}
    override fun postAnimateForceCollapseShade() {}
    override fun postAnimateExpandQs() {}
    override fun cancelExpansionAndCollapseShade() {}
    override fun closeShadeIfOpen(): Boolean {
        return false
    }
    override fun isKeyguard(): Boolean {
        return false
    }
    override fun isShadeFullyOpen(): Boolean {
        return false
    }
    override fun isExpandingOrCollapsing(): Boolean {
        return false
    }
    override fun postOnShadeExpanded(action: Runnable?) {}
    override fun addPostCollapseAction(action: Runnable?) {}
    override fun runPostCollapseRunnables() {}
    override fun collapseShade(): Boolean {
        return false
    }
    override fun collapseShade(animate: Boolean) {}
    override fun collapseOnMainThread() {}
    override fun makeExpandedInvisible() {}
    override fun makeExpandedVisible(force: Boolean) {}
    override fun isExpandedVisible(): Boolean {
        return false
    }
    override fun onStatusBarTouch(event: MotionEvent?) {}
    override fun onLaunchAnimationCancelled(isLaunchForActivity: Boolean) {}
    override fun onLaunchAnimationEnd(launchIsFullScreen: Boolean) {}
}
