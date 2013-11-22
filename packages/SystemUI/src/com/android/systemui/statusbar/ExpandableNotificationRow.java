/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class ExpandableNotificationRow extends FrameLayout {
    private int mRowHeight;

    /** does this row contain layouts that can adapt to row expansion */
    private boolean mExpandable;
    /** has the user manually expanded this row */
    private boolean mUserExpanded;
    /** is the user touching this row */
    private boolean mUserLocked;

    public ExpandableNotificationRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public int getRowHeight() {
        return mRowHeight;
    }

    public void setRowHeight(int rowHeight) {
        this.mRowHeight = rowHeight;
    }

    public boolean isExpandable() {
        return mExpandable;
    }

    public void setExpandable(boolean expandable) {
        mExpandable = expandable;
    }

    public boolean isUserExpanded() {
        return mUserExpanded;
    }

    public void setUserExpanded(boolean userExpanded) {
        mUserExpanded = userExpanded;
    }

    public boolean isUserLocked() {
        return mUserLocked;
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
    }

    public void setExpanded(boolean expand) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (expand && mExpandable) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            lp.height = mRowHeight;
        }
        setLayoutParams(lp);
    }
}
