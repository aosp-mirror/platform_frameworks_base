/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.models.recommendation

import android.app.smartspace.SmartspaceTarget
import android.util.Log
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import javax.inject.Inject

private const val TAG = "SsMediaDataProvider"

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

    /** Updates Smartspace data and propagates it to any listeners. */
    override fun onTargetsAvailable(targets: List<SmartspaceTarget>) {
        // Filter out non-media targets.
        val mediaTargets = mutableListOf<SmartspaceTarget>()
        for (target in targets) {
            val smartspaceTarget = target
            if (smartspaceTarget.featureType == SmartspaceTarget.FEATURE_MEDIA) {
                mediaTargets.add(smartspaceTarget)
            }
        }

        if (!mediaTargets.isEmpty()) {
            Log.d(TAG, "Forwarding Smartspace media updates $mediaTargets")
        }

        smartspaceMediaTargets = mediaTargets
        smartspaceMediaTargetListeners.forEach {
            it.onSmartspaceTargetsUpdated(smartspaceMediaTargets)
        }
    }
}
