/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.qs;

import android.content.Context;
import android.support.v4.widget.NestedScrollView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.LinearLayout;

import com.android.systemui.R;

/**
 * Quick setting scroll view containing the brightness slider and the QS tiles.
 *
 * <p>Call {@link #shouldIntercept(MotionEvent)} from parent views'
 * {@link #onInterceptTouchEvent(MotionEvent)} method to determine whether this view should
 * consume the touch event.
 */
public class QSScrollLayout extends NestedScrollView {
    private final int mTouchSlop;
    private final int mFooterHeight;
    private int mLastMotionY;

    public QSScrollLayout(Context context, View... children) {
        super(context);
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        mFooterHeight = getResources().getDimensionPixelSize(R.dimen.qs_footer_height);
        LinearLayout linearLayout = new LinearLayout(mContext);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        for (View view : children) {
            linearLayout.addView(view);
        }
        addView(linearLayout);
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (canScrollVertically(1) || canScrollVertically(-1)) {
            return super.onInterceptTouchEvent(ev);
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (canScrollVertically(1) || canScrollVertically(-1)) {
            return super.onTouchEvent(ev);
        }
        return false;
    }

    public boolean shouldIntercept(MotionEvent ev) {
        if (ev.getY() > (getBottom() - mFooterHeight)) {
            // Do not intercept touches that are below the divider between QS and the footer.
            return false;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastMotionY = (int) ev.getY();
        } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Do not allow NotificationPanelView to intercept touch events when this
            // view can be scrolled down.
            if (mLastMotionY >= 0 && Math.abs(ev.getY() - mLastMotionY) > mTouchSlop
                    && canScrollVertically(1)) {
                requestParentDisallowInterceptTouchEvent(true);
                mLastMotionY = (int) ev.getY();
                return true;
            }
        } else if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL
            || ev.getActionMasked() == MotionEvent.ACTION_UP) {
            mLastMotionY = -1;
            requestParentDisallowInterceptTouchEvent(false);
        }
        return false;
    }

    private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }
}
