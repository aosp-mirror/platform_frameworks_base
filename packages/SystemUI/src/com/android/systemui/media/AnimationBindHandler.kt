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

import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable

/**
 * AnimationBindHandler is responsible for tracking the bound animation state and preventing
 * jank and conflicts due to media notifications arriving at any time during an animation. It
 * does this in two parts.
 *  - Exit animations fired as a result of user input are tracked. When these are running, any
 *      bind actions are delayed until the animation completes (and then fired in sequence).
 *  - Continuous animations are tracked using their rebind id. Later calls using the same
 *      rebind id will be totally ignored to prevent the continuous animation from restarting.
 */
internal class AnimationBindHandler : Animatable2.AnimationCallback() {
    private val onAnimationsComplete = mutableListOf<() -> Unit>()
    private val registrations = mutableListOf<Animatable2>()
    private var rebindId: Int? = null

    val isAnimationRunning: Boolean
        get() = registrations.any { it.isRunning }

    /**
     * This check prevents rebinding to the action button if the identifier has not changed. A
     * null value is always considered to be changed. This is used to prevent the connecting
     * animation from rebinding (and restarting) if multiple buffer PlaybackStates are pushed by
     * an application in a row.
     */
    fun updateRebindId(newRebindId: Int?): Boolean {
        if (rebindId == null || newRebindId == null || rebindId != newRebindId) {
            rebindId = newRebindId
            return true
        }
        return false
    }

    fun tryRegister(drawable: Drawable?) {
        if (drawable is Animatable2) {
            val anim = drawable as Animatable2
            anim.registerAnimationCallback(this)
            registrations.add(anim)
        }
    }

    fun unregisterAll() {
        registrations.forEach { it.unregisterAnimationCallback(this) }
        registrations.clear()
    }

    fun tryExecute(action: () -> Unit) {
        if (isAnimationRunning) {
            onAnimationsComplete.add(action)
        } else {
            action()
        }
    }

    override fun onAnimationEnd(drawable: Drawable) {
        super.onAnimationEnd(drawable)
        if (!isAnimationRunning) {
            onAnimationsComplete.forEach { it() }
            onAnimationsComplete.clear()
        }
    }
}