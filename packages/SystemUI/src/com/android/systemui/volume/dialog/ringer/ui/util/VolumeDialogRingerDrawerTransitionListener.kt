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

package com.android.systemui.volume.dialog.ringer.ui.util

import androidx.constraintlayout.motion.widget.MotionLayout

class VolumeDialogRingerDrawerTransitionListener(private val onProgressChanged: (Float) -> Unit) :
    MotionLayout.TransitionListener {

    private var notifyProgressChangeEnabled = true

    fun setProgressChangeEnabled(enabled: Boolean) {
        notifyProgressChangeEnabled = enabled
    }

    override fun onTransitionStarted(motionLayout: MotionLayout?, startId: Int, endId: Int) {}

    override fun onTransitionChange(
        motionLayout: MotionLayout?,
        startId: Int,
        endId: Int,
        progress: Float,
    ) {
        if (notifyProgressChangeEnabled) {
            onProgressChanged(progress)
        }
    }

    override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {}

    override fun onTransitionTrigger(
        motionLayout: MotionLayout?,
        triggerId: Int,
        positive: Boolean,
        progress: Float,
    ) {}
}
