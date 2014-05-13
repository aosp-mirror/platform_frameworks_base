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

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;

/** Canvas that forwards calls to another canvas.  Can be subclassed to transform drawing calls.
 * Temporary solution to runtime modification of a single drawable shape into two
 * enabled & disabled versions.  See QSImageView. **/
public class FilterCanvas extends Canvas {
    private final Canvas mCanvas;

    public FilterCanvas(Canvas c) {
        mCanvas = c;
    }

    @Override
    public void drawPath(Path path, Paint paint) {
        mCanvas.drawPath(path, paint);
    }

    @Override
    public int getSaveCount() {
        return mCanvas.getSaveCount();
    }

    @Override
    public int save() {
        return mCanvas.save();
    }

    @Override
    public void translate(float dx, float dy) {
        mCanvas.translate(dx, dy);
    }

    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
        return mCanvas.clipRect(left, top, right, bottom);
    }

    @Override
    public boolean clipRect(Rect rect) {
        return mCanvas.clipRect(rect);
    }

    @Override
    public void concat(Matrix matrix) {
        mCanvas.concat(matrix);
    }

    @Override
    public void restoreToCount(int saveCount) {
        mCanvas.restoreToCount(saveCount);
    }

    @Override
    public void drawRect(Rect r, Paint paint) {
        mCanvas.drawRect(r, paint);
    }
}