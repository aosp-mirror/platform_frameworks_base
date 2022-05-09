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

package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter

/**
 * MetadataAnimationHandler controls the current state of the MediaControlPanel's transition motion.
 *
 * It checks for a changed data object (artist & title from MediaControlPanel) and runs the
 * animation if necessary. When the motion has fully transitioned the elements out, it runs the
 * update callback to modify the view data, before the enter animation runs.
 */
internal open class MetadataAnimationHandler(
    private val exitAnimator: Animator,
    private val enterAnimator: Animator
) : AnimatorListenerAdapter() {

    private var postExitUpdate: (() -> Unit)? = null
    private var postEnterUpdate: (() -> Unit)? = null
    private var targetData: Any? = null

    val isRunning: Boolean
        get() = enterAnimator.isRunning || exitAnimator.isRunning

    fun setNext(targetData: Any, postExit: () -> Unit, postEnter: () -> Unit): Boolean {
        if (targetData != this.targetData) {
            this.targetData = targetData
            postExitUpdate = postExit
            postEnterUpdate = postEnter
            if (!isRunning) {
                exitAnimator.start()
            }
            return true
        }
        return false
    }

    override fun onAnimationEnd(anim: Animator) {
        if (anim === exitAnimator) {
            postExitUpdate?.let { it() }
            postExitUpdate = null
            enterAnimator.start()
        }

        if (anim === enterAnimator) {
            // Another new update appeared while entering
            if (postExitUpdate != null) {
                exitAnimator.start()
            } else {
                postEnterUpdate?.let { it() }
                postEnterUpdate = null
            }
        }
    }

    init {
        exitAnimator.addListener(this)
        enterAnimator.addListener(this)
    }
}