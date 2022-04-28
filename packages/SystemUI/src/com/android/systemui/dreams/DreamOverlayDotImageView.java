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

package com.android.systemui.dreams;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.statusbar.AlphaOptimizedImageView;

/**
 * An {@link AlphaOptimizedImageView} that is responsible for rendering a dot. Used by
 * {@link DreamOverlayStatusBarView}.
 */
public class DreamOverlayDotImageView extends AlphaOptimizedImageView {
    private final @ColorInt int mDotColor;

    public DreamOverlayDotImageView(Context context) {
        this(context, null);
    }

    public DreamOverlayDotImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DreamOverlayDotImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DreamOverlayDotImageView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
                R.styleable.DreamOverlayDotImageView, 0, 0);

        try {
            mDotColor = a.getColor(R.styleable.DreamOverlayDotImageView_dotColor, Color.WHITE);
        } finally {
            a.recycle();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setImageDrawable(new DotDrawable(mDotColor));
    }

    private static class DotDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap mDotBitmap;
        private final Rect mBounds = new Rect();
        private final @ColorInt int mDotColor;

        DotDrawable(@ColorInt int color) {
            mDotColor = color;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mBounds.isEmpty()) {
                return;
            }

            if (mDotBitmap == null) {
                mDotBitmap = createBitmap(mBounds.width(), mBounds.height());
            }

            canvas.drawBitmap(mDotBitmap, null, mBounds, mPaint);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            mBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
            // Make sure to regenerate the dot bitmap when the bounds change.
            mDotBitmap = null;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return 0;
        }

        private Bitmap createBitmap(int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(mDotColor);
            canvas.drawCircle(width / 2.f, height / 2.f, Math.min(width, height) / 2.f, paint);
            return bitmap;
        }
    }
}
