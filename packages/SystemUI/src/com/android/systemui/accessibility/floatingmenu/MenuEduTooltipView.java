/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.SuppressLint;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.recents.TriangleShape;

/**
 * The tooltip view shows the information about the operation of the anchor view {@link MenuView}
 * . It's just shown on the left or right of the anchor view.
 */
@SuppressLint("ViewConstructor")
class MenuEduTooltipView extends FrameLayout implements ComponentCallbacks {
    private int mFontSize;
    private int mTextViewMargin;
    private int mTextViewPadding;
    private int mTextViewCornerRadius;
    private int mArrowMargin;
    private int mArrowWidth;
    private int mArrowHeight;
    private int mArrowCornerRadius;
    private int mColorAccentPrimary;
    private View mArrowLeftView;
    private View mArrowRightView;
    private TextView mMessageView;
    private final MenuViewAppearance mMenuViewAppearance;

    MenuEduTooltipView(@NonNull Context context, MenuViewAppearance menuViewAppearance) {
        super(context);

        mMenuViewAppearance = menuViewAppearance;

        updateResources();
        initViews();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        updateResources();
        updateMessageView();
        updateArrowView();

        updateLocationAndVisibility();
    }

    @Override
    public void onLowMemory() {
        // Do nothing.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        getContext().registerComponentCallbacks(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getContext().unregisterComponentCallbacks(this);
    }

    void show(CharSequence message) {
        mMessageView.setText(message);

        updateLocationAndVisibility();
    }

    void updateLocationAndVisibility() {
        final boolean isTooltipOnRightOfAnchor = mMenuViewAppearance.isMenuOnLeftSide();
        updateArrowVisibilityWith(isTooltipOnRightOfAnchor);
        updateLocationWith(getMenuBoundsInParent(), isTooltipOnRightOfAnchor);
    }

    /**
     * Gets the bounds of the {@link MenuView}. Besides, its parent view {@link MenuViewLayer} is
     * also the root view of the tooltip view.
     *
     * @return The menu bounds based on its parent view.
     */
    private Rect getMenuBoundsInParent() {
        final Rect bounds = new Rect();
        final PointF position = mMenuViewAppearance.getMenuPosition();

        bounds.set((int) position.x, (int) position.y,
                (int) position.x + mMenuViewAppearance.getMenuWidth(),
                (int) position.y + mMenuViewAppearance.getMenuHeight());

        return bounds;
    }

    private void updateResources() {
        final Resources res = getResources();

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
        mColorAccentPrimary = Utils.getColorAttrDefaultColor(getContext(),
                com.android.internal.R.attr.colorAccentPrimary);
    }

    private void updateLocationWith(Rect anchorBoundsInParent, boolean isTooltipOnRightOfAnchor) {
        final int widthSpec = MeasureSpec.makeMeasureSpec(
                getAvailableTextViewWidth(isTooltipOnRightOfAnchor), AT_MOST);
        final int heightSpec = MeasureSpec.makeMeasureSpec(/* size= */ 0, UNSPECIFIED);
        mMessageView.measure(widthSpec, heightSpec);
        final LinearLayout.LayoutParams textViewParams =
                (LinearLayout.LayoutParams) mMessageView.getLayoutParams();
        textViewParams.width = mMessageView.getMeasuredWidth();
        mMessageView.setLayoutParams(textViewParams);

        final int layoutWidth = mMessageView.getMeasuredWidth() + mArrowWidth + mArrowMargin;
        setTranslationX(isTooltipOnRightOfAnchor
                ? anchorBoundsInParent.right
                : anchorBoundsInParent.left - layoutWidth);

        setTranslationY(anchorBoundsInParent.centerY() - (mMessageView.getMeasuredHeight() / 2.0f));
    }

    private void updateMessageView() {
        mMessageView.setTextSize(COMPLEX_UNIT_PX, mFontSize);
        mMessageView.setPadding(mTextViewPadding, mTextViewPadding, mTextViewPadding,
                mTextViewPadding);

        final GradientDrawable gradientDrawable = (GradientDrawable) mMessageView.getBackground();
        gradientDrawable.setCornerRadius(mTextViewCornerRadius);
        gradientDrawable.setColor(mColorAccentPrimary);
    }

    private void updateArrowView() {
        drawArrow(mArrowLeftView, /* isPointingLeft= */ true);
        drawArrow(mArrowRightView, /* isPointingLeft= */ false);
    }

    private void updateArrowVisibilityWith(boolean isTooltipOnRightOfAnchor) {
        if (isTooltipOnRightOfAnchor) {
            mArrowLeftView.setVisibility(VISIBLE);
            mArrowRightView.setVisibility(GONE);
        } else {
            mArrowLeftView.setVisibility(GONE);
            mArrowRightView.setVisibility(VISIBLE);
        }
    }

    private void drawArrow(View arrowView, boolean isPointingLeft) {
        final TriangleShape triangleShape =
                TriangleShape.createHorizontal(mArrowWidth, mArrowHeight, isPointingLeft);
        final ShapeDrawable arrowDrawable = new ShapeDrawable(triangleShape);
        final Paint arrowPaint = arrowDrawable.getPaint();
        arrowPaint.setColor(mColorAccentPrimary);

        final CornerPathEffect effect = new CornerPathEffect(mArrowCornerRadius);
        arrowPaint.setPathEffect(effect);

        arrowView.setBackground(arrowDrawable);
    }

    private void initViews() {
        final View contentView = LayoutInflater.from(getContext()).inflate(
                R.layout.accessibility_floating_menu_tooltip, /* root= */ this, /* attachToRoot= */
                false);

        mMessageView = contentView.findViewById(R.id.text);
        mMessageView.setMovementMethod(LinkMovementMethod.getInstance());

        mArrowLeftView = contentView.findViewById(R.id.arrow_left);
        mArrowRightView = contentView.findViewById(R.id.arrow_right);

        updateMessageView();
        updateArrowView();

        addView(contentView);
    }

    private int getAvailableTextViewWidth(boolean isOnRightOfAnchor) {
        final PointF position = mMenuViewAppearance.getMenuPosition();
        final int availableWidth = isOnRightOfAnchor
                ? mMenuViewAppearance.getMenuDraggableBounds().width() - (int) position.x
                : (int) position.x;

        return availableWidth - mArrowWidth - mArrowMargin - mTextViewMargin;
    }
}
