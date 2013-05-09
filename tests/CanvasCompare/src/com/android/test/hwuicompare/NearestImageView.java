/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.test.hwuicompare;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.util.AttributeSet;
import android.widget.ImageView;

public class NearestImageView extends ImageView {
    public NearestImageView(Context context) {
        super(context);
    }

    public NearestImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NearestImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    final PaintFlagsDrawFilter mFilter = new PaintFlagsDrawFilter(Paint.FILTER_BITMAP_FLAG, 0);

    @Override
    public void onDraw(Canvas canvas) {
        canvas.setDrawFilter(mFilter);
        super.onDraw(canvas);
        canvas.setDrawFilter(null);
    }
}