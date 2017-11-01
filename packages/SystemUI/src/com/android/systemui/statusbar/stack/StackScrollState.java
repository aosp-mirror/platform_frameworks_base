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
    private WeakHashMap<ExpandableView, ExpandableViewState> mStateMap;

    public StackScrollState(ViewGroup hostView) {
        mHostView = hostView;
        mStateMap = new WeakHashMap<>();
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
        ExpandableViewState viewState = mStateMap.get(view);
        if (viewState == null) {
            viewState = view.createNewViewState(this);
            mStateMap.put(view, viewState);
        }
        // initialize with the default values of the view
        viewState.height = view.getIntrinsicHeight();
        viewState.gone = view.getVisibility() == View.GONE;
        viewState.alpha = 1f;
        viewState.shadowAlpha = 1f;
        viewState.notGoneIndex = -1;
        viewState.xTranslation = view.getTranslationX();
        viewState.hidden = false;
        viewState.scaleX = view.getScaleX();
        viewState.scaleY = view.getScaleY();
        viewState.inShelf = false;
    }

    public ExpandableViewState getViewStateForView(View requestedView) {
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
            ExpandableViewState state = mStateMap.get(child);
            if (state == null) {
                Log.wtf(CHILD_NOT_FOUND_TAG, "No child state was found when applying this state " +
                        "to the hostView");
                continue;
            }
            if (state.gone) {
                continue;
            }
            state.applyToView(child);
        }
    }
}
