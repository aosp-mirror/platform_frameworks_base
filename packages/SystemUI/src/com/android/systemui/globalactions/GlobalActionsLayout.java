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

package com.android.systemui.globalactions;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.MultiListLayout;
import com.android.systemui.res.R;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Locale;

/**
 * Grid-based implementation of the button layout created by the global actions dialog.
 */
public abstract class GlobalActionsLayout extends MultiListLayout {

    boolean mBackgroundsSet;

    public GlobalActionsLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void setBackgrounds() {
        ViewGroup listView = getListView();
        int listBgColor = getResources().getColor(
                R.color.global_actions_grid_background, null);
        HardwareBgDrawable listBackground = getBackgroundDrawable(listBgColor);
        if (listBackground != null) {
            listView.setBackground(listBackground);
        }

        ViewGroup separatedView = getSeparatedView();

        if (separatedView != null) {
            int separatedBgColor = getResources().getColor(
                    R.color.global_actions_separated_background, null);
            HardwareBgDrawable separatedBackground = getBackgroundDrawable(separatedBgColor);
            if (separatedBackground != null) {
                getSeparatedView().setBackground(separatedBackground);
            }
        }
    }

    protected HardwareBgDrawable getBackgroundDrawable(int backgroundColor) {
        HardwareBgDrawable background = new HardwareBgDrawable(true, true, getContext());
        background.setTint(backgroundColor);
        return background;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // backgrounds set only once, the first time onMeasure is called after inflation
        if (getListView() != null && !mBackgroundsSet) {
            setBackgrounds();
            mBackgroundsSet = true;
        }
    }

    protected void addToListView(View v, boolean reverse) {
        if (reverse) {
            getListView().addView(v, 0);
        } else {
            getListView().addView(v);
        }
    }

    protected void addToSeparatedView(View v, boolean reverse) {
        ViewGroup separated = getSeparatedView();
        if (separated != null) {
            if (reverse) {
                separated.addView(v, 0);
            } else {
                separated.addView(v);
            }
        } else {
            // if no separated view exists, just use the list view
            addToListView(v, reverse);
        }
    }

    @VisibleForTesting
    protected int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }

    @VisibleForTesting
    protected int getCurrentRotation() {
        return RotationUtils.getRotation(mContext);
    }

    /**
     * Determines whether the ListGridLayout should reverse the ordering of items within sublists.
     * Used for RTL languages to ensure that items appear in the same positions, without having to
     * override layoutDirection, which breaks Talkback ordering.
     */
    protected abstract boolean shouldReverseListItems();

    @Override
    public void onUpdateList() {
        super.onUpdateList();

        ViewGroup separatedView = getSeparatedView();
        ViewGroup listView = getListView();

        for (int i = 0; i < mAdapter.getCount(); i++) {
            // generate the view item
            View v;
            boolean separated = mAdapter.shouldBeSeparated(i);
            if (separated) {
                v = mAdapter.getView(i, null, separatedView);
            } else {
                v = mAdapter.getView(i, null, listView);
            }
            if (separated) {
                addToSeparatedView(v, false);
            } else {
                addToListView(v, shouldReverseListItems());
            }
        }
    }

    @Override
    protected ViewGroup getSeparatedView() {
        return findViewById(R.id.separated_button);
    }

    @Override
    protected ViewGroup getListView() {
        return findViewById(android.R.id.list);
    }

    protected View getWrapper() {
        return getChildAt(0);
    }

    /**
     * Not used in this implementation of the Global Actions Menu, but necessary for some others.
     */
    @Override
    public void setDivisionView(View v) {
        // do nothing
    }
}
