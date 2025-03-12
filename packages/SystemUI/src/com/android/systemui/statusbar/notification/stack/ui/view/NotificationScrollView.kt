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

package com.android.systemui.statusbar.notification.stack.ui.view

import android.view.View
import com.android.systemui.statusbar.notification.stack.shared.model.AccessibilityScrollEvent
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrollState
import java.util.function.Consumer

/**
 * This view (interface) is the view which scrolls and positions the heads up notification and
 * notification stack, but is otherwise agnostic to the content.
 */
interface NotificationScrollView {

    /**
     * Height in view pixels at which the Notification Stack would like to be laid out, including
     * Notification rows, paddings the Shelf and the Footer.
     */
    val intrinsicStackHeight: Int

    /** Height in pixels required to display the top HeadsUp Notification. */
    val topHeadsUpHeight: Int

    /** Bottom inset of the Notification Stack that us used to display the Shelf. */
    val stackBottomInset: Int

    /**
     * Since this is an interface rather than a literal View, this provides cast-like access to the
     * underlying view.
     */
    fun asView(): View

    /** Max alpha for this view */
    fun setMaxAlpha(alpha: Float)

    /** Set the clipping bounds used when drawing */
    fun setScrimClippingShape(shape: ShadeScrimShape?)

    /** set the y position in px of the top of the stack in this view's coordinates */
    fun setStackTop(stackTop: Float)

    /**
     * set the bottom-most acceptable y-position of the bottom of the notification stack/ shelf /
     * footer.
     */
    fun setStackCutoff(stackBottom: Float)

    /** set the y position in px of the top of the HUN in this view's coordinates */
    fun setHeadsUpTop(headsUpTop: Float)

    /** set the bottom-most y position in px, where we can draw HUNs in this view's coordinates */
    fun setHeadsUpBottom(headsUpBottom: Float)

    /** Updates the current scroll state of the notification shade. */
    fun setScrollState(scrollState: ShadeScrollState)

    /** Set a consumer for synthetic scroll events */
    fun setSyntheticScrollConsumer(consumer: Consumer<Float>?)

    /** Set a consumer for accessibility actions to be handled by the placeholder. */
    fun setAccessibilityScrollEventConsumer(consumer: Consumer<AccessibilityScrollEvent>?)

    /** Set a consumer for current gesture overscroll events */
    fun setCurrentGestureOverscrollConsumer(consumer: Consumer<Boolean>?)

    /** Set a consumer for current gesture in guts events */
    fun setCurrentGestureInGutsConsumer(consumer: Consumer<Boolean>?)

    /** Set a consumer for current remote input notification row bottom bound events */
    fun setRemoteInputRowBottomBoundConsumer(consumer: Consumer<Float?>?)

    /** sets that scrolling is allowed */
    fun setScrollingEnabled(enabled: Boolean)

    /** sets the current expand fraction */
    fun setExpandFraction(expandFraction: Float)

    /** sets the current QS expand fraction */
    fun setQsExpandFraction(expandFraction: Float)

    /** set whether we are idle on the lockscreen scene */
    fun setShowingStackOnLockscreen(showingStackOnLockscreen: Boolean)

    /** set the alpha from 0-1f of stack fade-in on lockscreen */
    fun setAlphaForLockscreenFadeIn(alphaForLockscreenFadeIn: Float)

    /** Sets whether the view is displayed in doze mode. */
    fun setDozing(dozing: Boolean)

    /** Sets whether the view is displayed in pulsing mode. */
    fun setPulsing(pulsing: Boolean, animated: Boolean)

    /** Gets the inset for HUNs when they are not visible */
    fun getHeadsUpInset(): Int

    /**
     * Signals that any open Notification guts should be closed, as scene container is handling
     * touch events.
     */
    fun closeGutsOnSceneTouch()

    /** Adds a listener to be notified, when the stack height might have changed. */
    fun addStackHeightChangedListener(runnable: Runnable)

    /** @see addStackHeightChangedListener */
    fun removeStackHeightChangedListener(runnable: Runnable)

    /**
     * Adds a listener to be notified, when the height of the top heads up notification might have
     * changed.
     */
    fun addHeadsUpHeightChangedListener(runnable: Runnable)

    /** @see addHeadsUpHeightChangedListener */
    fun removeHeadsUpHeightChangedListener(runnable: Runnable)

    /** Sets whether updates to the stack are are suppressed. */
    fun suppressHeightUpdates(suppress: Boolean)
}
