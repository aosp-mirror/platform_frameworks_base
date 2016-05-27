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

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.DismissView;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;

import java.util.List;
import java.util.WeakHashMap;

/**
 * A state of a {@link com.android.systemui.statusbar.stack.NotificationStackScrollLayout} which
 * can be applied to a viewGroup.
 */
public class StackScrollState {

    private static final String CHILD_NOT_FOUND_TAG = "StackScrollStateNoSuchChild";

    private final ViewGroup mHostView;
    private WeakHashMap<ExpandableView, StackViewState> mStateMap;
    private final int mClearAllTopPadding;

    public StackScrollState(ViewGroup hostView) {
        mHostView = hostView;
        mStateMap = new WeakHashMap<>();
        mClearAllTopPadding = hostView.getContext().getResources().getDimensionPixelSize(
                R.dimen.clear_all_padding_top);
    }

    public ViewGroup getHostView() {
        return mHostView;
    }

    public void resetViewStates() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) mHostView.getChildAt(i);
            resetViewState(child);

            // handling reset for child notifications
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                List<ExpandableNotificationRow> children =
                        row.getNotificationChildren();
                if (row.isSummaryWithChildren() && children != null) {
                    for (ExpandableNotificationRow childRow : children) {
                        resetViewState(childRow);
                    }
                }
            }
        }
    }

    private void resetViewState(ExpandableView view) {
        StackViewState viewState = mStateMap.get(view);
        if (viewState == null) {
            viewState = new StackViewState();
            mStateMap.put(view, viewState);
        }
        // initialize with the default values of the view
        viewState.height = view.getIntrinsicHeight();
        viewState.gone = view.getVisibility() == View.GONE;
        viewState.alpha = 1f;
        viewState.shadowAlpha = 1f;
        viewState.notGoneIndex = -1;
        viewState.hidden = false;
    }

    public StackViewState getViewStateForView(View requestedView) {
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
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) mHostView.getChildAt(i);
            StackViewState state = mStateMap.get(child);
            if (!applyState(child, state)) {
                continue;
            }
            if (child instanceof DismissView) {
                DismissView dismissView = (DismissView) child;
                boolean visible = state.clipTopAmount < mClearAllTopPadding;
                dismissView.performVisibilityAnimation(visible && !dismissView.willBeGone());
            } else if (child instanceof EmptyShadeView) {
                EmptyShadeView emptyShadeView = (EmptyShadeView) child;
                boolean visible = state.clipTopAmount <= 0;
                emptyShadeView.performVisibilityAnimation(
                        visible && !emptyShadeView.willBeGone());
            }
        }
    }

    /**
     * Applies a  {@link StackViewState} to an  {@link ExpandableView}.
     *
     * @return whether the state was applied correctly
     */
    public boolean applyState(ExpandableView view, StackViewState state) {
        if (state == null) {
            Log.wtf(CHILD_NOT_FOUND_TAG, "No child state was found when applying this state " +
                    "to the hostView");
            return false;
        }
        if (state.gone) {
            return false;
        }
        applyViewState(view, state);

        int height = view.getActualHeight();
        int newHeight = state.height;

        // apply height
        if (height != newHeight) {
            view.setActualHeight(newHeight, false /* notifyListeners */);
        }

        float shadowAlpha = view.getShadowAlpha();
        float newShadowAlpha = state.shadowAlpha;

        // apply shadowAlpha
        if (shadowAlpha != newShadowAlpha) {
            view.setShadowAlpha(newShadowAlpha);
        }

        // apply dimming
        view.setDimmed(state.dimmed, false /* animate */);

        // apply hiding sensitive
        view.setHideSensitive(
                state.hideSensitive, false /* animated */, 0 /* delay */, 0 /* duration */);

        // apply speed bump state
        view.setBelowSpeedBump(state.belowSpeedBump);

        // apply dark
        view.setDark(state.dark, false /* animate */, 0 /* delay */);

        // apply clipping
        float oldClipTopAmount = view.getClipTopAmount();
        if (oldClipTopAmount != state.clipTopAmount) {
            view.setClipTopAmount(state.clipTopAmount);
        }
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (state.isBottomClipped) {
                row.setClipToActualHeight(true);
            }
            row.applyChildrenState(this);
        }
        return true;
    }

    /**
     * Applies a  {@link ViewState} to a normal view.
     */
    public void applyViewState(View view, ViewState state) {
        float alpha = view.getAlpha();
        float yTranslation = view.getTranslationY();
        float xTranslation = view.getTranslationX();
        float zTranslation = view.getTranslationZ();
        float newAlpha = state.alpha;
        float newYTranslation = state.yTranslation;
        float newZTranslation = state.zTranslation;
        boolean becomesInvisible = newAlpha == 0.0f || state.hidden;
        if (alpha != newAlpha && xTranslation == 0) {
            // apply layer type
            boolean becomesFullyVisible = newAlpha == 1.0f;
            boolean newLayerTypeIsHardware = !becomesInvisible && !becomesFullyVisible
                    && view.hasOverlappingRendering();
            int layerType = view.getLayerType();
            int newLayerType = newLayerTypeIsHardware
                    ? View.LAYER_TYPE_HARDWARE
                    : View.LAYER_TYPE_NONE;
            if (layerType != newLayerType) {
                view.setLayerType(newLayerType, null);
            }

            // apply alpha
            view.setAlpha(newAlpha);
        }

        // apply visibility
        int oldVisibility = view.getVisibility();
        int newVisibility = becomesInvisible ? View.INVISIBLE : View.VISIBLE;
        if (newVisibility != oldVisibility) {
            if (!(view instanceof ExpandableView) || !((ExpandableView) view).willBeGone()) {
                // We don't want views to change visibility when they are animating to GONE
                view.setVisibility(newVisibility);
            }
        }

        // apply yTranslation
        if (yTranslation != newYTranslation) {
            view.setTranslationY(newYTranslation);
        }

        // apply zTranslation
        if (zTranslation != newZTranslation) {
            view.setTranslationZ(newZTranslation);
        }
    }
}
