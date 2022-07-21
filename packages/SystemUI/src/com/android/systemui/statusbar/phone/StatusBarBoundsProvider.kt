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

package com.android.systemui.statusbar.phone

import android.graphics.Rect
import android.view.View
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentModule.END_SIDE_CONTENT
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentModule.START_SIDE_CONTENT
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentScope
import com.android.systemui.util.boundsOnScreen
import javax.inject.Inject
import javax.inject.Named

/** Provides various bounds within the status bar. */
@StatusBarFragmentScope
class StatusBarBoundsProvider
@Inject
constructor(
    private val changeListeners: Set<@JvmSuppressWildcards BoundsChangeListener>,
    @Named(START_SIDE_CONTENT) private val startSideContent: View,
    @Named(END_SIDE_CONTENT) private val endSideContent: View,
) : StatusBarFragmentComponent.Startable {

    interface BoundsChangeListener {
        fun onStatusBarBoundsChanged()
    }

    private var previousBounds =
        BoundsPair(start = startSideContent.boundsOnScreen, end = endSideContent.boundsOnScreen)

    private val layoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val newBounds = BoundsPair(start = visibleStartSideBounds, end = visibleEndSideBounds)
            if (previousBounds != newBounds) {
                previousBounds = newBounds
                changeListeners.forEach { it.onStatusBarBoundsChanged() }
            }
        }

    override fun start() {
        startSideContent.addOnLayoutChangeListener(layoutListener)
        endSideContent.addOnLayoutChangeListener(layoutListener)
    }

    override fun stop() {
        startSideContent.removeOnLayoutChangeListener(layoutListener)
        endSideContent.removeOnLayoutChangeListener(layoutListener)
    }

    /**
     * Returns the bounds of the end side of the status bar that are visible to the user. The end
     * side is right when in LTR and is left when in RTL.
     *
     * Note that even though the layout might be larger, here we only return the bounds for what is
     * visible to the user.
     *
     * The end side of the status bar contains the multi-user switcher and status icons such as
     * wi-fi, battery, etc
     */
    val visibleEndSideBounds: Rect
        get() = endSideContent.boundsOnScreen

    /**
     * Returns the bounds of the start side of the status bar that are visible to the user. The
     * start side is left when in LTR and is right when in RTL.
     *
     * Note that even though the layout might be larger, here we only return the bounds for what is
     * visible to the user.
     *
     * The start side of the status bar contains the operator name, clock, on-going call chip, and
     * notifications.
     */
    val visibleStartSideBounds: Rect
        get() = startSideContent.boundsOnScreen
}

private data class BoundsPair(val start: Rect, val end: Rect)
