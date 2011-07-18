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

import com.android.systemui.recent.RecentsPanelView.ActivityDescriptionAdapter;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.android.systemui.R;

public class RecentsHorizontalScrollView extends HorizontalScrollView {
    private static final String TAG = RecentsPanelView.TAG;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean DEBUG = RecentsPanelView.DEBUG;
    private LinearLayout mLinearLayout;
    private ActivityDescriptionAdapter mAdapter;
    private RecentsCallback mCallback;
    protected int mLastScrollPosition;
    private View mCurrentView;
    private float mLastY;
    private boolean mDragging;
    private VelocityTracker mVelocityTracker;
    private float mDensityScale;
    private float mPagingTouchSlop;
    private OnLongClickListener mOnLongClick = new OnLongClickListener() {
        public boolean onLongClick(View v) {
            final View anchorView = v.findViewById(R.id.app_description);
            mCurrentView = v;
            mCallback.handleLongPress(v, anchorView);
            mCurrentView = null; // make sure we don't accept the return click from this
            return true;
        }
    };

    public RecentsHorizontalScrollView(Context context) {
        this(context, null);
    }

    public RecentsHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
        mDensityScale = getResources().getDisplayMetrics().density;
        mPagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
    }

    private int scrollPositionOfMostRecent() {
        return mLinearLayout.getWidth() - getWidth();
    }

    private void update() {
        mLinearLayout.removeAllViews();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            final View view = mAdapter.getView(i, null, mLinearLayout);
            view.setClickable(true);
            view.setOnLongClickListener(mOnLongClick);
            mLinearLayout.addView(view);
        }
        // Scroll to end after layout.
        post(new Runnable() {
            public void run() {
                mLastScrollPosition = scrollPositionOfMostRecent();
                scrollTo(mLastScrollPosition, 0);
            }
        });
    }

    @Override
    public void removeViewInLayout(final View view) {
        ObjectAnimator anim = animateClosed(view, Constants.MAX_ESCAPE_ANIMATION_DURATION,
                "y", view.getY(), view.getY() + view.getHeight());
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                RecentsHorizontalScrollView.super.removeViewInLayout(view);
            }
        });
        anim.start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = false;
                mLastY = ev.getY();
                final float x = ev.getX() + getScrollX();
                final float y = ev.getY() + getScrollY();
                mCurrentView = null;
                for (int i = 0; i < mLinearLayout.getChildCount(); i++) {
                    View item = mLinearLayout.getChildAt(i);
                    if (x >= item.getLeft() && x < item.getRight()
                            && y >= item.getTop() && y < item.getBottom()) {
                        mCurrentView = item;
                        if (DEBUG) Log.v(TAG, "Hit item " + item);
                        break;
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                float delta = ev.getY() - mLastY;
                if (DEBUG) Log.v(TAG, "ACTION_MOVE : " + delta);
                if (Math.abs(delta) > mPagingTouchSlop) {
                    mDragging = true;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mCurrentView != null) {
                    mCallback.handleOnClick(mCurrentView);
                }
                mDragging = false;
                break;
        }
        return mDragging ? true : super.onInterceptTouchEvent(ev);
    }

    private float getAlphaForOffset(View view, float thumbHeight) {
        final float fadeHeight = Constants.ALPHA_FADE_END * thumbHeight;
        float result = 1.0f;
        if (view.getY() >= thumbHeight * Constants.ALPHA_FADE_START) {
            result = 1.0f - (view.getY() - thumbHeight * Constants.ALPHA_FADE_START) / fadeHeight;
        } else if (view.getY() < thumbHeight * (1.0f - Constants.ALPHA_FADE_START)) {
            result = 1.0f + (thumbHeight * Constants.ALPHA_FADE_START + view.getY()) / fadeHeight;
        }
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            return super.onTouchEvent(ev);
        }

        mVelocityTracker.addMovement(ev);

        final View animView = mCurrentView;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (animView != null) {
                    final float delta = ev.getY() - mLastY;
                    final View thumb = animView.findViewById(R.id.app_thumbnail);
                    animView.setY(animView.getY() + delta);
                    animView.setAlpha(getAlphaForOffset(animView, thumb.getHeight()));
                    invalidateGlobalRegion(animView);
                }
                mLastY = ev.getY();
                break;

            case MotionEvent.ACTION_UP:
                final ObjectAnimator anim;
                if (animView != null) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, 10000);
                    final float velocityX = velocityTracker.getXVelocity();
                    final float velocityY = velocityTracker.getYVelocity();
                    final float curY = animView.getY();
                    final float newY = (velocityY >= 0.0f ? 1 : -1) * animView.getHeight();
                    final float maxVelocity = Constants.ESCAPE_VELOCITY * mDensityScale;
                    if (Math.abs(velocityY) > Math.abs(velocityX)
                            && Math.abs(velocityY) > maxVelocity
                            && (velocityY >= 0.0f) == (animView.getY() >= 0)) {
                        long duration =
                            (long) (Math.abs(newY - curY) * 1000.0f / Math.abs(velocityY));
                        duration = Math.min(duration, Constants.MAX_ESCAPE_ANIMATION_DURATION);
                        anim = animateClosed(animView, duration, "y", curY, newY);
                    } else { // Animate back to position
                        long duration = Math.abs(velocityY) > 0.0f ?
                                (long) (Math.abs(newY - curY) * 1000.0f / Math.abs(velocityY))
                                : Constants.SNAP_BACK_DURATION;
                        duration = Math.min(duration, Constants.SNAP_BACK_DURATION);
                        anim = ObjectAnimator.ofFloat(animView, "y", animView.getY(), 0.0f);
                        anim.setInterpolator(new DecelerateInterpolator(4.0f));
                        anim.setDuration(duration);
                    }

                    final View thumb = animView.findViewById(R.id.app_thumbnail);
                    anim.addUpdateListener(new AnimatorUpdateListener() {
                        public void onAnimationUpdate(ValueAnimator animation) {
                            animView.setAlpha(getAlphaForOffset(animView, thumb.getHeight()));
                            invalidateGlobalRegion(animView);
                        }
                    });
                    anim.start();
                }

                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
        }
        return true;
    }

    private ObjectAnimator animateClosed(final View animView, long duration,
            String attr, float from, float to) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(animView, attr, from, to);
        anim.setInterpolator(new LinearInterpolator());
        final int swipeDirection = animView.getX() >= 0.0f ?
                RecentsCallback.SWIPE_RIGHT : RecentsCallback.SWIPE_LEFT;
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mLinearLayout.removeView(animView);
                mCallback.handleSwipe(animView, swipeDirection);
            }
            public void onAnimationCancel(Animator animation) {
                mLinearLayout.removeView(animView);
                mCallback.handleSwipe(animView, swipeDirection);
            }
        });
        anim.setDuration(duration);
        return anim;
    }

    void invalidateGlobalRegion(View view) {
        RectF childBounds
                = new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
        childBounds.offset(view.getX(), view.getY());
        if (DEBUG_INVALIDATE) Log.v(TAG, "-------------");
        while (view.getParent() != null && view.getParent() instanceof View) {
            view = (View) view.getParent();
            view.getMatrix().mapRect(childBounds);
            view.invalidate((int) Math.floor(childBounds.left),
                    (int) Math.floor(childBounds.top),
                    (int) Math.ceil(childBounds.right),
                    (int) Math.ceil(childBounds.bottom));
            if (DEBUG_INVALIDATE) {
                Log.v(TAG, "INVALIDATE(" + (int) Math.floor(childBounds.left)
                        + "," + (int) Math.floor(childBounds.top)
                        + "," + (int) Math.ceil(childBounds.right)
                        + "," + (int) Math.ceil(childBounds.bottom));
            }
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
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDensityScale = getResources().getDisplayMetrics().density;
        mPagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
    }

    private void setOverScrollEffectPadding(int leftPadding, int i) {
        // TODO Add to (Vertical)ScrollView
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Keep track of the last visible item in the list so we can restore it
        // to the bottom when the orientation changes.
        mLastScrollPosition = scrollPositionOfMostRecent();

        // This has to happen post-layout, so run it "in the future"
        post(new Runnable() {
            public void run() {
                scrollTo(mLastScrollPosition, 0);
            }
        });
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

    public void setAdapter(ActivityDescriptionAdapter adapter) {
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
        // The layout transition applies to our embedded LinearLayout
        mLinearLayout.setLayoutTransition(transition);
    }

    public void setCallback(RecentsCallback callback) {
        mCallback = callback;
    }
}
