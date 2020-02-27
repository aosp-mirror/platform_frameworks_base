/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.AttributeSet;

import com.android.systemui.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * The screen record drawable draws a colored background and either a countdown or circle to
 * indicate that the screen is being recorded.
 */
public class ScreenRecordDrawable extends DrawableWrapper {
    private Drawable mFillDrawable;
    private int mHorizontalPadding;
    private int mLevel;
    private float mTextSize;
    private float mIconRadius;
    private Paint mPaint;

    /** No-arg constructor used by drawable inflation. */
    public ScreenRecordDrawable() {
        super(null);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Resources.Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);
        setDrawable(r.getDrawable(R.drawable.ic_screen_record_background, theme).mutate());
        mFillDrawable = r.getDrawable(R.drawable.ic_screen_record_background, theme).mutate();
        mHorizontalPadding = r.getDimensionPixelSize(R.dimen.status_bar_horizontal_padding);

        mTextSize = r.getDimensionPixelSize(R.dimen.screenrecord_status_text_size);
        mIconRadius = r.getDimensionPixelSize(R.dimen.screenrecord_status_icon_radius);
        mLevel = attrs.getAttributeIntValue(null, "level", 0);

        mPaint = new Paint();
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setColor(Color.WHITE);
        mPaint.setTextSize(mTextSize);
        mPaint.setFakeBoldText(true);
    }

    @Override
    public boolean canApplyTheme() {
        return mFillDrawable.canApplyTheme() || super.canApplyTheme();
    }

    @Override
    public void applyTheme(Resources.Theme t) {
        super.applyTheme(t);
        mFillDrawable.applyTheme(t);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mFillDrawable.setBounds(bounds);
    }

    @Override
    public boolean onLayoutDirectionChanged(int layoutDirection) {
        mFillDrawable.setLayoutDirection(layoutDirection);
        return super.onLayoutDirectionChanged(layoutDirection);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        mFillDrawable.draw(canvas);

        Rect b = mFillDrawable.getBounds();
        if (mLevel > 0) {
            String val = String.valueOf(mLevel);
            Rect textBounds = new Rect();
            mPaint.getTextBounds(val, 0, val.length(), textBounds);
            float yOffset = textBounds.height() / 4; // half, and half again since it's centered
            canvas.drawText(val, b.centerX(), b.centerY() + yOffset, mPaint);
        } else {
            canvas.drawCircle(b.centerX(), b.centerY() - mIconRadius / 2, mIconRadius, mPaint);
        }
    }

    @Override
    public boolean getPadding(Rect padding) {
        padding.left += mHorizontalPadding;
        padding.right += mHorizontalPadding;
        padding.top = 0;
        padding.bottom = 0;
        android.util.Log.d("ScreenRecordDrawable", "set zero top/bottom pad");
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        super.setAlpha(alpha);
        mFillDrawable.setAlpha(alpha);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mFillDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public Drawable mutate() {
        mFillDrawable.mutate();
        return super.mutate();
    }
}
