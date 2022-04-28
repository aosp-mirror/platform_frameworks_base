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

package com.android.systemui.wallet.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.MathUtils;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

final class DotIndicatorDecoration extends RecyclerView.ItemDecoration {
    private final int mUnselectedRadius;
    private final int mSelectedRadius;
    private final int mDotMargin;
    @ColorInt private final int mUnselectedColor;
    @ColorInt private final int mSelectedColor;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private WalletCardCarousel mCardCarousel;

    DotIndicatorDecoration(Context context) {
        super();
        mUnselectedRadius =
                context.getResources().getDimensionPixelSize(
                        R.dimen.card_carousel_dot_unselected_radius);
        mSelectedRadius =
                context.getResources().getDimensionPixelSize(
                        R.dimen.card_carousel_dot_selected_radius);
        mDotMargin = context.getResources().getDimensionPixelSize(R.dimen.card_carousel_dot_margin);

        mUnselectedColor = context.getColor(R.color.material_dynamic_neutral70);
        mSelectedColor = context.getColor(R.color.material_dynamic_neutral100);
    }

    @Override
    public void getItemOffsets(
            Rect rect, View view, RecyclerView recyclerView, RecyclerView.State state) {
        super.getItemOffsets(rect, view, recyclerView, state);
        if (recyclerView.getAdapter().getItemCount() > 1) {
            rect.bottom =
                    view.getResources().getDimensionPixelSize(R.dimen.card_carousel_dot_offset);
        }
    }

    @Override
    public void onDrawOver(Canvas canvas, RecyclerView recyclerView, RecyclerView.State state) {
        super.onDrawOver(canvas, recyclerView, state);

        mCardCarousel = (WalletCardCarousel) recyclerView;
        int itemCount = recyclerView.getAdapter().getItemCount();
        if (itemCount <= 1) {
            // Only shown if there are at least 2 items, and it's not a shimmer loader
            return;
        }
        canvas.save();

        float animationStartOffset = recyclerView.getWidth() / 6f;
        // 0 when a card is still very prominent, ie. edgeToCenterDistance is greater than
        // animationStartOffset
        // 1 when the two cards are equidistant from the center ie. edgeToCenterDistance == 0
        float interpolatedProgress =
                1 - Math.min(Math.abs(mCardCarousel.mEdgeToCenterDistance), animationStartOffset)
                        / animationStartOffset;

        float totalWidth =
                mDotMargin * (itemCount - 1)
                        + 2 * mUnselectedRadius * (itemCount - 2)
                        + 2 * mSelectedRadius;
        // Translate the canvas so the drawing can always start at (0, 0) coordinates.
        canvas.translate(
                (recyclerView.getWidth() - totalWidth) / 2f,
                recyclerView.getHeight() - mDotMargin);

        int itemsDrawn = 0;
        while (itemsDrawn < itemCount) {
            // count up from 0 to itemCount - 1 if LTR; count down from itemCount - 1 to 0 if RTL.
            int i = isLayoutLtr() ? itemsDrawn : itemCount - itemsDrawn - 1;

            if (isSelectedItem(i)) {
                drawSelectedDot(canvas, interpolatedProgress);
            } else if (isNextItemInScrollingDirection(i)) {
                drawFadingUnselectedDot(canvas, interpolatedProgress);
            } else {
                drawUnselectedDot(canvas);
            }
            canvas.translate(mDotMargin, 0);
            itemsDrawn++;
        }

        canvas.restore();
        this.mCardCarousel = null; // No need to hold a reference.
    }

    private void drawSelectedDot(Canvas canvas, float progress) {
        // Divide progress by 2 because the other half of the animation is done by
        // drawFadingUnselectedDot.
        mPaint.setColor(
                getTransitionAdjustedColor(
                        ColorUtils.blendARGB(mSelectedColor, mUnselectedColor, progress / 2)));
        float radius = MathUtils.lerp(mSelectedRadius, mUnselectedRadius, progress / 2);
        canvas.drawCircle(radius, 0, radius, mPaint);
        canvas.translate(radius * 2, 0);
    }

    private void drawFadingUnselectedDot(Canvas canvas, float progress) {
        // Divide progress by 2 because the first half of the animation is done by drawSelectedDot.
        int blendedColor =
                ColorUtils.blendARGB(
                        mUnselectedColor, mSelectedColor, progress / 2);
        mPaint.setColor(getTransitionAdjustedColor(blendedColor));
        float radius = MathUtils.lerp(mUnselectedRadius, mSelectedColor, progress / 2);
        canvas.drawCircle(radius, 0, radius, mPaint);
        canvas.translate(radius * 2, 0);
    }

    private void drawUnselectedDot(Canvas canvas) {
        mPaint.setColor(mUnselectedColor);
        canvas.drawCircle(mUnselectedRadius, 0, mUnselectedRadius, mPaint);
        canvas.translate(mUnselectedRadius * 2, 0);
    }

    private int getTransitionAdjustedColor(int color) {
        int transitionAlphaOverride = 0xff;
        return ColorUtils.setAlphaComponent(color, transitionAlphaOverride);
    }

    private boolean isSelectedItem(int position) {
        return mCardCarousel.mCenteredAdapterPosition == position;
    }

    private boolean isNextItemInScrollingDirection(int position) {
        if (isLayoutLtr()) {
            return (mCardCarousel.mCenteredAdapterPosition + 1 == position
                    && mCardCarousel.mEdgeToCenterDistance >= 0f)
                    || (mCardCarousel.mCenteredAdapterPosition - 1 == position
                    && mCardCarousel.mEdgeToCenterDistance < 0f);
        }
        return (mCardCarousel.mCenteredAdapterPosition - 1 == position
                && mCardCarousel.mEdgeToCenterDistance >= 0f)
                || (mCardCarousel.mCenteredAdapterPosition + 1 == position
                && mCardCarousel.mEdgeToCenterDistance < 0f);
    }

    private boolean isLayoutLtr() {
        if (mCardCarousel == null) {
            // Shouldn't happen, but assume LTR for now.
            return true;
        }
        return mCardCarousel.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
    }
}
