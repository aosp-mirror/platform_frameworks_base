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

package com.android.server.wm;

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_FIXED_TRANSFORM;

import java.util.ArrayList;

/**
 * Controller to fade out and in system ui when applying a fixed rotation transform to a window
 * token.
 *
 * The system bars will be fade out when the fixed rotation transform starts and will be fade in
 * once all surfaces have been rotated.
 */
public class FixedRotationAnimationController extends FadeAnimationController {

    private final WindowState mStatusBar;
    private final WindowState mNavigationBar;
    private final ArrayList<WindowToken> mAnimatedWindowToken = new ArrayList<>(2);

    public FixedRotationAnimationController(DisplayContent displayContent) {
        super(displayContent);
        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        mStatusBar = displayPolicy.getStatusBar();

        final RecentsAnimationController controller =
                displayContent.mWmService.getRecentsAnimationController();
        final boolean navBarControlledByRecents =
                controller != null && controller.isNavigationBarAttachedToApp();
        // Do not animate movable navigation bar (e.g. non-gesture mode) or when the navigation bar
        // is currently controlled by recents animation.
        mNavigationBar = !displayPolicy.navigationBarCanMove()
                && !navBarControlledByRecents ? displayPolicy.getNavigationBar() : null;
    }

    /** Applies show animation on the previously hidden window tokens. */
    void show() {
        for (int i = mAnimatedWindowToken.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mAnimatedWindowToken.get(i);
            fadeWindowToken(true /* show */, windowToken, ANIMATION_TYPE_FIXED_TRANSFORM);
        }
    }

    /** Applies hide animation on the window tokens which may be seamlessly rotated later. */
    void hide() {
        if (mNavigationBar != null) {
            fadeWindowToken(false /* show */, mNavigationBar.mToken,
                    ANIMATION_TYPE_FIXED_TRANSFORM);
        }
        if (mStatusBar != null) {
            fadeWindowToken(false /* show */, mStatusBar.mToken,
                    ANIMATION_TYPE_FIXED_TRANSFORM);
        }
    }

    @Override
    public void fadeWindowToken(boolean show, WindowToken windowToken, int animationType) {
        super.fadeWindowToken(show, windowToken, animationType);
        mAnimatedWindowToken.add(windowToken);
    }
}
