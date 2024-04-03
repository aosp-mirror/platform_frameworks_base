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
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import java.util.function.Consumer

/**
 * This view (interface) is the view which scrolls and positions the heads up notification and
 * notification stack, but is otherwise agnostic to the content.
 */
interface NotificationScrollView {
    /**
     * Since this is an interface rather than a literal View, this provides cast-like access to the
     * underlying view.
     */
    fun asView(): View

    /** Set the clipping bounds used when drawing */
    fun setScrimClippingShape(shape: ShadeScrimShape?)

    /** set the y position in px of the top of the stack in this view's coordinates */
    fun setStackTop(stackTop: Float)

    /** set the y position in px of the bottom of the stack in this view's coordinates */
    fun setStackBottom(stackBottom: Float)

    /** set the y position in px of the top of the HUN in this view's coordinates */
    fun setHeadsUpTop(headsUpTop: Float)

    /** set whether the view has been scrolled all the way to the top */
    fun setScrolledToTop(scrolledToTop: Boolean)

    /** Set a consumer for synthetic scroll events */
    fun setSyntheticScrollConsumer(consumer: Consumer<Float>?)
    /** Set a consumer for stack height changed events */
    fun setStackHeightConsumer(consumer: Consumer<Float>?)
    /** Set a consumer for heads up height changed events */
    fun setHeadsUpHeightConsumer(consumer: Consumer<Float>?)

    /** sets that scrolling is allowed */
    fun setScrollingEnabled(enabled: Boolean)

    /** sets the current expand fraction */
    fun setExpandFraction(expandFraction: Float)

    /** Sets whether the view is displayed in doze mode. */
    fun setDozing(dozing: Boolean)
}
