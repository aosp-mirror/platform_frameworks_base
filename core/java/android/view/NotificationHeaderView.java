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
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.widget.CachingIconView;

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
    private View mAppName;
    private View mHeaderText;
    private OnClickListener mExpandClickListener;
    private HeaderTouchListener mTouchListener = new HeaderTouchListener();
    private ImageView mExpandButton;
    private CachingIconView mIcon;
    private View mProfileBadge;
    private View mInfo;
    private int mIconColor;
    private int mOriginalNotificationColor;
    private boolean mExpanded;
    private boolean mShowExpandButtonAtEnd;
    private boolean mShowWorkBadgeAtEnd;
    private Drawable mBackground;
    private int mHeaderBackgroundHeight;
    private boolean mEntireHeaderClickable;
    private boolean mExpandOnlyOnButton;
    private boolean mAcceptAllTouches;

    ViewOutlineProvider mProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (mBackground != null) {
                outline.setRect(0, 0, getWidth(), mHeaderBackgroundHeight);
                outline.setAlpha(1f);
            }
        }
    };

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
        Resources res = getResources();
        mChildMinWidth = res.getDimensionPixelSize(R.dimen.notification_header_shrink_min_width);
        mContentEndMargin = res.getDimensionPixelSize(R.dimen.notification_content_margin_end);
        mHeaderBackgroundHeight = res.getDimensionPixelSize(
                R.dimen.notification_header_background_height);
        mEntireHeaderClickable = res.getBoolean(R.bool.config_notificationHeaderClickableForExpand);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAppName = findViewById(com.android.internal.R.id.app_name_text);
        mHeaderText = findViewById(com.android.internal.R.id.header_text);
        mExpandButton = findViewById(com.android.internal.R.id.expand_button);
        mIcon = findViewById(com.android.internal.R.id.icon);
        mProfileBadge = findViewById(com.android.internal.R.id.profile_badge);
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
            // We are overflowing, lets shrink the app name first
            final int appWidth = mAppName.getMeasuredWidth();
            if (overFlow > 0 && mAppName.getVisibility() != GONE && appWidth > mChildMinWidth) {
                int newSize = appWidth - Math.min(appWidth - mChildMinWidth, overFlow);
                int childWidthSpec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST);
                mAppName.measure(childWidthSpec, wrapContentHeightSpec);
                overFlow -= appWidth - newSize;
            }
            // still overflowing, finaly we shrink the header text
            if (overFlow > 0 && mHeaderText.getVisibility() != GONE) {
                // we're still too big
                final int textWidth = mHeaderText.getMeasuredWidth();
                int newSize = Math.max(0, textWidth - overFlow);
                int childWidthSpec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST);
                mHeaderText.measure(childWidthSpec, wrapContentHeightSpec);
            }
        }
        setMeasuredDimension(givenWidth, givenHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left = getPaddingStart();
        int end = getMeasuredWidth();
        int childCount = getChildCount();
        int ownHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childHeight = child.getMeasuredHeight();
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            left += params.getMarginStart();
            int right = left + child.getMeasuredWidth();
            int top = (int) (getPaddingTop() + (ownHeight - childHeight) / 2.0f);
            int bottom = top + childHeight;
            int layoutLeft = left;
            int layoutRight = right;
            if (child == mExpandButton && mShowExpandButtonAtEnd) {
                layoutRight = end - mContentEndMargin;
                end = layoutLeft = layoutRight - child.getMeasuredWidth();
            }
            if (child == mProfileBadge) {
                int paddingEnd = getPaddingEnd();
                if (mShowWorkBadgeAtEnd) {
                    paddingEnd = mContentEndMargin;
                }
                layoutRight = end - paddingEnd;
                end = layoutLeft = layoutRight - child.getMeasuredWidth();
            }
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                int ltrLeft = layoutLeft;
                layoutLeft = getWidth() - layoutRight;
                layoutRight = getWidth() - ltrLeft;
            }
            child.layout(layoutLeft, top, layoutRight, bottom);
            left = right + params.getMarginEnd();
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
            mBackground.setBounds(0, 0, getWidth(), mHeaderBackgroundHeight);
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
        if (mExpandClickListener != null) {
            mTouchListener.bindTouchRects();
        }
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        mExpandClickListener = l;
        setOnTouchListener(mExpandClickListener != null ? mTouchListener : null);
        mExpandButton.setOnClickListener(mExpandClickListener);
        updateTouchListener();
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
        mExpandButton.setColorFilter(mOriginalNotificationColor);
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

    public class HeaderTouchListener implements View.OnTouchListener {

        private final ArrayList<Rect> mTouchRects = new ArrayList<>();
        private Rect mExpandButtonRect;
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
