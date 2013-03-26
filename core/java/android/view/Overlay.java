/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.view;

import android.graphics.drawable.Drawable;

/**
 * An overlay is an extra layer that sits on top of a View (the "host view")
 * which is drawn after all other content in that view (including children,
 * if the view is a ViewGroup). Interaction with the overlay layer is done in
 * terms of adding/removing views and drawables.
 *
 * @see android.view.View#getOverlay()
 */
public interface Overlay {

    /**
     * Adds a Drawable to the overlay. The bounds of the drawable should be relative to
     * the host view. Any drawable added to the overlay should be removed when it is no longer
     * needed or no longer visible.
     *
     * @param drawable The Drawable to be added to the overlay. This drawable will be
     * drawn when the view redraws its overlay.
     * @see #remove(android.graphics.drawable.Drawable)
     * @see #add(View)
     */
    void add(Drawable drawable);

    /**
     * Removes the specified Drawable from the overlay.
     *
     * @param drawable The Drawable to be removed from the overlay.
     * @see #add(android.graphics.drawable.Drawable)
     */
    void remove(Drawable drawable);

    /**
     * Adds a View to the overlay. The bounds of the added view should be
     * relative to the host view. Any view added to the overlay should be
     * removed when it is no longer needed or no longer visible.
     *
     * <p>If the view has a parent, the view will be removed from that parent
     * before being added to the overlay. Also, the view will be repositioned
     * such that it is in the same relative location inside the activity. For
     * example, if the view's current parent lies 100 pixels to the right
     * and 200 pixels down from the origin of the overlay's
     * host view, then the view will be offset by (100, 200).</p>
     *
     * @param view The View to be added to the overlay. The added view will be
     * drawn when the overlay is drawn.
     * @see #remove(View)
     * @see #add(android.graphics.drawable.Drawable)
     */
    void add(View view);

    /**
     * Removes the specified View from the overlay.
     *
     * @param view The View to be removed from the overlay.
     * @see #add(View)
     */
    void remove(View view);

    /**
     * Removes all views and drawables from the overlay.
     */
    void clear();
}
