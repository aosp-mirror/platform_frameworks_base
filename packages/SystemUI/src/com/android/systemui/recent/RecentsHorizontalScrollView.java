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

import com.android.systemui.recent.RecentsPanelView.ActvityDescriptionAdapter;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.android.systemui.R;

public class RecentsHorizontalScrollView extends HorizontalScrollView
        implements View.OnClickListener, View.OnTouchListener {
    private static final float FADE_CONSTANT = 0.5f;
    private static final int SNAP_BACK_DURATION = 250;
    private static final int ESCAPE_VELOCITY = 100; // speed of item required to "curate" it
    private static final String TAG = RecentsPanelView.TAG;
    private static final float THRESHHOLD = 50;
    private static final boolean DEBUG_INVALIDATE = false;
    private LinearLayout mLinearLayout;
    private ActvityDescriptionAdapter mAdapter;
    private RecentsCallback mCallback;
    protected int mLastScrollPosition;
    private View mCurrentView;
    private float mLastY;
    private boolean mDragging;
    private VelocityTracker mVelocityTracker;

    public RecentsHorizontalScrollView(Context context) {
        this(context, null);
    }

    public RecentsHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    private int scrollPositionOfMostRecent() {
        return mLinearLayout.getWidth() - getWidth();
    }

    public void update() {
        mLinearLayout.removeAllViews();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            View view = mAdapter.getView(i, null, mLinearLayout);
            view.setClickable(true);
            view.setOnClickListener(this);
            view.setOnTouchListener(this);
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
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = false;
                mLastY = ev.getY();
                break;

            case MotionEvent.ACTION_MOVE:
                float delta = ev.getY() - mLastY;
                if (Math.abs(delta) > THRESHHOLD) {
                    mDragging = true;
                }
                break;

            case MotionEvent.ACTION_UP:
                mDragging = false;
                break;
        }
        return mDragging ? true : super.onInterceptTouchEvent(ev);
    }

    private float getAlphaForOffset(View view, float thumbHeight) {
        final float fadeHeight = FADE_CONSTANT * thumbHeight;
        float result = 1.0f;
        if (view.getY() >= thumbHeight) {
            result = 1.0f - (view.getY() - thumbHeight) / fadeHeight;
        } else if (view.getY() < 0.0f) {
            result = 1.0f + (thumbHeight + view.getY()) / fadeHeight;
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
        // TODO: Cache thumbnail
        final View thumb = animView.findViewById(R.id.app_thumbnail);
        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (animView != null) {
                    final float delta = ev.getY() - mLastY;
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

                    if (Math.abs(velocityY) > Math.abs(velocityX)
                            && Math.abs(velocityY) > ESCAPE_VELOCITY
                            && (velocityY >= 0.0f) == (animView.getY() >= 0)) {
                        final long duration =
                            (long) (Math.abs(newY - curY) * 1000.0f / Math.abs(velocityY));
                        anim = ObjectAnimator.ofFloat(animView, "y", curY, newY);
                        anim.setInterpolator(new LinearInterpolator());
                        final int swipeDirection = animView.getY() >= 0.0f ?
                                RecentsCallback.SWIPE_RIGHT : RecentsCallback.SWIPE_LEFT;
                        anim.addListener(new AnimatorListener() {
                            public void onAnimationStart(Animator animation) {
                            }
                            public void onAnimationRepeat(Animator animation) {
                            }
                            public void onAnimationEnd(Animator animation) {
                                mLinearLayout.removeView(mCurrentView);
                                mCallback.handleSwipe(animView, swipeDirection);
                            }
                            public void onAnimationCancel(Animator animation) {
                                mLinearLayout.removeView(mCurrentView);
                                mCallback.handleSwipe(animView, swipeDirection);
                            }
                        });
                        anim.setDuration(duration);
                    } else { // Animate back to position
                        final long duration = Math.abs(velocityY) > 0.0f ?
                                (long) (Math.abs(newY - curY) * 1000.0f / Math.abs(velocityY))
                                : SNAP_BACK_DURATION;
                        anim = ObjectAnimator.ofFloat(animView, "y", animView.getY(), 0.0f);
                        anim.setInterpolator(new DecelerateInterpolator(2.0f));
                        anim.setDuration(duration);
                    }

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
        LayoutInflater inflater = (LayoutInflater)
                mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        setScrollbarFadingEnabled(true);

        mLinearLayout = (LinearLayout) findViewById(R.id.recents_linear_layout);

        final int leftPadding = mContext.getResources()
            .getDimensionPixelOffset(R.dimen.status_bar_recents_thumbnail_left_margin);
        setOverScrollEffectPadding(leftPadding, 0);
    }

    private void setOverScrollEffectPadding(int leftPadding, int i) {
        // TODO Add to RecentsHorizontalScrollView
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
                scrollTo(0, mLastScrollPosition);
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

    public void setAdapter(ActvityDescriptionAdapter adapter) {
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

    public void onClick(View view) {
        mCallback.handleOnClick(view);
    }

    public void setCallback(RecentsCallback callback) {
        mCallback = callback;
    }

    public boolean onTouch(View v, MotionEvent event) {
        mCurrentView = v;
        return false;
    }
}
