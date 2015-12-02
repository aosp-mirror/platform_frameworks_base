/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.view;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * A header of a notification view
 *
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationHeaderView extends LinearLayout {
    public static final int NO_COLOR = -1;
    private final int mHeaderMinWidth;
    private View mAppName;
    private View mSubTextView;
    private OnClickListener mExpandClickListener;
    private HeaderTouchListener mTouchListener = new HeaderTouchListener();
    private View mExpandButton;
    private View mIcon;
    private TextView mChildCount;
    private int mIconColor;
    private int mOriginalNotificationColor;

    public NotificationHeaderView(Context context) {
        this(context, null);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationHeaderView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mHeaderMinWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_header_shrink_min_width);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAppName = findViewById(com.android.internal.R.id.app_name_text);
        mSubTextView = findViewById(com.android.internal.R.id.header_sub_text);
        mExpandButton = findViewById(com.android.internal.R.id.expand_button);
        mIcon = findViewById(com.android.internal.R.id.icon);
        mChildCount = (TextView) findViewById(com.android.internal.R.id.number_of_children);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int givenWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int givenHeight = MeasureSpec.getSize(heightMeasureSpec);
        int wrapContentWidthSpec = MeasureSpec.makeMeasureSpec(givenWidth,
                MeasureSpec.AT_MOST);
        int wrapContentHeightSpec = MeasureSpec.makeMeasureSpec(givenHeight,
                MeasureSpec.AT_MOST);
        int totalWidth = getPaddingStart() + getPaddingEnd();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                // We'll give it the rest of the space in the end
                continue;
            }
            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidthSpec = getChildMeasureSpec(wrapContentWidthSpec,
                    lp.leftMargin + lp.rightMargin, lp.width);
            int childHeightSpec = getChildMeasureSpec(wrapContentHeightSpec,
                    lp.topMargin + lp.bottomMargin, lp.height);
            child.measure(childWidthSpec, childHeightSpec);
            totalWidth += lp.leftMargin + lp.rightMargin + child.getMeasuredWidth();
        }
        if (totalWidth > givenWidth) {
            int overFlow = totalWidth - givenWidth;
            // We are overflowing, lets shrink
            final int appWidth = mAppName.getMeasuredWidth();
            if (appWidth > mHeaderMinWidth) {
                int newSize = appWidth - Math.min(appWidth - mHeaderMinWidth, overFlow);
                int childWidthSpec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST);
                mAppName.measure(childWidthSpec, wrapContentHeightSpec);
                overFlow -= appWidth - newSize;
            }
            if (overFlow > 0 && mSubTextView.getVisibility() != GONE) {
                // we're still too big
                final int subTextWidth = mSubTextView.getMeasuredWidth();
                int newSize = Math.max(0, subTextWidth - overFlow);
                int childWidthSpec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST);
                mSubTextView.measure(childWidthSpec, wrapContentHeightSpec);
            }
            totalWidth = givenWidth;
        }
        setMeasuredDimension(totalWidth, givenHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        updateTouchListener();
    }

    private void updateTouchListener() {
        if (mExpandClickListener != null) {
            mTouchListener.bindTouchRects();
        }
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        mExpandClickListener = l;
        setOnTouchListener(mExpandClickListener != null ? mTouchListener : null);
        updateTouchListener();
    }

    public void setChildCount(int childCount) {
        if (childCount > 0) {
            mChildCount.setText(getContext().getString(
                    com.android.internal.R.string.notification_children_count_bracketed,
                    childCount));
            mChildCount.setVisibility(VISIBLE);
        } else {
            mChildCount.setVisibility(GONE);
        }
    }

    @RemotableViewMethod
    public void setOriginalIconColor(int color) {
        mIconColor = color;
    }

    public int getOriginalIconColor() {
        return mIconColor;
    }

    @RemotableViewMethod
    public void setOriginalNotificationColor(int color) {
        mOriginalNotificationColor = color;
    }

    public int getOriginalNotificationColor() {
        return mOriginalNotificationColor;
    }

    public class HeaderTouchListener implements View.OnTouchListener {

        private final ArrayList<Rect> mTouchRects = new ArrayList<>();
        private int mTouchSlop;
        private boolean mTrackGesture;
        private float mDownX;
        private float mDownY;

        public HeaderTouchListener() {
        }

        public void bindTouchRects() {
            mTouchRects.clear();
            addRectAroundViewView(mIcon);
            addRectAroundViewView(mExpandButton);
            addInBetweenRect();
            mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }

        private void addInBetweenRect() {
            final Rect r = new Rect();
            r.top = 0;
            r.bottom = (int) (32 * getResources().getDisplayMetrics().density);
            Rect leftRect = mTouchRects.get(0);
            r.left = leftRect.right;
            Rect rightRect = mTouchRects.get(1);
            r.right = rightRect.left;
            mTouchRects.add(r);
        }

        private void addRectAroundViewView(View view) {
            final Rect r = getRectAroundView(view);
            mTouchRects.add(r);
        }

        private Rect getRectAroundView(View view) {
            float size = 48 * getResources().getDisplayMetrics().density;
            final Rect r = new Rect();
            if (view.getVisibility() == GONE) {
                view = getFirstChildNotGone();
                r.left = (int) (view.getLeft() - size / 2.0f);
            } else {
                r.left = (int) ((view.getLeft() + view.getRight()) / 2.0f - size / 2.0f);
            }
            r.top = (int) ((view.getTop() + view.getBottom()) / 2.0f - size / 2.0f);
            r.bottom = (int) (r.top + size);
            r.right = (int) (r.left + size);
            return r;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    mTrackGesture = false;
                    if (isInside(x, y)) {
                        mTrackGesture = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mTrackGesture) {
                        if (Math.abs(mDownX - x) > mTouchSlop
                                || Math.abs(mDownY - y) > mTouchSlop) {
                            mTrackGesture = false;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (mTrackGesture) {
                        mExpandClickListener.onClick(NotificationHeaderView.this);
                    }
                    break;
            }
            return mTrackGesture;
        }

        private boolean isInside(float x, float y) {
            for (int i = 0; i < mTouchRects.size(); i++) {
                Rect r = mTouchRects.get(i);
                if (r.contains((int) x, (int) y)) {
                    mDownX = x;
                    mDownY = y;
                    return true;
                }
            }
            return false;
        }
    }

    private View getFirstChildNotGone() {
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                return child;
            }
        }
        return this;
    }
}
