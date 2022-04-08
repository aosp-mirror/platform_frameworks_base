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

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.android.systemui.statusbar.policy.KeyButtonDrawable;

/** Containing logic for the rotation button in nav bar. */
public class RotationContextButton extends ContextualButton implements
        NavigationModeController.ModeChangedListener, RotationButton {
    public static final boolean DEBUG_ROTATION = false;

    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;
    private RotationButtonController mRotationButtonController;

    public RotationContextButton(@IdRes int buttonResId, @DrawableRes int iconResId) {
        super(buttonResId, iconResId);
    }

    @Override
    public void setRotationButtonController(RotationButtonController rotationButtonController) {
        mRotationButtonController = rotationButtonController;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        // Start the rotation animation once it becomes visible
        final KeyButtonDrawable currentDrawable = getImageDrawable();
        if (visibility == View.VISIBLE && currentDrawable != null) {
            currentDrawable.resetAnimation();
            currentDrawable.startAnimation();
        }
    }

    @Override
    protected KeyButtonDrawable getNewDrawable() {
        Context context = new ContextThemeWrapper(getContext().getApplicationContext(),
                mRotationButtonController.getStyleRes());
        return KeyButtonDrawable.create(context, mIconResId, false /* shadow */,
                null /* ovalBackgroundColor */);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    @Override
    public boolean acceptRotationProposal() {
        View currentView = getCurrentView();
        return currentView != null && currentView.isAttachedToWindow();
    }
}
