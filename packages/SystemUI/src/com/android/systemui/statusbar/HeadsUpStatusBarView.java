/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

/**
 * The view in the statusBar that contains part of the heads-up information
 */
public class HeadsUpStatusBarView extends AlphaOptimizedLinearLayout {
    private static final String HEADS_UP_STATUS_BAR_VIEW_SUPER_PARCELABLE =
            "heads_up_status_bar_view_super_parcelable";
    private static final String FIRST_LAYOUT = "first_layout";
    private static final String VISIBILITY = "visibility";
    private static final String ALPHA = "alpha";
    private int mAbsoluteStartPadding;
    private int mEndMargin;
    private View mIconPlaceholder;
    private TextView mTextView;
    private NotificationEntry mShowingEntry;
    private Rect mLayoutedIconRect = new Rect();
    private int[] mTmpPosition = new int[2];
    private boolean mFirstLayout = true;
    private int mMaxWidth;
    private View mRootView;
    private int mSysWinInset;
    private int mCutOutInset;
    private List<Rect> mCutOutBounds;
    private Rect mIconDrawingRect = new Rect();
    private Point mDisplaySize;
    private Runnable mOnDrawingRectChangedListener;

    public HeadsUpStatusBarView(Context context) {
        this(context, null);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources res = getResources();
        mAbsoluteStartPadding = res.getDimensionPixelSize(R.dimen.notification_side_paddings)
            + res.getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_content_margin_start);
        mEndMargin = res.getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        setPaddingRelative(mAbsoluteStartPadding, 0, mEndMargin, 0);
        updateMaxWidth();
    }

    private void updateMaxWidth() {
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.qs_panel_width);
        if (maxWidth != mMaxWidth) {
            // maxWidth doesn't work with fill_parent, let's manually make it at most as big as the
            // notification panel
            mMaxWidth = maxWidth;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxWidth > 0) {
            int newSize = Math.min(MeasureSpec.getSize(widthMeasureSpec), mMaxWidth);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(newSize,
                    MeasureSpec.getMode(widthMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateMaxWidth();
    }

    @Override
    public Bundle onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(HEADS_UP_STATUS_BAR_VIEW_SUPER_PARCELABLE,
                super.onSaveInstanceState());
        bundle.putBoolean(FIRST_LAYOUT, mFirstLayout);
        bundle.putInt(VISIBILITY, getVisibility());
        bundle.putFloat(ALPHA, getAlpha());

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !(state instanceof Bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }

        Bundle bundle = (Bundle) state;
        Parcelable superState = bundle.getParcelable(HEADS_UP_STATUS_BAR_VIEW_SUPER_PARCELABLE);
        super.onRestoreInstanceState(superState);
        mFirstLayout = bundle.getBoolean(FIRST_LAYOUT, true);
        if (bundle.containsKey(VISIBILITY)) {
            setVisibility(bundle.getInt(VISIBILITY));
        }
        if (bundle.containsKey(ALPHA)) {
            setAlpha(bundle.getFloat(ALPHA));
        }
    }

    @VisibleForTesting
    public HeadsUpStatusBarView(Context context, View iconPlaceholder, TextView textView) {
        this(context);
        mIconPlaceholder = iconPlaceholder;
        mTextView = textView;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIconPlaceholder = findViewById(R.id.icon_placeholder);
        mTextView = findViewById(R.id.text);
    }

    public void setEntry(NotificationEntry entry) {
        if (entry != null) {
            mShowingEntry = entry;
            CharSequence text = entry.headsUpStatusBarText;
            if (entry.isSensitive()) {
                text = entry.headsUpStatusBarTextPublic;
            }
            mTextView.setText(text);
            mShowingEntry.setOnSensitiveChangedListener(() -> setEntry(entry));
        } else if (mShowingEntry != null){
            mShowingEntry.setOnSensitiveChangedListener(null);
            mShowingEntry = null;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mIconPlaceholder.getLocationOnScreen(mTmpPosition);
        int left = (int) (mTmpPosition[0] - getTranslationX());
        int top = mTmpPosition[1];
        int right = left + mIconPlaceholder.getWidth();
        int bottom = top + mIconPlaceholder.getHeight();
        mLayoutedIconRect.set(left, top, right, bottom);
        updateDrawingRect();
        int targetPadding = mAbsoluteStartPadding + mSysWinInset + mCutOutInset;
        boolean isRtl = isLayoutRtl();
        int start = isRtl ? (mDisplaySize.x - right) : left;

        if (start != targetPadding) {
            if (mCutOutBounds != null) {
                for (Rect cutOutRect : mCutOutBounds) {
                    int cutOutStart = (isRtl)
                            ? (mDisplaySize.x - cutOutRect.right) : cutOutRect.left;
                    if (start > cutOutStart) {
                        start -= cutOutRect.width();
                        break;
                    }
                }
            }

            int newPadding = targetPadding - start + getPaddingStart();
            setPaddingRelative(newPadding, 0, mEndMargin, 0);
        }
        if (mFirstLayout) {
            // we need to do the padding calculation in the first frame, so the layout specified
            // our visibility to be INVISIBLE in the beginning. let's correct that and set it
            // to GONE.
            setVisibility(GONE);
            mFirstLayout = false;
        }
    }

    /** In order to do UI alignment, this view will be notified by
     * {@link com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout}.
     * After scroller laid out, the scroller will tell this view about scroller's getX()
     * @param translationX how to translate the horizontal position
     */
    public void setPanelTranslation(float translationX) {
        setTranslationX(translationX);
        updateDrawingRect();
    }

    private void updateDrawingRect() {
        float oldLeft = mIconDrawingRect.left;
        mIconDrawingRect.set(mLayoutedIconRect);
        mIconDrawingRect.offset((int) getTranslationX(), 0);
        if (oldLeft != mIconDrawingRect.left && mOnDrawingRectChangedListener != null) {
            mOnDrawingRectChangedListener.run();
        }
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        boolean isRtl = isLayoutRtl();
        mSysWinInset = isRtl ? insets.right : insets.left;
        DisplayCutout displayCutout = getRootWindowInsets().getDisplayCutout();
        mCutOutInset = (displayCutout != null)
                ? (isRtl ? displayCutout.getSafeInsetRight() : displayCutout.getSafeInsetLeft())
                : 0;

        getDisplaySize();

        mCutOutBounds = null;
        if (displayCutout != null && displayCutout.getSafeInsetRight() == 0
                && displayCutout.getSafeInsetLeft() == 0) {
            mCutOutBounds = displayCutout.getBoundingRects();
        }

        // For Double Cut Out mode, the System window navigation bar is at the right
        // side of the left cut out. In this condition, mSysWinInset include the left cut
        // out width so we set mCutOutInset to be 0. For RTL, the condition is the same.
        // The navigation bar is at the left side of the right cut out and include the
        // right cut out width.
        if (mSysWinInset != 0) {
            mCutOutInset = 0;
        }

        return super.fitSystemWindows(insets);
    }

    public NotificationEntry getShowingEntry() {
        return mShowingEntry;
    }

    public Rect getIconDrawingRect() {
        return mIconDrawingRect;
    }

    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTextView.setTextColor(DarkIconDispatcher.getTint(area, this, tint));
    }

    public void setOnDrawingRectChangedListener(Runnable onDrawingRectChangedListener) {
        mOnDrawingRectChangedListener = onDrawingRectChangedListener;
    }

    private void getDisplaySize() {
        if (mDisplaySize == null) {
            mDisplaySize = new Point();
        }
        getDisplay().getRealSize(mDisplaySize);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getDisplaySize();
    }
}
