package com.android.systemui.media

import android.app.smartspace.SmartspaceTarget
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import javax.inject.Inject

/** Provides SmartspaceTargets of media types for SystemUI media control. */
class SmartspaceMediaDataProvider @Inject constructor() : BcSmartspaceDataPlugin {

    private val smartspaceMediaTargetListeners: MutableList<SmartspaceTargetListener> =
        mutableListOf()
    private var smartspaceMediaTargets: List<SmartspaceTarget> = listOf()

    override fun registerListener(smartspaceTargetListener: SmartspaceTargetListener) {
        smartspaceMediaTargetListeners.add(smartspaceTargetListener)
    }

    override fun unregisterListener(smartspaceTargetListener: SmartspaceTargetListener?) {
        smartspaceMediaTargetListeners.remove(smartspaceTargetListener)
    }

    /** Updates Smartspace data and propagates it to any listeners.  */
    override fun onTargetsAvailable(targets: List<SmartspaceTarget>) {
        // Filter out non-media targets.
        val mediaTargets = mutableListOf<SmartspaceTarget>()
        for (target in targets) {
            val smartspaceTarget = target
            if (smartspaceTarget.featureType == SmartspaceTarget.FEATURE_MEDIA) {
                mediaTargets.add(smartspaceTarget)
            }
        }

        smartspaceMediaTargets = mediaTargets
        smartspaceMediaTargetListeners.forEach {
            it.onSmartspaceTargetsUpdated(smartspaceMediaTargets)
        }
    }
}
