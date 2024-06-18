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

package com.android.systemui.statusbar.notification.stack

import android.util.IndentingPrintWriter
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import java.util.function.Consumer

/**
 * This is a state holder object used by [NSSL][NotificationStackScrollLayout] to contain states
 * provided by the `NotificationScrollViewBinder` to the `NotificationScrollView`.
 *
 * Unlike AmbientState, no class other than NSSL should ever have access to this class in any way.
 * These fields are effectively NSSL's private fields.
 */
class ScrollViewFields {
    /** Used to produce the clipping path */
    var scrimClippingShape: ShadeScrimShape? = null
    /** Y coordinate in view pixels of the top of the notification stack */
    var stackTop: Float = 0f
    /** Y coordinate in view pixels of the top of the HUN */
    var headsUpTop: Float = 0f
    /** Whether the notifications are scrolled all the way to the top (i.e. when freshly opened) */
    var isScrolledToTop: Boolean = true

    /**
     * Height in view pixels at which the Notification Stack would like to be laid out, including
     * Notification rows, paddings the Shelf and the Footer.
     */
    var intrinsicStackHeight: Int = 0

    /**
     * When internal NSSL expansion requires the stack to be scrolled (e.g. to keep an expanding
     * notification in view), that scroll amount can be sent here and it will be handled by the
     * placeholder
     */
    var syntheticScrollConsumer: Consumer<Float>? = null
    /**
     * When a gesture is consumed internally by NSSL but needs to be handled by other elements (such
     * as the notif scrim) as overscroll, we can notify the placeholder through here.
     */
    var currentGestureOverscrollConsumer: Consumer<Boolean>? = null
    /**
     * Any time the heads up height is recalculated, it should be updated here to be used by the
     * placeholder
     */
    var headsUpHeightConsumer: Consumer<Float>? = null

    /** send the [syntheticScroll] to the [syntheticScrollConsumer], if present. */
    fun sendSyntheticScroll(syntheticScroll: Float) =
        syntheticScrollConsumer?.accept(syntheticScroll)

    /** send [isCurrentGestureOverscroll] to the [currentGestureOverscrollConsumer], if present. */
    fun sendCurrentGestureOverscroll(isCurrentGestureOverscroll: Boolean) =
        currentGestureOverscrollConsumer?.accept(isCurrentGestureOverscroll)

    /** send the [headsUpHeight] to the [headsUpHeightConsumer], if present. */
    fun sendHeadsUpHeight(headsUpHeight: Float) = headsUpHeightConsumer?.accept(headsUpHeight)

    fun dump(pw: IndentingPrintWriter) {
        pw.printSection("StackViewStates") {
            pw.println("scrimClippingShape", scrimClippingShape)
            pw.println("stackTop", stackTop)
            pw.println("headsUpTop", headsUpTop)
            pw.println("isScrolledToTop", isScrolledToTop)
        }
    }
}
