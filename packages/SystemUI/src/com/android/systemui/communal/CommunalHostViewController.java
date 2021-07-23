/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;

import android.view.View;

import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Injectable controller for {@link CommunalHostView}.
 */
public class CommunalHostViewController extends ViewController<CommunalHostView> {
    private static final AnimationProperties ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    @Inject
    protected CommunalHostViewController(CommunalHostView view) {
        super(view);
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }

    /**
     * Sets whether the {@link CommunalHostView} is visible.
     *
     * @param visible {@code true} if the view should be shown, {@code false} otherwise.
     */
    public void show(boolean visible) {
        mView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Sets the Y position of the {@link CommunalHostView}
     *
     * @param y       Offset from parent top.
     * @param animate Whether the change should be animated.
     */
    public void updatePositionY(int y, boolean animate) {
        PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, ANIMATION_PROPERTIES, animate);
    }
}
