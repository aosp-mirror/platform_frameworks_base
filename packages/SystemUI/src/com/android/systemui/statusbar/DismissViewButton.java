/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.android.systemui.R;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

public class DismissViewButton extends Button {
    private AnimatedVectorDrawable mAnimatedDismissDrawable;
    private final Drawable mStaticDismissDrawable;
    private Drawable mActiveDrawable;

    public DismissViewButton(Context context) {
        this(context, null);
    }

    public DismissViewButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DismissViewButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DismissViewButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mAnimatedDismissDrawable = (AnimatedVectorDrawable) getContext().getDrawable(
                R.drawable.dismiss_all_shape_animation).mutate();
        mAnimatedDismissDrawable.setCallback(this);
        mAnimatedDismissDrawable.setBounds(0,
                0,
                mAnimatedDismissDrawable.getIntrinsicWidth(),
                mAnimatedDismissDrawable.getIntrinsicHeight());
        mStaticDismissDrawable = getContext().getDrawable(R.drawable.dismiss_all_shape);
        mStaticDismissDrawable.setBounds(0,
                0,
                mStaticDismissDrawable.getIntrinsicWidth(),
                mStaticDismissDrawable.getIntrinsicHeight());
        mStaticDismissDrawable.setCallback(this);
        mActiveDrawable = mStaticDismissDrawable;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        int drawableHeight = mActiveDrawable.getBounds().height();
        boolean isRtl = (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        int dx = isRtl ? getWidth() / 2 + drawableHeight / 2 : getWidth() / 2 - drawableHeight / 2;
        canvas.translate(dx, getHeight() / 2.0f + drawableHeight /
                2.0f);
        canvas.scale(isRtl ? -1.0f : 1.0f, -1.0f);
        mActiveDrawable.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean performClick() {
        if (!mAnimatedDismissDrawable.isRunning()) {
            mActiveDrawable = mAnimatedDismissDrawable;
            mAnimatedDismissDrawable.start();
        }
        return super.performClick();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who)
                || who == mAnimatedDismissDrawable
                || who == mStaticDismissDrawable;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children in the {@link NotificationStackScrollLayout} at
     * position 0 and usually the translation is neglected. The standard implementation doesn't
     * account for translation.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = ((ViewGroup) mParent).getTranslationX();
        float translationY = ((ViewGroup) mParent).getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    public void showButton() {
        mActiveDrawable = mStaticDismissDrawable;
        invalidate();
    }

    /**
     * @return Whether the button is currently static and not being animated.
     */
    public boolean isButtonStatic() {
        return mActiveDrawable == mStaticDismissDrawable;
    }
}
