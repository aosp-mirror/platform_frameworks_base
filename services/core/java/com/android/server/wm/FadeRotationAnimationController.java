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

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * Controller to fade out and in windows when the display is changing rotation. It can be used for
 * both fixed rotation and normal rotation to hide some non-activity windows. The caller should show
 * the windows until they are drawn with the new rotation.
 */
public class FadeRotationAnimationController extends FadeAnimationController {

    private final ArrayList<WindowToken> mTargetWindowTokens = new ArrayList<>();
    private final WindowManagerService mService;
    /** If non-null, it usually indicates that there will be a screen rotation animation. */
    private final Runnable mFrozenTimeoutRunnable;
    private final WindowToken mNavBarToken;

    /** A runnable which gets called when the {@link #show()} is called. */
    private Runnable mOnShowRunnable;

    public FadeRotationAnimationController(DisplayContent displayContent) {
        super(displayContent);
        mService = displayContent.mWmService;
        mFrozenTimeoutRunnable = mService.mDisplayFrozen ? () -> {
            synchronized (mService.mGlobalLock) {
                displayContent.finishFadeRotationAnimationIfPossible();
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
        } : null;
        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        final WindowState navigationBar = displayPolicy.getNavigationBar();
        if (navigationBar != null) {
            mNavBarToken = navigationBar.mToken;
            final RecentsAnimationController controller = mService.getRecentsAnimationController();
            final boolean navBarControlledByRecents =
                    controller != null && controller.isNavigationBarAttachedToApp();
            // Do not animate movable navigation bar (e.g. non-gesture mode) or when the navigation
            // bar is currently controlled by recents animation.
            if (!displayPolicy.navigationBarCanMove() && !navBarControlledByRecents) {
                mTargetWindowTokens.add(mNavBarToken);
            }
        } else {
            mNavBarToken = null;
        }
        // Collect the target windows to fade out. The display won't wait for them to unfreeze.
        final WindowState notificationShade = displayPolicy.getNotificationShade();
        displayContent.forAllWindows(w -> {
            if (w.mActivityRecord == null && w.mHasSurface && !w.mForceSeamlesslyRotate
                    && !w.mIsWallpaper && !w.mIsImWindow && w != navigationBar
                    && w != notificationShade) {
                mTargetWindowTokens.add(w.mToken);
            }
        }, true /* traverseTopToBottom */);
    }

    /** Applies show animation on the previously hidden window tokens. */
    void show() {
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mTargetWindowTokens.get(i);
            fadeWindowToken(true /* show */, windowToken, ANIMATION_TYPE_FIXED_TRANSFORM);
        }
        mTargetWindowTokens.clear();
        if (mFrozenTimeoutRunnable != null) {
            mService.mH.removeCallbacks(mFrozenTimeoutRunnable);
        }
        if (mOnShowRunnable != null) {
            mOnShowRunnable.run();
            mOnShowRunnable = null;
        }
    }

    /**
     * Returns {@code true} if all target windows are shown. It only takes effects if this
     * controller is created for normal rotation.
     */
    boolean show(WindowToken token) {
        if (mFrozenTimeoutRunnable != null && mTargetWindowTokens.remove(token)) {
            fadeWindowToken(true /* show */, token, ANIMATION_TYPE_FIXED_TRANSFORM);
            if (mTargetWindowTokens.isEmpty()) {
                mService.mH.removeCallbacks(mFrozenTimeoutRunnable);
                return true;
            }
        }
        return false;
    }

    /** Applies hide animation on the window tokens which may be seamlessly rotated later. */
    void hide() {
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mTargetWindowTokens.get(i);
            fadeWindowToken(false /* show */, windowToken, ANIMATION_TYPE_FIXED_TRANSFORM);
        }
        if (mFrozenTimeoutRunnable != null) {
            mService.mH.postDelayed(mFrozenTimeoutRunnable,
                    WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION);
        }
    }

    /** Returns {@code true} if the window is handled by this controller. */
    boolean isHandledToken(WindowToken token) {
        return token == mNavBarToken || isTargetToken(token);
    }

    /** Returns {@code true} if the controller will run fade animations on the window. */
    boolean isTargetToken(WindowToken token) {
        return mTargetWindowTokens.contains(token);
    }

    void setOnShowRunnable(Runnable onShowRunnable) {
        mOnShowRunnable = onShowRunnable;
    }

    @Override
    public Animation getFadeInAnimation() {
        if (mFrozenTimeoutRunnable != null) {
            // Use a shorter animation so it is easier to align with screen rotation animation.
            return AnimationUtils.loadAnimation(mContext, R.anim.screen_rotate_0_enter);
        }
        return super.getFadeInAnimation();
    }

    @Override
    public Animation getFadeOutAnimation() {
        if (mFrozenTimeoutRunnable != null) {
            // Hide the window immediately because screen should have been covered by screenshot.
            return new AlphaAnimation(0 /* fromAlpha */, 0 /* toAlpha */);
        }
        return super.getFadeOutAnimation();
    }
}
