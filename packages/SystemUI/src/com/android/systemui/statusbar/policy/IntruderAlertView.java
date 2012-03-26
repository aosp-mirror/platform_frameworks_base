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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.BaseStatusBar;

import java.util.HashMap;

public class IntruderAlertView extends LinearLayout implements SwipeHelper.Callback {
    private static final String TAG = "IntruderAlertView";
    private static final boolean DEBUG = false;

    Rect mTmpRect = new Rect();

    private SwipeHelper mSwipeHelper;
    
    BaseStatusBar mBar;
    private ViewGroup mContentHolder;
    
    private RemoteViews mIntruderRemoteViews;
    private OnClickListener mOnClickListener;

    public IntruderAlertView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IntruderAlertView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.VERTICAL);
    }

    @Override
    public void onAttachedToWindow() {
        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, pagingTouchSlop);
        
        mContentHolder = (ViewGroup) findViewById(R.id.contentHolder);
        if (mIntruderRemoteViews != null) {
            // whoops, we're on already!
            applyIntruderContent(mIntruderRemoteViews, mOnClickListener);
        }
    }
    
    public void setBar(BaseStatusBar bar) {
        mBar = bar;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        return mSwipeHelper.onInterceptTouchEvent(ev) ||
            super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mSwipeHelper.onTouchEvent(ev) ||
            super.onTouchEvent(ev);
    }

    public boolean canChildBeDismissed(View v) {
        return true;
    }

    public void onChildDismissed(View v) {
        Slog.v(TAG, "User swiped intruder to dismiss");
        mBar.dismissIntruder();
    }

    public void onBeginDrag(View v) {
    }

    public void onDragCancelled(View v) {
        mContentHolder.setAlpha(1f); // sometimes this isn't quite reset
    }

    public View getChildAtPosition(MotionEvent ev) {
        return mContentHolder;
    }

    public View getChildContentView(View v) {
        return v;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    @Override
    public void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        if (DEBUG) {
            //Slog.d(TAG, "onDraw: canvas height: " + c.getHeight() + "px; measured height: "
            //        + getMeasuredHeight() + "px");
            c.save();
            c.clipRect(6, 6, c.getWidth() - 6, getMeasuredHeight() - 6,
                    android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(0xFFcc00cc);
            c.restore();
        }
    }

    public void applyIntruderContent(RemoteViews intruderView, OnClickListener listener) {
        if (DEBUG) {
            Slog.v(TAG, "applyIntruderContent: view=" + intruderView + " listener=" + listener);
        }
        mIntruderRemoteViews = intruderView;
        mOnClickListener = listener;
        if (mContentHolder == null) { 
            // too soon!
            return;
        }
        mContentHolder.setX(0);
        mContentHolder.setVisibility(View.VISIBLE);
        mContentHolder.setAlpha(1f);
        mContentHolder.removeAllViews();
        final View content = intruderView.apply(getContext(), mContentHolder);
        if (listener != null) {
            content.setOnClickListener(listener);
            
            //content.setBackgroundResource(R.drawable.intruder_row_bg);
            Drawable bg = getResources().getDrawable(R.drawable.intruder_row_bg);
            if (bg == null) {
                Log.e(TAG, String.format("Can't find background drawable id=0x%08x", R.drawable.intruder_row_bg));
            } else {
                content.setBackgroundDrawable(bg);
            }
        }
        mContentHolder.addView(content);
        
    }
}