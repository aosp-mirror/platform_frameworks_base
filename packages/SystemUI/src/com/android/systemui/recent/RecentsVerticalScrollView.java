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

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.recent.RecentsPanelView.TaskDescriptionAdapter;

public class RecentsVerticalScrollView extends ScrollView implements SwipeHelper.Callback {
    private static final String TAG = RecentsPanelView.TAG;
    private static final boolean DEBUG = RecentsPanelView.DEBUG;
    private LinearLayout mLinearLayout;
    private TaskDescriptionAdapter mAdapter;
    private RecentsCallback mCallback;
    protected int mLastScrollPosition;
    private SwipeHelper mSwipeHelper;
    private RecentsScrollViewPerformanceHelper mPerformanceHelper;

    public RecentsVerticalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, pagingTouchSlop);

        mPerformanceHelper = RecentsScrollViewPerformanceHelper.create(context, attrs, this, true);
    }

    private int scrollPositionOfMostRecent() {
        return mLinearLayout.getHeight() - getHeight();
    }

    private void update() {
        mLinearLayout.removeAllViews();
        // Once we can clear the data associated with individual item views,
        // we can get rid of the removeAllViews() and the code below will
        // recycle them.
        for (int i = 0; i < mAdapter.getCount(); i++) {
            View old = null;
            if (i < mLinearLayout.getChildCount()) {
                old = mLinearLayout.getChildAt(i);
                old.setVisibility(View.VISIBLE);
            }
            final View view = mAdapter.getView(i, old, mLinearLayout);

            if (mPerformanceHelper != null) {
                mPerformanceHelper.addViewCallback(view);
            }

            if (old == null) {
                OnTouchListener noOpListener = new OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return true;
                    }
                };

                view.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        mCallback.dismiss();
                    }
                });
                // We don't want a click sound when we dimiss recents
                view.setSoundEffectsEnabled(false);

                OnClickListener launchAppListener = new OnClickListener() {
                    public void onClick(View v) {
                        mCallback.handleOnClick(view);
                    }
                };

                final View thumbnailView = view.findViewById(R.id.app_thumbnail);
                OnLongClickListener longClickListener = new OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        final View anchorView = view.findViewById(R.id.app_description);
                        mCallback.handleLongPress(view, anchorView, thumbnailView);
                        return true;
                    }
                };
                thumbnailView.setClickable(true);
                thumbnailView.setOnClickListener(launchAppListener);
                thumbnailView.setOnLongClickListener(longClickListener);

                // We don't want to dismiss recents if a user clicks on the app title
                // (we also don't want to launch the app either, though, because the
                // app title is a small target and doesn't have great click feedback)
                final View appTitle = view.findViewById(R.id.app_label);
                appTitle.setContentDescription(" ");
                appTitle.setOnTouchListener(noOpListener);
                final View calloutLine = view.findViewById(R.id.recents_callout_line);
                calloutLine.setOnTouchListener(noOpListener);
                mLinearLayout.addView(view);
            }
        }
        for (int i = mAdapter.getCount(); i < mLinearLayout.getChildCount(); i++) {
            mLinearLayout.getChildAt(i).setVisibility(View.GONE);
        }
        // Scroll to end after layout.
        post(new Runnable() {
            public void run() {
                mLastScrollPosition = scrollPositionOfMostRecent();
                scrollTo(0, mLastScrollPosition);
            }
        });
    }

    @Override
    public void removeViewInLayout(final View view) {
        dismissChild(view);
    }

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

    public void dismissChild(View v) {
        mSwipeHelper.dismissChild(v, 0);
    }

    public void onChildDismissed(View v) {
        mLinearLayout.removeView(v);
        mCallback.handleSwipe(v);
    }

    public void onBeginDrag(View v) {
        // We do this so the underlying ScrollView knows that it won't get
        // the chance to intercept events anymore
        requestDisallowInterceptTouchEvent(true);
        v.setActivated(true);
    }

    public void onDragCancelled(View v) {
        v.setActivated(false);
    }

    public View getChildAtPosition(MotionEvent ev) {
        final float x = ev.getX() + getScrollX();
        final float y = ev.getY() + getScrollY();
        for (int i = 0; i < mLinearLayout.getChildCount(); i++) {
            View item = mLinearLayout.getChildAt(i);
            if (item.getVisibility() == View.VISIBLE
                    && x >= item.getLeft() && x < item.getRight()
                    && y >= item.getTop() && y < item.getBottom()) {
                return item;
            }
        }
        return null;
    }

    public View getChildContentView(View v) {
        return v.findViewById(R.id.recent_item);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mPerformanceHelper != null) {
            mPerformanceHelper.onLayoutCallback();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (mPerformanceHelper != null) {
            int paddingLeft = mPaddingLeft;
            final boolean offsetRequired = isPaddingOffsetRequired();
            if (offsetRequired) {
                paddingLeft += getLeftPaddingOffset();
            }

            int left = mScrollX + paddingLeft;
            int right = left + mRight - mLeft - mPaddingRight - paddingLeft;
            int top = mScrollY + getFadeTop(offsetRequired);
            int bottom = top + getFadeHeight(offsetRequired);

            if (offsetRequired) {
                right += getRightPaddingOffset();
                bottom += getBottomPaddingOffset();
            }
            mPerformanceHelper.drawCallback(canvas,
                    left, right, top, bottom, mScrollX, mScrollY,
                    getTopFadingEdgeStrength(), getBottomFadingEdgeStrength(),
                    0, 0);
        }
    }

    @Override
    public int getVerticalFadingEdgeLength() {
        if (mPerformanceHelper != null) {
            return mPerformanceHelper.getVerticalFadingEdgeLengthCallback();
        } else {
            return super.getVerticalFadingEdgeLength();
        }
    }

    @Override
    public int getHorizontalFadingEdgeLength() {
        if (mPerformanceHelper != null) {
            return mPerformanceHelper.getHorizontalFadingEdgeLengthCallback();
        } else {
            return super.getHorizontalFadingEdgeLength();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setScrollbarFadingEnabled(true);
        mLinearLayout = (LinearLayout) findViewById(R.id.recents_linear_layout);
        final int leftPadding = mContext.getResources()
            .getDimensionPixelOffset(R.dimen.status_bar_recents_thumbnail_left_margin);
        setOverScrollEffectPadding(leftPadding, 0);
    }

    @Override
    public void onAttachedToWindow() {
        if (mPerformanceHelper != null) {
            mPerformanceHelper.onAttachedToWindowCallback(
                    mCallback, mLinearLayout, isHardwareAccelerated());
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    private void setOverScrollEffectPadding(int leftPadding, int i) {
        // TODO Add to (Vertical)ScrollView
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Skip this work if a transition is running; it sets the scroll values independently
        // and should not have those animated values clobbered by this logic
        LayoutTransition transition = mLinearLayout.getLayoutTransition();
        if (transition != null && transition.isRunning()) {
            return;
        }
        // Keep track of the last visible item in the list so we can restore it
        // to the bottom when the orientation changes.
        mLastScrollPosition = scrollPositionOfMostRecent();

        // This has to happen post-layout, so run it "in the future"
        post(new Runnable() {
            public void run() {
                // Make sure we're still not clobbering the transition-set values, since this
                // runnable launches asynchronously
                LayoutTransition transition = mLinearLayout.getLayoutTransition();
                if (transition == null || !transition.isRunning()) {
                    scrollTo(0, mLastScrollPosition);
                }
            }
        });
    }

    public void onRecentsVisibilityChanged() {
        if (mPerformanceHelper != null) {
            mPerformanceHelper.updateShowBackground();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // scroll to bottom after reloading
        if (visibility == View.VISIBLE && changedView == this) {
            post(new Runnable() {
                public void run() {
                    update();
                }
            });
        }
    }

    public void setAdapter(TaskDescriptionAdapter adapter) {
        mAdapter = adapter;
        mAdapter.registerDataSetObserver(new DataSetObserver() {
            public void onChanged() {
                update();
            }

            public void onInvalidated() {
                update();
            }
        });
    }

    @Override
    public void setLayoutTransition(LayoutTransition transition) {
        if (mPerformanceHelper != null) {
            mPerformanceHelper.setLayoutTransitionCallback(transition);
        }
        // The layout transition applies to our embedded LinearLayout
        mLinearLayout.setLayoutTransition(transition);
    }

    public void setCallback(RecentsCallback callback) {
        mCallback = callback;
    }
}
