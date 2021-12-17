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

import android.os.HandlerExecutor;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.view.WindowManager;
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

    /** The map of window token to its animation leash. */
    private final ArrayMap<WindowToken, SurfaceControl> mTargetWindowTokens = new ArrayMap<>();
    private final WindowManagerService mService;
    /** If non-null, it usually indicates that there will be a screen rotation animation. */
    private final Runnable mTimeoutRunnable;
    private final WindowToken mNavBarToken;

    /** A runnable which gets called when the {@link #show()} is called. */
    private Runnable mOnShowRunnable;

    /** Whether to use constant zero alpha animation. */
    private boolean mHideImmediately;

    /** Whether this controller is triggered from shell transition with type CHANGE. */
    private final boolean mIsChangeTransition;

    /** Whether the start transaction of the transition is committed (by shell). */
    private boolean mIsStartTransactionCommitted;

    /** The list to store the drawn tokens before the rotation animation starts. */
    private ArrayList<WindowToken> mPendingShowTokens;

    /** It is used when the display has rotated, but some windows fade out in old rotation. */
    private SeamlessRotator mRotator;

    private final int mOriginalRotation;
    private final boolean mHasScreenRotationAnimation;

    public FadeRotationAnimationController(DisplayContent displayContent) {
        super(displayContent);
        mService = displayContent.mWmService;
        mOriginalRotation = displayContent.getWindowConfiguration().getRotation();
        final int transitionType =
                displayContent.mTransitionController.getCollectingTransitionType();
        mIsChangeTransition = transitionType == WindowManager.TRANSIT_CHANGE;
        // Only CHANGE type (rotation animation) needs to wait for the start transaction.
        mIsStartTransactionCommitted = !mIsChangeTransition;
        mTimeoutRunnable = displayContent.inTransition() ? () -> {
            synchronized (mService.mGlobalLock) {
                displayContent.finishFadeRotationAnimationIfPossible();
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
        } : null;
        mHasScreenRotationAnimation =
                displayContent.getRotationAnimation() != null || mIsChangeTransition;
        if (mHasScreenRotationAnimation) {
            // Hide the windows immediately because screen should have been covered by screenshot.
            mHideImmediately = true;
        }
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
                mTargetWindowTokens.put(mNavBarToken, null);
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
                mTargetWindowTokens.put(w.mToken, null);
            }
        }, true /* traverseTopToBottom */);
    }

    @Override
    public void fadeWindowToken(boolean show, WindowToken windowToken, int animationType) {
        if (show) {
            final SurfaceControl leash = mTargetWindowTokens.remove(windowToken);
            if (leash != null && mRotator != null) {
                // The leash was unrotated by start transaction of transition. Clear the transform
                // to reshow the window in current rotation.
                mRotator.setIdentityMatrix(mDisplayContent.getPendingTransaction(), leash);
            }
        }
        super.fadeWindowToken(show, windowToken, animationType);
    }

    /** Applies show animation on the previously hidden window tokens. */
    void show() {
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mTargetWindowTokens.keyAt(i);
            fadeWindowToken(true /* show */, windowToken, ANIMATION_TYPE_FIXED_TRANSFORM);
        }
        mTargetWindowTokens.clear();
        mPendingShowTokens = null;
        if (mTimeoutRunnable != null) {
            mService.mH.removeCallbacks(mTimeoutRunnable);
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
        if (!isTargetToken(token)) return false;
        if (!mIsStartTransactionCommitted) {
            // The fade-in animation should only start after the screenshot layer is shown by shell.
            // Otherwise the window will be blinking before the rotation animation starts. So store
            // to a pending list and animate them until the transaction is committed.
            if (mPendingShowTokens == null) {
                mPendingShowTokens = new ArrayList<>();
            }
            mPendingShowTokens.add(token);
            return false;
        }
        if (!mHasScreenRotationAnimation && token.mTransitionController.inTransition()) {
            // Defer showing to onTransitionFinished().
            return false;
        }
        // If the timeout runnable is null (fixed rotation), the case will be handled by show().
        if (mTimeoutRunnable != null) {
            fadeWindowToken(true /* show */, token, ANIMATION_TYPE_FIXED_TRANSFORM);
            if (mTargetWindowTokens.isEmpty()) {
                mService.mH.removeCallbacks(mTimeoutRunnable);
                return true;
            }
        }
        return false;
    }

    /** Applies hide animation on the window tokens which may be seamlessly rotated later. */
    void hide() {
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mTargetWindowTokens.keyAt(i);
            fadeWindowToken(false /* show */, windowToken, ANIMATION_TYPE_FIXED_TRANSFORM);
        }
        if (mTimeoutRunnable != null) {
            mService.mH.postDelayed(mTimeoutRunnable,
                    WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION);
        }
    }

    /** Hides the window immediately until it is drawn in new rotation. */
    void hideImmediately(WindowToken windowToken) {
        final boolean original = mHideImmediately;
        mHideImmediately = true;
        fadeWindowToken(false /* show */, windowToken, ANIMATION_TYPE_FIXED_TRANSFORM);
        mHideImmediately = original;
    }

    /** Returns {@code true} if the window is handled by this controller. */
    boolean isHandledToken(WindowToken token) {
        return token == mNavBarToken || isTargetToken(token);
    }

    /** Returns {@code true} if the controller will run fade animations on the window. */
    boolean isTargetToken(WindowToken token) {
        return mTargetWindowTokens.containsKey(token);
    }

    /**
     * Whether the insets animation leash should use previous position when running fade out
     * animation in rotated display.
     */
    boolean shouldFreezeInsetsPosition(WindowState w) {
        return !mHasScreenRotationAnimation && w.mTransitionController.inTransition()
                && isTargetToken(w.mToken);
    }

    void setOnShowRunnable(Runnable onShowRunnable) {
        mOnShowRunnable = onShowRunnable;
    }

    /**
     * Puts initial operation of leash to the transaction which will be executed when the
     * transition starts. And associate transaction callback to consume pending animations.
     */
    void setupStartTransaction(SurfaceControl.Transaction t) {
        if (!mIsChangeTransition) {
            // Take OPEN/CLOSE transition type as the example, the non-activity windows need to
            // fade out in previous rotation while display has rotated to the new rotation, so
            // their leashes are unrotated with the start transaction.
            mRotator = new SeamlessRotator(mOriginalRotation,
                    mDisplayContent.getWindowConfiguration().getRotation(),
                    mDisplayContent.getDisplayInfo(),
                    false /* applyFixedTransformationHint */);
            for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
                final SurfaceControl leash = mTargetWindowTokens.valueAt(i);
                if (leash != null) {
                    mRotator.applyTransform(t, leash);
                }
            }
            return;
        }
        // Hide the windows immediately because a screenshot layer should cover the screen.
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final SurfaceControl leash = mTargetWindowTokens.valueAt(i);
            if (leash != null) {
                t.setAlpha(leash, 0f);
            }
        }
        // If there are windows have redrawn in new rotation but the start transaction has not
        // been applied yet, the fade-in animation will be deferred. So once the transaction is
        // committed, the fade-in animation can run with screen rotation animation.
        t.addTransactionCommittedListener(new HandlerExecutor(mService.mH), () -> {
            synchronized (mService.mGlobalLock) {
                mIsStartTransactionCommitted = true;
                if (mPendingShowTokens == null) return;
                for (int i = mPendingShowTokens.size() - 1; i >= 0; i--) {
                    mDisplayContent.finishFadeRotationAnimation(mPendingShowTokens.get(i));
                }
                mPendingShowTokens = null;
            }
        });
    }

    void onTransitionFinished() {
        if (mIsChangeTransition) {
            // With screen rotation animation, the windows are always faded in when they are drawn.
            // Because if they are drawn fast enough, the fade animation should not be observable.
            return;
        }
        // For other transition types, the fade-in animation runs after the transition to make the
        // transition animation (e.g. launch activity) look cleaner.
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken token = mTargetWindowTokens.keyAt(i);
            for (int j = token.getChildCount() - 1; j >= 0; j--) {
                // Only fade in the drawn windows. If the remaining windows are drawn later,
                // show(WindowToken) will be called to fade in them.
                if (token.getChildAt(j).isDrawFinishedLw()) {
                    mDisplayContent.finishFadeRotationAnimation(token);
                    break;
                }
            }
        }
    }

    @Override
    public Animation getFadeInAnimation() {
        if (mHasScreenRotationAnimation) {
            // Use a shorter animation so it is easier to align with screen rotation animation.
            return AnimationUtils.loadAnimation(mContext, R.anim.screen_rotate_0_enter);
        }
        return super.getFadeInAnimation();
    }

    @Override
    public Animation getFadeOutAnimation() {
        if (mHideImmediately) {
            // For change transition, the hide transaction needs to be applied with sync transaction
            // (setupStartTransaction). So keep alpha 1 just to get the animation leash.
            final float alpha = mIsChangeTransition ? 1 : 0;
            return new AlphaAnimation(alpha /* fromAlpha */, alpha /* toAlpha */);
        }
        return super.getFadeOutAnimation();
    }

    @Override
    protected FadeAnimationAdapter createAdapter(LocalAnimationAdapter.AnimationSpec animationSpec,
            boolean show, WindowToken windowToken) {
        return new FadeAnimationAdapter(animationSpec,  windowToken.getSurfaceAnimationRunner(),
                show, windowToken) {
            @Override
            public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                    int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
                // The fade cycle is done when showing, so only need to store the leash when hiding.
                if (!show) {
                    mTargetWindowTokens.put(mToken, animationLeash);
                }
                super.startAnimation(animationLeash, t, type, finishCallback);
            }
        };
    }
}
