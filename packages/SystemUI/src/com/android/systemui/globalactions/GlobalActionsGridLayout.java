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

import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.MultiListLayout;
import com.android.systemui.util.leak.RotationUtils;

import java.util.ArrayList;
import java.util.Locale;

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

    /**
     * Sets the number of items expected to be rendered in the list container. This allows the
     * layout to correctly determine which parent containers will be used for items before they have
     * beenadded to the layout.
     * @param count The number of items expected.
     */
    public void setExpectedListItemCount(int count) {
        getListView().setExpectedCount(count);
    }

    @Override
    public void onUpdateList() {
        removeAllItems();
        ArrayList<GlobalActionsDialog.Action> separatedActions =
                mAdapter.getSeparatedItems(mSeparated);
        ArrayList<GlobalActionsDialog.Action> listActions = mAdapter.getListItems(mSeparated);
        setExpectedListItemCount(listActions.size());
        int rotation = RotationUtils.getRotation(mContext);

        boolean reverse = false; // should we add items to parents in the reverse order?
        if (rotation == RotationUtils.ROTATION_NONE
                || rotation == RotationUtils.ROTATION_SEASCAPE) {
            reverse = !reverse; // if we're in portrait or seascape, reverse items
        }
        if (TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                == View.LAYOUT_DIRECTION_RTL) {
            reverse = !reverse; // if we're in an RTL language, reverse items (again)
        }

        for (int i = 0; i < mAdapter.getCount(); i++) {
            Object action = mAdapter.getItem(i);
            int separatedIndex = separatedActions.indexOf(action);
            ViewGroup parent;
            if (separatedIndex != -1) {
                parent = getParentView(true, separatedIndex, rotation);
            } else {
                int listIndex = listActions.indexOf(action);
                parent = getParentView(false, listIndex, rotation);
            }
            View v = mAdapter.getView(i, null, parent);
            final int pos = i;
            v.setOnClickListener(view -> mAdapter.onClickItem(pos));
            v.setOnLongClickListener(view -> mAdapter.onLongClickItem(pos));
            if (reverse) {
                parent.addView(v, 0); // reverse order of items
            } else {
                parent.addView(v);
            }
        }
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

    public ViewGroup getParentView(boolean separated, int index, int rotation) {
        if (separated) {
            return getSeparatedView();
        } else {
            switch (rotation) {
                case RotationUtils.ROTATION_LANDSCAPE:
                    return getListView().getParentView(index, false, true);
                case RotationUtils.ROTATION_SEASCAPE:
                    return getListView().getParentView(index, true, true);
                default:
                    return getListView().getParentView(index, false, false);
            }
        }
    }

    /**
     * Not used in this implementation of the Global Actions Menu, but necessary for some others.
     */
    @Override
    public void setDivisionView(View v) {
        // do nothing
    }
}
