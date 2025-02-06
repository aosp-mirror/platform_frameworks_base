package com.android.systemui.statusbar

import android.content.Context
import android.util.IndentingPrintWriter
import android.util.MathUtils
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Controls the lockscreen to shade transition for the keyguard elements. */
class LockscreenShadeKeyguardTransitionController
@AssistedInject
constructor(
    private val mediaHierarchyManager: MediaHierarchyManager,
    @Assisted private val shadeLockscreenInteractor: ShadeLockscreenInteractor,
    @ShadeDisplayAware context: Context,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    splitShadeStateController: SplitShadeStateController,
) :
    AbstractLockscreenShadeTransitionController(
        context,
        configurationController,
        dumpManager,
        splitShadeStateController,
    ) {

    /**
     * Distance that the full shade transition takes in order for the keyguard content on
     * NotificationPanelViewController to fully fade (e.g. Clock & Smartspace).
     */
    private var alphaTransitionDistance = 0

    /** The amount of alpha that was last set on the keyguard elements. */
    private var alpha = 0f

    /** The latest progress [0,1] of the alpha transition. */
    private var alphaProgress = 0f

    /** The amount of alpha that was last set on the keyguard status bar. */
    private var statusBarAlpha = 0f

    override fun updateResources() {
        alphaTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_npvc_keyguard_content_alpha_transition_distance
            )
    }

    override fun onDragDownAmountChanged(dragDownAmount: Float) {
        alphaProgress = MathUtils.saturate(dragDownAmount / alphaTransitionDistance)
        alpha = 1f - alphaProgress

        statusBarAlpha = if (useSplitShade) alpha else -1f
        shadeLockscreenInteractor.setKeyguardStatusBarAlpha(statusBarAlpha)
    }

    override fun dump(indentingPrintWriter: IndentingPrintWriter) {
        indentingPrintWriter.let {
            it.println("LockscreenShadeKeyguardTransitionController:")
            it.increaseIndent()
            it.println("Resources:")
            it.increaseIndent()
            it.println("alphaTransitionDistance: $alphaTransitionDistance")
            it.decreaseIndent()
            it.println("State:")
            it.increaseIndent()
            it.println("dragDownAmount: $dragDownAmount")
            it.println("alpha: $alpha")
            it.println("alphaProgress: $alphaProgress")
            it.println("statusBarAlpha: $statusBarAlpha")
        }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(
            shadeLockscreenInteractor: ShadeLockscreenInteractor
        ): LockscreenShadeKeyguardTransitionController
    }
}
