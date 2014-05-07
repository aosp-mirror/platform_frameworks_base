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
 * limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.systemui.R;

/** ImageView that performs runtime modification of vector drawables (using FilterCanvas). **/
public class QSImageView extends ImageView {

    private final int mOutlineWidth;
    private final int mColorEnabled;
    private final int mColorDisabled;
    private FilterCanvas mFilterCanvas;
    private Canvas mCanvas;
    private boolean mEnabledVersion = true;
    private boolean mFilter;

    public QSImageView(Context context) {
        this(context, null);
    }

    public QSImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final Resources res = context.getResources();
        mOutlineWidth = res.getDimensionPixelSize(R.dimen.quick_settings_tile_icon_outline);
        mColorEnabled = res.getColor(R.color.quick_settings_tile_icon_enabled);
        mColorDisabled = res.getColor(R.color.quick_settings_tile_icon_disabled);
    }

    public void setEnabledVersion(boolean enabledVersion) {
        mEnabledVersion = enabledVersion;
        invalidate();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        mFilter = drawable instanceof VectorDrawable;
        super.setImageDrawable(drawable);
    }

    @Override
    public void setImageResource(int resId) {
        setImageDrawable(mContext.getDrawable(resId));
    }

    @Override
    public void draw(Canvas canvas) {
        if (mFilter) {
            if (canvas != mCanvas) {
                mCanvas = canvas;
                mFilterCanvas = new QSFilterCanvas(canvas);
            }
            super.draw(mFilterCanvas);
        } else {
            super.draw(canvas);
        }
    }

    private class QSFilterCanvas extends FilterCanvas {
        public QSFilterCanvas(Canvas c) {
            super(c);
        }

        @Override
        public void drawPath(Path path, Paint paint) {
            if (mEnabledVersion) {
                paint.setColor(mColorEnabled);
            } else {
                paint.setStyle(Style.STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(mColorDisabled);
                paint.setStrokeWidth(mOutlineWidth);
            }
            super.drawPath(path, paint);
        }
    }
}
