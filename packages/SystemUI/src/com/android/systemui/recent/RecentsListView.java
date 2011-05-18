/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.recent;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListView;

import com.android.systemui.R;

public class RecentsListView extends ListView {
    private int mLastVisiblePosition;
    private RecentsCallback mCallback;

    public RecentsListView(Context context) {
        this(context, null);
    }

    public RecentsListView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        LayoutInflater inflater = (LayoutInflater)
                mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View footer = inflater.inflate(R.layout.status_bar_recent_panel_footer, this, false);
        setScrollbarFadingEnabled(true);
        addFooterView(footer, null, false);
        final int leftPadding = mContext.getResources()
            .getDimensionPixelOffset(R.dimen.status_bar_recents_thumbnail_left_margin);
        setOverScrollEffectPadding(leftPadding, 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Keep track of the last visible item in the list so we can restore it
        // to the bottom when the orientation changes.
        final int childCount = getChildCount();
        if (childCount > 0) {
            mLastVisiblePosition = getFirstVisiblePosition() + childCount - 1;
            View view = getChildAt(childCount - 1);
            final int distanceFromBottom = getHeight() - view.getTop();

            // This has to happen post-layout, so run it "in the future"
            post(new Runnable() {
                public void run() {
                    setSelectionFromTop(mLastVisiblePosition, getHeight() - distanceFromBottom);
                }
            });
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // scroll to bottom after reloading
        int count = getAdapter().getCount();
        mLastVisiblePosition = count - 1;
        if (visibility == View.VISIBLE && changedView == this) {
            post(new Runnable() {
                public void run() {
                    setSelection(mLastVisiblePosition);
                }
            });
        }
    }

    public void setCallback(RecentsCallback callback) {
        mCallback = callback;
    }

}
