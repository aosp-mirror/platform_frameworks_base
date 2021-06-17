/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static android.view.View.MeasureSpec.AT_MOST;
import static android.view.View.MeasureSpec.UNSPECIFIED;

import android.annotation.UiContext;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;
import android.text.method.MovementMethod;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;

/**
 * Base tooltip view that shows the information about the operation of the
 * Accessibility floating menu. In addition, the anchor view is only for {@link
 * AccessibilityFloatingMenuView}, it should be more suited for displaying one-off menus to avoid
 * the performance hit for the extra window.
 */
class BaseTooltipView extends FrameLayout {
    private int mFontSize;
    private int mTextViewMargin;
    private int mTextViewPadding;
    private int mTextViewCornerRadius;
    private int mArrowMargin;
    private int mArrowWidth;
    private int mArrowHeight;
    private int mArrowCornerRadius;
    private int mScreenWidth;
    private boolean mIsShowing;
    private TextView mTextView;
    private final WindowManager.LayoutParams mCurrentLayoutParams;
    private final WindowManager mWindowManager;
    private final AccessibilityFloatingMenuView mAnchorView;

    BaseTooltipView(@UiContext Context context, AccessibilityFloatingMenuView anchorView) {
        super(context);
        mWindowManager = context.getSystemService(WindowManager.class);
        mAnchorView = anchorView;
        mCurrentLayoutParams = createDefaultLayoutParams();

        initViews();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mAnchorView.onConfigurationChanged(newConfig);
        updateTooltipView();

        mWindowManager.updateViewLayout(this, mCurrentLayoutParams);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            hide();
        }

        return super.onTouchEvent(event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);

        info.addAction(AccessibilityAction.ACTION_DISMISS);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityAction.ACTION_DISMISS.getId()) {
            hide();
            return true;
        }

        return super.performAccessibilityAction(action, arguments);
    }

    void show() {
        if (isShowing()) {
            return;
        }

        mIsShowing = true;
        updateTooltipView();

        mWindowManager.addView(this, mCurrentLayoutParams);
    }

    void hide() {
        if (!isShowing()) {
            return;
        }

        mIsShowing = false;
        mWindowManager.removeView(this);
    }

    void setDescription(CharSequence text) {
        mTextView.setText(text);
    }

    void setMovementMethod(MovementMethod movement) {
        mTextView.setMovementMethod(movement);
    }

    private boolean isShowing() {
        return mIsShowing;
    }

    private void initViews() {
        final View contentView =
                LayoutInflater.from(getContext()).inflate(
                        R.layout.accessibility_floating_menu_tooltip, this, false);

        mTextView = contentView.findViewById(R.id.text);

        addView(contentView);
    }

    private static WindowManager.LayoutParams createDefaultLayoutParams() {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.windowAnimations = android.R.style.Animation_Translucent;
        params.gravity = Gravity.START | Gravity.TOP;

        return params;
    }

    private void updateDimensions() {
        final Resources res = getResources();
        final DisplayMetrics dm = res.getDisplayMetrics();
        mScreenWidth = dm.widthPixels;
        mArrowWidth =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_arrow_width);
        mArrowHeight =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_arrow_height);
        mArrowMargin =
                res.getDimensionPixelSize(
                        R.dimen.accessibility_floating_tooltip_arrow_margin);
        mArrowCornerRadius =
                res.getDimensionPixelSize(
                        R.dimen.accessibility_floating_tooltip_arrow_corner_radius);
        mFontSize =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_font_size);
        mTextViewMargin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_margin);
        mTextViewPadding =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_padding);
        mTextViewCornerRadius =
                res.getDimensionPixelSize(
                        R.dimen.accessibility_floating_tooltip_text_corner_radius);
    }

    private void updateTooltipView() {
        updateDimensions();
        updateTextView();

        final Rect anchorViewLocation = mAnchorView.getWindowLocationOnScreen();
        updateArrowWith(anchorViewLocation);
        updateWidthWith(anchorViewLocation);
        updateLocationWith(anchorViewLocation);
    }

    private void updateTextView() {
        mTextView.setTextSize(COMPLEX_UNIT_PX, mFontSize);
        mTextView.setPadding(mTextViewPadding, mTextViewPadding, mTextViewPadding,
                mTextViewPadding);

        final GradientDrawable gradientDrawable = (GradientDrawable) mTextView.getBackground();
        gradientDrawable.setCornerRadius(mTextViewCornerRadius);
        gradientDrawable.setColor(Utils.getColorAttrDefaultColor(getContext(),
                com.android.internal.R.attr.colorAccentPrimary));
    }

    private void updateArrowWith(Rect anchorViewLocation) {
        final boolean isAnchorViewOnLeft = isAnchorViewOnLeft(anchorViewLocation);
        final View arrowView = findViewById(isAnchorViewOnLeft
                ? R.id.arrow_left
                : R.id.arrow_right);
        arrowView.setVisibility(VISIBLE);
        drawArrow(arrowView, isAnchorViewOnLeft);

        final LinearLayout.LayoutParams layoutParams =
                (LinearLayout.LayoutParams) arrowView.getLayoutParams();
        layoutParams.width = mArrowWidth;
        layoutParams.height = mArrowHeight;

        final int leftMargin = isAnchorViewOnLeft ? 0 : mArrowMargin;
        final int rightMargin = isAnchorViewOnLeft ? mArrowMargin : 0;
        layoutParams.setMargins(leftMargin, 0, rightMargin, 0);
        arrowView.setLayoutParams(layoutParams);
    }

    private void updateWidthWith(Rect anchorViewLocation) {
        final ViewGroup.LayoutParams layoutParams = mTextView.getLayoutParams();
        layoutParams.width = getTextWidthWith(anchorViewLocation);
        mTextView.setLayoutParams(layoutParams);
    }

    private void updateLocationWith(Rect anchorViewLocation) {
        mCurrentLayoutParams.x = isAnchorViewOnLeft(anchorViewLocation)
                ? anchorViewLocation.width()
                : mScreenWidth - getWindowWidthWith(anchorViewLocation)
                        - anchorViewLocation.width();
        mCurrentLayoutParams.y =
                anchorViewLocation.centerY() - (getTextHeightWith(anchorViewLocation) / 2);
    }

    private void drawArrow(View view, boolean isPointingLeft) {
        final ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        final TriangleShape triangleShape =
                TriangleShape.createHorizontal(layoutParams.width, layoutParams.height,
                        isPointingLeft);
        final ShapeDrawable arrowDrawable = new ShapeDrawable(triangleShape);
        final Paint arrowPaint = arrowDrawable.getPaint();
        arrowPaint.setColor(Utils.getColorAttrDefaultColor(getContext(),
                com.android.internal.R.attr.colorAccentPrimary));
        final CornerPathEffect effect = new CornerPathEffect(mArrowCornerRadius);
        arrowPaint.setPathEffect(effect);
        view.setBackground(arrowDrawable);
    }

    private boolean isAnchorViewOnLeft(Rect anchorViewLocation) {
        return anchorViewLocation.left < (mScreenWidth / 2);
    }

    private int getTextWidthWith(Rect anchorViewLocation) {
        final int widthSpec =
                MeasureSpec.makeMeasureSpec(getAvailableTextWidthWith(anchorViewLocation), AT_MOST);
        final int heightSpec =
                MeasureSpec.makeMeasureSpec(0, UNSPECIFIED);
        mTextView.measure(widthSpec, heightSpec);
        return mTextView.getMeasuredWidth();
    }

    private int getTextHeightWith(Rect anchorViewLocation) {
        final int widthSpec =
                MeasureSpec.makeMeasureSpec(getAvailableTextWidthWith(anchorViewLocation), AT_MOST);
        final int heightSpec =
                MeasureSpec.makeMeasureSpec(0, UNSPECIFIED);
        mTextView.measure(widthSpec, heightSpec);
        return mTextView.getMeasuredHeight();
    }

    private int getAvailableTextWidthWith(Rect anchorViewLocation) {
        return mScreenWidth - anchorViewLocation.width() - mArrowWidth - mArrowMargin
                - mTextViewMargin;
    }

    private int getWindowWidthWith(Rect anchorViewLocation) {
        return getTextWidthWith(anchorViewLocation) + mArrowWidth + mArrowMargin;
    }
}
