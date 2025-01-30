/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import androidx.dynamicanimation.animation.SpringForce

/** A listener that responds to magnetic forces applied to an [ExpandableNotificationRow] */
interface MagneticRowListener {

    /** Set a translation due to a magnetic attachment. */
    fun setMagneticTranslation(translation: Float)

    /**
     * Trigger the magnetic behavior when the row detaches or snaps back from its magnetic
     * couplings.
     *
     * @param[endTranslation] Translation that the row detaches to.
     * @param[springForce] A [SpringForce] that guides the dynamics of the behavior towards the end
     *   translation. This could be a detachment spring force or a snap-back spring force.
     * @param[startVelocity] A start velocity for the animation.
     */
    fun triggerMagneticForce(
        endTranslation: Float,
        springForce: SpringForce,
        startVelocity: Float = 0f,
    )

    /** Cancel any animations related to the magnetic interactions of the row */
    fun cancelMagneticAnimations()

    /** Can the row be dismissed. */
    fun canRowBeDismissed(): Boolean
}
