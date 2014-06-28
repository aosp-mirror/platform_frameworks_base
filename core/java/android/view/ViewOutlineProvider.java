/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.graphics.Outline;
import android.graphics.drawable.Drawable;

/**
 * Interface by which a View builds its {@link Outline}, used for shadow casting and clipping.
 */
public abstract class ViewOutlineProvider {
    /**
     * Default outline provider for Views, which queries the Outline from the View's background,
     * or returns <code>false</code> if the View does not have a background.
     *
     * @see Drawable#getOutline(Outline)
     */
    public static final ViewOutlineProvider BACKGROUND = new ViewOutlineProvider() {
        @Override
        public boolean getOutline(View view, Outline outline) {
            Drawable background = view.getBackground();
            if (background == null) {
                // no background, no outline
                return false;
            }
            return background.getOutline(outline);
        }
    };

    /**
     * Called to get the provider to populate the Outline.
     *
     * This method will be called by a View when its owned Drawables are invalidated, when the
     * View's size changes, or if {@link View#invalidateOutline()} is called
     * explicitly.
     *
     * @param view The view building the outline.
     * @param outline The empty outline to be populated.
     * @return true if this View should have an outline, else false. The outline must be
     *         populated by this method, and non-empty if true is returned.
     */
    public abstract boolean getOutline(View view, Outline outline);
}
