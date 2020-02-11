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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

/** Dismiss view that contains a scrim gradient, as well as a dismiss icon, text, and circle. */
public class BubbleDismissView extends FrameLayout {
    /** Duration for animations involving the dismiss target text/icon. */
    private static final int DISMISS_TARGET_ANIMATION_BASE_DURATION = 150;
    private static final float SCALE_FOR_POP = 1.2f;
    private static final float SCALE_FOR_DISMISS = 0.9f;

    private LinearLayout mDismissTarget;
    private ImageView mDismissIcon;
    private View mDismissCircle;

    private SpringAnimation mDismissTargetAlphaSpring;
    private SpringAnimation mDismissTargetVerticalSpring;

    public BubbleDismissView(Context context) {
        super(context);
        setVisibility(GONE);

        LayoutInflater.from(context).inflate(R.layout.bubble_dismiss_target, this, true);
        mDismissTarget = findViewById(R.id.bubble_dismiss_icon_container);
        mDismissIcon = findViewById(R.id.bubble_dismiss_close_icon);
        mDismissCircle = findViewById(R.id.bubble_dismiss_circle);

        // Set up the basic target area animations. These are very simple animations that don't need
        // fancy interpolators.
        final AccelerateDecelerateInterpolator interpolator =
                new AccelerateDecelerateInterpolator();
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
                setVisibility(INVISIBLE);
            }
        });
    }

    /** Springs in the dismiss target. */
    void springIn() {
        setVisibility(View.VISIBLE);

        // Fade in the dismiss target icon.
        mDismissIcon.animate()
                .setDuration(50)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f);
        mDismissTarget.setAlpha(0f);
        mDismissTargetAlphaSpring.animateToFinalPosition(1f);

        // Spring up the dismiss target.
        mDismissTarget.setTranslationY(mDismissTarget.getHeight() / 2f);
        mDismissTargetVerticalSpring.animateToFinalPosition(0);

        mDismissCircle.setAlpha(0f);
        mDismissCircle.setScaleX(SCALE_FOR_POP);
        mDismissCircle.setScaleY(SCALE_FOR_POP);

        // Fade in circle and reduce size.
        mDismissCircle.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f);
    }

    /** Springs out the dismiss target. */
    void springOut() {
        // Fade out the target icon.
        mDismissIcon.animate()
                .setDuration(50)
                .scaleX(SCALE_FOR_DISMISS)
                .scaleY(SCALE_FOR_DISMISS)
                .alpha(0f);

        // Fade out the target.
        mDismissTargetAlphaSpring.animateToFinalPosition(0f);

        // Spring the target down a bit.
        mDismissTargetVerticalSpring.animateToFinalPosition(mDismissTarget.getHeight() / 2f);

        // Pop out the circle.
        mDismissCircle.animate()
                .scaleX(SCALE_FOR_DISMISS)
                .scaleY(SCALE_FOR_DISMISS)
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
