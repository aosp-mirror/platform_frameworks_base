/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;

import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;

public class IconMerger extends LinearLayout {
    private static final String TAG = "IconMerger";

    private int mIconSize;
    private StatusBarIconView mMoreView;
    private StatusBarIcon mMoreIcon = new StatusBarIcon(null, R.drawable.stat_notify_more, 0, 0,
            null);

    public IconMerger(Context context, AttributeSet attrs) {
        super(context, attrs);

        mIconSize = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size);

        mMoreView = new StatusBarIconView(context, "more", null);
        mMoreView.set(mMoreIcon);
        addView(mMoreView, 0, new LinearLayout.LayoutParams(mIconSize, mIconSize));
    }

    public void addView(StatusBarIconView v, int index) {
        if (index == 0) {
            throw new RuntimeException("Attempt to put view before the more view: " + v);
        }
        addView(v, index, new LinearLayout.LayoutParams(mIconSize, mIconSize));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        final int maxWidth = r - l;
        final int N = getChildCount();
        int i;

        // get the rightmost one, and see if we even need to do anything
        int fitRight = -1;
        for (i=N-1; i>=0; i--) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                fitRight = child.getRight();
                break;
            }
        }

        // find the first visible one that isn't the more icon
        final StatusBarIconView moreView = mMoreView;
        int fitLeft = -1;
        int startIndex = -1;
        for (i=0; i<N; i++) {
            final View child = getChildAt(i);
            if (child == moreView) {
                startIndex = i+1;
            }
            else if (child.getVisibility() != GONE) {
                fitLeft = child.getLeft();
                break;
            }
        }

        if (moreView == null || startIndex < 0) {
            return;
            /*
            throw new RuntimeException("Status Bar / IconMerger moreView == " + moreView
                    + " startIndex=" + startIndex);
            */
        }
        
        // if it fits without the more icon, then hide the more icon and update fitLeft
        // so everything gets pushed left
        int adjust = 0;
        if (fitRight - fitLeft <= maxWidth) {
            adjust = fitLeft - moreView.getLeft();
            fitLeft -= adjust;
            fitRight -= adjust;
            moreView.layout(0, moreView.getTop(), 0, moreView.getBottom());
        }
        int extra = fitRight - r;
        int shift = -1;

        int breakingPoint = fitLeft + extra + adjust;
        int number = 0;
        for (i=startIndex; i<N; i++) {
            final StatusBarIconView child = (StatusBarIconView)getChildAt(i);
            if (child.getVisibility() != GONE) {
                int childLeft = child.getLeft();
                int childRight = child.getRight();
                if (childLeft < breakingPoint) {
                    // hide this one
                    child.layout(0, child.getTop(), 0, child.getBottom());
                    int n = child.getStatusBarIcon().number;
                    if (n == 0) {
                        number += 1;
                    } else if (n > 0) {
                        number += n;
                    }
                } else {
                    // decide how much to shift by
                    if (shift < 0) {
                        shift = childLeft - fitLeft;
                    }
                    // shift this left by shift
                    child.layout(childLeft-shift, child.getTop(),
                                    childRight-shift, child.getBottom());
                }
            }
        }

        mMoreIcon.number = number;
        mMoreView.set(mMoreIcon);
    }
}
