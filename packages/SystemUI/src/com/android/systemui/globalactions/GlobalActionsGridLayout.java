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
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.MultiListLayout;

/**
 * Grid-based implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsGridLayout extends MultiListLayout {

    boolean mBackgroundsSet;

    public GlobalActionsGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void setBackgrounds() {
        HardwareBgDrawable listBackground  = new HardwareBgDrawable(true, true, getContext());
        HardwareBgDrawable separatedViewBackground = new HardwareBgDrawable(true, true,
                getContext());
        getListView().setBackground(listBackground);
        getSeparatedView().setBackground(separatedViewBackground);
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

    @Override
    public void setExpectedListItemCount(int count) {
        mExpectedListItemCount = count;
        getListView().setExpectedCount(count);
    }

    @Override
    protected ViewGroup getSeparatedView() {
        return findViewById(com.android.systemui.R.id.separated_button);
    }

    @Override
    protected ListGridLayout getListView() {
        return findViewById(android.R.id.list);
    }

    @Override
    public void removeAllItems() {
        ViewGroup separatedList = getSeparatedView();
        ListGridLayout list = getListView();
        if (separatedList != null) {
            separatedList.removeAllViews();
        }
        if (list != null) {
            list.removeAllItems();
        }
    }

    @Override
    public ViewGroup getParentView(boolean separated, int index) {
        if (separated) {
            return getSeparatedView();
        } else {
            return getListView().getParentView(index);
        }
    }

    /**
     * Not used in this implementation of the Global Actions Menu, but necessary for some others.
     */
    @Override
    public void setDivisionView(View v) {

    }
}
