/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.R;
public class KeyguardWidgetRegion extends LinearLayout implements PagedView.PageSwitchListener {
    KeyguardGlowStripView mLeftStrip;
    KeyguardGlowStripView mRightStrip;
    KeyguardWidgetPager mPager;
    private int mPage = 0;
    private Callbacks mCallbacks;

    private static final long CUSTOM_WIDGET_USER_ACTIVITY_TIMEOUT = 30000;

    public KeyguardWidgetRegion(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetRegion(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetRegion(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLeftStrip = (KeyguardGlowStripView) findViewById(R.id.left_strip);
        mRightStrip = (KeyguardGlowStripView) findViewById(R.id.right_strip);
        mPager = (KeyguardWidgetPager) findViewById(R.id.app_widget_container);
        mPager.setPageSwitchListener(this);

        setSoundEffectsEnabled(false);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showPagingFeedback();
            }
        });
    }

    public void showPagingFeedback() {
        if (true || (mPage < mPager.getPageCount() - 1)) {
            mLeftStrip.makeEmGo();
        }
        if (true || (mPage > 0)) {
            mRightStrip.makeEmGo();
        }
    }

    @Override
    public void onPageSwitch(View newPage, int newPageIndex) {
        boolean showingStatusWidget = false;
        if (newPage instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) newPage;
            if (vg.getChildAt(0) instanceof KeyguardStatusView) {
                showingStatusWidget = true;
            }
        }

        // Disable the status bar clock if we're showing the default status widget
        if (showingStatusWidget) {
            setSystemUiVisibility(getSystemUiVisibility() | View.STATUS_BAR_DISABLE_CLOCK);
        } else {
            setSystemUiVisibility(getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_CLOCK);
        }

        // Extend the display timeout if the user switches pages
        if (mPage != newPageIndex) {
            mPage = newPageIndex;
            if (mCallbacks != null) {
                mCallbacks.onUserActivityTimeoutChanged();
                mCallbacks.userActivity();
            }
        }
    }

    public long getUserActivityTimeout() {
        View page = mPager.getPageAt(mPage);
        if (page instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) page;
            View view = vg.getChildAt(0);
            if (!(view instanceof KeyguardStatusView)
                    && !(view instanceof KeyguardMultiUserSelectorView)) {
                return CUSTOM_WIDGET_USER_ACTIVITY_TIMEOUT;
            }
        }
        return -1;
    }

    public void setCallbacks(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    public interface Callbacks {
        public void userActivity();
        public void onUserActivityTimeoutChanged();
    }
}
