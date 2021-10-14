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
 * limitations under the License.
 */

package android.view;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.util.HashSet;
import java.util.Set;

/**
 * The top line of content in a notification view.
 * This includes the text views and badges but excludes the icon and the expander.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationTopLineView extends ViewGroup {
    private final OverflowAdjuster mOverflowAdjuster = new OverflowAdjuster();
    private final int mGravityY;
    private final int mChildMinWidth;
    private final int mChildHideWidth;
    @Nullable private View mAppName;
    @Nullable private View mTitle;
    private View mHeaderText;
    private View mHeaderTextDivider;
    private View mSecondaryHeaderText;
    private View mSecondaryHeaderTextDivider;
    private OnClickListener mFeedbackListener;
    private HeaderTouchListener mTouchListener = new HeaderTouchListener();
    private View mFeedbackIcon;
    private int mHeaderTextMarginEnd;

    private Set<View> mViewsToDisappear = new HashSet<>();

    private int mMaxAscent;
    private int mMaxDescent;

    public NotificationTopLineView(Context context) {
        this(context, null);
    }

    public NotificationTopLineView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationTopLineView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationTopLineView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources res = getResources();
        mChildMinWidth = res.getDimensionPixelSize(R.dimen.notification_header_shrink_min_width);
        mChildHideWidth = res.getDimensionPixelSize(R.dimen.notification_header_shrink_hide_width);

        // NOTE: Implementation only supports TOP, BOTTOM, and CENTER_VERTICAL gravities,
        // with CENTER_VERTICAL being the default.
        int[] attrIds = {android.R.attr.gravity};
        TypedArray ta = context.obtainStyledAttributes(attrs, attrIds, defStyleAttr, defStyleRes);
        int gravity = ta.getInt(0, 0);
        ta.recycle();
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            mGravityY = Gravity.BOTTOM;
        } else if ((gravity & Gravity.TOP) == Gravity.TOP) {
            mGravityY = Gravity.TOP;
        } else {
            mGravityY = Gravity.CENTER_VERTICAL;
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAppName = findViewById(R.id.app_name_text);
        mTitle = findViewById(R.id.title);
        mHeaderText = findViewById(R.id.header_text);
        mHeaderTextDivider = findViewById(R.id.header_text_divider);
        mSecondaryHeaderText = findViewById(R.id.header_text_secondary);
        mSecondaryHeaderTextDivider = findViewById(R.id.header_text_secondary_divider);
        mFeedbackIcon = findViewById(R.id.feedback);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int givenWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int givenHeight = MeasureSpec.getSize(heightMeasureSpec);
        final boolean wrapHeight = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST;
        int wrapContentWidthSpec = MeasureSpec.makeMeasureSpec(givenWidth, MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(givenHeight, MeasureSpec.AT_MOST);
        int totalWidth = getPaddingStart();
        int maxChildHeight = -1;
        mMaxAscent = -1;
        mMaxDescent = -1;
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                // We'll give it the rest of the space in the end
                continue;
            }
            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidthSpec = getChildMeasureSpec(wrapContentWidthSpec,
                    lp.leftMargin + lp.rightMargin, lp.width);
            int childHeightSpec = getChildMeasureSpec(heightSpec,
                    lp.topMargin + lp.bottomMargin, lp.height);
            child.measure(childWidthSpec, childHeightSpec);
            totalWidth += lp.leftMargin + lp.rightMargin + child.getMeasuredWidth();
            int childBaseline = child.getBaseline();
            int childHeight = child.getMeasuredHeight();
            if (childBaseline != -1) {
                mMaxAscent = Math.max(mMaxAscent, childBaseline);
                mMaxDescent = Math.max(mMaxDescent, childHeight - childBaseline);
            }
            maxChildHeight = Math.max(maxChildHeight, childHeight);
        }

        mViewsToDisappear.clear();
        // Ensure that there is at least enough space for the icons
        int endMargin = Math.max(mHeaderTextMarginEnd, getPaddingEnd());
        if (totalWidth > givenWidth - endMargin) {
            int overFlow = totalWidth - givenWidth + endMargin;

            mOverflowAdjuster.resetForOverflow(overFlow, heightSpec)
                    // First shrink the app name, down to a minimum size
                    .adjust(mAppName, null, mChildMinWidth)
                    // Next, shrink the header text (this usually has subText)
                    //   This shrinks the subtext first, but not all the way (yet!)
                    .adjust(mHeaderText, mHeaderTextDivider, mChildMinWidth)
                    // Next, shrink the secondary header text  (this rarely has conversationTitle)
                    .adjust(mSecondaryHeaderText, mSecondaryHeaderTextDivider, 0)
                    // Next, shrink the title text (this has contentTitle; only in headerless views)
                    .adjust(mTitle, null, mChildMinWidth)
                    // Next, shrink the header down to 0 if still necessary.
                    .adjust(mHeaderText, mHeaderTextDivider, 0)
                    // Finally, shrink the title to 0 if necessary (media is super cramped)
                    .adjust(mTitle, null, 0)
                    // Clean up
                    .finish();
        }
        setMeasuredDimension(givenWidth, wrapHeight ? maxChildHeight : givenHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final int width = getWidth();
        int start = getPaddingStart();
        int childCount = getChildCount();
        int ownHeight = b - t;
        int childSpace = ownHeight - mPaddingTop - mPaddingBottom;

        // Instead of centering the baseline, pick a baseline that centers views which align to it.
        // Only used when mGravityY is CENTER_VERTICAL
        int baselineY = mPaddingTop + ((childSpace - (mMaxAscent + mMaxDescent)) / 2) + mMaxAscent;

        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childHeight = child.getMeasuredHeight();
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();

            // Calculate vertical alignment of the views, accounting for the view baselines
            int childTop;
            int childBaseline = child.getBaseline();
            switch (mGravityY) {
                case Gravity.TOP:
                    childTop = mPaddingTop + params.topMargin;
                    if (childBaseline != -1) {
                        childTop += mMaxAscent - childBaseline;
                    }
                    break;
                case Gravity.CENTER_VERTICAL:
                    if (childBaseline != -1) {
                        // Align baselines vertically only if the child is smaller than us
                        if (childSpace - childHeight > 0) {
                            childTop = baselineY - childBaseline;
                        } else {
                            childTop = mPaddingTop + (childSpace - childHeight) / 2;
                        }
                    } else {
                        childTop = mPaddingTop + ((childSpace - childHeight) / 2)
                                + params.topMargin - params.bottomMargin;
                    }
                    break;
                case Gravity.BOTTOM:
                    int childBottom = ownHeight - mPaddingBottom;
                    childTop = childBottom - childHeight - params.bottomMargin;
                    if (childBaseline != -1) {
                        int descent = childHeight - childBaseline;
                        childTop -= (mMaxDescent - descent);
                    }
                    break;
                default:
                    childTop = mPaddingTop;
            }
            if (mViewsToDisappear.contains(child)) {
                child.layout(start, childTop, start, childTop + childHeight);
            } else {
                start += params.getMarginStart();
                int end = start + child.getMeasuredWidth();
                int layoutLeft = isRtl ? width - end : start;
                int layoutRight = isRtl ? width - start : end;
                start = end + params.getMarginEnd();
                child.layout(layoutLeft, childTop, layoutRight, childTop + childHeight);
            }
        }
        updateTouchListener();
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    private void updateTouchListener() {
        if (mFeedbackListener == null) {
            setOnTouchListener(null);
            return;
        }
        setOnTouchListener(mTouchListener);
        mTouchListener.bindTouchRects();
    }

    /**
     * Sets onclick listener for feedback icon.
     */
    public void setFeedbackOnClickListener(OnClickListener l) {
        mFeedbackListener = l;
        mFeedbackIcon.setOnClickListener(mFeedbackListener);
        updateTouchListener();
    }

    /**
     * Sets the margin end for the text portion of the header, excluding right-aligned elements
     *
     * @param headerTextMarginEnd margin size
     */
    public void setHeaderTextMarginEnd(int headerTextMarginEnd) {
        if (mHeaderTextMarginEnd != headerTextMarginEnd) {
            mHeaderTextMarginEnd = headerTextMarginEnd;
            requestLayout();
        }
    }

    /**
     * Get the current margin end value for the header text
     *
     * @return margin size
     */
    public int getHeaderTextMarginEnd() {
        return mHeaderTextMarginEnd;
    }

    /**
     * Set padding at the start of the view.
     */
    public void setPaddingStart(int paddingStart) {
        setPaddingRelative(paddingStart, getPaddingTop(), getPaddingEnd(), getPaddingBottom());
    }

    private class HeaderTouchListener implements OnTouchListener {

        private Rect mFeedbackRect;
        private int mTouchSlop;
        private boolean mTrackGesture;
        private float mDownX;
        private float mDownY;

        HeaderTouchListener() {
        }

        public void bindTouchRects() {
            mFeedbackRect = getRectAroundView(mFeedbackIcon);
            mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }

        private Rect getRectAroundView(View view) {
            float size = 48 * getResources().getDisplayMetrics().density;
            float width = Math.max(size, view.getWidth());
            float height = Math.max(size, view.getHeight());
            final Rect r = new Rect();
            if (view.getVisibility() == GONE) {
                view = getFirstChildNotGone();
                r.left = (int) (view.getLeft() - width / 2.0f);
            } else {
                r.left = (int) ((view.getLeft() + view.getRight()) / 2.0f - width / 2.0f);
            }
            r.top = (int) ((view.getTop() + view.getBottom()) / 2.0f - height / 2.0f);
            r.bottom = (int) (r.top + height);
            r.right = (int) (r.left + width);
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
                        mDownX = x;
                        mDownY = y;
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
                    if (mTrackGesture && onTouchUp(x, y, mDownX, mDownY)) {
                        return true;
                    }
                    break;
            }
            return mTrackGesture;
        }

        private boolean onTouchUp(float upX, float upY, float downX, float downY) {
            if (mFeedbackIcon.isVisibleToUser()
                    && (mFeedbackRect.contains((int) upX, (int) upY)
                    || mFeedbackRect.contains((int) downX, (int) downY))) {
                mFeedbackIcon.performClick();
                return true;
            }
            return false;
        }

        private boolean isInside(float x, float y) {
            return mFeedbackRect.contains((int) x, (int) y);
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

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * Determine if the given point is touching an active part of the top line.
     */
    public boolean isInTouchRect(float x, float y) {
        if (mFeedbackListener == null) {
            return false;
        }
        return mTouchListener.isInside(x, y);
    }

    /**
     * Perform a click on an active part of the top line, if touching.
     */
    public boolean onTouchUp(float upX, float upY, float downX, float downY) {
        if (mFeedbackListener == null) {
            return false;
        }
        return mTouchListener.onTouchUp(upX, upY, downX, downY);
    }

    private final class OverflowAdjuster {
        private int mOverflow;
        private int mHeightSpec;
        private View mRegrowView;

        OverflowAdjuster resetForOverflow(int overflow, int heightSpec) {
            mOverflow = overflow;
            mHeightSpec = heightSpec;
            mRegrowView = null;
            return this;
        }

        /**
         * Shrink the targetView's width by up to overFlow, down to minimumWidth.
         * @param targetView the view to shrink the width of
         * @param targetDivider a divider view which should be set to 0 width if the targetView is
         * @param minimumWidth the minimum width allowed for the targetView
         * @return this object
         */
        OverflowAdjuster adjust(View targetView, View targetDivider, int minimumWidth) {
            if (mOverflow <= 0 || targetView == null || targetView.getVisibility() == View.GONE) {
                return this;
            }
            final int oldWidth = targetView.getMeasuredWidth();
            if (oldWidth <= minimumWidth) {
                return this;
            }
            // we're too big
            int newSize = Math.max(minimumWidth, oldWidth - mOverflow);
            if (minimumWidth == 0 && newSize < mChildHideWidth
                    && mRegrowView != null && mRegrowView != targetView) {
                // View is so small it's better to hide it entirely (and its divider and margins)
                // so we can give that space back to another previously shrunken view.
                newSize = 0;
            }

            int childWidthSpec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST);
            targetView.measure(childWidthSpec, mHeightSpec);
            mOverflow -= oldWidth - newSize;

            if (newSize == 0) {
                mViewsToDisappear.add(targetView);
                mOverflow -= getHorizontalMargins(targetView);
                if (targetDivider != null && targetDivider.getVisibility() != GONE) {
                    mViewsToDisappear.add(targetDivider);
                    int oldDividerWidth = targetDivider.getMeasuredWidth();
                    int dividerWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.AT_MOST);
                    targetDivider.measure(dividerWidthSpec, mHeightSpec);
                    mOverflow -= (oldDividerWidth + getHorizontalMargins(targetDivider));
                }
            }
            if (mOverflow < 0 && mRegrowView != null) {
                // We're now under-flowing, so regrow the last view.
                final int regrowCurrentSize = mRegrowView.getMeasuredWidth();
                final int maxSize = regrowCurrentSize - mOverflow;
                int regrowWidthSpec = MeasureSpec.makeMeasureSpec(maxSize, MeasureSpec.AT_MOST);
                mRegrowView.measure(regrowWidthSpec, mHeightSpec);
                finish();
                return this;
            }

            if (newSize != 0) {
                // if we shrunk this view (but did not completely hide it) store it for potential
                // re-growth if we proactively shorten a future view.
                mRegrowView = targetView;
            }
            return this;
        }

        void finish() {
            resetForOverflow(0, 0);
        }

        private int getHorizontalMargins(View view) {
            MarginLayoutParams params = (MarginLayoutParams) view.getLayoutParams();
            return params.getMarginStart() + params.getMarginEnd();
        }
    }
}
