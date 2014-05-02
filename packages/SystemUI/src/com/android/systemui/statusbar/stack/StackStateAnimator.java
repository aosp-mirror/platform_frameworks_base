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

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.systemui.statusbar.ExpandableView;

import java.util.ArrayList;

/**
 * An stack state animator which handles animations to new StackScrollStates
 */
public class StackStateAnimator {

    private static final int ANIMATION_DURATION = 360;

    private final Interpolator mFastOutSlowInInterpolator;
    public NotificationStackScrollLayout mHostLayout;
    private boolean mAnimationIsRunning;
    private ArrayList<NotificationStackScrollLayout.AnimationEvent> mHandledEvents =
            new ArrayList<>();

    public StackStateAnimator(NotificationStackScrollLayout hostLayout) {
        mHostLayout = hostLayout;
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(hostLayout.getContext(),
                        android.R.interpolator.fast_out_slow_in);
    }

    public boolean isRunning() {
        return mAnimationIsRunning;
    }

    public void startAnimationForEvents(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> mAnimationEvents,
            StackScrollState finalState) {
        int numEvents = mAnimationEvents.size();
        if (numEvents == 0) {
            // No events, so we don't perform any animation
            return;
        }
        long lastEventStartTime = mAnimationEvents.get(numEvents - 1).eventStartTime;
        long eventEnd = lastEventStartTime + ANIMATION_DURATION;
        long currentTime = AnimationUtils.currentAnimationTimeMillis();
        long newDuration = eventEnd - currentTime;
        if (newDuration <= 0) {
            // last event is long before this, so we don't do anything
            return;
        }
        initializeAddedViewStates(mAnimationEvents, finalState);
        int childCount = mHostLayout.getChildCount();
        boolean isFirstAnimatingView = true;
        for (int i = 0; i < childCount; i++) {
            final ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);
            StackScrollState.ViewState viewState = finalState.getViewStateForView(child);
            if (viewState == null) {
                continue;
            }
            int childVisibility = child.getVisibility();
            boolean wasVisible = childVisibility == View.VISIBLE;
            final float alpha = viewState.alpha;
            if (!wasVisible && alpha != 0 && !viewState.gone) {
                child.setVisibility(View.VISIBLE);
            }

            startPropertyAnimation(newDuration, isFirstAnimatingView, child, viewState, alpha);

            // TODO: animate clipBounds
            child.setClipBounds(null);
            int currentHeigth = child.getActualHeight();
            if (viewState.height != currentHeigth) {
                startHeightAnimation(newDuration, child, viewState, currentHeigth);
            }
            isFirstAnimatingView = false;
        }
        mAnimationIsRunning = true;
    }

    private void startPropertyAnimation(long newDuration, final boolean hasFinishAction,
            final ExpandableView child, StackScrollState.ViewState viewState, final float alpha) {
        child.animate().setInterpolator(mFastOutSlowInInterpolator)
                .alpha(alpha)
                .translationY(viewState.yTranslation)
                .translationZ(viewState.zTranslation)
                .setDuration(newDuration)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mAnimationIsRunning = false;
                        if (hasFinishAction) {
                            mHandledEvents.clear();
                            mHostLayout.onChildAnimationFinished();
                        }
                        if (alpha == 0) {
                            child.setVisibility(View.INVISIBLE);
                        }
                    }
                });
    }

    private void startHeightAnimation(long newDuration, final ExpandableView child,
            StackScrollState.ViewState viewState, int currentHeigth) {
        ValueAnimator heightAnimator = ValueAnimator.ofInt(currentHeigth, viewState.height);
        heightAnimator.setInterpolator(mFastOutSlowInInterpolator);
        heightAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                child.setActualHeight((int) animation.getAnimatedValue());
            }
        });
        heightAnimator.setDuration(newDuration);
        heightAnimator.start();
    }

    /**
     * Initialize the viewStates for the added children
     *
     * @param animationEvents the animation events who contain the added children
     * @param finalState the final state to animate to
     */
    private void initializeAddedViewStates(
            ArrayList<NotificationStackScrollLayout.AnimationEvent> animationEvents,
            StackScrollState finalState) {
        for (NotificationStackScrollLayout.AnimationEvent event: animationEvents) {
            View changingView = event.changingView;
            if (event.animationType == NotificationStackScrollLayout.AnimationEvent
                    .ANIMATION_TYPE_ADD && !mHandledEvents.contains(event)) {

                // This item is added, initialize it's properties.
                StackScrollState.ViewState viewState = finalState.getViewStateForView(changingView);
                if (viewState == null) {
                    // The position for this child was never generated, let's continue.
                    continue;
                }
                changingView.setAlpha(0);
                changingView.setTranslationY(viewState.yTranslation);
                changingView.setTranslationZ(viewState.zTranslation);
                mHandledEvents.add(event);
            }
        }
    }
}
