/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.paint;

public interface PaintChanges {


    int CLEAR_TEXT_STYLE = 1 << (PaintBundle.TYPEFACE - 1);
    int CLEAR_COLOR = 1 << (PaintBundle.COLOR - 1);
    int CLEAR_STROKE_WIDTH = 1 << (PaintBundle.STROKE_WIDTH - 1);
    int CLEAR_STROKE_MITER = 1 << (PaintBundle.STROKE_MITER - 1);
    int CLEAR_CAP = 1 << (PaintBundle.STROKE_CAP - 1);
    int CLEAR_STYLE = 1 << (PaintBundle.STYLE - 1);
    int CLEAR_SHADER = 1 << (PaintBundle.SHADER - 1);
    int CLEAR_IMAGE_FILTER_QUALITY =
            1 << (PaintBundle.IMAGE_FILTER_QUALITY - 1);
    int CLEAR_RADIENT = 1 << (PaintBundle.GRADIENT - 1);
    int CLEAR_ALPHA = 1 << (PaintBundle.ALPHA - 1);
    int CLEAR_COLOR_FILTER = 1 << (PaintBundle.COLOR_FILTER - 1);
    int VALID_BITS = 0x1FFF; // only the first 13 bit are valid now


    void setTextSize(float size);
    void setStrokeWidth(float width);
    void setColor(int color);
    void setStrokeCap(int cap);
    void setStyle(int style);
    void setShader(int shader, String shaderString);
    void setImageFilterQuality(int quality);
    void setAlpha(float a);
    void setStrokeMiter(float miter);
    void setStrokeJoin(int join);
    void setFilterBitmap(boolean filter);
    void setBlendMode(int mode);
    void setAntiAlias(boolean aa);
    void clear(long mask);
    void setLinearGradient(
            int[] colorsArray,
            float[] stopsArray,
            float startX,
            float startY,
            float endX,
            float endY,
            int tileMode
    );

    void setRadialGradient(
            int[] colorsArray,
            float[] stopsArray,
            float centerX,
            float centerY,
            float radius,
            int tileMode
    );

    void setSweepGradient(
            int[] colorsArray,
            float[] stopsArray,
            float centerX,
            float centerY
    );


    void setColorFilter(int color, int mode);

    void setTypeFace(int fontType, int weight, boolean italic);
}

