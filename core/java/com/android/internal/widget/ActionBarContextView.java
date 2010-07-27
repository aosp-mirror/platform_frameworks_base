/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.internal.widget;

import com.android.internal.R;
import com.android.internal.view.menu.ActionMenuView;
import com.android.internal.view.menu.MenuBuilder;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * @hide
 */
public class ActionBarContextView extends ViewGroup {
    // TODO: This must be defined in the default theme
    private static final int CONTENT_HEIGHT_DIP = 50;
    
    private int mItemPadding;
    private int mItemMargin;
    private int mActionSpacing;
    private int mContentHeight;
    
    private CharSequence mTitle;
    private CharSequence mSubtitle;
    
    private ImageButton mCloseButton;
    private View mCustomView;
    private LinearLayout mTitleLayout;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private Drawable mCloseDrawable;
    private ActionMenuView mMenuView;
    
    public ActionBarContextView(Context context) {
        this(context, null, 0);
    }
    
    public ActionBarContextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public ActionBarContextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Theme);
        mItemPadding = a.getDimensionPixelOffset(
                com.android.internal.R.styleable.Theme_actionButtonPadding, 0);
        setBackgroundDrawable(a.getDrawable(
                com.android.internal.R.styleable.Theme_actionBarContextBackground));
        mCloseDrawable = a.getDrawable(
                com.android.internal.R.styleable.Theme_actionBarCloseContextDrawable);
        mItemMargin = mItemPadding / 2;

        mContentHeight =
                (int) (CONTENT_HEIGHT_DIP * getResources().getDisplayMetrics().density + 0.5f);
        a.recycle();
    }
    
    public void setCustomView(View view) {
        if (mCustomView != null) {
            removeView(mCustomView);
        }
        mCustomView = view;
        if (mTitleLayout != null) {
            removeView(mTitleLayout);
            mTitleLayout = null;
        }
        if (view != null) {
            addView(view);
        }
        requestLayout();
    }

    public void setTitle(CharSequence title) {
        mTitle = title;
        initTitle();
    }

    public void setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
        initTitle();
    }

    public CharSequence getTitle() {
        return mTitle;
    }

    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    private void initTitle() {
        if (mTitleLayout == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            mTitleLayout = (LinearLayout) inflater.inflate(R.layout.action_bar_title_item, null);
            mTitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_title);
            mSubtitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_subtitle);
            if (mTitle != null) {
                mTitleView.setText(mTitle);
            }
            if (mSubtitle != null) {
                mSubtitleView.setText(mSubtitle);
            }
            addView(mTitleLayout);
        } else {
            mTitleView.setText(mTitle);
            mSubtitleView.setText(mSubtitle);
            if (mTitleLayout.getParent() == null) {
                addView(mTitleLayout);
            }
        }
    }

    public void initForMode(final ActionMode mode) {
        if (mCloseButton == null) {
            mCloseButton = new ImageButton(getContext());
            mCloseButton.setImageDrawable(mCloseDrawable);
            mCloseButton.setBackgroundDrawable(null);
        }
        mCloseButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mode.finish();
            }
        });
        addView(mCloseButton);

        final MenuBuilder menu = (MenuBuilder) mode.getMenu();
        mMenuView = (ActionMenuView) menu.getMenuView(MenuBuilder.TYPE_ACTION_BUTTON, this);
        mMenuView.setOverflowReserved(true);
        mMenuView.updateChildren(false);
        addView(mMenuView);
    }

    public void closeMode() {
        removeAllViews();
        mCustomView = null;
        mMenuView = null;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        // Used by custom views if they don't supply layout params. Everything else
        // added to an ActionBarContextView should have them already.
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode != MeasureSpec.EXACTLY) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_width=\"match_parent\" (or fill_parent)");
        }

        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode != MeasureSpec.AT_MOST) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_height=\"wrap_content\"");
        }
        
        final int contentWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int itemMargin = mItemPadding;

        int availableWidth = contentWidth - getPaddingLeft() - getPaddingRight();
        final int height = mContentHeight - getPaddingTop() - getPaddingBottom();
        final int childSpecHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
        
        if (mCloseButton != null) {
            availableWidth = measureChildView(mCloseButton, availableWidth,
                    childSpecHeight, itemMargin);
        }

        if (mTitleLayout != null && mCustomView == null) {
            availableWidth = measureChildView(mTitleLayout, availableWidth,
                    childSpecHeight, itemMargin);
        }

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child == mCloseButton || child == mTitleLayout || child == mCustomView) {
                continue;
            }
            
            availableWidth = measureChildView(child, availableWidth, childSpecHeight, itemMargin);
        }

        if (mCustomView != null) {
            LayoutParams lp = mCustomView.getLayoutParams();
            final int customWidthMode = lp.width != LayoutParams.WRAP_CONTENT ?
                    MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            final int customWidth = lp.width >= 0 ?
                    Math.min(lp.width, availableWidth) : availableWidth;
            final int customHeightMode = lp.height != LayoutParams.WRAP_CONTENT ?
                    MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            final int customHeight = lp.height >= 0 ?
                    Math.min(lp.height, height) : height;
            mCustomView.measure(MeasureSpec.makeMeasureSpec(customWidth, customWidthMode),
                    MeasureSpec.makeMeasureSpec(customHeight, customHeightMode));
        }

        setMeasuredDimension(contentWidth, mContentHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = getPaddingLeft();
        final int y = getPaddingTop();
        final int contentHeight = b - t - getPaddingTop() - getPaddingBottom();
        final int itemMargin = mItemPadding;
        
        if (mCloseButton != null && mCloseButton.getVisibility() != GONE) {
            x += positionChild(mCloseButton, x, y, contentHeight);
        }
        
        if (mTitleLayout != null && mCustomView == null) {
            x += positionChild(mTitleLayout, x, y, contentHeight) + itemMargin;
        }
        
        if (mCustomView != null) {
            x += positionChild(mCustomView, x, y, contentHeight) + itemMargin;
        }
        
        x = r - l - getPaddingRight();

        if (mMenuView != null) {
            x -= positionChildInverse(mMenuView, x + mActionSpacing, y, contentHeight)
                    - mActionSpacing;
        }
    }

    private int measureChildView(View child, int availableWidth, int childSpecHeight, int spacing) {
        child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                childSpecHeight);

        availableWidth -= child.getMeasuredWidth();
        availableWidth -= spacing;

        return availableWidth;
    }
    
    private int positionChild(View child, int x, int y, int contentHeight) {
        int childWidth = child.getMeasuredWidth();
        int childHeight = child.getMeasuredHeight();
        int childTop = y + (contentHeight - childHeight) / 2;

        child.layout(x, childTop, x + childWidth, childTop + childHeight);

        return childWidth;
    }
    
    private int positionChildInverse(View child, int x, int y, int contentHeight) {
        int childWidth = child.getMeasuredWidth();
        int childHeight = child.getMeasuredHeight();
        int childTop = y + (contentHeight - childHeight) / 2;

        child.layout(x - childWidth, childTop, x, childTop + childHeight);

        return childWidth;
    }
}
