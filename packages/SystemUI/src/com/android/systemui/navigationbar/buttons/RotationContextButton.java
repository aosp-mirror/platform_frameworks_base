/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.buttons;

import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.content.Context;
import android.view.View;

import com.android.systemui.shared.rotation.RotationButton;
import com.android.systemui.shared.rotation.RotationButtonController;

/** Containing logic for the rotation button in nav bar. */
public class RotationContextButton extends ContextualButton implements RotationButton {
    public static final boolean DEBUG_ROTATION = false;

    private RotationButtonController mRotationButtonController;

    /**
     * @param lightContext the context to use to load the icon resource
     */
    public RotationContextButton(@IdRes int buttonResId, Context lightContext,
            @DrawableRes int iconResId) {
        super(buttonResId, lightContext, iconResId);
    }

    @Override
    public void setRotationButtonController(RotationButtonController rotationButtonController) {
        mRotationButtonController = rotationButtonController;
    }

    @Override
    public void setUpdatesCallback(RotationButtonUpdatesCallback updatesCallback) {
        setListener((button, visible) -> {
            if (updatesCallback != null) {
                updatesCallback.onVisibilityChanged(visible);
            }
        });
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
    protected KeyButtonDrawable getNewDrawable(int lightIconColor, int darkIconColor) {
        return KeyButtonDrawable.create(mRotationButtonController.getContext(),
                lightIconColor, darkIconColor, mRotationButtonController.getIconResId(),
                false /* shadow */, null /* ovalBackgroundColor */);
    }

    @Override
    public boolean acceptRotationProposal() {
        View currentView = getCurrentView();
        return currentView != null && currentView.isAttachedToWindow();
    }
}
