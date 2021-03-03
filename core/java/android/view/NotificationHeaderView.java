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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.RelativeLayout;
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
public class NotificationHeaderView extends RelativeLayout {
    private final int mHeadingEndMargin;
    private final int mTouchableHeight;
    private OnClickListener mExpandClickListener;
    private HeaderTouchListener mTouchListener = new HeaderTouchListener();
    private NotificationTopLineView mTopLineView;
    private NotificationExpandButton mExpandButton;
    private View mAltExpandTarget;
    private CachingIconView mIcon;
    private Drawable mBackground;
    private boolean mEntireHeaderClickable;
    private boolean mExpandOnlyOnButton;
    private boolean mAcceptAllTouches;

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

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationHeaderView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources res = getResources();
        mHeadingEndMargin = res.getDimensionPixelSize(R.dimen.notification_heading_margin_end);
        mTouchableHeight = res.getDimensionPixelSize(R.dimen.notification_header_touchable_height);
        mEntireHeaderClickable = res.getBoolean(R.bool.config_notificationHeaderClickableForExpand);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIcon = findViewById(R.id.icon);
        mTopLineView = findViewById(R.id.notification_top_line);
        mExpandButton = findViewById(R.id.expand_button);
        mAltExpandTarget = findViewById(R.id.alternate_expand_target);
        setClipToPadding(false);
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
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(getDrawableState());
        }
    }

    private void updateTouchListener() {
        if (mExpandClickListener == null) {
            setOnTouchListener(null);
            return;
        }
        setOnTouchListener(mTouchListener);
        mTouchListener.bindTouchRects();
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        mExpandClickListener = l;
        mExpandButton.setOnClickListener(mExpandClickListener);
        mAltExpandTarget.setOnClickListener(mExpandClickListener);
        updateTouchListener();
    }

    /**
     * Sets the extra margin at the end of the top line of left-aligned text + icons.
     * This value will have the margin required to accommodate the expand button added to it.
     *
     * @param extraMarginEnd extra margin in px
     */
    public void setTopLineExtraMarginEnd(int extraMarginEnd) {
        mTopLineView.setHeaderTextMarginEnd(extraMarginEnd);
    }

    /**
     * Sets the extra margin at the end of the top line of left-aligned text + icons.
     * This value will have the margin required to accommodate the expand button added to it.
     *
     * @param extraMarginEndDp extra margin in dp
     */
    @RemotableViewMethod
    public void setTopLineExtraMarginEndDp(float extraMarginEndDp) {
        setTopLineExtraMarginEnd(
                (int) (extraMarginEndDp * getResources().getDisplayMetrics().density));
    }

    /**
     * Get the current margin end value for the header text.
     * Add this to {@link #getTopLineBaseMarginEnd()} to get the total margin of the top line.
     *
     * @return extra margin
     */
    public int getTopLineExtraMarginEnd() {
        return mTopLineView.getHeaderTextMarginEnd();
    }

    /**
     * Get the base margin at the end of the top line view.
     * Add this to {@link #getTopLineExtraMarginEnd()} to get the total margin of the top line.
     * <p>
     * NOTE: This method's result is only valid if the expander does not have a number. Currently
     * only groups headers and conversations have numbers, so this is safe to use by MediaStyle.
     *
     * @return base margin
     */
    public int getTopLineBaseMarginEnd() {
        return mHeadingEndMargin;
    }

    /**
     * Handles clicks on the header based on the region tapped.
     */
    public class HeaderTouchListener implements OnTouchListener {

        private final ArrayList<Rect> mTouchRects = new ArrayList<>();
        private Rect mExpandButtonRect;
        private Rect mAltExpandTargetRect;
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
            mAltExpandTargetRect = addRectAroundView(mAltExpandTarget);
            addWidthRect();
            mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }

        private void addWidthRect() {
            Rect r = new Rect();
            r.top = 0;
            r.bottom = mTouchableHeight;
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
                        float topLineX = mTopLineView.getX();
                        float topLineY = mTopLineView.getY();
                        if (mTopLineView.onTouchUp(x - topLineX, y - topLineY,
                                mDownX - topLineX, mDownY - topLineY)) {
                            break;
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
                return mExpandButtonRect.contains((int) x, (int) y)
                        || mAltExpandTargetRect.contains((int) x, (int) y);
            }
            for (int i = 0; i < mTouchRects.size(); i++) {
                Rect r = mTouchRects.get(i);
                if (r.contains((int) x, (int) y)) {
                    return true;
                }
            }
            float topLineX = x - mTopLineView.getX();
            float topLineY = y - mTopLineView.getY();
            return mTopLineView.isInTouchRect(topLineX, topLineY);
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
