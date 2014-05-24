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

import android.graphics.Outline;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.SpeedBumpView;

import java.util.HashMap;
import java.util.Map;

/**
 * A state of a {@link com.android.systemui.statusbar.stack.NotificationStackScrollLayout} which
 * can be applied to a viewGroup.
 */
public class StackScrollState {

    private static final String CHILD_NOT_FOUND_TAG = "StackScrollStateNoSuchChild";

    private final ViewGroup mHostView;
    private final int mRoundedRectCornerRadius;
    private Map<ExpandableView, ViewState> mStateMap;
    private final Rect mClipRect = new Rect();

    public StackScrollState(ViewGroup hostView) {
        mHostView = hostView;
        mStateMap = new HashMap<ExpandableView, ViewState>();
        mRoundedRectCornerRadius = mHostView.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_quantum_rounded_rect_radius);
    }

    public ViewGroup getHostView() {
        return mHostView;
    }

    public void resetViewStates() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) mHostView.getChildAt(i);
            ViewState viewState = mStateMap.get(child);
            if (viewState == null) {
                viewState = new ViewState();
                mStateMap.put(child, viewState);
            }
            // initialize with the default values of the view
            viewState.height = child.getIntrinsicHeight();
            viewState.gone = child.getVisibility() == View.GONE;
            viewState.alpha = 1;
            viewState.notGoneIndex = -1;
        }
    }

    public ViewState getViewStateForView(View requestedView) {
        return mStateMap.get(requestedView);
    }

    public void removeViewStateForView(View child) {
        mStateMap.remove(child);
    }

    /**
     * Apply the properties saved in {@link #mStateMap} to the children of the {@link #mHostView}.
     * The properties are only applied if they effectively changed.
     */
    public void apply() {
        int numChildren = mHostView.getChildCount();
        float previousNotificationEnd = 0;
        float previousNotificationStart = 0;
        boolean previousNotificationIsSwiped = false;
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) mHostView.getChildAt(i);
            ViewState state = mStateMap.get(child);
            if (state == null) {
                Log.wtf(CHILD_NOT_FOUND_TAG, "No child state was found when applying this state " +
                        "to the hostView");
                continue;
            }
            if (!state.gone) {
                float alpha = child.getAlpha();
                float yTranslation = child.getTranslationY();
                float zTranslation = child.getTranslationZ();
                float scale = child.getScaleX();
                int height = child.getActualHeight();
                float newAlpha = state.alpha;
                float newYTranslation = state.yTranslation;
                float newZTranslation = state.zTranslation;
                float newScale = state.scale;
                int newHeight = state.height;
                boolean becomesInvisible = newAlpha == 0.0f;
                if (alpha != newAlpha) {
                    // apply layer type
                    boolean becomesFullyVisible = newAlpha == 1.0f;
                    boolean newLayerTypeIsHardware = !becomesInvisible && !becomesFullyVisible;
                    int layerType = child.getLayerType();
                    int newLayerType = newLayerTypeIsHardware
                            ? View.LAYER_TYPE_HARDWARE
                            : View.LAYER_TYPE_NONE;
                    if (layerType != newLayerType) {
                        child.setLayerType(newLayerType, null);
                    }

                    // apply alpha
                    if (!becomesInvisible) {
                        child.setAlpha(newAlpha);
                    }
                }

                // apply visibility
                int oldVisibility = child.getVisibility();
                int newVisibility = becomesInvisible ? View.INVISIBLE : View.VISIBLE;
                if (newVisibility != oldVisibility) {
                    child.setVisibility(newVisibility);
                }

                // apply yTranslation
                if (yTranslation != newYTranslation) {
                    child.setTranslationY(newYTranslation);
                }

                // apply zTranslation
                if (zTranslation != newZTranslation) {
                    child.setTranslationZ(newZTranslation);
                }

                // apply scale
                if (scale != newScale) {
                    child.setScaleX(newScale);
                    child.setScaleY(newScale);
                }

                // apply height
                if (height != newHeight) {
                    child.setActualHeight(newHeight, false /* notifyListeners */);
                }

                // apply dimming
                child.setDimmed(state.dimmed, false /* animate */);

                // apply clipping and shadow
                float newNotificationEnd = newYTranslation + newHeight;

                // In the unlocked shade we have to clip a little bit higher because of the rounded
                // corners of the notifications.
                float clippingCorrection = state.dimmed ? 0 : mRoundedRectCornerRadius;

                // When the previous notification is swiped, we don't clip the content to the
                // bottom of it.
                float clipHeight = previousNotificationIsSwiped
                        ? newHeight
                        : newNotificationEnd - (previousNotificationEnd - clippingCorrection);

                updateChildClippingAndBackground(child, newHeight,
                        clipHeight,
                        (int) (newHeight - (previousNotificationStart - newYTranslation)));

                if (!child.isTransparent()) {
                    // Only update the previous values if we are not transparent,
                    // otherwise we would clip to a transparent view.
                    previousNotificationStart = newYTranslation + child.getClipTopAmount();
                    previousNotificationEnd = newNotificationEnd;
                    previousNotificationIsSwiped = child.getTranslationX() != 0;
                }

                if(child instanceof SpeedBumpView) {
                    performSpeedBumpAnimation(i, (SpeedBumpView) child, newNotificationEnd,
                            newYTranslation);
                }
            }
        }
    }

    private void performSpeedBumpAnimation(int i, SpeedBumpView speedBump, float speedBumpEnd,
            float speedBumpStart) {
        View nextChild = getNextChildNotGone(i);
        if (nextChild != null) {
            ViewState nextState = getViewStateForView(nextChild);
            boolean startIsAboveNext = nextState.yTranslation > speedBumpStart;
            speedBump.animateDivider(startIsAboveNext, null /* onFinishedRunnable */);

            // handle expanded case
            if (speedBump.isExpanded()) {
                boolean endIsAboveNext = nextState.yTranslation > speedBumpEnd;
                speedBump.animateExplanationText(endIsAboveNext);
            }

        }
    }

    private View getNextChildNotGone(int childIndex) {
        int childCount = mHostView.getChildCount();
        for (int i = childIndex + 1; i < childCount; i++) {
            View child = mHostView.getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                return child;
            }
        }
        return null;
    }

    /**
     * Updates the shadow outline and the clipping for a view.
     *
     * @param child the view to update
     * @param realHeight the currently applied height of the view
     * @param clipHeight the desired clip height, the rest of the view will be clipped from the top
     * @param backgroundHeight the desired background height. The shadows of the view will be
     *                         based on this height and the content will be clipped from the top
     */
    private void updateChildClippingAndBackground(ExpandableView child, int realHeight,
            float clipHeight, int backgroundHeight) {
        if (realHeight > clipHeight) {
            updateChildClip(child, realHeight, clipHeight);
        } else {
            child.setClipBounds(null);
        }
        if (realHeight > backgroundHeight) {
            child.setClipTopAmount(realHeight - backgroundHeight);
        } else {
            child.setClipTopAmount(0);
        }
    }

    /**
     * Updates the clipping of a view
     *
     * @param child the view to update
     * @param height the currently applied height of the view
     * @param clipHeight the desired clip height, the rest of the view will be clipped from the top
     */
    private void updateChildClip(View child, int height, float clipHeight) {
        int clipInset = (int) (height - clipHeight);
        mClipRect.set(0,
                clipInset,
                child.getWidth(),
                height);
        child.setClipBounds(mClipRect);
    }

    public static class ViewState {

        // These are flags such that we can create masks for filtering.

        public static final int LOCATION_UNKNOWN = 0x00;
        public static final int LOCATION_FIRST_CARD = 0x01;
        public static final int LOCATION_TOP_STACK_HIDDEN = 0x02;
        public static final int LOCATION_TOP_STACK_PEEKING = 0x04;
        public static final int LOCATION_MAIN_AREA = 0x08;
        public static final int LOCATION_BOTTOM_STACK_PEEKING = 0x10;
        public static final int LOCATION_BOTTOM_STACK_HIDDEN = 0x20;

        float alpha;
        float yTranslation;
        float zTranslation;
        int height;
        boolean gone;
        float scale;
        boolean dimmed;

        /**
         * The index of the view, only accounting for views not equal to GONE
         */
        int notGoneIndex;

        /**
         * The location this view is currently rendered at.
         *
         * <p>See <code>LOCATION_</code> flags.</p>
         */
        int location;
    }
}
