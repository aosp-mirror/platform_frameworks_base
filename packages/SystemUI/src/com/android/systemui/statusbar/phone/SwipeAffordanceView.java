/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonView;

/**
 * A swipeable button for affordances on the lockscreen. This is used for the camera and phone
 * affordance.
 */
public class SwipeAffordanceView extends KeyButtonView {

    private static final int SWIPE_DIRECTION_START = 0;
    private static final int SWIPE_DIRECTION_END = 1;

    private static final int SWIPE_DIRECTION_LEFT = 0;
    private static final int SWIPE_DIRECTION_RIGHT = 1;

    private AffordanceListener mListener;
    private int mScaledTouchSlop;
    private float mDragDistance;
    private int mResolvedSwipeDirection;
    private int mSwipeDirection;

    public SwipeAffordanceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeAffordanceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.SwipeAffordanceView,
                0, 0);
        try {
            mSwipeDirection = a.getInt(R.styleable.SwipeAffordanceView_swipeDirection, 0);
        } finally {
            a.recycle();
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        if (!isLayoutRtl()) {
            mResolvedSwipeDirection = mSwipeDirection;
        } else {
            mResolvedSwipeDirection = mSwipeDirection == SWIPE_DIRECTION_START
                    ? SWIPE_DIRECTION_RIGHT
                    : SWIPE_DIRECTION_LEFT;
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDragDistance = getResources().getDimension(R.dimen.affordance_drag_distance);
        mScaledTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
    }

    public void enableAccessibility(boolean touchExplorationEnabled) {

        // Add a touch handler or accessibility click listener for camera button.
        if (touchExplorationEnabled) {
            setOnTouchListener(null);
            setOnClickListener(mClickListener);
        } else {
            setOnTouchListener(mTouchListener);
            setOnClickListener(null);
        }
    }

    public void setAffordanceListener(AffordanceListener listener) {
        mListener = listener;
    }

    private void onActionPerformed() {
        if (mListener != null) {
            mListener.onActionPerformed(this);
        }
    }

    private void onUserActivity(long when) {
        if (mListener != null) {
            mListener.onUserActivity(when);
        }
    }

    private final OnClickListener mClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            onActionPerformed();
        }
    };

    private final OnTouchListener mTouchListener = new OnTouchListener() {
        private float mStartX;
        private boolean mTouchSlopReached;
        private boolean mSkipCancelAnimation;

        @Override
        public boolean onTouch(final View view, MotionEvent event) {
            float realX = event.getRawX();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mStartX = realX;
                    mTouchSlopReached = false;
                    mSkipCancelAnimation = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                            ? realX > mStartX
                            : realX < mStartX) {
                        realX = mStartX;
                    }
                    if (mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                            ? realX < mStartX - mDragDistance
                            : realX > mStartX + mDragDistance) {
                        view.setPressed(true);
                        onUserActivity(event.getEventTime());
                    } else {
                        view.setPressed(false);
                    }
                    if (mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                            ? realX < mStartX - mScaledTouchSlop
                            : realX > mStartX + mScaledTouchSlop) {
                        mTouchSlopReached = true;
                    }
                    view.setTranslationX(mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                                    ? Math.max(realX - mStartX, -mDragDistance)
                                    : Math.min(realX - mStartX, mDragDistance));
                    break;
                case MotionEvent.ACTION_UP:
                    if (mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                            ? realX < mStartX - mDragDistance
                            : realX > mStartX + mDragDistance) {
                        onActionPerformed();
                        view.animate().x(mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                                ? -view.getWidth()
                                : ((View) view.getParent()).getWidth() + view.getWidth())
                                .setInterpolator(new AccelerateInterpolator(2f)).withEndAction(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        view.setTranslationX(0);
                                    }
                                });
                        mSkipCancelAnimation = true;
                    }
                    if (mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                            ? realX < mStartX - mScaledTouchSlop
                            : realX > mStartX + mScaledTouchSlop) {
                        mTouchSlopReached = true;
                    }
                    if (!mTouchSlopReached) {
                        mSkipCancelAnimation = true;
                        view.animate().translationX(mResolvedSwipeDirection == SWIPE_DIRECTION_LEFT
                                ? -mDragDistance / 2
                                : mDragDistance / 2).
                                setInterpolator(new DecelerateInterpolator()).withEndAction(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        view.animate().translationX(0).
                                                setInterpolator(new AccelerateInterpolator());
                                    }
                                });
                    }
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    if (!mSkipCancelAnimation) {
                        view.animate().translationX(0)
                                .setInterpolator(new AccelerateInterpolator(2f));
                    }
                    break;
            }
            return true;
        }
    };

    public interface AffordanceListener {

        /**
         * Called when the view would like to report user activity.
         *
         * @param when The timestamp of the user activity in {@link SystemClock#uptimeMillis} time
         *             base.
         */
        void onUserActivity(long when);

        /**
         * Called when the action of the affordance has been performed.
         */
        void onActionPerformed(SwipeAffordanceView view);
    }
}
