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

import java.util.HashMap;
import java.util.Map;

/**
 * A state of a {@link com.android.systemui.statusbar.stack.NotificationStackScrollLayout} which
 * can be applied to a viewGroup.
 */
public class StackScrollState {

    private static final String CHILD_NOT_FOUND_TAG = "StackScrollStateNoSuchChild";

    private final ViewGroup mHostView;
    private Map<View, ViewState> mStateMap;
    private int mScrollY;

    public int getScrollY() {
        return mScrollY;
    }

    public void setScrollY(int scrollY) {
        this.mScrollY = scrollY;
    }

    public StackScrollState(ViewGroup hostView) {
        mHostView = hostView;
        mStateMap = new HashMap<View, ViewState>(mHostView.getChildCount());
    }

    public ViewGroup getHostView() {
        return mHostView;
    }

    public void resetViewStates() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = mHostView.getChildAt(i);
            ViewState viewState = mStateMap.get(child);
            if (viewState == null) {
                viewState = new ViewState();
                mStateMap.put(child, viewState);
            }
            // initialize with the default values of the view
            viewState.height = child.getHeight();
            viewState.alpha = 1.0f;
        }
    }


    public ViewState getViewStateForView(View requestedView) {
        return mStateMap.get(requestedView);
    }

    /**
     * Apply the properties saved in {@link #mStateMap} to the children of the {@link #mHostView}.
     * The properties are only applied if they effectively changed.
     */
    public void apply() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = mHostView.getChildAt(i);
            ViewState state = mStateMap.get(child);
            if (state != null) {
                float alpha = child.getAlpha();
                float yTranslation = child.getTranslationY();
                float zTranslation = child.getTranslationZ();
                int height = child.getHeight();
                float newAlpha = state.alpha;
                float newYTranslation = state.yTranslation;
                float newZTranslation = state.zTranslation;
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

                // apply height
                if (height != newHeight) {
                    applyNewHeight(child, newHeight);
                }
            } else {
                Log.wtf(CHILD_NOT_FOUND_TAG, "No child state was found when applying this state " +
                        "to the hostView");
            }
        }
    }

    private void applyNewHeight(View child, int newHeight) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        lp.height = newHeight;
        child.setLayoutParams(lp);
    }


    public class ViewState {
        float alpha;
        float yTranslation;
        float zTranslation;
        int height;
    }
}
