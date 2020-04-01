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
import android.app.AppOpsManager;
import android.app.Notification;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.widget.CachingIconView;
import com.android.internal.widget.NotificationExpandButton;

import java.util.ArrayList;

/**
 * A header of a notification view
 *
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationHeaderView extends ViewGroup {
    public static final int NO_COLOR = Notification.COLOR_INVALID;
    private final int mChildMinWidth;
    private final int mContentEndMargin;
    private final int mGravity;
    private View mAppName;
    private View mHeaderText;
    private View mSecondaryHeaderText;
    private OnClickListener mExpandClickListener;
    private OnClickListener mAppOpsListener;
    private HeaderTouchListener mTouchListener = new HeaderTouchListener();
    private LinearLayout mTransferChip;
    private NotificationExpandButton mExpandButton;
    private CachingIconView mIcon;
    private View mProfileBadge;
    private View mAppOps;
    private boolean mExpanded;
    private boolean mShowExpandButtonAtEnd;
    private boolean mShowWorkBadgeAtEnd;
    private int mHeaderTextMarginEnd;
    private Drawable mBackground;
    private boolean mEntireHeaderClickable;
    private boolean mExpandOnlyOnButton;
    private boolean mAcceptAllTouches;
    private int mTotalWidth;

    ViewOutlineProvider mProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (mBackground != null) {
                outline.setRect(0, 0, getWidth(), getHeight());
                outline.setAlpha(1f);
            }
        }
    };

    public NotificationHeaderView(Context context) {
        this(context, null);
    }

    @UnsupportedAppUsage
    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationHeaderView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources res = getResources();
        mChildMinWidth = res.getDimensionPixelSize(R.dimen.notification_header_shrink_min_width);
        mContentEndMargin = res.getDimensionPixelSize(R.dimen.notification_content_margin_end);
        mEntireHeaderClickable = res.getBoolean(R.bool.config_notificationHeaderClickableForExpand);

        int[] attrIds = { android.R.attr.gravity };
        TypedArray ta = context.obtainStyledAttributes(attrs, attrIds, defStyleAttr, defStyleRes);
        mGravity = ta.getInt(0, 0);
        ta.recycle();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAppName = findViewById(com.android.internal.R.id.app_name_text);
        mHeaderText = findViewById(com.android.internal.R.id.header_text);
        mSecondaryHeaderText = findViewById(com.android.internal.R.id.header_text_secondary);
        mTransferChip = findViewById(com.android.internal.R.id.media_seamless);
        mExpandButton = findViewById(com.android.internal.R.id.expand_button);
        mIcon = findViewById(com.android.internal.R.id.icon);
        mProfileBadge = findViewById(com.android.internal.R.id.profile_badge);
        mAppOps = findViewById(com.android.internal.R.id.app_ops);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int givenWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int givenHeight = MeasureSpec.getSize(heightMeasureSpec);
        int wrapContentWidthSpec = MeasureSpec.makeMeasureSpec(givenWidth,
                MeasureSpec.AT_MOST);
        int wrapContentHeightSpec = MeasureSpec.makeMeasureSpec(givenHeight,
                MeasureSpec.AT_MOST);
        int totalWidth = getPaddingStart();
        int iconWidth = getPaddingEnd();
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
            // Icons that should go at the end
            if ((child == mExpandButton && mShowExpandButtonAtEnd)
                    || child == mProfileBadge
                    || child == mAppOps
                    || child == mTransferChip) {
                iconWidth += lp.leftMargin + lp.rightMargin + child.getMeasuredWidth();
            } else {
                totalWidth += lp.leftMargin + lp.rightMargin + child.getMeasuredWidth();
            }
        }

        // Ensure that there is at least enough space for the icons
        int endMargin = Math.max(mHeaderTextMarginEnd, iconWidth);
        if (totalWidth > givenWidth - endMargin) {
            int overFlow = totalWidth - givenWidth + endMargin;
            // We are overflowing, lets shrink the app name first
            overFlow = shrinkViewForOverflow(wrapContentHeightSpec, overFlow, mAppName,
                    mChildMinWidth);

            // still overflowing, we shrink the header text
            overFlow = shrinkViewForOverflow(wrapContentHeightSpec, overFlow, mHeaderText, 0);

            // still overflowing, finally we shrink the secondary header text
            shrinkViewForOverflow(wrapContentHeightSpec, overFlow, mSecondaryHeaderText,
                    0);
        }
        totalWidth += getPaddingEnd();
        mTotalWidth = Math.min(totalWidth, givenWidth);
        setMeasuredDimension(givenWidth, givenHeight);
    }

    private int shrinkViewForOverflow(int heightSpec, int overFlow, View targetView,
            int minimumWidth) {
        final int oldWidth = targetView.getMeasuredWidth();
        if (overFlow > 0 && targetView.getVisibility() != GONE && oldWidth > minimumWidth) {
            // we're still too big
            int newSize = Math.max(minimumWidth, oldWidth - overFlow);
            int childWidthSpec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST);
            targetView.measure(childWidthSpec, heightSpec);
            overFlow -= oldWidth - newSize;
        }
        return overFlow;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left = getPaddingStart();
        int end = getMeasuredWidth();
        final boolean centerAligned = (mGravity & Gravity.CENTER_HORIZONTAL) != 0;
        if (centerAligned) {
            left += getMeasuredWidth() / 2 - mTotalWidth / 2;
        }
        int childCount = getChildCount();
        int ownHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childHeight = child.getMeasuredHeight();
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int layoutLeft;
            int layoutRight;
            int top = (int) (getPaddingTop() + (ownHeight - childHeight) / 2.0f);
            int bottom = top + childHeight;
            // Icons that should go at the end
            if ((child == mExpandButton && mShowExpandButtonAtEnd)
                    || child == mProfileBadge
                    || child == mAppOps
                    || child == mTransferChip) {
                if (end == getMeasuredWidth()) {
                    layoutRight = end - mContentEndMargin;
                } else {
                    layoutRight = end - params.getMarginEnd();
                }
                layoutLeft = layoutRight - child.getMeasuredWidth();
                end = layoutLeft - params.getMarginStart();
            } else {
                left += params.getMarginStart();
                int right = left + child.getMeasuredWidth();
                layoutLeft = left;
                layoutRight = right;
                left = right + params.getMarginEnd();
            }
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                int ltrLeft = layoutLeft;
                layoutLeft = getWidth() - layoutRight;
                layoutRight = getWidth() - ltrLeft;
            }
            child.layout(layoutLeft, top, layoutRight, bottom);
        }
        updateTouchListener();
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new ViewGroup.MarginLayoutParams(getContext(), attrs);
    }

    /**
     * Set a {@link Drawable} to be displayed as a background on the header.
     */
    public void setHeaderBackgroundDrawable(Drawable drawable) {
        if (drawable != null) {
            setWillNotDraw(false);
            mBackground = drawable;
            mBackground.setCallback(this);
            setOutlineProvider(mProvider);
        } else {
            setWillNotDraw(true);
            mBackground = null;
            setOutlineProvider(null);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mBackground != null) {
            mBackground.setBounds(0, 0, getWidth(), getHeight());
            mBackground.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(getDrawableState());
        }
    }

    private void updateTouchListener() {
        if (mExpandClickListener == null && mAppOpsListener == null) {
            setOnTouchListener(null);
            return;
        }
        setOnTouchListener(mTouchListener);
        mTouchListener.bindTouchRects();
    }

    /**
     * Sets onclick listener for app ops icons.
     */
    public void setAppOpsOnClickListener(OnClickListener l) {
        mAppOpsListener = l;
        updateTouchListener();
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        mExpandClickListener = l;
        mExpandButton.setOnClickListener(mExpandClickListener);
        updateTouchListener();
    }

    public int getOriginalIconColor() {
        return mIcon.getOriginalIconColor();
    }

    public int getOriginalNotificationColor() {
        return mExpandButton.getOriginalNotificationColor();
    }

    @RemotableViewMethod
    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
        updateExpandButton();
    }

    private void updateExpandButton() {
        int drawableId;
        int contentDescriptionId;
        if (mExpanded) {
            drawableId = R.drawable.ic_collapse_notification;
            contentDescriptionId = R.string.expand_button_content_description_expanded;
        } else {
            drawableId = R.drawable.ic_expand_notification;
            contentDescriptionId = R.string.expand_button_content_description_collapsed;
        }
        mExpandButton.setImageDrawable(getContext().getDrawable(drawableId));
        mExpandButton.setColorFilter(getOriginalNotificationColor());
        mExpandButton.setContentDescription(mContext.getText(contentDescriptionId));
    }

    public void setShowWorkBadgeAtEnd(boolean showWorkBadgeAtEnd) {
        if (showWorkBadgeAtEnd != mShowWorkBadgeAtEnd) {
            setClipToPadding(!showWorkBadgeAtEnd);
            mShowWorkBadgeAtEnd = showWorkBadgeAtEnd;
        }
    }

    /**
     * Sets whether or not the expand button appears at the end of the NotificationHeaderView. If
     * both this and {@link #setShowWorkBadgeAtEnd(boolean)} have been set to true, then the
     * expand button will appear closer to the end than the work badge.
     */
    public void setShowExpandButtonAtEnd(boolean showExpandButtonAtEnd) {
        if (showExpandButtonAtEnd != mShowExpandButtonAtEnd) {
            setClipToPadding(!showExpandButtonAtEnd);
            mShowExpandButtonAtEnd = showExpandButtonAtEnd;
        }
    }

    public View getWorkProfileIcon() {
        return mProfileBadge;
    }

    public CachingIconView getIcon() {
        return mIcon;
    }

    /**
     * Sets the margin end for the text portion of the header, excluding right-aligned elements
     * @param headerTextMarginEnd margin size
     */
    @RemotableViewMethod
    public void setHeaderTextMarginEnd(int headerTextMarginEnd) {
        if (mHeaderTextMarginEnd != headerTextMarginEnd) {
            mHeaderTextMarginEnd = headerTextMarginEnd;
            requestLayout();
        }
    }

    /**
     * Get the current margin end value for the header text
     * @return margin size
     */
    public int getHeaderTextMarginEnd() {
        return mHeaderTextMarginEnd;
    }

    public class HeaderTouchListener implements View.OnTouchListener {

        private final ArrayList<Rect> mTouchRects = new ArrayList<>();
        private Rect mExpandButtonRect;
        private Rect mAppOpsRect;
        private int mTouchSlop;
        private boolean mTrackGesture;
        private float mDownX;
        private float mDownY;

        public HeaderTouchListener() {
        }

        public void bindTouchRects() {
            mTouchRects.clear();
            addRectAroundView(mIcon);
            mExpandButtonRect = addRectAroundView(mExpandButton);
            mAppOpsRect = addRectAroundView(mAppOps);
            addWidthRect();
            mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }

        private void addWidthRect() {
            Rect r = new Rect();
            r.top = 0;
            r.bottom = (int) (32 * getResources().getDisplayMetrics().density);
            r.left = 0;
            r.right = getWidth();
            mTouchRects.add(r);
        }

        private Rect addRectAroundView(View view) {
            final Rect r = getRectAroundView(view);
            mTouchRects.add(r);
            return r;
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
                    if (mTrackGesture) {
                        if (mAppOps.isVisibleToUser() && (mAppOpsRect.contains((int) x, (int) y)
                                || mAppOpsRect.contains((int) mDownX, (int) mDownY))) {
                            mAppOps.performClick();
                            return true;
                        }
                        mExpandButton.performClick();
                    }
                    break;
            }
            return mTrackGesture;
        }

        private boolean isInside(float x, float y) {
            if (mAcceptAllTouches) {
                return true;
            }
            if (mExpandOnlyOnButton) {
                return mExpandButtonRect.contains((int) x, (int) y);
            }
            for (int i = 0; i < mTouchRects.size(); i++) {
                Rect r = mTouchRects.get(i);
                if (r.contains((int) x, (int) y)) {
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

    public ImageView getExpandButton() {
        return mExpandButton;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public boolean isInTouchRect(float x, float y) {
        if (mExpandClickListener == null) {
            return false;
        }
        return mTouchListener.isInside(x, y);
    }

    /**
     * Sets whether or not all touches to this header view will register as a click. Note that
     * if the config value for {@code config_notificationHeaderClickableForExpand} is {@code true},
     * then calling this method with {@code false} will not override that configuration.
     */
    @RemotableViewMethod
    public void setAcceptAllTouches(boolean acceptAllTouches) {
        mAcceptAllTouches = mEntireHeaderClickable || acceptAllTouches;
    }

    /**
     * Sets whether only the expand icon itself should serve as the expand target.
     */
    @RemotableViewMethod
    public void setExpandOnlyOnButton(boolean expandOnlyOnButton) {
        mExpandOnlyOnButton = expandOnlyOnButton;
    }
}
