/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.bubbles;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

/** Dismiss view that contains a scrim gradient, as well as a dismiss icon, text, and circle. */
public class BubbleDismissView extends FrameLayout {
    /** Duration for animations involving the dismiss target text/icon/gradient. */
    private static final int DISMISS_TARGET_ANIMATION_BASE_DURATION = 150;

    private View mDismissGradient;

    private LinearLayout mDismissTarget;
    private ImageView mDismissIcon;
    private TextView mDismissText;
    private View mDismissCircle;

    private SpringAnimation mDismissTargetAlphaSpring;
    private SpringAnimation mDismissTargetVerticalSpring;

    public BubbleDismissView(Context context) {
        super(context);
        setVisibility(GONE);

        mDismissGradient = new FrameLayout(mContext);

        FrameLayout.LayoutParams gradientParams =
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        gradientParams.gravity = Gravity.BOTTOM;
        mDismissGradient.setLayoutParams(gradientParams);

        Drawable gradient = mContext.getResources().getDrawable(R.drawable.pip_dismiss_scrim);
        gradient.setAlpha((int) (255 * 0.85f));
        mDismissGradient.setBackground(gradient);

        mDismissGradient.setVisibility(GONE);
        addView(mDismissGradient);

        LayoutInflater.from(context).inflate(R.layout.bubble_dismiss_target, this, true);
        mDismissTarget = findViewById(R.id.bubble_dismiss_icon_container);
        mDismissIcon = findViewById(R.id.bubble_dismiss_close_icon);
        mDismissText = findViewById(R.id.bubble_dismiss_text);
        mDismissCircle = findViewById(R.id.bubble_dismiss_circle);

        // Set up the basic target area animations. These are very simple animations that don't need
        // fancy interpolators.
        final AccelerateDecelerateInterpolator interpolator =
                new AccelerateDecelerateInterpolator();
        mDismissGradient.animate()
                .setDuration(DISMISS_TARGET_ANIMATION_BASE_DURATION)
                .setInterpolator(interpolator);
        mDismissText.animate()
                .setDuration(DISMISS_TARGET_ANIMATION_BASE_DURATION)
                .setInterpolator(interpolator);
        mDismissIcon.animate()
                .setDuration(DISMISS_TARGET_ANIMATION_BASE_DURATION)
                .setInterpolator(interpolator);
        mDismissCircle.animate()
                .setDuration(DISMISS_TARGET_ANIMATION_BASE_DURATION / 2)
                .setInterpolator(interpolator);

        mDismissTargetAlphaSpring =
                new SpringAnimation(mDismissTarget, DynamicAnimation.ALPHA)
                        .setSpring(new SpringForce()
                                .setStiffness(SpringForce.STIFFNESS_LOW)
                                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        mDismissTargetVerticalSpring =
                new SpringAnimation(mDismissTarget, DynamicAnimation.TRANSLATION_Y)
                        .setSpring(new SpringForce()
                                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));

        mDismissTargetAlphaSpring.addEndListener((anim, canceled, alpha, velocity) -> {
            // Since DynamicAnimations end when they're 'nearly' done, we can't rely on alpha being
            // exactly zero when this listener is triggered. However, if it's less than 50% we can
            // safely assume it was animating out rather than in.
            if (alpha < 0.5f) {
                // If the alpha spring was animating the view out, set it to GONE when it's done.
                setVisibility(GONE);
            }
        });
    }

    /** Springs in the dismiss target and fades in the gradient. */
    void springIn() {
        setVisibility(View.VISIBLE);

        // Fade in the dismiss target (icon + text).
        mDismissTarget.setAlpha(0f);
        mDismissTargetAlphaSpring.animateToFinalPosition(1f);

        // Spring up the dismiss target (icon + text).
        mDismissTarget.setTranslationY(mDismissTarget.getHeight() / 2f);
        mDismissTargetVerticalSpring.animateToFinalPosition(0);

        // Fade in the gradient.
        mDismissGradient.setVisibility(VISIBLE);
        mDismissGradient.animate().alpha(1f);

        // Make sure the dismiss elements are in the separated position (in case we hid the target
        // while they were condensed to cover the bubbles being in the target).
        mDismissIcon.setAlpha(1f);
        mDismissIcon.setScaleX(1f);
        mDismissIcon.setScaleY(1f);
        mDismissIcon.setTranslationX(0f);
        mDismissText.setAlpha(1f);
        mDismissText.setTranslationX(0f);
    }

    /** Springs out the dismiss target and fades out the gradient. */
    void springOut() {
        // Fade out the target.
        mDismissTargetAlphaSpring.animateToFinalPosition(0f);

        // Spring the target down a bit.
        mDismissTargetVerticalSpring.animateToFinalPosition(mDismissTarget.getHeight() / 2f);

        // Fade out the gradient and then set it to GONE so it's not in the SBV hierarchy.
        mDismissGradient.animate().alpha(0f).withEndAction(
                () -> mDismissGradient.setVisibility(GONE));

        // Pop out the dismiss circle.
        mDismissCircle.animate().alpha(0f).scaleX(1.2f).scaleY(1.2f);
    }

    /**
     * Encircles the center of the dismiss target, pulling the X towards the center and hiding the
     * text.
     */
    void animateEncircleCenterWithX(boolean encircle) {
        // Pull the text towards the center if we're encircling (it'll be faded out, leaving only
        // the X icon over the bubbles), or back to normal if we're un-encircling.
        final float textTranslation = encircle
                ? -mDismissIcon.getWidth() / 4f
                : 0f;

        // Center the icon if we're encircling, or put it back to normal if not.
        final float iconTranslation = encircle
                ? mDismissTarget.getWidth() / 2f
                - mDismissIcon.getWidth() / 2f
                - mDismissIcon.getLeft()
                : 0f;

        // Fade in/out the text and translate it.
        mDismissText.animate()
                .alpha(encircle ? 0f : 1f)
                .translationX(textTranslation);

        mDismissIcon.animate()
                .setDuration(150)
                .translationX(iconTranslation);

        // Fade out the gradient if we're encircling (the bubbles will 'absorb' it by darkening
        // themselves).
        mDismissGradient.animate()
                .alpha(encircle ? 0f : 1f);

        // Prepare the circle to be 'dropped in'.
        if (encircle) {
            mDismissCircle.setAlpha(0f);
            mDismissCircle.setScaleX(1.2f);
            mDismissCircle.setScaleY(1.2f);
        }

        // Drop in the circle, or pull it back up.
        mDismissCircle.animate()
                .alpha(encircle ? 1f : 0f)
                .scaleX(encircle ? 1f : 0f)
                .scaleY(encircle ? 1f : 0f);
    }

    /** Animates the circle and the centered icon out. */
    void animateEncirclingCircleDisappearance() {
        // Pop out the dismiss icon and circle.
        mDismissIcon.animate()
                .setDuration(50)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0f);
        mDismissCircle.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0f);
    }

    /** Returns the Y value of the center of the dismiss target. */
    float getDismissTargetCenterY() {
        return getTop() + mDismissTarget.getTop() + mDismissTarget.getHeight() / 2f;
    }

    /** Returns the dismiss target, which contains the text/icon and any added padding. */
    View getDismissTarget() {
        return mDismissTarget;
    }
}
