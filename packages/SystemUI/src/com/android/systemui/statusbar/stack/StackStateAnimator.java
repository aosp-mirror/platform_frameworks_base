/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.stack;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

/**
 * An stack state animator which handles animations to new StackScrollStates
 */
public class StackStateAnimator {

    public static final int ANIMATION_DURATION_STANDARD = 360;
    public static final int ANIMATION_DURATION_DIMMED_ACTIVATED = 220;

    private static final int TAG_ANIMATOR_TRANSLATION_Y = R.id.translation_y_animator_tag;
    private static final int TAG_ANIMATOR_TRANSLATION_Z = R.id.translation_z_animator_tag;
    private static final int TAG_ANIMATOR_SCALE = R.id.scale_animator_tag;
    private static final int TAG_ANIMATOR_ALPHA = R.id.alpha_animator_tag;
    private static final int TAG_ANIMATOR_HEIGHT = R.id.height_animator_tag;
    private static final int TAG_ANIMATOR_TOP_INSET = R.id.top_inset_animator_tag;
    private static final int TAG_END_TRANSLATION_Y = R.id.translation_y_animator_end_value_tag;
    private static final int TAG_END_TRANSLATION_Z = R.id.translation_z_animator_end_value_tag;
    private static final int TAG_END_SCALE = R.id.scale_animator_end_value_tag;
    private static final int TAG_END_ALPHA = R.id.alpha_animator_end_value_tag;
    private static final int TAG_END_HEIGHT = R.id.height_animator_end_value_tag;
    private static final int TAG_END_TOP_INSET = R.id.top_inset_animator_end_value_tag;

    private final Interpolator mFastOutSlowInInterpolator;
    public NotificationStackScrollLayout mHostLayout;
    private ArrayList<NotificationStackScrollLayout.AnimationEvent> mHandledEvents =
            new ArrayList<>();
    private ArrayList<NotificationStackScrollLayout.AnimationEvent> mNewEvents =
            new ArrayList<>();
    private Set<Animator> mAnimatorSet = new HashSet<Animator>();
    private Stack<AnimatorListenerAdapter> mAnimationListenerPool
            = new Stack<AnimatorListenerAdapter>();
    private AnimationFilter mAnimationFilter = new AnimationFilter();
    private long mCurrentLength;

    public StackStateAnimator(NotificationStackScrollLayout hostLayout) {
        mHostLayout = hostLayout;
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(hostLayout.getContext(),
                android.R.interpolator.fast_out_slow_in);
    }

    public boolean isRunning() {
        return !mAnimatorSet.isEmpty();
    }

    public void startAnimationForEvents(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> mAnimationEvents,
            StackScrollState finalState) {

        processAnimationEvents(mAnimationEvents, finalState);

        int childCount = mHostLayout.getChildCount();
        mAnimationFilter.applyCombination(mNewEvents);
        mCurrentLength = NotificationStackScrollLayout.AnimationEvent.combineLength(mNewEvents);
        for (int i = 0; i < childCount; i++) {
            final ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);
            StackScrollState.ViewState viewState = finalState.getViewStateForView(child);
            if (viewState == null) {
                continue;
            }

            startAnimations(child, viewState);

            child.setClipBounds(null);
        }
        if (!isRunning()) {
            // no child has preformed any animation, lets finish
            onAnimationFinished();
        }
    }

    /**
     * Start an animation to the given viewState
     */
    private void startAnimations(final ExpandableView child, StackScrollState.ViewState viewState) {
        int childVisibility = child.getVisibility();
        boolean wasVisible = childVisibility == View.VISIBLE;
        final float alpha = viewState.alpha;
        if (!wasVisible && alpha != 0 && !viewState.gone) {
            child.setVisibility(View.VISIBLE);
        }
        // start translationY animation
        if (child.getTranslationY() != viewState.yTranslation) {
            startYTranslationAnimation(child, viewState);
        }
        // start translationZ animation
        if (child.getTranslationZ() != viewState.zTranslation) {
            startZTranslationAnimation(child, viewState);
        }
        // start scale animation
        if (child.getScaleX() != viewState.scale) {
            startScaleAnimation(child, viewState);
        }
        // start alpha animation
        if (alpha != child.getAlpha()) {
            startAlphaAnimation(child, viewState);
        }
        // start height animation
        if (viewState.height != child.getActualHeight()) {
            startHeightAnimation(child, viewState);
        }
        // start dimmed animation
        child.setDimmed(viewState.dimmed, mAnimationFilter.animateDimmed);
    }

    private void startHeightAnimation(final ExpandableView child,
            StackScrollState.ViewState viewState) {
        Integer previousEndValue = getChildTag(child, TAG_END_HEIGHT);
        if (previousEndValue != null && previousEndValue == viewState.height) {
            return;
        }
        ValueAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_HEIGHT);
        long newDuration = cancelAnimatorAndGetNewDuration(previousAnimator,
                mAnimationFilter.animateHeight);
        if (newDuration <= 0) {
            // no new animation needed, let's just apply the value
            child.setActualHeight(viewState.height, false /* notifyListeners */);
            if (previousAnimator != null && !isRunning()) {
                onAnimationFinished();
            }
            return;
        }

        ValueAnimator animator = ValueAnimator.ofInt(child.getActualHeight(), viewState.height);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                child.setActualHeight((int) animation.getAnimatedValue(),
                        false /* notifyListeners */);
            }
        });
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.setDuration(newDuration);
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_HEIGHT, null);
                child.setTag(TAG_END_HEIGHT, null);
            }
        });
        startInstantly(animator);
        child.setTag(TAG_ANIMATOR_HEIGHT, animator);
        child.setTag(TAG_END_HEIGHT, viewState.height);
    }

    private void startAlphaAnimation(final ExpandableView child,
            final StackScrollState.ViewState viewState) {
        final float endAlpha = viewState.alpha;
        Float previousEndValue = getChildTag(child,TAG_END_ALPHA);
        if (previousEndValue != null && previousEndValue == endAlpha) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_ALPHA);
        long newDuration = cancelAnimatorAndGetNewDuration(previousAnimator,
                mAnimationFilter.animateAlpha);
        if (newDuration <= 0) {
            // no new animation needed, let's just apply the value
            child.setAlpha(endAlpha);
            if (endAlpha == 0) {
                child.setVisibility(View.INVISIBLE);
            }
            if (previousAnimator != null && !isRunning()) {
                onAnimationFinished();
            }
            return;
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.ALPHA,
                child.getAlpha(), endAlpha);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        // Handle layer type
        final int currentLayerType = child.getLayerType();
        child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animator.addListener(new AnimatorListenerAdapter() {
            public boolean mWasCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                child.setLayerType(currentLayerType, null);
                if (endAlpha == 0 && !mWasCancelled) {
                    child.setVisibility(View.INVISIBLE);
                }
                child.setTag(TAG_ANIMATOR_ALPHA, null);
                child.setTag(TAG_END_ALPHA, null);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mWasCancelled = true;
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mWasCancelled = false;
            }
        });
        animator.setDuration(newDuration);
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {

            }
        });
        startInstantly(animator);
        child.setTag(TAG_ANIMATOR_ALPHA, animator);
        child.setTag(TAG_END_ALPHA, endAlpha);
    }

    private void startZTranslationAnimation(final ExpandableView child,
            final StackScrollState.ViewState viewState) {
        Float previousEndValue = getChildTag(child,TAG_END_TRANSLATION_Z);
        if (previousEndValue != null && previousEndValue == viewState.zTranslation) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_Z);
        long newDuration = cancelAnimatorAndGetNewDuration(previousAnimator,
                mAnimationFilter.animateZ);
        if (newDuration <= 0) {
            // no new animation needed, let's just apply the value
            child.setTranslationZ(viewState.zTranslation);

            if (previousAnimator != null && !isRunning()) {
                onAnimationFinished();
            }
            return;
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_Z,
                child.getTranslationZ(), viewState.zTranslation);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.setDuration(newDuration);
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_TRANSLATION_Z, null);
                child.setTag(TAG_END_TRANSLATION_Z, null);
            }
        });
        startInstantly(animator);
        child.setTag(TAG_ANIMATOR_TRANSLATION_Z, animator);
        child.setTag(TAG_END_TRANSLATION_Z, viewState.zTranslation);
    }

    private void startYTranslationAnimation(final ExpandableView child,
            StackScrollState.ViewState viewState) {
        Float previousEndValue = getChildTag(child,TAG_END_TRANSLATION_Y);
        if (previousEndValue != null && previousEndValue == viewState.yTranslation) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_Y);
        long newDuration = cancelAnimatorAndGetNewDuration(previousAnimator,
                mAnimationFilter.animateY);
        if (newDuration <= 0) {
            // no new animation needed, let's just apply the value
            child.setTranslationY(viewState.yTranslation);
            if (previousAnimator != null && !isRunning()) {
                onAnimationFinished();
            }
            return;
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y,
                child.getTranslationY(), viewState.yTranslation);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.setDuration(newDuration);
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_TRANSLATION_Y, null);
                child.setTag(TAG_END_TRANSLATION_Y, null);
            }
        });
        startInstantly(animator);
        child.setTag(TAG_ANIMATOR_TRANSLATION_Y, animator);
        child.setTag(TAG_END_TRANSLATION_Y, viewState.yTranslation);
    }

    private void startScaleAnimation(final ExpandableView child,
            StackScrollState.ViewState viewState) {
        Float previousEndValue = getChildTag(child, TAG_END_SCALE);
        if (previousEndValue != null && previousEndValue == viewState.scale) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_SCALE);
        long newDuration = cancelAnimatorAndGetNewDuration(previousAnimator,
                mAnimationFilter.animateScale);
        if (newDuration <= 0) {
            // no new animation needed, let's just apply the value
            child.setScaleX(viewState.scale);
            child.setScaleY(viewState.scale);
            if (previousAnimator != null && !isRunning()) {
                onAnimationFinished();
            }
            return;
        }

        PropertyValuesHolder holderX =
                PropertyValuesHolder.ofFloat(View.SCALE_X, child.getScaleX(), viewState.scale);
        PropertyValuesHolder holderY =
                PropertyValuesHolder.ofFloat(View.SCALE_Y, child.getScaleY(), viewState.scale);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(child, holderX, holderY);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.setDuration(newDuration);
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_SCALE, null);
                child.setTag(TAG_END_SCALE, null);
            }
        });
        startInstantly(animator);
        child.setTag(TAG_ANIMATOR_SCALE, animator);
        child.setTag(TAG_END_SCALE, viewState.scale);
    }

    /**
     * Start an animator instantly instead of waiting on the next synchronization frame
     */
    private void startInstantly(ValueAnimator animator) {
        animator.start();
        animator.setCurrentPlayTime(0);
    }

    /**
     * @return an adapter which ensures that onAnimationFinished is called once no animation is
     *         running anymore
     */
    private AnimatorListenerAdapter getGlobalAnimationFinishedListener() {
        if (!mAnimationListenerPool.empty()) {
            return mAnimationListenerPool.pop();
        }

        // We need to create a new one, no reusable ones found
        return new AnimatorListenerAdapter() {
            private boolean mWasCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimatorSet.remove(animation);
                if (mAnimatorSet.isEmpty() && !mWasCancelled) {
                    onAnimationFinished();
                }
                mAnimationListenerPool.push(this);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mWasCancelled = true;
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mAnimatorSet.add(animation);
                mWasCancelled = false;
            }
        };
    }

    private <T> T getChildTag(View child, int tag) {
        return (T) child.getTag(tag);
    }

    /**
     * Cancel the previous animator and get the duration of the new animation.
     *
     * @param previousAnimator the animator which was running before
     * @param newAnimationNeeded indicating whether a new animation should be started for this
     *                           property
     * @return the new duration
     */
    private long cancelAnimatorAndGetNewDuration(ValueAnimator previousAnimator,
            boolean newAnimationNeeded) {
        long newDuration = mCurrentLength;
        if (previousAnimator != null) {
            if (!newAnimationNeeded) {
                // This is only an update, no new event came in. lets just take the remaining
                // duration as the new duration
                newDuration = previousAnimator.getDuration()
                        - previousAnimator.getCurrentPlayTime();
            }
            previousAnimator.cancel();
        } else if (!newAnimationNeeded){
            newDuration = 0;
        }
        return newDuration;
    }

    private void onAnimationFinished() {
        mHandledEvents.clear();
        mNewEvents.clear();
        mHostLayout.onChildAnimationFinished();
    }

    /**
     * Process the animationEvents for a new animation
     *
     * @param animationEvents the animation events for the animation to perform
     * @param finalState the final state to animate to
     */
    private void processAnimationEvents(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> animationEvents,
            StackScrollState finalState) {
        mNewEvents.clear();
        for (NotificationStackScrollLayout.AnimationEvent event: animationEvents) {
            View changingView = event.changingView;
            if (!mHandledEvents.contains(event)) {
                if (event.animationType == NotificationStackScrollLayout.AnimationEvent
                        .ANIMATION_TYPE_ADD) {

                    // This item is added, initialize it's properties.
                    StackScrollState.ViewState viewState = finalState
                            .getViewStateForView(changingView);
                    if (viewState == null) {
                        // The position for this child was never generated, let's continue.
                        continue;
                    }
                    changingView.setAlpha(0);
                    changingView.setTranslationY(viewState.yTranslation);
                    changingView.setTranslationZ(viewState.zTranslation);
                }
                mHandledEvents.add(event);
                mNewEvents.add(event);
            }
        }
    }
}
