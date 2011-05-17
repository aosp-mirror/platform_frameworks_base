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

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * @hide
 */
public class ActionBarContextView extends ViewGroup implements AnimatorListener {
    private static final String TAG = "ActionBarContextView";

    private int mContentHeight;
    
    private CharSequence mTitle;
    private CharSequence mSubtitle;

    private View mClose;
    private View mCustomView;
    private LinearLayout mTitleLayout;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private int mTitleStyleRes;
    private int mSubtitleStyleRes;
    private ActionMenuView mMenuView;

    private Animator mCurrentAnimation;
    private boolean mAnimateInOnLayout;
    private int mAnimationMode;

    private static final int ANIMATE_IDLE = 0;
    private static final int ANIMATE_IN = 1;
    private static final int ANIMATE_OUT = 2;
    
    public ActionBarContextView(Context context) {
        this(context, null);
    }
    
    public ActionBarContextView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.actionModeStyle);
    }
    
    public ActionBarContextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ActionMode, defStyle, 0);
        setBackgroundDrawable(a.getDrawable(
                com.android.internal.R.styleable.ActionMode_background));
        mTitleStyleRes = a.getResourceId(
                com.android.internal.R.styleable.ActionMode_titleTextStyle, 0);
        mSubtitleStyleRes = a.getResourceId(
                com.android.internal.R.styleable.ActionMode_subtitleTextStyle, 0);

        mContentHeight = a.getLayoutDimension(
                com.android.internal.R.styleable.ActionMode_height, 0);
        a.recycle();
    }
    
    @Override
    public ActionMode startActionModeForChild(View child, ActionMode.Callback callback) {
        // No starting an action mode for an existing action mode UI child! (Where would it go?)
        return null;
    }

    public void setHeight(int height) {
        mContentHeight = height;
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
            inflater.inflate(R.layout.action_bar_title_item, this);
            mTitleLayout = (LinearLayout) getChildAt(getChildCount() - 1);
            mTitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_title);
            mSubtitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_subtitle);
            if (mTitle != null) {
                mTitleView.setText(mTitle);
                if (mTitleStyleRes != 0) {
                    mTitleView.setTextAppearance(mContext, mTitleStyleRes);
                }
            }
            if (mSubtitle != null) {
                mSubtitleView.setText(mSubtitle);
                if (mSubtitleStyleRes != 0) {
                    mSubtitleView.setTextAppearance(mContext, mSubtitleStyleRes);
                }
                mSubtitleView.setVisibility(VISIBLE);
            }
        } else {
            mTitleView.setText(mTitle);
            mSubtitleView.setText(mSubtitle);
            mSubtitleView.setVisibility(mSubtitle != null ? VISIBLE : GONE);
            if (mTitleLayout.getParent() == null) {
                addView(mTitleLayout);
            }
        }
    }

    public void initForMode(final ActionMode mode) {
        if (mClose == null) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mClose = inflater.inflate(R.layout.action_mode_close_item, this, false);
            addView(mClose);
        } else if (mClose.getParent() == null) {
            addView(mClose);
        }

        View closeButton = mClose.findViewById(R.id.action_mode_close_button);
        closeButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mode.finish();
            }
        });

        final MenuBuilder menu = (MenuBuilder) mode.getMenu();
        mMenuView = (ActionMenuView) menu.getMenuView(MenuBuilder.TYPE_ACTION_BUTTON, this);
        mMenuView.setOverflowReserved(true);
        mMenuView.updateChildren(false);
        addView(mMenuView);

        mAnimateInOnLayout = true;
    }

    public void closeMode() {
        if (mAnimationMode == ANIMATE_OUT) {
            // Called again during close; just finish what we were doing.
            return;
        }
        if (mClose == null) {
            killMode();
            return;
        }

        finishAnimation();
        mAnimationMode = ANIMATE_OUT;
        mCurrentAnimation = makeOutAnimation();
        mCurrentAnimation.start();
    }

    private void finishAnimation() {
        final Animator a = mCurrentAnimation;
        if (a != null) {
            mCurrentAnimation = null;
            a.end();
        }
    }

    public void killMode() {
        finishAnimation();
        removeAllViews();
        mCustomView = null;
        mMenuView = null;
        mAnimateInOnLayout = false;
    }

    public boolean showOverflowMenu() {
        if (mMenuView != null) {
            return mMenuView.showOverflowMenu();
        }
        return false;
    }

    public void openOverflowMenu() {
        if (mMenuView != null) {
            mMenuView.openOverflowMenu();
        }
    }

    public boolean hideOverflowMenu() {
        if (mMenuView != null) {
            return mMenuView.hideOverflowMenu();
        }
        return false;
    }

    public boolean isOverflowMenuShowing() {
        if (mMenuView != null) {
            return mMenuView.isOverflowMenuShowing();
        }
        return false;
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
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_height=\"wrap_content\"");
        }
        
        final int contentWidth = MeasureSpec.getSize(widthMeasureSpec);

        int maxHeight = mContentHeight > 0 ?
                mContentHeight : MeasureSpec.getSize(heightMeasureSpec);

        final int verticalPadding = getPaddingTop() + getPaddingBottom();
        int availableWidth = contentWidth - getPaddingLeft() - getPaddingRight();
        final int height = maxHeight - verticalPadding;
        final int childSpecHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
        
        if (mClose != null) {
            availableWidth = measureChildView(mClose, availableWidth, childSpecHeight, 0);
        }

        if (mMenuView != null) {
            availableWidth = measureChildView(mMenuView, availableWidth,
                    childSpecHeight, 0);
        }

        if (mTitleLayout != null && mCustomView == null) {
            availableWidth = measureChildView(mTitleLayout, availableWidth, childSpecHeight, 0);
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

        if (mContentHeight <= 0) {
            int measuredHeight = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View v = getChildAt(i);
                int paddedViewHeight = v.getMeasuredHeight() + verticalPadding;
                if (paddedViewHeight > measuredHeight) {
                    measuredHeight = paddedViewHeight;
                }
            }
            setMeasuredDimension(contentWidth, measuredHeight);
        } else {
            setMeasuredDimension(contentWidth, maxHeight);
        }
    }

    private Animator makeInAnimation() {
        mClose.setTranslationX(-mClose.getWidth());
        ObjectAnimator buttonAnimator = ObjectAnimator.ofFloat(mClose, "translationX", 0);
        buttonAnimator.setDuration(200);
        buttonAnimator.addListener(this);
        buttonAnimator.setInterpolator(new DecelerateInterpolator());

        AnimatorSet set = new AnimatorSet();
        AnimatorSet.Builder b = set.play(buttonAnimator);

        if (mMenuView != null) {
            final int count = mMenuView.getChildCount();
            if (count > 0) {
                for (int i = count - 1, j = 0; i >= 0; i--, j++) {
                    View child = mMenuView.getChildAt(i);
                    child.setScaleY(0);
                    ObjectAnimator a = ObjectAnimator.ofFloat(child, "scaleY", 0, 1);
                    a.setDuration(100);
                    a.setStartDelay(j * 70);
                    b.with(a);
                }
            }
        }

        return set;
    }

    private Animator makeOutAnimation() {
        ObjectAnimator buttonAnimator = ObjectAnimator.ofFloat(mClose, "translationX",
                0, -mClose.getWidth());
        buttonAnimator.setDuration(200);
        buttonAnimator.addListener(this);
        buttonAnimator.setInterpolator(new DecelerateInterpolator());

        AnimatorSet set = new AnimatorSet();
        AnimatorSet.Builder b = set.play(buttonAnimator);

        if (mMenuView != null) {
            final int count = mMenuView.getChildCount();
            if (count > 0) {
                for (int i = 0; i < 0; i++) {
                    View child = mMenuView.getChildAt(i);
                    child.setScaleY(0);
                    ObjectAnimator a = ObjectAnimator.ofFloat(child, "scaleY", 1, 0);
                    a.setDuration(100);
                    a.setStartDelay(i * 70);
                    b.with(a);
                }
            }
        }

        return set;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = getPaddingLeft();
        final int y = getPaddingTop();
        final int contentHeight = b - t - getPaddingTop() - getPaddingBottom();
        
        if (mClose != null && mClose.getVisibility() != GONE) {
            x += positionChild(mClose, x, y, contentHeight);

            if (mAnimateInOnLayout) {
                mAnimationMode = ANIMATE_IN;
                mCurrentAnimation = makeInAnimation();
                mCurrentAnimation.start();
                mAnimateInOnLayout = false;
            }
        }
        
        if (mTitleLayout != null && mCustomView == null) {
            x += positionChild(mTitleLayout, x, y, contentHeight);
        }
        
        if (mCustomView != null) {
            x += positionChild(mCustomView, x, y, contentHeight);
        }
        
        x = r - l - getPaddingRight();

        if (mMenuView != null) {
            x -= positionChildInverse(mMenuView, x, y, contentHeight);
        }
    }

    private int measureChildView(View child, int availableWidth, int childSpecHeight, int spacing) {
        child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                childSpecHeight);

        availableWidth -= child.getMeasuredWidth();
        availableWidth -= spacing;

        return Math.max(0, availableWidth);
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

    @Override
    public void onAnimationStart(Animator animation) {
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        if (mAnimationMode == ANIMATE_OUT) {
            killMode();
        }
        mAnimationMode = ANIMATE_IDLE;
    }

    @Override
    public void onAnimationCancel(Animator animation) {
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
    }
}
