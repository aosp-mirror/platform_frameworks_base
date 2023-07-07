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

package com.android.systemui.statusbar.events

import android.annotation.IntDef
import androidx.core.animation.Animator
import androidx.core.animation.AnimatorSet
import androidx.core.animation.PathInterpolator
import com.android.systemui.Dumpable
import com.android.systemui.statusbar.policy.CallbackController

interface SystemStatusAnimationScheduler :
        CallbackController<SystemStatusAnimationCallback>, Dumpable {

    @SystemAnimationState fun getAnimationState(): Int

    fun onStatusEvent(event: StatusEvent)

    fun removePersistentDot()
}

/**
 * The general idea here is that this scheduler will run two value animators, and provide
 * animator-like callbacks for each kind of animation. The SystemChrome animation is expected to
 * create space for the chip animation to display. This means hiding the system elements in the
 * status bar and keyguard.
 *
 * The value animators themselves are simple animators from 0.0 to 1.0. Listeners can apply any
 * interpolation they choose but realistically these are most likely to be simple alpha transitions
 */
interface SystemStatusAnimationCallback {
    /** Implement this method to return an [Animator] or [AnimatorSet] that presents the chip */
    fun onSystemEventAnimationBegin(): Animator? { return null }
    /** Implement this method to return an [Animator] or [AnimatorSet] that hides the chip */
    fun onSystemEventAnimationFinish(hasPersistentDot: Boolean): Animator? { return null }

    // Best method name, change my mind
    @JvmDefault
    fun onSystemStatusAnimationTransitionToPersistentDot(contentDescription: String?): Animator? {
        return null
    }
    @JvmDefault fun onHidePersistentDot(): Animator? { return null }
}


/**
 * Animation state IntDef
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
        value = [
            IDLE,
            ANIMATION_QUEUED,
            ANIMATING_IN,
            RUNNING_CHIP_ANIM,
            ANIMATING_OUT,
            SHOWING_PERSISTENT_DOT
        ]
)
annotation class SystemAnimationState

/** No animation is in progress */
@SystemAnimationState const val IDLE = 0
/** An animation is queued, and awaiting the debounce period */
const val ANIMATION_QUEUED = 1
/** System is animating out, and chip is animating in */
const val ANIMATING_IN = 2
/** Chip has animated in and is awaiting exit animation, and optionally playing its own animation */
const val RUNNING_CHIP_ANIM = 3
/** Chip is animating away and system is animating back */
const val ANIMATING_OUT = 4
/** Chip has animated away, and the persistent dot is showing */
const val SHOWING_PERSISTENT_DOT = 5

/** Commonly-needed interpolators can go here */
@JvmField val STATUS_BAR_X_MOVE_OUT = PathInterpolator(0.33f, 0f, 0f, 1f)
@JvmField val STATUS_BAR_X_MOVE_IN = PathInterpolator(0f, 0f, 0f, 1f)
/**
 * Status chip animation to dot have multiple stages of motion, the _1 and _2 interpolators should
 * be used in succession
 */
val STATUS_CHIP_WIDTH_TO_DOT_KEYFRAME_1 = PathInterpolator(0.44f, 0f, 0.25f, 1f)
val STATUS_CHIP_WIDTH_TO_DOT_KEYFRAME_2 = PathInterpolator(0.3f, 0f, 0.26f, 1f)
val STATUS_CHIP_HEIGHT_TO_DOT_KEYFRAME_1 = PathInterpolator(0.4f, 0f, 0.17f, 1f)
val STATUS_CHIP_HEIGHT_TO_DOT_KEYFRAME_2 = PathInterpolator(0.3f, 0f, 0f, 1f)
val STATUS_CHIP_MOVE_TO_DOT = PathInterpolator(0f, 0f, 0.05f, 1f)

internal const val DEBOUNCE_DELAY = 100L

/**
 * The total time spent on the chip animation is 1500ms, broken up into 3 sections:
 * - 500ms to animate the chip in (including animating system icons away)
 * - 500ms holding the chip on screen
 * - 500ms to animate the chip away (and system icons back)
 */
internal const val APPEAR_ANIMATION_DURATION = 500L
internal const val DISPLAY_LENGTH = 3000L
internal const val DISAPPEAR_ANIMATION_DURATION = 500L

internal const val MIN_UPTIME: Long = 5 * 1000