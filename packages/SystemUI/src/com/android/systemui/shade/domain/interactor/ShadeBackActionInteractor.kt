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

package com.android.systemui.shade.domain.interactor

/** Allows the back action to interact with the shade. */
interface ShadeBackActionInteractor {
    /**
     * Animate QS collapse by flinging it. If QS is expanded, it will collapse into QQS and stop. If
     * in split shade, it will collapse the whole shade.
     *
     * @param fullyCollapse Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    fun animateCollapseQs(fullyCollapse: Boolean)

    /** Returns whether the shade can be collapsed. */
    fun canBeCollapsed(): Boolean

    /**
     * Close the keyguard user switcher if it is open and capable of closing.
     *
     * Has no effect if user switcher isn't supported, if the user switcher is already closed, or if
     * the user switcher uses "simple" mode. The simple user switcher cannot be closed.
     *
     * @return true if the keyguard user switcher was open, and is now closed
     */
    @Deprecated("Only supported by very old devices that will not adopt scenes.")
    fun closeUserSwitcherIfOpen(): Boolean

    /** Called when Back gesture has been committed (i.e. a back event has definitely occurred) */
    fun onBackPressed()

    /** Sets progress of the predictive back animation. */
    @Deprecated("According to b/318376223, shade predictive back is not be supported.")
    fun onBackProgressed(progressFraction: Float)
}
