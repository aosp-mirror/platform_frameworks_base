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

package com.android.systemui.accessibility.floatingmenu;

import android.graphics.PointF;

import androidx.dynamicanimation.animation.DynamicAnimation;

/**
 * Controls the interaction animations of the menu view {@link MenuView}.
 */
class MenuAnimationController {
    private final MenuView mMenuView;

    MenuAnimationController(MenuView menuView) {
        mMenuView = menuView;
    }

    void moveToPosition(PointF position) {
        DynamicAnimation.TRANSLATION_X.setValue(mMenuView, position.x);
        DynamicAnimation.TRANSLATION_Y.setValue(mMenuView, position.y);

        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);
    }
}
