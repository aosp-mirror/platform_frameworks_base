/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.content.Context;
import android.view.View;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonView;

/**
 * Simple contextual button that is added to the {@link ContextualButtonGroup}. Extend if need extra
 * functionality.
 */
public class ContextualButton extends ButtonDispatcher {

    protected final @DrawableRes int mIconResId;

    /**
      * Create a contextual button that will use a {@link KeyButtonView} and
      * {@link KeyButtonDrawable} get and show the button from xml to its icon drawable.
      * @param buttonResId the button view from xml layout
      * @param iconResId icon resource to be used
      */
    public ContextualButton(@IdRes int buttonResId, @DrawableRes int iconResId) {
        super(buttonResId);
        mIconResId = iconResId;
    }

    /**
     * Reload the drawable from resource id, should reapply the previous dark intensity.
     */
    public void updateIcon() {
        final KeyButtonDrawable currentDrawable = getImageDrawable();
        KeyButtonDrawable drawable = getNewDrawable();
        if (currentDrawable != null) {
            drawable.setDarkIntensity(currentDrawable.getDarkIntensity());
        }
        setImageDrawable(drawable);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        // Stop any active animations if hidden
        final KeyButtonDrawable currentDrawable = getImageDrawable();
        if (visibility != View.VISIBLE && currentDrawable != null && currentDrawable.canAnimate()) {
            currentDrawable.clearAnimationCallbacks();
            currentDrawable.resetAnimation();
        }
    }

    protected KeyButtonDrawable getNewDrawable() {
        return KeyButtonDrawable.create(getContext().getApplicationContext(), mIconResId,
                false /* shadow */);
    }

    /**
     * This context is from the view that could be stale after rotation or config change. To get
     * correct resources use getApplicationContext() as well.
     * @return current view context
     */
    protected Context getContext() {
        return getCurrentView().getContext();
    }
}
