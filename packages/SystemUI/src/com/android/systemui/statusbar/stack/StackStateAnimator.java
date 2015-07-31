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
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.SpeedBumpView;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Stack;

/**
 * An stack state animator which handles animations to new StackScrollStates
 */
public class StackStateAnimator {

    public static final int ANIMATION_DURATION_STANDARD = 360;
    public static final int ANIMATION_DURATION_GO_TO_FULL_SHADE = 448;
    public static final int ANIMATION_DURATION_APPEAR_DISAPPEAR = 464;
    public static final int ANIMATION_DURATION_EXPAND_CLICKED = 360;
    public static final int ANIMATION_DURATION_DIMMED_ACTIVATED = 220;
    public static final int ANIMATION_DURATION_HEADS_UP_APPEAR = 650;
    public static final int ANIMATION_DURATION_HEADS_UP_DISAPPEAR = 230;
    public static final int ANIMATION_DELAY_PER_ELEMENT_INTERRUPTING = 80;
    public static final int ANIMATION_DELAY_PER_ELEMENT_EXPAND_CHILDREN = 54;
    public static final int ANIMATION_DELAY_PER_ELEMENT_MANUAL = 32;
    public static final int ANIMATION_DELAY_PER_ELEMENT_GO_TO_FULL_SHADE = 48;
    public static final int ANIMATION_DELAY_PER_ELEMENT_DARK = 24;
    public static final int DELAY_EFFECT_MAX_INDEX_DIFFERENCE = 2;
    public static final int DELAY_EFFECT_MAX_INDEX_DIFFERENCE_CHILDREN = 3;

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
    private static final int TAG_START_TRANSLATION_Y = R.id.translation_y_animator_start_value_tag;
    private static final int TAG_START_TRANSLATION_Z = R.id.translation_z_animator_start_value_tag;
    private static final int TAG_START_SCALE = R.id.scale_animator_start_value_tag;
    private static final int TAG_START_ALPHA = R.id.alpha_animator_start_value_tag;
    private static final int TAG_START_HEIGHT = R.id.height_animator_start_value_tag;
    private static final int TAG_START_TOP_INSET = R.id.top_inset_animator_start_value_tag;

    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mHeadsUpAppearInterpolator;
    private final int mGoToFullShadeAppearingTranslation;
    private final StackViewState mTmpState = new StackViewState();
    public NotificationStackScrollLayout mHostLayout;
    private ArrayList<NotificationStackScrollLayout.AnimationEvent> mNewEvents =
            new ArrayList<>();
    private ArrayList<View> mNewAddChildren = new ArrayList<>();
    private HashSet<View> mHeadsUpAppearChildren = new HashSet<>();
    private HashSet<View> mHeadsUpDisappearChildren = new HashSet<>();
    private HashSet<Animator> mAnimatorSet = new HashSet<>();
    private Stack<AnimatorListenerAdapter> mAnimationListenerPool = new Stack<>();
    private AnimationFilter mAnimationFilter = new AnimationFilter();
    private long mCurrentLength;
    private long mCurrentAdditionalDelay;

    /** The current index for the last child which was not added in this event set. */
    private int mCurrentLastNotAddedIndex;
    private ValueAnimator mTopOverScrollAnimator;
    private ValueAnimator mBottomOverScrollAnimator;
    private ExpandableNotificationRow mChildExpandingView;
    private int mHeadsUpAppearHeightBottom;
    private boolean mShadeExpanded;
    private ArrayList<View> mChildrenToClearFromOverlay = new ArrayList<>();

    public StackStateAnimator(NotificationStackScrollLayout hostLayout) {
        mHostLayout = hostLayout;
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(hostLayout.getContext(),
                android.R.interpolator.fast_out_slow_in);
        mGoToFullShadeAppearingTranslation =
                hostLayout.getContext().getResources().getDimensionPixelSize(
                        R.dimen.go_to_full_shade_appearing_translation);
        mHeadsUpAppearInterpolator = new HeadsUpAppearInterpolator();
    }

    public boolean isRunning() {
        return !mAnimatorSet.isEmpty();
    }

    public void startAnimationForEvents(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> mAnimationEvents,
            StackScrollState finalState, long additionalDelay) {

        processAnimationEvents(mAnimationEvents, finalState);

        int childCount = mHostLayout.getChildCount();
        mAnimationFilter.applyCombination(mNewEvents);
        mCurrentAdditionalDelay = additionalDelay;
        mCurrentLength = NotificationStackScrollLayout.AnimationEvent.combineLength(mNewEvents);
        mCurrentLastNotAddedIndex = findLastNotAddedIndex(finalState);
        for (int i = 0; i < childCount; i++) {
            final ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);

            StackViewState viewState = finalState.getViewStateForView(child);
            if (viewState == null || child.getVisibility() == View.GONE
                    || applyWithoutAnimation(child, viewState, finalState)) {
                continue;
            }

            child.setClipTopOptimization(0);
            startStackAnimations(child, viewState, finalState, i, -1 /* fixedDelay */);
        }
        if (!isRunning()) {
            // no child has preformed any animation, lets finish
            onAnimationFinished();
        }
        mHeadsUpAppearChildren.clear();
        mHeadsUpDisappearChildren.clear();
        mNewEvents.clear();
        mNewAddChildren.clear();
        mChildExpandingView = null;
    }

    /**
     * Determines if a view should not perform an animation and applies it directly.
     *
     * @return true if no animation should be performed
     */
    private boolean applyWithoutAnimation(ExpandableView child, StackViewState viewState,
            StackScrollState finalState) {
        if (mShadeExpanded) {
            return false;
        }
        if (getChildTag(child, TAG_ANIMATOR_TRANSLATION_Y) != null) {
            // A Y translation animation is running
            return false;
        }
        if (mHeadsUpDisappearChildren.contains(child) || mHeadsUpAppearChildren.contains(child)) {
            // This is a heads up animation
            return false;
        }
        if (NotificationStackScrollLayout.isPinnedHeadsUp(child)) {
            // This is another headsUp which might move. Let's animate!
            return false;
        }
        finalState.applyState(child, viewState);
        return true;
    }

    private int findLastNotAddedIndex(StackScrollState finalState) {
        int childCount = mHostLayout.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            final ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);

            StackViewState viewState = finalState.getViewStateForView(child);
            if (viewState == null || child.getVisibility() == View.GONE) {
                continue;
            }
            if (!mNewAddChildren.contains(child)) {
                return viewState.notGoneIndex;
            }
        }
        return -1;
    }


    /**
     * Start an animation to the given  {@link StackViewState}.
     *
     * @param child the child to start the animation on
     * @param viewState the {@link StackViewState} of the view to animate to
     * @param finalState the final state after the animation
     * @param i the index of the view; only relevant if the view is the speed bump and is
     *          ignored otherwise
     * @param fixedDelay a fixed delay if desired or -1 if the delay should be calculated
     */
    public void startStackAnimations(final ExpandableView child, StackViewState viewState,
            StackScrollState finalState, int i, long fixedDelay) {
        final float alpha = viewState.alpha;
        boolean wasAdded = mNewAddChildren.contains(child);
        long duration = mCurrentLength;
        if (wasAdded && mAnimationFilter.hasGoToFullShadeEvent) {
            child.setTranslationY(child.getTranslationY() + mGoToFullShadeAppearingTranslation);
            float longerDurationFactor = viewState.notGoneIndex - mCurrentLastNotAddedIndex;
            longerDurationFactor = (float) Math.pow(longerDurationFactor, 0.7f);
            duration = ANIMATION_DURATION_APPEAR_DISAPPEAR + 50 +
                    (long) (100 * longerDurationFactor);
        }
        boolean yTranslationChanging = child.getTranslationY() != viewState.yTranslation;
        boolean zTranslationChanging = child.getTranslationZ() != viewState.zTranslation;
        boolean scaleChanging = child.getScaleX() != viewState.scale;
        boolean alphaChanging = alpha != child.getAlpha();
        boolean heightChanging = viewState.height != child.getActualHeight();
        boolean darkChanging = viewState.dark != child.isDark();
        boolean topInsetChanging = viewState.clipTopAmount != child.getClipTopAmount();
        boolean hasDelays = mAnimationFilter.hasDelays;
        boolean isDelayRelevant = yTranslationChanging || zTranslationChanging || scaleChanging ||
                alphaChanging || heightChanging || topInsetChanging || darkChanging;
        long delay = 0;
        if (fixedDelay != -1) {
            delay = fixedDelay;
        } else if (hasDelays && isDelayRelevant || wasAdded) {
            delay = mCurrentAdditionalDelay + calculateChildAnimationDelay(viewState, finalState);
        }

        startViewAnimations(child, viewState, delay, duration);

        // start height animation
        if (heightChanging && child.getActualHeight() != 0) {
            startHeightAnimation(child, viewState, duration, delay);
        }

        // start top inset animation
        if (topInsetChanging) {
            startInsetAnimation(child, viewState, duration, delay);
        }

        // start dimmed animation
        child.setDimmed(viewState.dimmed, mAnimationFilter.animateDimmed);

        // apply speed bump state
        child.setBelowSpeedBump(viewState.belowSpeedBump);

        // start hiding sensitive animation
        child.setHideSensitive(viewState.hideSensitive, mAnimationFilter.animateHideSensitive,
                delay, duration);

        // start dark animation
        child.setDark(viewState.dark, mAnimationFilter.animateDark, delay);

        if (wasAdded) {
            child.performAddAnimation(delay, mCurrentLength);
        }
        if (child instanceof SpeedBumpView) {
            finalState.performSpeedBumpAnimation(i, (SpeedBumpView) child, viewState,
                    delay + duration);
        } else if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            row.startChildAnimation(finalState, this, child == mChildExpandingView, delay,
                    duration);
        }
    }

    /**
     * Start an animation to a new {@link ViewState}.
     *
     * @param child the child to start the animation on
     * @param viewState the  {@link StackViewState} of the view to animate to
     * @param delay a fixed delay
     * @param duration the duration of the animation
     */
    public void startViewAnimations(View child, ViewState viewState, long delay, long duration) {
        boolean wasVisible = child.getVisibility() == View.VISIBLE;
        final float alpha = viewState.alpha;
        if (!wasVisible && alpha != 0 && !viewState.gone) {
            child.setVisibility(View.VISIBLE);
        }
        boolean yTranslationChanging = child.getTranslationY() != viewState.yTranslation;
        boolean zTranslationChanging = child.getTranslationZ() != viewState.zTranslation;
        boolean scaleChanging = child.getScaleX() != viewState.scale;
        float childAlpha = child.getVisibility() == View.INVISIBLE ? 0.0f : child.getAlpha();
        boolean alphaChanging = viewState.alpha != childAlpha;
        if (child instanceof ExpandableView) {
            // We don't want views to change visibility when they are animating to GONE
            alphaChanging &= !((ExpandableView) child).willBeGone();
        }

        // start translationY animation
        if (yTranslationChanging) {
            startYTranslationAnimation(child, viewState, duration, delay);
        }

        // start translationZ animation
        if (zTranslationChanging) {
            startZTranslationAnimation(child, viewState, duration, delay);
        }

        // start scale animation
        if (scaleChanging) {
            startScaleAnimation(child, viewState, duration);
        }

        // start alpha animation
        if (alphaChanging && child.getTranslationX() == 0) {
            startAlphaAnimation(child, viewState, duration, delay);
        }
    }

    private long calculateChildAnimationDelay(StackViewState viewState,
            StackScrollState finalState) {
        if (mAnimationFilter.hasDarkEvent) {
            return calculateDelayDark(viewState);
        }
        if (mAnimationFilter.hasGoToFullShadeEvent) {
            return calculateDelayGoToFullShade(viewState);
        }
        long minDelay = 0;
        for (NotificationStackScrollLayout.AnimationEvent event : mNewEvents) {
            long delayPerElement = ANIMATION_DELAY_PER_ELEMENT_INTERRUPTING;
            switch (event.animationType) {
                case NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_ADD: {
                    int ownIndex = viewState.notGoneIndex;
                    int changingIndex = finalState
                            .getViewStateForView(event.changingView).notGoneIndex;
                    int difference = Math.abs(ownIndex - changingIndex);
                    difference = Math.max(0, Math.min(DELAY_EFFECT_MAX_INDEX_DIFFERENCE,
                            difference - 1));
                    long delay = (DELAY_EFFECT_MAX_INDEX_DIFFERENCE - difference) * delayPerElement;
                    minDelay = Math.max(delay, minDelay);
                    break;
                }
                case NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT:
                    delayPerElement = ANIMATION_DELAY_PER_ELEMENT_MANUAL;
                case NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE: {
                    int ownIndex = viewState.notGoneIndex;
                    boolean noNextView = event.viewAfterChangingView == null;
                    View viewAfterChangingView = noNextView
                            ? mHostLayout.getLastChildNotGone()
                            : event.viewAfterChangingView;

                    int nextIndex = finalState
                            .getViewStateForView(viewAfterChangingView).notGoneIndex;
                    if (ownIndex >= nextIndex) {
                        // we only have the view afterwards
                        ownIndex++;
                    }
                    int difference = Math.abs(ownIndex - nextIndex);
                    difference = Math.max(0, Math.min(DELAY_EFFECT_MAX_INDEX_DIFFERENCE,
                            difference - 1));
                    long delay = difference * delayPerElement;
                    minDelay = Math.max(delay, minDelay);
                    break;
                }
                default:
                    break;
            }
        }
        return minDelay;
    }

    private long calculateDelayDark(StackViewState viewState) {
        int referenceIndex;
        if (mAnimationFilter.darkAnimationOriginIndex ==
                NotificationStackScrollLayout.AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_ABOVE) {
            referenceIndex = 0;
        } else if (mAnimationFilter.darkAnimationOriginIndex ==
                NotificationStackScrollLayout.AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_BELOW) {
            referenceIndex = mHostLayout.getNotGoneChildCount() - 1;
        } else {
            referenceIndex = mAnimationFilter.darkAnimationOriginIndex;
        }
        return Math.abs(referenceIndex - viewState.notGoneIndex) * ANIMATION_DELAY_PER_ELEMENT_DARK;
    }

    private long calculateDelayGoToFullShade(StackViewState viewState) {
        float index = viewState.notGoneIndex;
        index = (float) Math.pow(index, 0.7f);
        return (long) (index * ANIMATION_DELAY_PER_ELEMENT_GO_TO_FULL_SHADE);
    }

    private void startHeightAnimation(final ExpandableView child,
            StackViewState viewState, long duration, long delay) {
        Integer previousStartValue = getChildTag(child, TAG_START_HEIGHT);
        Integer previousEndValue = getChildTag(child, TAG_END_HEIGHT);
        int newEndValue = viewState.height;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ValueAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_HEIGHT);
        if (!mAnimationFilter.animateHeight) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                int relativeDiff = newEndValue - previousEndValue;
                int newStartValue = previousStartValue + relativeDiff;
                values[0].setIntValues(newStartValue, newEndValue);
                child.setTag(TAG_START_HEIGHT, newStartValue);
                child.setTag(TAG_END_HEIGHT, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setActualHeight(newEndValue, false);
                return;
            }
        }

        ValueAnimator animator = ValueAnimator.ofInt(child.getActualHeight(), newEndValue);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                child.setActualHeight((int) animation.getAnimatedValue(),
                        false /* notifyListeners */);
            }
        });
        animator.setInterpolator(mFastOutSlowInInterpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(duration, previousAnimator);
        animator.setDuration(newDuration);
        if (delay > 0 && (previousAnimator == null || !previousAnimator.isRunning())) {
            animator.setStartDelay(delay);
        }
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_HEIGHT, null);
                child.setTag(TAG_START_HEIGHT, null);
                child.setTag(TAG_END_HEIGHT, null);
            }
        });
        startAnimator(animator);
        child.setTag(TAG_ANIMATOR_HEIGHT, animator);
        child.setTag(TAG_START_HEIGHT, child.getActualHeight());
        child.setTag(TAG_END_HEIGHT, newEndValue);
    }

    private void startInsetAnimation(final ExpandableView child,
            StackViewState viewState, long duration, long delay) {
        Integer previousStartValue = getChildTag(child, TAG_START_TOP_INSET);
        Integer previousEndValue = getChildTag(child, TAG_END_TOP_INSET);
        int newEndValue = viewState.clipTopAmount;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ValueAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TOP_INSET);
        if (!mAnimationFilter.animateTopInset) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                int relativeDiff = newEndValue - previousEndValue;
                int newStartValue = previousStartValue + relativeDiff;
                values[0].setIntValues(newStartValue, newEndValue);
                child.setTag(TAG_START_TOP_INSET, newStartValue);
                child.setTag(TAG_END_TOP_INSET, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setClipTopAmount(newEndValue);
                return;
            }
        }

        ValueAnimator animator = ValueAnimator.ofInt(child.getClipTopAmount(), newEndValue);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                child.setClipTopAmount((int) animation.getAnimatedValue());
            }
        });
        animator.setInterpolator(mFastOutSlowInInterpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(duration, previousAnimator);
        animator.setDuration(newDuration);
        if (delay > 0 && (previousAnimator == null || !previousAnimator.isRunning())) {
            animator.setStartDelay(delay);
        }
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_TOP_INSET, null);
                child.setTag(TAG_START_TOP_INSET, null);
                child.setTag(TAG_END_TOP_INSET, null);
            }
        });
        startAnimator(animator);
        child.setTag(TAG_ANIMATOR_TOP_INSET, animator);
        child.setTag(TAG_START_TOP_INSET, child.getClipTopAmount());
        child.setTag(TAG_END_TOP_INSET, newEndValue);
    }

    private void startAlphaAnimation(final View child,
            final ViewState viewState, long duration, long delay) {
        Float previousStartValue = getChildTag(child,TAG_START_ALPHA);
        Float previousEndValue = getChildTag(child,TAG_END_ALPHA);
        final float newEndValue = viewState.alpha;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_ALPHA);
        if (!mAnimationFilter.animateAlpha) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_ALPHA, newStartValue);
                child.setTag(TAG_END_ALPHA, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setAlpha(newEndValue);
                if (newEndValue == 0) {
                    child.setVisibility(View.INVISIBLE);
                }
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.ALPHA,
                child.getAlpha(), newEndValue);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        // Handle layer type
        child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animator.addListener(new AnimatorListenerAdapter() {
            public boolean mWasCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                child.setLayerType(View.LAYER_TYPE_NONE, null);
                if (newEndValue == 0 && !mWasCancelled) {
                    child.setVisibility(View.INVISIBLE);
                }
                // remove the tag when the animation is finished
                child.setTag(TAG_ANIMATOR_ALPHA, null);
                child.setTag(TAG_START_ALPHA, null);
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
        long newDuration = cancelAnimatorAndGetNewDuration(duration, previousAnimator);
        animator.setDuration(newDuration);
        if (delay > 0 && (previousAnimator == null || !previousAnimator.isRunning())) {
            animator.setStartDelay(delay);
        }
        animator.addListener(getGlobalAnimationFinishedListener());

        startAnimator(animator);
        child.setTag(TAG_ANIMATOR_ALPHA, animator);
        child.setTag(TAG_START_ALPHA, child.getAlpha());
        child.setTag(TAG_END_ALPHA, newEndValue);
    }

    private void startZTranslationAnimation(final View child,
            final ViewState viewState, long duration, long delay) {
        Float previousStartValue = getChildTag(child,TAG_START_TRANSLATION_Z);
        Float previousEndValue = getChildTag(child,TAG_END_TRANSLATION_Z);
        float newEndValue = viewState.zTranslation;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_Z);
        if (!mAnimationFilter.animateZ) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_TRANSLATION_Z, newStartValue);
                child.setTag(TAG_END_TRANSLATION_Z, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setTranslationZ(newEndValue);
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_Z,
                child.getTranslationZ(), newEndValue);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(duration, previousAnimator);
        animator.setDuration(newDuration);
        if (delay > 0 && (previousAnimator == null || !previousAnimator.isRunning())) {
            animator.setStartDelay(delay);
        }
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_TRANSLATION_Z, null);
                child.setTag(TAG_START_TRANSLATION_Z, null);
                child.setTag(TAG_END_TRANSLATION_Z, null);
            }
        });
        startAnimator(animator);
        child.setTag(TAG_ANIMATOR_TRANSLATION_Z, animator);
        child.setTag(TAG_START_TRANSLATION_Z, child.getTranslationZ());
        child.setTag(TAG_END_TRANSLATION_Z, newEndValue);
    }

    private void startYTranslationAnimation(final View child,
            ViewState viewState, long duration, long delay) {
        Float previousStartValue = getChildTag(child,TAG_START_TRANSLATION_Y);
        Float previousEndValue = getChildTag(child,TAG_END_TRANSLATION_Y);
        float newEndValue = viewState.yTranslation;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_Y);
        if (!mAnimationFilter.animateY) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_TRANSLATION_Y, newStartValue);
                child.setTag(TAG_END_TRANSLATION_Y, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setTranslationY(newEndValue);
                return;
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y,
                child.getTranslationY(), newEndValue);
        Interpolator interpolator = mHeadsUpAppearChildren.contains(child) ?
                mHeadsUpAppearInterpolator :mFastOutSlowInInterpolator;
        animator.setInterpolator(interpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(duration, previousAnimator);
        animator.setDuration(newDuration);
        if (delay > 0 && (previousAnimator == null || !previousAnimator.isRunning())) {
            animator.setStartDelay(delay);
        }
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                HeadsUpManager.setIsClickedNotification(child, false);
                child.setTag(TAG_ANIMATOR_TRANSLATION_Y, null);
                child.setTag(TAG_START_TRANSLATION_Y, null);
                child.setTag(TAG_END_TRANSLATION_Y, null);
            }
        });
        startAnimator(animator);
        child.setTag(TAG_ANIMATOR_TRANSLATION_Y, animator);
        child.setTag(TAG_START_TRANSLATION_Y, child.getTranslationY());
        child.setTag(TAG_END_TRANSLATION_Y, newEndValue);
    }

    private void startScaleAnimation(final View child,
            ViewState viewState, long duration) {
        Float previousStartValue = getChildTag(child, TAG_START_SCALE);
        Float previousEndValue = getChildTag(child, TAG_END_SCALE);
        float newEndValue = viewState.scale;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_SCALE);
        if (!mAnimationFilter.animateScale) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                values[1].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_SCALE, newStartValue);
                child.setTag(TAG_END_SCALE, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setScaleX(newEndValue);
                child.setScaleY(newEndValue);
            }
        }

        PropertyValuesHolder holderX =
                PropertyValuesHolder.ofFloat(View.SCALE_X, child.getScaleX(), newEndValue);
        PropertyValuesHolder holderY =
                PropertyValuesHolder.ofFloat(View.SCALE_Y, child.getScaleY(), newEndValue);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(child, holderX, holderY);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(duration, previousAnimator);
        animator.setDuration(newDuration);
        animator.addListener(getGlobalAnimationFinishedListener());
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_SCALE, null);
                child.setTag(TAG_START_SCALE, null);
                child.setTag(TAG_END_SCALE, null);
            }
        });
        startAnimator(animator);
        child.setTag(TAG_ANIMATOR_SCALE, animator);
        child.setTag(TAG_START_SCALE, child.getScaleX());
        child.setTag(TAG_END_SCALE, newEndValue);
    }

    private void startAnimator(ValueAnimator animator) {
        mAnimatorSet.add(animator);
        animator.start();
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
                mWasCancelled = false;
            }
        };
    }

    public static <T> T getChildTag(View child, int tag) {
        return (T) child.getTag(tag);
    }

    /**
     * Cancel the previous animator and get the duration of the new animation.
     *
     * @param duration the new duration
     * @param previousAnimator the animator which was running before
     * @return the new duration
     */
    private long cancelAnimatorAndGetNewDuration(long duration, ValueAnimator previousAnimator) {
        long newDuration = duration;
        if (previousAnimator != null) {
            // We take either the desired length of the new animation or the remaining time of
            // the previous animator, whichever is longer.
            newDuration = Math.max(previousAnimator.getDuration()
                    - previousAnimator.getCurrentPlayTime(), newDuration);
            previousAnimator.cancel();
        }
        return newDuration;
    }

    private void onAnimationFinished() {
        mHostLayout.onChildAnimationFinished();
        for (View v : mChildrenToClearFromOverlay) {
            mHostLayout.getOverlay().remove(v);
        }
        mChildrenToClearFromOverlay.clear();
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
        for (NotificationStackScrollLayout.AnimationEvent event : animationEvents) {
            final ExpandableView changingView = (ExpandableView) event.changingView;
            if (event.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_ADD) {

                // This item is added, initialize it's properties.
                StackViewState viewState = finalState
                        .getViewStateForView(changingView);
                if (viewState == null) {
                    // The position for this child was never generated, let's continue.
                    continue;
                }
                if (changingView.getVisibility() == View.GONE) {
                    // The view was set to gone but the state never removed
                    finalState.removeViewStateForView(changingView);
                    continue;
                }
                finalState.applyState(changingView, viewState);
                mNewAddChildren.add(changingView);

            } else if (event.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE) {
                if (changingView.getVisibility() == View.GONE) {
                    mHostLayout.getOverlay().remove(changingView);
                    continue;
                }

                // Find the amount to translate up. This is needed in order to understand the
                // direction of the remove animation (either downwards or upwards)
                StackViewState viewState = finalState
                        .getViewStateForView(event.viewAfterChangingView);
                int actualHeight = changingView.getActualHeight();
                // upwards by default
                float translationDirection = -1.0f;
                if (viewState != null) {
                    // there was a view after this one, Approximate the distance the next child
                    // travelled
                    translationDirection = ((viewState.yTranslation
                            - (changingView.getTranslationY() + actualHeight / 2.0f)) * 2 /
                            actualHeight);
                    translationDirection = Math.max(Math.min(translationDirection, 1.0f),-1.0f);

                }
                changingView.performRemoveAnimation(ANIMATION_DURATION_APPEAR_DISAPPEAR,
                        translationDirection, new Runnable() {
                    @Override
                    public void run() {
                        // remove the temporary overlay
                        mHostLayout.getOverlay().remove(changingView);
                    }
                });
            } else if (event.animationType ==
                NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT) {
                // A race condition can trigger the view to be added to the overlay even though
                // it is swiped out. So let's remove it
                mHostLayout.getOverlay().remove(changingView);
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_GROUP_EXPANSION_CHANGED) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) event.changingView;
                row.prepareExpansionChanged(finalState);
                mChildExpandingView = row;
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR) {
                // This item is added, initialize it's properties.
                StackViewState viewState = finalState.getViewStateForView(changingView);
                mTmpState.copyFrom(viewState);
                if (event.headsUpFromBottom) {
                    mTmpState.yTranslation = mHeadsUpAppearHeightBottom;
                } else {
                    mTmpState.yTranslation = -mTmpState.height;
                }
                mHeadsUpAppearChildren.add(changingView);
                finalState.applyState(changingView, mTmpState);
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR) {
                mHeadsUpDisappearChildren.add(changingView);
                if (mHostLayout.indexOfChild(changingView) == -1) {
                    // This notification was actually removed, so we need to add it to the overlay
                    mHostLayout.getOverlay().add(changingView);
                    mTmpState.initFrom(changingView);
                    mTmpState.yTranslation = -changingView.getActualHeight();
                    // We temporarily enable Y animations, the real filter will be combined
                    // afterwards anyway
                    mAnimationFilter.animateY = true;
                    startViewAnimations(changingView, mTmpState, 0,
                            ANIMATION_DURATION_HEADS_UP_DISAPPEAR);
                    mChildrenToClearFromOverlay.add(changingView);
                }
            }
            mNewEvents.add(event);
        }
    }

    public void animateOverScrollToAmount(float targetAmount, final boolean onTop,
            final boolean isRubberbanded) {
        final float startOverScrollAmount = mHostLayout.getCurrentOverScrollAmount(onTop);
        if (targetAmount == startOverScrollAmount) {
            return;
        }
        cancelOverScrollAnimators(onTop);
        ValueAnimator overScrollAnimator = ValueAnimator.ofFloat(startOverScrollAmount,
                targetAmount);
        overScrollAnimator.setDuration(ANIMATION_DURATION_STANDARD);
        overScrollAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float currentOverScroll = (float) animation.getAnimatedValue();
                mHostLayout.setOverScrollAmount(
                        currentOverScroll, onTop, false /* animate */, false /* cancelAnimators */,
                        isRubberbanded);
            }
        });
        overScrollAnimator.setInterpolator(mFastOutSlowInInterpolator);
        overScrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onTop) {
                    mTopOverScrollAnimator = null;
                } else {
                    mBottomOverScrollAnimator = null;
                }
            }
        });
        overScrollAnimator.start();
        if (onTop) {
            mTopOverScrollAnimator = overScrollAnimator;
        } else {
            mBottomOverScrollAnimator = overScrollAnimator;
        }
    }

    public void cancelOverScrollAnimators(boolean onTop) {
        ValueAnimator currentAnimator = onTop ? mTopOverScrollAnimator : mBottomOverScrollAnimator;
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
    }

    /**
     * Get the end value of the height animation running on a view or the actualHeight
     * if no animation is running.
     */
    public static int getFinalActualHeight(ExpandableView view) {
        if (view == null) {
            return 0;
        }
        ValueAnimator heightAnimator = getChildTag(view, TAG_ANIMATOR_HEIGHT);
        if (heightAnimator == null) {
            return view.getActualHeight();
        } else {
            return getChildTag(view, TAG_END_HEIGHT);
        }
    }

    public void setHeadsUpAppearHeightBottom(int headsUpAppearHeightBottom) {
        mHeadsUpAppearHeightBottom = headsUpAppearHeightBottom;
    }

    public void setShadeExpanded(boolean shadeExpanded) {
        mShadeExpanded = shadeExpanded;
    }
}
