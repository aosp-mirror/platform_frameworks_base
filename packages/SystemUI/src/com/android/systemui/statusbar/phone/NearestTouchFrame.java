/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Redirects touches that aren't handled by any child view to the nearest
 * clickable child. Only takes effect on <sw600dp.
 */
public class NearestTouchFrame extends FrameLayout {

    private final ArrayList<View> mClickableChildren = new ArrayList<>();
    private final boolean mIsActive;
    private final int[] mTmpInt = new int[2];
    private final int[] mOffset = new int[2];
    private View mTouchingChild;

    public NearestTouchFrame(Context context, AttributeSet attrs) {
        this(context, attrs, context.getResources().getConfiguration());
    }

    @VisibleForTesting
    NearestTouchFrame(Context context, AttributeSet attrs, Configuration c) {
        super(context, attrs);
        mIsActive = c.smallestScreenWidthDp < 600;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mClickableChildren.clear();
        addClickableChildren(this);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        getLocationInWindow(mOffset);
    }

    private void addClickableChildren(ViewGroup group) {
        final int N = group.getChildCount();
        for (int i = 0; i < N; i++) {
            View child = group.getChildAt(i);
            if (child.isClickable()) {
                mClickableChildren.add(child);
            } else if (child instanceof ViewGroup) {
                addClickableChildren((ViewGroup) child);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mIsActive) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mTouchingChild = findNearestChild(event);
            }
            if (mTouchingChild != null) {
                event.offsetLocation(mTouchingChild.getWidth() / 2 - event.getX(),
                        mTouchingChild.getHeight() / 2 - event.getY());
                return mTouchingChild.getVisibility() == VISIBLE
                        && mTouchingChild.dispatchTouchEvent(event);
            }
        }
        return super.onTouchEvent(event);
    }

    private View findNearestChild(MotionEvent event) {
        return mClickableChildren
                .stream()
                .filter(v -> v.isAttachedToWindow())
                .map(v -> new Pair<>(distance(v, event), v))
                .min(Comparator.comparingInt(f -> f.first))
                .get().second;
    }

    private int distance(View v, MotionEvent event) {
        v.getLocationInWindow(mTmpInt);
        int left = mTmpInt[0] - mOffset[0];
        int top = mTmpInt[1] - mOffset[1];
        int right = left + v.getWidth();
        int bottom = top + v.getHeight();

        int x = Math.min(Math.abs(left - (int) event.getX()),
                Math.abs((int) event.getX() - right));
        int y = Math.min(Math.abs(top - (int) event.getY()),
                Math.abs((int) event.getY() - bottom));

        return Math.max(x, y);
    }
}
