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
import android.animation.ValueAnimator;
import android.util.Property;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.NotificationShelf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Stack;

/**
 * An stack state animator which handles animations to new StackScrollStates
 */
public class StackStateAnimator {

    public static final int ANIMATION_DURATION_STANDARD = 360;
    public static final int ANIMATION_DURATION_WAKEUP = 200;
    public static final int ANIMATION_DURATION_GO_TO_FULL_SHADE = 448;
    public static final int ANIMATION_DURATION_APPEAR_DISAPPEAR = 464;
    public static final int ANIMATION_DURATION_DIMMED_ACTIVATED = 220;
    public static final int ANIMATION_DURATION_CLOSE_REMOTE_INPUT = 150;
    public static final int ANIMATION_DURATION_HEADS_UP_APPEAR = 650;
    public static final int ANIMATION_DURATION_HEADS_UP_DISAPPEAR = 230;
    public static final int ANIMATION_DELAY_PER_ELEMENT_INTERRUPTING = 80;
    public static final int ANIMATION_DELAY_PER_ELEMENT_MANUAL = 32;
    public static final int ANIMATION_DELAY_PER_ELEMENT_GO_TO_FULL_SHADE = 48;
    public static final int DELAY_EFFECT_MAX_INDEX_DIFFERENCE = 2;
    public static final int ANIMATION_DELAY_HEADS_UP = 120;

    private final int mGoToFullShadeAppearingTranslation;
    private final ExpandableViewState mTmpState = new ExpandableViewState();
    private final AnimationProperties mAnimationProperties;
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
    private int mHeadsUpAppearHeightBottom;
    private boolean mShadeExpanded;
    private ArrayList<View> mChildrenToClearFromOverlay = new ArrayList<>();
    private NotificationShelf mShelf;

    public StackStateAnimator(NotificationStackScrollLayout hostLayout) {
        mHostLayout = hostLayout;
        mGoToFullShadeAppearingTranslation =
                hostLayout.getContext().getResources().getDimensionPixelSize(
                        R.dimen.go_to_full_shade_appearing_translation);
        mAnimationProperties = new AnimationProperties() {
            @Override
            public AnimationFilter getAnimationFilter() {
                return mAnimationFilter;
            }

            @Override
            public AnimatorListenerAdapter getAnimationFinishListener() {
                return getGlobalAnimationFinishedListener();
            }

            @Override
            public boolean wasAdded(View view) {
                return mNewAddChildren.contains(view);
            }

            @Override
            public Interpolator getCustomInterpolator(View child, Property property) {
                if (mHeadsUpAppearChildren.contains(child) && View.TRANSLATION_Y.equals(property)) {
                    return Interpolators.HEADS_UP_APPEAR;
                }
                return null;
            }
        };
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

            ExpandableViewState viewState = finalState.getViewStateForView(child);
            if (viewState == null || child.getVisibility() == View.GONE
                    || applyWithoutAnimation(child, viewState, finalState)) {
                continue;
            }

            initAnimationProperties(finalState, child, viewState);
            viewState.animateTo(child, mAnimationProperties);
        }
        if (!isRunning()) {
            // no child has preformed any animation, lets finish
            onAnimationFinished();
        }
        mHeadsUpAppearChildren.clear();
        mHeadsUpDisappearChildren.clear();
        mNewEvents.clear();
        mNewAddChildren.clear();
    }

    private void initAnimationProperties(StackScrollState finalState, ExpandableView child,
            ExpandableViewState viewState) {
        boolean wasAdded = mAnimationProperties.wasAdded(child);
        mAnimationProperties.duration = mCurrentLength;
        adaptDurationWhenGoingToFullShade(child, viewState, wasAdded);
        mAnimationProperties.delay = 0;
        if (wasAdded || mAnimationFilter.hasDelays
                        && (viewState.yTranslation != child.getTranslationY()
                        || viewState.zTranslation != child.getTranslationZ()
                        || viewState.alpha != child.getAlpha()
                        || viewState.height != child.getActualHeight()
                        || viewState.clipTopAmount != child.getClipTopAmount()
                        || viewState.dark != child.isDark()
                        || viewState.shadowAlpha != child.getShadowAlpha())) {
            mAnimationProperties.delay = mCurrentAdditionalDelay
                    + calculateChildAnimationDelay(viewState, finalState);
        }
    }

    private void adaptDurationWhenGoingToFullShade(ExpandableView child,
            ExpandableViewState viewState, boolean wasAdded) {
        if (wasAdded && mAnimationFilter.hasGoToFullShadeEvent) {
            child.setTranslationY(child.getTranslationY() + mGoToFullShadeAppearingTranslation);
            float longerDurationFactor = viewState.notGoneIndex - mCurrentLastNotAddedIndex;
            longerDurationFactor = (float) Math.pow(longerDurationFactor, 0.7f);
            mAnimationProperties.duration = ANIMATION_DURATION_APPEAR_DISAPPEAR + 50 +
                    (long) (100 * longerDurationFactor);
        }
    }

    /**
     * Determines if a view should not perform an animation and applies it directly.
     *
     * @return true if no animation should be performed
     */
    private boolean applyWithoutAnimation(ExpandableView child, ExpandableViewState viewState,
            StackScrollState finalState) {
        if (mShadeExpanded) {
            return false;
        }
        if (ViewState.isAnimatingY(child)) {
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
        viewState.applyToView(child);
        return true;
    }

    private int findLastNotAddedIndex(StackScrollState finalState) {
        int childCount = mHostLayout.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            final ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);

            ExpandableViewState viewState = finalState.getViewStateForView(child);
            if (viewState == null || child.getVisibility() == View.GONE) {
                continue;
            }
            if (!mNewAddChildren.contains(child)) {
                return viewState.notGoneIndex;
            }
        }
        return -1;
    }

    private long calculateChildAnimationDelay(ExpandableViewState viewState,
            StackScrollState finalState) {
        if (mAnimationFilter.hasGoToFullShadeEvent) {
            return calculateDelayGoToFullShade(viewState);
        }
        if (mAnimationFilter.hasHeadsUpDisappearClickEvent) {
            return ANIMATION_DELAY_HEADS_UP;
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
                    if (viewAfterChangingView == null) {
                        // This can happen when the last view in the list is removed.
                        // Since the shelf is still around and the only view, the code still goes
                        // in here and tries to calculate the delay for it when case its properties
                        // have changed.
                        continue;
                    }
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

    private long calculateDelayGoToFullShade(ExpandableViewState viewState) {
        int shelfIndex = mShelf.getNotGoneIndex();
        float index = viewState.notGoneIndex;
        long result = 0;
        if (index > shelfIndex) {
            float diff = index - shelfIndex;
            diff = (float) Math.pow(diff, 0.7f);
            result += (long) (diff * ANIMATION_DELAY_PER_ELEMENT_GO_TO_FULL_SHADE * 0.25);
            index = shelfIndex;
        }
        index = (float) Math.pow(index, 0.7f);
        result += (long) (index * ANIMATION_DELAY_PER_ELEMENT_GO_TO_FULL_SHADE);
        return result;
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
                mAnimatorSet.add(animation);
            }
        };
    }

    private void onAnimationFinished() {
        mHostLayout.onChildAnimationFinished();
        for (View v : mChildrenToClearFromOverlay) {
            removeFromOverlay(v);
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
                ExpandableViewState viewState = finalState
                        .getViewStateForView(changingView);
                if (viewState == null) {
                    // The position for this child was never generated, let's continue.
                    continue;
                }
                viewState.applyToView(changingView);
                mNewAddChildren.add(changingView);

            } else if (event.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE) {
                if (changingView.getVisibility() != View.VISIBLE) {
                    removeFromOverlay(changingView);
                    continue;
                }

                // Find the amount to translate up. This is needed in order to understand the
                // direction of the remove animation (either downwards or upwards)
                ExpandableViewState viewState = finalState
                        .getViewStateForView(event.viewAfterChangingView);
                int actualHeight = changingView.getActualHeight();
                // upwards by default
                float translationDirection = -1.0f;
                if (viewState != null) {
                    float ownPosition = changingView.getTranslationY();
                    if (changingView instanceof ExpandableNotificationRow
                            && event.viewAfterChangingView instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow changingRow =
                                (ExpandableNotificationRow) changingView;
                        ExpandableNotificationRow nextRow =
                                (ExpandableNotificationRow) event.viewAfterChangingView;
                        if (changingRow.isRemoved()
                                && changingRow.wasChildInGroupWhenRemoved()
                                && !nextRow.isChildInGroup()) {
                            // the next row isn't actually a child from a group! Let's
                            // compare absolute positions!
                            ownPosition = changingRow.getTranslationWhenRemoved();
                        }
                    }
                    // there was a view after this one, Approximate the distance the next child
                    // travelled
                    translationDirection = ((viewState.yTranslation
                            - (ownPosition + actualHeight / 2.0f)) * 2 /
                            actualHeight);
                    translationDirection = Math.max(Math.min(translationDirection, 1.0f),-1.0f);

                }
                changingView.performRemoveAnimation(ANIMATION_DURATION_APPEAR_DISAPPEAR,
                        translationDirection, new Runnable() {
                    @Override
                    public void run() {
                        // remove the temporary overlay
                        removeFromOverlay(changingView);
                    }
                });
            } else if (event.animationType ==
                NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT) {
                // A race condition can trigger the view to be added to the overlay even though
                // it was fully swiped out. So let's remove it
                mHostLayout.getOverlay().remove(changingView);
                if (Math.abs(changingView.getTranslation()) == changingView.getWidth()
                        && changingView.getTransientContainer() != null) {
                    changingView.getTransientContainer().removeTransientView(changingView);
                }
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_GROUP_EXPANSION_CHANGED) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) event.changingView;
                row.prepareExpansionChanged(finalState);
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR) {
                // This item is added, initialize it's properties.
                ExpandableViewState viewState = finalState.getViewStateForView(changingView);
                mTmpState.copyFrom(viewState);
                if (event.headsUpFromBottom) {
                    mTmpState.yTranslation = mHeadsUpAppearHeightBottom;
                } else {
                    mTmpState.yTranslation = -mTmpState.height;
                }
                mHeadsUpAppearChildren.add(changingView);
                mTmpState.applyToView(changingView);
            } else if (event.animationType == NotificationStackScrollLayout
                            .AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR ||
                    event.animationType == NotificationStackScrollLayout
                            .AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK) {
                mHeadsUpDisappearChildren.add(changingView);
                if (changingView.getParent() == null) {
                    // This notification was actually removed, so we need to add it to the overlay
                    mHostLayout.getOverlay().add(changingView);
                    mTmpState.initFrom(changingView);
                    mTmpState.yTranslation = -changingView.getActualHeight();
                    // We temporarily enable Y animations, the real filter will be combined
                    // afterwards anyway
                    mAnimationFilter.animateY = true;
                    mAnimationProperties.delay =
                            event.animationType == NotificationStackScrollLayout
                                    .AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                            ? ANIMATION_DELAY_HEADS_UP
                            : 0;
                    mAnimationProperties.duration = ANIMATION_DURATION_HEADS_UP_DISAPPEAR;
                    mTmpState.animateTo(changingView, mAnimationProperties);
                    mChildrenToClearFromOverlay.add(changingView);
                }
            }
            mNewEvents.add(event);
        }
    }

    public static void removeFromOverlay(View changingView) {
        ViewGroup parent = (ViewGroup) changingView.getParent();
        if (parent != null) {
            parent.removeView(changingView);
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
        overScrollAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
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

    public void setHeadsUpAppearHeightBottom(int headsUpAppearHeightBottom) {
        mHeadsUpAppearHeightBottom = headsUpAppearHeightBottom;
    }

    public void setShadeExpanded(boolean shadeExpanded) {
        mShadeExpanded = shadeExpanded;
    }

    public void setShelf(NotificationShelf shelf) {
        mShelf = shelf;
    }
}
