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

package com.android.systemui.statusbar.notification.stack;

import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.Property;
import android.view.View;

import com.android.app.animation.Interpolators;
import com.android.keyguard.KeyguardSliceView;
import com.android.systemui.res.R;
import com.android.systemui.shared.clocks.AnimatableClockView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Stack;

/**
 * An stack state animator which handles animations to new StackScrollStates
 */
public class StackStateAnimator {

    public static final int ANIMATION_DURATION_STANDARD = 360;
    public static final int ANIMATION_DURATION_CORNER_RADIUS = 200;
    public static final int ANIMATION_DURATION_WAKEUP = 500;
    public static final int ANIMATION_DURATION_WAKEUP_SCRIM = 667;
    public static final int ANIMATION_DURATION_GO_TO_FULL_SHADE = 448;
    public static final int ANIMATION_DURATION_APPEAR_DISAPPEAR = 464;
    public static final int ANIMATION_DURATION_SWIPE = 200;
    public static final int ANIMATION_DURATION_DIMMED_ACTIVATED = 220;
    public static final int ANIMATION_DURATION_CLOSE_REMOTE_INPUT = 150;
    public static final int ANIMATION_DURATION_HEADS_UP_APPEAR = 400;
    public static final int ANIMATION_DURATION_HEADS_UP_DISAPPEAR = 400;
    public static final int ANIMATION_DURATION_FOLD_TO_AOD =
            AnimatableClockView.ANIMATION_DURATION_FOLD_TO_AOD;
    public static final int ANIMATION_DURATION_PULSE_APPEAR =
            KeyguardSliceView.DEFAULT_ANIM_DURATION;
    public static final int ANIMATION_DURATION_BLOCKING_HELPER_FADE = 240;
    public static final int ANIMATION_DURATION_PRIORITY_CHANGE = 500;
    public static final int ANIMATION_DELAY_PER_ELEMENT_INTERRUPTING = 80;
    public static final int ANIMATION_DELAY_PER_ELEMENT_MANUAL = 32;
    public static final int ANIMATION_DELAY_PER_ELEMENT_GO_TO_FULL_SHADE = 48;
    public static final int DELAY_EFFECT_MAX_INDEX_DIFFERENCE = 2;
    private static final int MAX_STAGGER_COUNT = 5;

    private final int mGoToFullShadeAppearingTranslation;
    private final int mPulsingAppearingTranslation;
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

    private ValueAnimator mTopOverScrollAnimator;
    private ValueAnimator mBottomOverScrollAnimator;
    private int mHeadsUpAppearHeightBottom;
    private boolean mShadeExpanded;
    private ArrayList<ExpandableView> mTransientViewsToRemove = new ArrayList<>();
    private NotificationShelf mShelf;
    private float mStatusBarIconLocation;
    private int[] mTmpLocation = new int[2];
    private StackStateLogger mLogger;

    public StackStateAnimator(NotificationStackScrollLayout hostLayout) {
        mHostLayout = hostLayout;
        mGoToFullShadeAppearingTranslation =
                hostLayout.getContext().getResources().getDimensionPixelSize(
                        R.dimen.go_to_full_shade_appearing_translation);
        mPulsingAppearingTranslation =
                hostLayout.getContext().getResources().getDimensionPixelSize(
                        R.dimen.pulsing_notification_appear_translation);
        mAnimationProperties = new AnimationProperties() {
            @Override
            public AnimationFilter getAnimationFilter() {
                return mAnimationFilter;
            }

            @Override
            public AnimatorListenerAdapter getAnimationFinishListener(Property property) {
                return getGlobalAnimationFinishedListener();
            }

            @Override
            public boolean wasAdded(View view) {
                return mNewAddChildren.contains(view);
            }
        };
    }

    protected void setLogger(StackStateLogger logger) {
        mLogger = logger;
    }

    public boolean isRunning() {
        return !mAnimatorSet.isEmpty();
    }

    public void startAnimationForEvents(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> mAnimationEvents,
            long additionalDelay) {

        // Animation events might generate custom animations, which are started async
        boolean anyCustomAnimationCreated = processAnimationEvents(mAnimationEvents);

        int childCount = mHostLayout.getChildCount();
        mAnimationFilter.applyCombination(mNewEvents);
        mCurrentAdditionalDelay = additionalDelay;
        mCurrentLength = NotificationStackScrollLayout.AnimationEvent.combineLength(mNewEvents);
        // Used to stagger concurrent animations' delays and durations for visual effect
        int animationStaggerCount = 0;
        for (int i = 0; i < childCount; i++) {
            final ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);

            ExpandableViewState viewState = child.getViewState();
            if (viewState == null || child.getVisibility() == View.GONE
                    || applyWithoutAnimation(child, viewState)) {
                continue;
            }

            if (mAnimationProperties.wasAdded(child) && animationStaggerCount < MAX_STAGGER_COUNT) {
                animationStaggerCount++;
            }
            initAnimationProperties(child, viewState, animationStaggerCount);
            viewState.animateTo(child, mAnimationProperties);
        }
        if (!isRunning() && !anyCustomAnimationCreated) {
            // no child has performed any animation or is about to animate, lets finish
            onAnimationFinished();
        }
        mHeadsUpAppearChildren.clear();
        mHeadsUpDisappearChildren.clear();
        mNewEvents.clear();
        mNewAddChildren.clear();
    }

    private void initAnimationProperties(ExpandableView child,
            ExpandableViewState viewState, int animationStaggerCount) {
        boolean wasAdded = mAnimationProperties.wasAdded(child);
        mAnimationProperties.duration = mCurrentLength;
        adaptDurationWhenGoingToFullShade(child, viewState, wasAdded, animationStaggerCount);
        mAnimationProperties.delay = 0;
        if (wasAdded || mAnimationFilter.hasDelays
                        && (viewState.getYTranslation() != child.getTranslationY()
                        || viewState.getZTranslation() != child.getTranslationZ()
                        || viewState.getAlpha() != child.getAlpha()
                        || viewState.height != child.getActualHeight()
                        || viewState.clipTopAmount != child.getClipTopAmount())) {
            mAnimationProperties.delay = mCurrentAdditionalDelay
                    + calculateChildAnimationDelay(viewState, animationStaggerCount);
        }
    }

    private void adaptDurationWhenGoingToFullShade(ExpandableView child,
            ExpandableViewState viewState, boolean wasAdded, int animationStaggerCount) {
        boolean isDecorView = child instanceof StackScrollerDecorView;
        boolean needsAdjustment = wasAdded || isDecorView;
        if (needsAdjustment && mAnimationFilter.hasGoToFullShadeEvent) {
            int startOffset = 0;
            if (!isDecorView) {
                startOffset = mGoToFullShadeAppearingTranslation;
                float longerDurationFactor = (float) Math.pow(animationStaggerCount, 0.7f);
                mAnimationProperties.duration = ANIMATION_DURATION_APPEAR_DISAPPEAR + 50
                        + (long) (100 * longerDurationFactor);
            }
            child.setTranslationY(viewState.getYTranslation() + startOffset);
        }
    }

    /**
     * Determines if a view should not perform an animation and applies it directly.
     *
     * @return true if no animation should be performed
     */
    private boolean applyWithoutAnimation(ExpandableView child, ExpandableViewState viewState) {
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

    private long calculateChildAnimationDelay(ExpandableViewState viewState,
            int animationStaggerCount) {
        if (mAnimationFilter.hasGoToFullShadeEvent) {
            return calculateDelayGoToFullShade(viewState, animationStaggerCount);
        }
        if (mAnimationFilter.customDelay != AnimationFilter.NO_DELAY) {
            return mAnimationFilter.customDelay;
        }
        long minDelay = 0;
        for (NotificationStackScrollLayout.AnimationEvent event : mNewEvents) {
            long delayPerElement = ANIMATION_DELAY_PER_ELEMENT_INTERRUPTING;
            switch (event.animationType) {
                case NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_ADD: {
                    int ownIndex = viewState.notGoneIndex;
                    int changingIndex =
                            ((ExpandableView) (event.mChangingView)).getViewState().notGoneIndex;
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
                    ExpandableView viewAfterChangingView = noNextView
                            ? mHostLayout.getLastChildNotGone()
                            : (ExpandableView) event.viewAfterChangingView;
                    if (viewAfterChangingView == null) {
                        // This can happen when the last view in the list is removed.
                        // Since the shelf is still around and the only view, the code still goes
                        // in here and tries to calculate the delay for it when case its properties
                        // have changed.
                        continue;
                    }
                    int nextIndex = viewAfterChangingView.getViewState().notGoneIndex;
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

    private long calculateDelayGoToFullShade(ExpandableViewState viewState,
            int animationStaggerCount) {
        int shelfIndex = mShelf.getNotGoneIndex();
        float index = viewState.notGoneIndex;
        long result = 0;
        if (index > shelfIndex) {
            float diff = (float) Math.pow(animationStaggerCount, 0.7f);
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

        for (ExpandableView transientViewToRemove : mTransientViewsToRemove) {
            transientViewToRemove.removeFromTransientContainer();
        }
        mTransientViewsToRemove.clear();
    }

    /**
     * Process the animationEvents for a new animation. Here is the place to do something custom,
     * like to modify the ViewState or to create a custom animation for an event.
     *
     *  @param animationEvents the animation events for the animation to perform
     * @return true if any custom animation was created
     */
    private boolean processAnimationEvents(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> animationEvents) {
        boolean needsCustomAnimation = false;
        for (NotificationStackScrollLayout.AnimationEvent event : animationEvents) {
            final ExpandableView changingView = event.mChangingView;
            boolean loggable = false;
            boolean isHeadsUp = false;
            String key = null;
            if (changingView instanceof ExpandableNotificationRow && mLogger != null) {
                loggable = true;
                isHeadsUp = ((ExpandableNotificationRow) changingView).isHeadsUp();
                key = ((ExpandableNotificationRow) changingView).getEntry().getKey();
            }
            if (event.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_ADD) {

                // This item is added, initialize its properties.
                ExpandableViewState viewState = changingView.getViewState();
                if (viewState == null || viewState.gone) {
                    // The position for this child was never generated, let's continue.
                    continue;
                }
                if (loggable && isHeadsUp) {
                    mLogger.logHUNViewAppearingWithAddEvent(key);
                }
                viewState.applyToView(changingView);
                mNewAddChildren.add(changingView);

            } else if (event.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE) {
                int changingViewVisibility = changingView.getVisibility();
                if (loggable) {
                    mLogger.processAnimationEventsRemoval(key, changingViewVisibility, isHeadsUp);
                }
                if (changingViewVisibility != View.VISIBLE) {
                    changingView.removeFromTransientContainer();
                    continue;
                }

                // Find the amount to translate up. This is needed in order to understand the
                // direction of the remove animation (either downwards or upwards)
                // upwards by default
                float translationDirection = -1.0f;
                if (event.viewAfterChangingView != null) {
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
                    int actualHeight = changingView.getActualHeight();
                    // there was a view after this one, Approximate the distance the next child
                    // travelled
                    ExpandableViewState viewState =
                            ((ExpandableView) event.viewAfterChangingView).getViewState();
                    translationDirection = ((viewState.getYTranslation()
                            - (ownPosition + actualHeight / 2.0f)) * 2 /
                            actualHeight);
                    translationDirection = Math.max(Math.min(translationDirection, 1.0f),-1.0f);

                }
                Runnable postAnimation;
                Runnable startAnimation;
                if (loggable) {
                    String finalKey = key;
                    final boolean finalIsHeadsHp = isHeadsUp;
                    startAnimation = () -> {
                        mLogger.animationStart(finalKey, "ANIMATION_TYPE_REMOVE", finalIsHeadsHp);
                        changingView.setInRemovalAnimation(true);
                    };
                    postAnimation = () -> {
                        mLogger.animationEnd(finalKey, "ANIMATION_TYPE_REMOVE", finalIsHeadsHp);
                        changingView.setInRemovalAnimation(false);
                        changingView.removeFromTransientContainer();
                    };
                } else {
                    startAnimation = ()-> {
                        changingView.setInRemovalAnimation(true);
                    };
                    postAnimation = () -> {
                        changingView.setInRemovalAnimation(false);
                        changingView.removeFromTransientContainer();
                    };
                }
                changingView.performRemoveAnimation(ANIMATION_DURATION_APPEAR_DISAPPEAR,
                        0 /* delay */, translationDirection, false /* isHeadsUpAppear */,
                        startAnimation, postAnimation, getGlobalAnimationFinishedListener());
                needsCustomAnimation = true;
            } else if (event.animationType ==
                NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT) {
                boolean isFullySwipedOut = mHostLayout.isFullySwipedOut(changingView);
                if (loggable) {
                    mLogger.processAnimationEventsRemoveSwipeOut(key, isFullySwipedOut, isHeadsUp);
                }
                if (isFullySwipedOut) {
                    changingView.removeFromTransientContainer();
                }
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_GROUP_EXPANSION_CHANGED) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) event.mChangingView;
                row.prepareExpansionChanged();
            } else if (event.animationType == NotificationStackScrollLayout
                    .AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR) {
                // This item is added, initialize its properties.
                ExpandableViewState viewState = changingView.getViewState();
                mTmpState.copyFrom(viewState);
                if (event.headsUpFromBottom) {
                    mTmpState.setYTranslation(mHeadsUpAppearHeightBottom);
                } else {
                    Runnable onAnimationEnd = null;
                    if (loggable) {
                        String finalKey = key;
                        onAnimationEnd = () -> mLogger.appearAnimationEnded(finalKey);
                    }
                    changingView.performAddAnimation(0, ANIMATION_DURATION_HEADS_UP_APPEAR,
                            true /* isHeadsUpAppear */, onAnimationEnd);
                }
                mHeadsUpAppearChildren.add(changingView);
                // this only captures HEADS_UP_APPEAR animations, but HUNs can appear with normal
                // ADD animations, which would not be logged here.
                if (loggable) {
                    mLogger.logHUNViewAppearing(key);
                }

                mTmpState.applyToView(changingView);
            } else if (event.animationType == ANIMATION_TYPE_HEADS_UP_DISAPPEAR
                    || event.animationType == ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK) {
                mHeadsUpDisappearChildren.add(changingView);
                Runnable endRunnable = null;
                if (changingView.getParent() == null) {
                    // This notification was actually removed, so we need to add it
                    // transiently
                    mHostLayout.addTransientView(changingView, 0);
                    changingView.setTransientContainer(mHostLayout);
                    mTmpState.initFrom(changingView);
                    endRunnable = changingView::removeFromTransientContainer;
                }

                boolean needsAnimation = true;
                if (changingView instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row =
                            (ExpandableNotificationRow) changingView;
                    if (row.isDismissed()) {
                        needsAnimation = false;
                    }
                }
                if (needsAnimation) {
                    // We need to add the global animation listener, since once no animations are
                    // running anymore, the panel will instantly hide itself. We need to wait until
                    // the animation is fully finished for this though.
                    final Runnable tmpEndRunnable = endRunnable;
                    Runnable postAnimation;
                    Runnable startAnimation;
                    if (loggable) {
                        String finalKey1 = key;
                        final boolean finalIsHeadsUp = isHeadsUp;
                        final String type =
                                event.animationType == ANIMATION_TYPE_HEADS_UP_DISAPPEAR
                                        ? "ANIMATION_TYPE_HEADS_UP_DISAPPEAR"
                                        : "ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK";
                        startAnimation = () -> {
                            mLogger.animationStart(finalKey1, type, finalIsHeadsUp);
                            changingView.setInRemovalAnimation(true);
                        };
                        postAnimation = () -> {
                            mLogger.animationEnd(finalKey1, type, finalIsHeadsUp);
                            changingView.setInRemovalAnimation(false);
                            if (tmpEndRunnable != null) {
                                tmpEndRunnable.run();
                            }
                        };
                    } else {
                        postAnimation = () -> {
                            changingView.setInRemovalAnimation(false);
                            if (tmpEndRunnable != null) {
                                tmpEndRunnable.run();
                            }
                        };
                        startAnimation = () -> {
                            changingView.setInRemovalAnimation(true);
                        };
                    }
                    long removeAnimationDelay = changingView.performRemoveAnimation(
                            ANIMATION_DURATION_HEADS_UP_DISAPPEAR,
                            0, 0.0f, true /* isHeadsUpAppear */,
                            startAnimation, postAnimation,
                            getGlobalAnimationFinishedListener());
                    mAnimationProperties.delay += removeAnimationDelay;
                } else if (endRunnable != null) {
                    endRunnable.run();
                }
                needsCustomAnimation |= needsAnimation;
            }
            mNewEvents.add(event);
        }
        return needsCustomAnimation;
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
