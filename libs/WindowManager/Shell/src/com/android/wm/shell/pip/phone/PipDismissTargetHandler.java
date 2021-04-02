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

package com.android.wm.shell.pip.phone;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.TransitionDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.wm.shell.R;
import com.android.wm.shell.animation.PhysicsAnimator;
import com.android.wm.shell.common.DismissCircleView;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;
import com.android.wm.shell.pip.PipUiEventLogger;

import kotlin.Unit;

/**
 * Handler of all Magnetized Object related code for PiP.
 */
public class PipDismissTargetHandler {

    /* The multiplier to apply scale the target size by when applying the magnetic field radius */
    private static final float MAGNETIC_FIELD_RADIUS_MULTIPLIER = 1.25f;

    /** Duration of the dismiss scrim fading in/out. */
    private static final int DISMISS_TRANSITION_DURATION_MS = 200;

    /**
     * MagnetizedObject wrapper for PIP. This allows the magnetic target library to locate and move
     * PIP.
     */
    private MagnetizedObject<Rect> mMagnetizedPip;

    /**
     * Container for the dismiss circle, so that it can be animated within the container via
     * translation rather than within the WindowManager via slow layout animations.
     */
    private ViewGroup mTargetViewContainer;

    /** Circle view used to render the dismiss target. */
    private DismissCircleView mTargetView;

    /**
     * MagneticTarget instance wrapping the target view and allowing us to set its magnetic radius.
     */
    private MagnetizedObject.MagneticTarget mMagneticTarget;

    /**
     * PhysicsAnimator instance for animating the dismiss target in/out.
     */
    private PhysicsAnimator<View> mMagneticTargetAnimator;

    /** Default configuration to use for springing the dismiss target in/out. */
    private final PhysicsAnimator.SpringConfig mTargetSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    // Allow dragging the PIP to a location to close it
    private boolean mEnableDismissDragToEdge;

    private int mTargetSize;
    private int mDismissAreaHeight;
    private float mMagneticFieldRadiusPercent = 1f;

    private final Context mContext;
    private final PipMotionHelper mMotionHelper;
    private final PipUiEventLogger mPipUiEventLogger;
    private final WindowManager mWindowManager;
    private final ShellExecutor mMainExecutor;

    public PipDismissTargetHandler(Context context, PipUiEventLogger pipUiEventLogger,
            PipMotionHelper motionHelper, ShellExecutor mainExecutor) {
        mContext = context;
        mPipUiEventLogger = pipUiEventLogger;
        mMotionHelper = motionHelper;
        mMainExecutor = mainExecutor;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public void init() {
        Resources res = mContext.getResources();
        mEnableDismissDragToEdge = res.getBoolean(R.bool.config_pipEnableDismissDragToEdge);
        mDismissAreaHeight = res.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height);

        mTargetView = new DismissCircleView(mContext);
        mTargetViewContainer = new FrameLayout(mContext);
        mTargetViewContainer.setBackgroundDrawable(
                mContext.getDrawable(R.drawable.floating_dismiss_gradient_transition));
        mTargetViewContainer.setClipChildren(false);
        mTargetViewContainer.addView(mTargetView);

        mMagnetizedPip = mMotionHelper.getMagnetizedPip();
        mMagneticTarget = mMagnetizedPip.addTarget(mTargetView, 0);
        updateMagneticTargetSize();

        mMagnetizedPip.setAnimateStuckToTarget(
                (target, velX, velY, flung, after) -> {
                    if (mEnableDismissDragToEdge) {
                        mMotionHelper.animateIntoDismissTarget(target, velX, velY, flung, after);
                    }
                    return Unit.INSTANCE;
                });
        mMagnetizedPip.setMagnetListener(new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                // Show the dismiss target, in case the initial touch event occurred within
                // the magnetic field radius.
                if (mEnableDismissDragToEdge) {
                    showDismissTargetMaybe();
                }
            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    float velX, float velY, boolean wasFlungOut) {
                if (wasFlungOut) {
                    mMotionHelper.flingToSnapTarget(velX, velY, null /* endAction */);
                    hideDismissTargetMaybe();
                } else {
                    mMotionHelper.setSpringingToTouch(true);
                }
            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                mMainExecutor.executeDelayed(() -> {
                    mMotionHelper.notifyDismissalPending();
                    mMotionHelper.animateDismiss();
                    hideDismissTargetMaybe();

                    mPipUiEventLogger.log(
                            PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_DRAG_TO_REMOVE);
                }, 0);
            }
        });

        mMagneticTargetAnimator = PhysicsAnimator.getInstance(mTargetView);
    }

    /**
     * Potentially start consuming future motion events if PiP is currently near the magnetized
     * object.
     */
    public boolean maybeConsumeMotionEvent(MotionEvent ev) {
        return mMagnetizedPip.maybeConsumeMotionEvent(ev);
    }

    /**
     * Update the magnet size.
     */
    public void updateMagneticTargetSize() {
        if (mTargetView == null) {
            return;
        }

        final Resources res = mContext.getResources();
        mTargetSize = res.getDimensionPixelSize(R.dimen.dismiss_circle_size);
        mDismissAreaHeight = res.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height);
        final FrameLayout.LayoutParams newParams =
                new FrameLayout.LayoutParams(mTargetSize, mTargetSize);
        newParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        newParams.bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.floating_dismiss_bottom_margin);
        mTargetView.setLayoutParams(newParams);

        // Set the magnetic field radius equal to the target size from the center of the target
        setMagneticFieldRadiusPercent(mMagneticFieldRadiusPercent);
    }

    /**
     * Increase or decrease the field radius of the magnet object, e.g. with larger percent,
     * PiP will magnetize to the field sooner.
     */
    public void setMagneticFieldRadiusPercent(float percent) {
        mMagneticFieldRadiusPercent = percent;
        mMagneticTarget.setMagneticFieldRadiusPx((int) (mMagneticFieldRadiusPercent * mTargetSize
                        * MAGNETIC_FIELD_RADIUS_MULTIPLIER));
    }

    /** Adds the magnetic target view to the WindowManager so it's ready to be animated in. */
    public void createOrUpdateDismissTarget() {
        if (!mTargetViewContainer.isAttachedToWindow()) {
            mMagneticTargetAnimator.cancel();

            mTargetViewContainer.setVisibility(View.INVISIBLE);

            try {
                mWindowManager.addView(mTargetViewContainer, getDismissTargetLayoutParams());
            } catch (IllegalStateException e) {
                // This shouldn't happen, but if the target is already added, just update its layout
                // params.
                mWindowManager.updateViewLayout(
                        mTargetViewContainer, getDismissTargetLayoutParams());
            }
        } else {
            mWindowManager.updateViewLayout(mTargetViewContainer, getDismissTargetLayoutParams());
        }
    }

    /** Returns layout params for the dismiss target, using the latest display metrics. */
    private WindowManager.LayoutParams getDismissTargetLayoutParams() {
        final Point windowSize = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(windowSize);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                mDismissAreaHeight,
                0, windowSize.y - mDismissAreaHeight,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        lp.setTitle("pip-dismiss-overlay");
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0 /* types */);

        return lp;
    }

    /** Makes the dismiss target visible and animates it in, if it isn't already visible. */
    public void showDismissTargetMaybe() {
        if (!mEnableDismissDragToEdge) {
            return;
        }

        createOrUpdateDismissTarget();

        if (mTargetViewContainer.getVisibility() != View.VISIBLE) {

            mTargetView.setTranslationY(mTargetViewContainer.getHeight());
            mTargetViewContainer.setVisibility(View.VISIBLE);

            // Cancel in case we were in the middle of animating it out.
            mMagneticTargetAnimator.cancel();
            mMagneticTargetAnimator
                    .spring(DynamicAnimation.TRANSLATION_Y, 0f, mTargetSpringConfig)
                    .start();

            ((TransitionDrawable) mTargetViewContainer.getBackground()).startTransition(
                    DISMISS_TRANSITION_DURATION_MS);
        }
    }

    /** Animates the magnetic dismiss target out and then sets it to GONE. */
    public void hideDismissTargetMaybe() {
        if (!mEnableDismissDragToEdge) {
            return;
        }

        mMagneticTargetAnimator
                .spring(DynamicAnimation.TRANSLATION_Y,
                        mTargetViewContainer.getHeight(),
                        mTargetSpringConfig)
                .withEndActions(() -> mTargetViewContainer.setVisibility(View.GONE))
                .start();

        ((TransitionDrawable) mTargetViewContainer.getBackground()).reverseTransition(
                DISMISS_TRANSITION_DURATION_MS);
    }

    /**
     * Removes the dismiss target and cancels any pending callbacks to show it.
     */
    public void cleanUpDismissTarget() {
        if (mTargetViewContainer.isAttachedToWindow()) {
            mWindowManager.removeViewImmediate(mTargetViewContainer);
        }
    }
}
