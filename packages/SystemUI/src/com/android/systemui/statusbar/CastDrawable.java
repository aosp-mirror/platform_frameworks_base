/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.AttributeSet;

import com.android.systemui.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * The status bar cast drawable draws ic_cast and ic_cast_connected_fill to indicate that the
 * screen is being recorded. A simple layer-list drawable isn't used here because the record fill
 * must not be tinted by the caller.
 */
public class CastDrawable extends DrawableWrapper {
    private Drawable mFillDrawable;
    private int mHorizontalPadding;

    /** No-arg constructor used by drawable inflation. */
    public CastDrawable() {
        super(null);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Resources.Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);
        setDrawable(r.getDrawable(R.drawable.ic_cast, theme).mutate());
        mFillDrawable = r.getDrawable(R.drawable.ic_cast_connected_fill, theme).mutate();
        mHorizontalPadding = r.getDimensionPixelSize(R.dimen.status_bar_horizontal_padding);
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
    }

    @Override
    public boolean getPadding(Rect padding) {
        padding.left += mHorizontalPadding;
        padding.right += mHorizontalPadding;
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
