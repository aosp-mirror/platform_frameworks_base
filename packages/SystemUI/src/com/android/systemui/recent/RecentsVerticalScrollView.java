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
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.recent.RecentsPanelView.TaskDescriptionAdapter;

import java.util.HashSet;
import java.util.Iterator;

public class RecentsVerticalScrollView extends ScrollView
        implements SwipeHelper.Callback, RecentsPanelView.RecentsScrollView {
    private static final String TAG = RecentsPanelView.TAG;
    private static final boolean DEBUG = RecentsPanelView.DEBUG;
    private LinearLayout mLinearLayout;
    private TaskDescriptionAdapter mAdapter;
    private RecentsCallback mCallback;
    protected int mLastScrollPosition;
    private SwipeHelper mSwipeHelper;
    private FadedEdgeDrawHelper mFadedEdgeDrawHelper;
    private HashSet<View> mRecycledViews;
    private int mNumItemsInOneScreenful;
    private Runnable mOnScrollListener;

    public RecentsVerticalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, context);

        mFadedEdgeDrawHelper = FadedEdgeDrawHelper.create(context, attrs, this, true);
        mRecycledViews = new HashSet<View>();
    }

    public void setMinSwipeAlpha(float minAlpha) {
        mSwipeHelper.setMinSwipeProgress(minAlpha);
    }

    private int scrollPositionOfMostRecent() {
        return mLinearLayout.getHeight() - getHeight() + getPaddingTop();
    }

    private void addToRecycledViews(View v) {
        if (mRecycledViews.size() < mNumItemsInOneScreenful) {
            mRecycledViews.add(v);
        }
    }

    public View findViewForTask(int persistentTaskId) {
        for (int i = 0; i < mLinearLayout.getChildCount(); i++) {
            View v = mLinearLayout.getChildAt(i);
            RecentsPanelView.ViewHolder holder = (RecentsPanelView.ViewHolder) v.getTag();
            if (holder.taskDescription.persistentTaskId == persistentTaskId) {
                return v;
            }
        }
        return null;
    }

    private void update() {
        for (int i = 0; i < mLinearLayout.getChildCount(); i++) {
            View v = mLinearLayout.getChildAt(i);
            addToRecycledViews(v);
            mAdapter.recycleView(v);
        }
        LayoutTransition transitioner = getLayoutTransition();
        setLayoutTransition(null);

        mLinearLayout.removeAllViews();

        // Once we can clear the data associated with individual item views,
        // we can get rid of the removeAllViews() and the code below will
        // recycle them.
        Iterator<View> recycledViews = mRecycledViews.iterator();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            View old = null;
            if (recycledViews.hasNext()) {
                old = recycledViews.next();
                recycledViews.remove();
                old.setVisibility(VISIBLE);
            }
            final View view = mAdapter.getView(i, old, mLinearLayout);

            if (mFadedEdgeDrawHelper != null) {
                mFadedEdgeDrawHelper.addViewCallback(view);
            }

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

            RecentsPanelView.ViewHolder holder = (RecentsPanelView.ViewHolder) view.getTag();
            final View thumbnailView = holder.thumbnailView;
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
            if (calloutLine != null) {
                calloutLine.setOnTouchListener(noOpListener);
            }

            mLinearLayout.addView(view);
        }
        setLayoutTransition(transitioner);

        // Scroll to end after initial layout.
        final OnGlobalLayoutListener updateScroll = new OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                    mLastScrollPosition = scrollPositionOfMostRecent();
                    scrollTo(0, mLastScrollPosition);
                    final ViewTreeObserver observer = getViewTreeObserver();
                    if (observer.isAlive()) {
                        observer.removeOnGlobalLayoutListener(this);
                    }
                }
            };
        getViewTreeObserver().addOnGlobalLayoutListener(updateScroll);
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

    @Override
    public boolean isAntiFalsingNeeded() {
        return false;
    }

    public void dismissChild(View v) {
        mSwipeHelper.dismissChild(v, 0);
    }

    public void onChildDismissed(View v) {
        addToRecycledViews(v);
        mLinearLayout.removeView(v);
        mCallback.handleSwipe(v);
        // Restore the alpha/translation parameters to what they were before swiping
        // (for when these items are recycled)
        View contentView = getChildContentView(v);
        contentView.setAlpha(1f);
        contentView.setTranslationX(0);
    }

    public void onBeginDrag(View v) {
        // We do this so the underlying ScrollView knows that it won't get
        // the chance to intercept events anymore
        requestDisallowInterceptTouchEvent(true);
    }

    public void onDragCancelled(View v) {
    }

    @Override
    public void onChildSnappedBack(View animView) {
    }

    @Override
    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        return false;
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
    public void drawFadedEdges(Canvas canvas, int left, int right, int top, int bottom) {
        if (mFadedEdgeDrawHelper != null) {
            final boolean offsetRequired = isPaddingOffsetRequired();
            mFadedEdgeDrawHelper.drawCallback(canvas,
                    left, right, top + getFadeTop(offsetRequired), bottom, getScrollX(), getScrollY(),
                    getTopFadingEdgeStrength(), getBottomFadingEdgeStrength(),
                    0, 0, getPaddingTop());
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
       super.onScrollChanged(l, t, oldl, oldt);
       if (mOnScrollListener != null) {
           mOnScrollListener.run();
       }
    }

    public void setOnScrollListener(Runnable listener) {
        mOnScrollListener = listener;
    }

    @Override
    public int getVerticalFadingEdgeLength() {
        if (mFadedEdgeDrawHelper != null) {
            return mFadedEdgeDrawHelper.getVerticalFadingEdgeLength();
        } else {
            return super.getVerticalFadingEdgeLength();
        }
    }

    @Override
    public int getHorizontalFadingEdgeLength() {
        if (mFadedEdgeDrawHelper != null) {
            return mFadedEdgeDrawHelper.getHorizontalFadingEdgeLength();
        } else {
            return super.getHorizontalFadingEdgeLength();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setScrollbarFadingEnabled(true);
        mLinearLayout = (LinearLayout) findViewById(R.id.recents_linear_layout);
        final int leftPadding = getContext().getResources()
            .getDimensionPixelOffset(R.dimen.status_bar_recents_thumbnail_left_margin);
        setOverScrollEffectPadding(leftPadding, 0);
    }

    @Override
    public void onAttachedToWindow() {
        if (mFadedEdgeDrawHelper != null) {
            mFadedEdgeDrawHelper.onAttachedToWindowCallback(mLinearLayout, isHardwareAccelerated());
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
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

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int childWidthMeasureSpec =
                MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.AT_MOST);
        int childheightMeasureSpec =
                MeasureSpec.makeMeasureSpec(dm.heightPixels, MeasureSpec.AT_MOST);
        View child = mAdapter.createView(mLinearLayout);
        child.measure(childWidthMeasureSpec, childheightMeasureSpec);
        mNumItemsInOneScreenful =
                (int) FloatMath.ceil(dm.heightPixels / (float) child.getMeasuredHeight());
        addToRecycledViews(child);

        for (int i = 0; i < mNumItemsInOneScreenful - 1; i++) {
            addToRecycledViews(mAdapter.createView(mLinearLayout));
        }
    }

    public int numItemsInOneScreenful() {
        return mNumItemsInOneScreenful;
    }

    @Override
    public void setLayoutTransition(LayoutTransition transition) {
        // The layout transition applies to our embedded LinearLayout
        mLinearLayout.setLayoutTransition(transition);
    }

    public void setCallback(RecentsCallback callback) {
        mCallback = callback;
    }
}
