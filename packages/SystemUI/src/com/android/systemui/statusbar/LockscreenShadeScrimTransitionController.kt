package com.android.systemui.statusbar

import android.content.Context
import android.util.IndentingPrintWriter
import android.util.MathUtils
import com.android.systemui.res.R
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import javax.inject.Inject

/** Controls the lockscreen to shade transition for scrims. */
class LockscreenShadeScrimTransitionController
@Inject
constructor(
    private val scrimController: ScrimController,
    context: Context,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    splitShadeStateController: SplitShadeStateController
) : AbstractLockscreenShadeTransitionController(context, configurationController, dumpManager,
    splitShadeStateController) {

    /**
     * Distance that the full shade transition takes in order for scrim to fully transition to the
     * shade (in alpha)
     */
    private var scrimTransitionDistance = 0

    /** Distance it takes in order for the notifications scrim fade in to start. */
    private var notificationsScrimTransitionDelay = 0

    /** Distance it takes for the notifications scrim to fully fade if after it started. */
    private var notificationsScrimTransitionDistance = 0

    /** The latest progress [0,1] the scrims transition. */
    var scrimProgress = 0f

    /** The latest progress [0,1] specifically of the notifications scrim transition. */
    var notificationsScrimProgress = 0f

    /**
     * The last drag amount specifically for the notifications scrim. It is different to the normal
     * [dragDownAmount] as the notifications scrim transition starts relative to the other scrims'
     * progress.
     */
    var notificationsScrimDragAmount = 0f

    override fun updateResources() {
        scrimTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_scrim_transition_distance)
        notificationsScrimTransitionDelay =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_notifications_scrim_transition_delay)
        notificationsScrimTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_notifications_scrim_transition_distance)
    }

    override fun onDragDownAmountChanged(dragDownAmount: Float) {
        scrimProgress = MathUtils.saturate(dragDownAmount / scrimTransitionDistance)
        notificationsScrimDragAmount = dragDownAmount - notificationsScrimTransitionDelay
        notificationsScrimProgress =
            MathUtils.saturate(notificationsScrimDragAmount / notificationsScrimTransitionDistance)
        scrimController.setTransitionToFullShadeProgress(scrimProgress, notificationsScrimProgress)
    }

    override fun dump(indentingPrintWriter: IndentingPrintWriter) {
        indentingPrintWriter.let {
            it.println("LockscreenShadeScrimTransitionController:")
            it.increaseIndent()
            it.println("Resources:")
            it.increaseIndent()
            it.println("scrimTransitionDistance: $scrimTransitionDistance")
            it.println("notificationsScrimTransitionDelay: $notificationsScrimTransitionDelay")
            it.println(
                "notificationsScrimTransitionDistance: $notificationsScrimTransitionDistance")
            it.decreaseIndent()
            it.println("State")
            it.increaseIndent()
            it.println("dragDownAmount: $dragDownAmount")
            it.println("scrimProgress: $scrimProgress")
            it.println("notificationsScrimProgress: $notificationsScrimProgress")
            it.println("notificationsScrimDragAmount: $notificationsScrimDragAmount")
        }
    }
}
