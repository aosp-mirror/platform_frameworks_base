/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player.platform;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.ClipPath;
import com.android.internal.widget.remotecompose.core.operations.ShaderData;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintChanges;

/**
 * An implementation of PaintContext for the Android Canvas.
 * This is used to play the RemoteCompose operations on Android.
 */
public class AndroidPaintContext extends PaintContext {
    Paint mPaint = new Paint();
    Canvas mCanvas;
    Rect mTmpRect = new Rect(); // use in calculation of bounds

    public AndroidPaintContext(RemoteContext context, Canvas canvas) {
        super(context);
        this.mCanvas = canvas;
    }

    public Canvas getCanvas() {
        return mCanvas;
    }

    public void setCanvas(Canvas canvas) {
        this.mCanvas = canvas;
    }

    /**
     * Draw an image onto the canvas
     *
     * @param imageId   the id of the image
     * @param srcLeft   left coordinate of the source area
     * @param srcTop    top coordinate of the source area
     * @param srcRight  right coordinate of the source area
     * @param srcBottom bottom coordinate of the source area
     * @param dstLeft   left coordinate of the destination area
     * @param dstTop    top coordinate of the destination area
     * @param dstRight  right coordinate of the destination area
     * @param dstBottom bottom coordinate of the destination area
     */

    @Override
    public void drawBitmap(int imageId,
                           int srcLeft,
                           int srcTop,
                           int srcRight,
                           int srcBottom,
                           int dstLeft,
                           int dstTop,
                           int dstRight,
                           int dstBottom,
                           int cdId) {
        AndroidRemoteContext androidContext = (AndroidRemoteContext) mContext;
        if (androidContext.mRemoteComposeState.containsId(imageId)) {
            Bitmap bitmap = (Bitmap) androidContext.mRemoteComposeState
                    .getFromId(imageId);
            mCanvas.drawBitmap(
                    bitmap,
                    new Rect(srcLeft, srcTop, srcRight, srcBottom),
                    new Rect(dstLeft, dstTop, dstRight, dstBottom), mPaint
            );
        }
    }

    @Override
    public void scale(float scaleX, float scaleY) {
        mCanvas.scale(scaleX, scaleY);
    }

    @Override
    public void translate(float translateX, float translateY) {
        mCanvas.translate(translateX, translateY);
    }

    @Override
    public void drawArc(float left,
                        float top,
                        float right,
                        float bottom,
                        float startAngle,
                        float sweepAngle) {
        mCanvas.drawArc(left, top, right, bottom, startAngle,
                sweepAngle, true, mPaint);
    }

    @Override
    public void drawBitmap(int id,
                           float left,
                           float top,
                           float right,
                           float bottom) {
        AndroidRemoteContext androidContext = (AndroidRemoteContext) mContext;
        if (androidContext.mRemoteComposeState.containsId(id)) {
            Bitmap bitmap =
                    (Bitmap) androidContext.mRemoteComposeState.getFromId(id);
            Rect src = new Rect(0, 0,
                    bitmap.getWidth(), bitmap.getHeight());
            RectF dst = new RectF(left, top, right, bottom);
            mCanvas.drawBitmap(bitmap, src, dst, mPaint);
        }
    }

    @Override
    public void drawCircle(float centerX, float centerY, float radius) {
        mCanvas.drawCircle(centerX, centerY, radius, mPaint);
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2) {
        mCanvas.drawLine(x1, y1, x2, y2, mPaint);
    }

    @Override
    public void drawOval(float left, float top, float right, float bottom) {
        mCanvas.drawOval(left, top, right, bottom, mPaint);
    }

    @Override
    public void drawPath(int id, float start, float end) {
        mCanvas.drawPath(getPath(id, start, end), mPaint);
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom) {
        mCanvas.drawRect(left, top, right, bottom, mPaint);
    }

    @Override
    public void drawRoundRect(float left,
                              float top,
                              float right,
                              float bottom,
                              float radiusX,
                              float radiusY) {
        mCanvas.drawRoundRect(left, top, right, bottom,
                radiusX, radiusY, mPaint);
    }

    @Override
    public void drawTextOnPath(int textId,
                               int pathId,
                               float hOffset,
                               float vOffset) {
        mCanvas.drawTextOnPath(getText(textId), getPath(pathId, 0, 1), hOffset, vOffset, mPaint);
    }

    @Override
    public void getTextBounds(int textId, int start, int end, boolean monospace, float[] bounds) {
        String str = getText(textId);
        if (end == -1) {
            end = str.length();
        }

        mPaint.getTextBounds(str, start, end, mTmpRect);

        bounds[0] = mTmpRect.left;
        bounds[1] = mTmpRect.top;
        bounds[2] = monospace ? (mPaint.measureText(str, start, end) - mTmpRect.left)
                : mTmpRect.right;
        bounds[3] = mTmpRect.bottom;
    }

    @Override
    public void drawTextRun(int textID,
                            int start,
                            int end,
                            int contextStart,
                            int contextEnd,
                            float x,
                            float y,
                            boolean rtl) {

        String textToPaint = getText(textID);
        if (end == -1) {
            if (start != 0) {
                textToPaint = textToPaint.substring(start);
            }
        } else {
            textToPaint = textToPaint.substring(start, end);
        }

        mCanvas.drawText(textToPaint, x, y, mPaint);
    }

    @Override
    public void drawTweenPath(int path1Id,
                              int path2Id,
                              float tween,
                              float start,
                              float end) {
        mCanvas.drawPath(getPath(path1Id, path2Id, tween, start, end), mPaint);
    }

    private static PorterDuff.Mode origamiToPorterDuffMode(int mode) {
        switch (mode) {
            case PaintBundle.BLEND_MODE_CLEAR:
                return PorterDuff.Mode.CLEAR;
            case PaintBundle.BLEND_MODE_SRC:
                return PorterDuff.Mode.SRC;
            case PaintBundle.BLEND_MODE_DST:
                return PorterDuff.Mode.DST;
            case PaintBundle.BLEND_MODE_SRC_OVER:
                return PorterDuff.Mode.SRC_OVER;
            case PaintBundle.BLEND_MODE_DST_OVER:
                return PorterDuff.Mode.DST_OVER;
            case PaintBundle.BLEND_MODE_SRC_IN:
                return PorterDuff.Mode.SRC_IN;
            case PaintBundle.BLEND_MODE_DST_IN:
                return PorterDuff.Mode.DST_IN;
            case PaintBundle.BLEND_MODE_SRC_OUT:
                return PorterDuff.Mode.SRC_OUT;
            case PaintBundle.BLEND_MODE_DST_OUT:
                return PorterDuff.Mode.DST_OUT;
            case PaintBundle.BLEND_MODE_SRC_ATOP:
                return PorterDuff.Mode.SRC_ATOP;
            case PaintBundle.BLEND_MODE_DST_ATOP:
                return PorterDuff.Mode.DST_ATOP;
            case PaintBundle.BLEND_MODE_XOR:
                return PorterDuff.Mode.XOR;
            case PaintBundle.BLEND_MODE_SCREEN:
                return PorterDuff.Mode.SCREEN;
            case PaintBundle.BLEND_MODE_OVERLAY:
                return PorterDuff.Mode.OVERLAY;
            case PaintBundle.BLEND_MODE_DARKEN:
                return PorterDuff.Mode.DARKEN;
            case PaintBundle.BLEND_MODE_LIGHTEN:
                return PorterDuff.Mode.LIGHTEN;
            case PaintBundle.BLEND_MODE_MULTIPLY:
                return PorterDuff.Mode.MULTIPLY;
            case PaintBundle.PORTER_MODE_ADD:
                return PorterDuff.Mode.ADD;
        }
        return PorterDuff.Mode.SRC_OVER;
    }

    public static BlendMode origamiToBlendMode(int mode) {
        switch (mode) {
            case PaintBundle.BLEND_MODE_CLEAR:
                return BlendMode.CLEAR;
            case PaintBundle.BLEND_MODE_SRC:
                return BlendMode.SRC;
            case PaintBundle.BLEND_MODE_DST:
                return BlendMode.DST;
            case PaintBundle.BLEND_MODE_SRC_OVER:
                return BlendMode.SRC_OVER;
            case PaintBundle.BLEND_MODE_DST_OVER:
                return BlendMode.DST_OVER;
            case PaintBundle.BLEND_MODE_SRC_IN:
                return BlendMode.SRC_IN;
            case PaintBundle.BLEND_MODE_DST_IN:
                return BlendMode.DST_IN;
            case PaintBundle.BLEND_MODE_SRC_OUT:
                return BlendMode.SRC_OUT;
            case PaintBundle.BLEND_MODE_DST_OUT:
                return BlendMode.DST_OUT;
            case PaintBundle.BLEND_MODE_SRC_ATOP:
                return BlendMode.SRC_ATOP;
            case PaintBundle.BLEND_MODE_DST_ATOP:
                return BlendMode.DST_ATOP;
            case PaintBundle.BLEND_MODE_XOR:
                return BlendMode.XOR;
            case PaintBundle.BLEND_MODE_PLUS:
                return BlendMode.PLUS;
            case PaintBundle.BLEND_MODE_MODULATE:
                return BlendMode.MODULATE;
            case PaintBundle.BLEND_MODE_SCREEN:
                return BlendMode.SCREEN;
            case PaintBundle.BLEND_MODE_OVERLAY:
                return BlendMode.OVERLAY;
            case PaintBundle.BLEND_MODE_DARKEN:
                return BlendMode.DARKEN;
            case PaintBundle.BLEND_MODE_LIGHTEN:
                return BlendMode.LIGHTEN;
            case PaintBundle.BLEND_MODE_COLOR_DODGE:
                return BlendMode.COLOR_DODGE;
            case PaintBundle.BLEND_MODE_COLOR_BURN:
                return BlendMode.COLOR_BURN;
            case PaintBundle.BLEND_MODE_HARD_LIGHT:
                return BlendMode.HARD_LIGHT;
            case PaintBundle.BLEND_MODE_SOFT_LIGHT:
                return BlendMode.SOFT_LIGHT;
            case PaintBundle.BLEND_MODE_DIFFERENCE:
                return BlendMode.DIFFERENCE;
            case PaintBundle.BLEND_MODE_EXCLUSION:
                return BlendMode.EXCLUSION;
            case PaintBundle.BLEND_MODE_MULTIPLY:
                return BlendMode.MULTIPLY;
            case PaintBundle.BLEND_MODE_HUE:
                return BlendMode.HUE;
            case PaintBundle.BLEND_MODE_SATURATION:
                return BlendMode.SATURATION;
            case PaintBundle.BLEND_MODE_COLOR:
                return BlendMode.COLOR;
            case PaintBundle.BLEND_MODE_LUMINOSITY:
                return BlendMode.LUMINOSITY;
            case PaintBundle.BLEND_MODE_NULL:
                return null;
        }
        return null;
    }

    @Override
    public void applyPaint(PaintBundle mPaintData) {
        mPaintData.applyPaintChange((PaintContext) this, new PaintChanges() {
            @Override
            public void setTextSize(float size) {
                mPaint.setTextSize(size);
            }

            @Override
            public void setTypeFace(int fontType, int weight, boolean italic) {
                int[] type = new int[]{Typeface.NORMAL, Typeface.BOLD,
                        Typeface.ITALIC, Typeface.BOLD_ITALIC};

                switch (fontType) {
                    case PaintBundle.FONT_TYPE_DEFAULT: {
                        if (weight == 400 && !italic) { // for normal case
                            mPaint.setTypeface(Typeface.DEFAULT);
                        } else {
                            mPaint.setTypeface(Typeface.create(Typeface.DEFAULT,
                                    weight, italic));
                        }
                        break;
                    }
                    case PaintBundle.FONT_TYPE_SERIF: {
                        if (weight == 400 && !italic) { // for normal case
                            mPaint.setTypeface(Typeface.SERIF);
                        } else {
                            mPaint.setTypeface(Typeface.create(Typeface.SERIF,
                                    weight, italic));
                        }
                        break;
                    }
                    case PaintBundle.FONT_TYPE_SANS_SERIF: {
                        if (weight == 400 && !italic) { //  for normal case
                            mPaint.setTypeface(Typeface.SANS_SERIF);
                        } else {
                            mPaint.setTypeface(
                                    Typeface.create(Typeface.SANS_SERIF,
                                            weight, italic));
                        }
                        break;
                    }
                    case PaintBundle.FONT_TYPE_MONOSPACE: {
                        if (weight == 400 && !italic) { //  for normal case
                            mPaint.setTypeface(Typeface.MONOSPACE);
                        } else {
                            mPaint.setTypeface(
                                    Typeface.create(Typeface.MONOSPACE,
                                            weight, italic));
                        }

                        break;
                    }
                }

            }

            @Override
            public void setStrokeWidth(float width) {
                mPaint.setStrokeWidth(width);
            }

            @Override
            public void setColor(int color) {
                mPaint.setColor(color);
            }

            @Override
            public void setStrokeCap(int cap) {
                mPaint.setStrokeCap(Paint.Cap.values()[cap]);
            }

            @Override
            public void setStyle(int style) {
                mPaint.setStyle(Paint.Style.values()[style]);
            }

            @Override
            public void setShader(int shaderId) {
                // TODO this stuff should check the shader creation
                if (shaderId == 0) {
                    mPaint.setShader(null);
                    return;
                }
                ShaderData data = getShaderData(shaderId);
                RuntimeShader shader = new RuntimeShader(getText(data.getShaderTextId()));
                String[] names = data.getUniformFloatNames();
                for (int i = 0; i < names.length; i++) {
                    String name = names[i];
                    float[] val = data.getUniformFloats(name);
                    shader.setFloatUniform(name, val);
                }
                names = data.getUniformIntegerNames();
                for (int i = 0; i < names.length; i++) {
                    String name = names[i];
                    int[] val = data.getUniformInts(name);
                    shader.setIntUniform(name, val);
                }
                names = data.getUniformBitmapNames();
                for (int i = 0; i < names.length; i++) {
                    String name = names[i];
                    int val = data.getUniformBitmapId(name);
                }
                mPaint.setShader(shader);
            }

            @Override
            public void setImageFilterQuality(int quality) {
                Utils.log(" quality =" + quality);
            }

            @Override
            public void setBlendMode(int mode) {
                mPaint.setBlendMode(origamiToBlendMode(mode));
            }

            @Override
            public void setAlpha(float a) {
                mPaint.setAlpha((int) (255 * a));
            }

            @Override
            public void setStrokeMiter(float miter) {
                mPaint.setStrokeMiter(miter);
            }

            @Override
            public void setStrokeJoin(int join) {
                mPaint.setStrokeJoin(Paint.Join.values()[join]);
            }

            @Override
            public void setFilterBitmap(boolean filter) {
                mPaint.setFilterBitmap(filter);
            }

            @Override
            public void setAntiAlias(boolean aa) {
                mPaint.setAntiAlias(aa);
            }

            @Override
            public void clear(long mask) {
                if (true) return;
                long m = mask;
                int k = 1;
                while (m > 0) {
                    if ((m & 1) == 1L) {
                        switch (k) {

                            case PaintBundle.COLOR_FILTER:
                                mPaint.setColorFilter(null);
                                break;
                        }
                    }
                    k++;
                    m = m >> 1;
                }
            }

            Shader.TileMode[] mTileModes = new Shader.TileMode[]{
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.REPEAT,
                    Shader.TileMode.MIRROR};

            @Override
            public void setLinearGradient(int[] colors,
                                          float[] stops,
                                          float startX,
                                          float startY,
                                          float endX,
                                          float endY,
                                          int tileMode) {
                mPaint.setShader(new LinearGradient(startX,
                        startY,
                        endX,
                        endY, colors, stops, mTileModes[tileMode]));

            }

            @Override
            public void setRadialGradient(int[] colors,
                                          float[] stops,
                                          float centerX,
                                          float centerY,
                                          float radius,
                                          int tileMode) {
                mPaint.setShader(new RadialGradient(centerX, centerY, radius,
                        colors, stops, mTileModes[tileMode]));
            }

            @Override
            public void setSweepGradient(int[] colors,
                                         float[] stops,
                                         float centerX,
                                         float centerY) {
                mPaint.setShader(new SweepGradient(centerX, centerY, colors, stops));

            }

            @Override
            public void setColorFilter(int color, int mode) {
                PorterDuff.Mode pmode = origamiToPorterDuffMode(mode);
                if (pmode != null) {
                    mPaint.setColorFilter(
                            new PorterDuffColorFilter(color, pmode));
                }
            }
        });
    }

    @Override
    public void matrixScale(float scaleX,
                            float scaleY,
                            float centerX,
                            float centerY) {
        if (Float.isNaN(centerX)) {
            mCanvas.scale(scaleX, scaleY);
        } else {
            mCanvas.scale(scaleX, scaleY, centerX, centerY);
        }
    }

    @Override
    public void matrixTranslate(float translateX, float translateY) {
        mCanvas.translate(translateX, translateY);
    }

    @Override
    public void matrixSkew(float skewX, float skewY) {
        mCanvas.skew(skewX, skewY);
    }

    @Override
    public void matrixRotate(float rotate, float pivotX, float pivotY) {
        if (Float.isNaN(pivotX)) {
            mCanvas.rotate(rotate);
        } else {
            mCanvas.rotate(rotate, pivotX, pivotY);

        }
    }

    @Override
    public void matrixSave() {
        mCanvas.save();
    }

    @Override
    public void matrixRestore() {
        mCanvas.restore();
    }

    @Override
    public void clipRect(float left, float top, float right, float bottom) {
        mCanvas.clipRect(left, top, right, bottom);
    }

    @Override
    public void clipPath(int pathId, int regionOp) {
        Path path = getPath(pathId, 0, 1);
        if (regionOp == ClipPath.DIFFERENCE) {
            mCanvas.clipOutPath(path); // DIFFERENCE
        } else {
            mCanvas.clipPath(path);  // INTERSECT
        }
    }

    @Override
    public void reset() {
        mPaint.reset();
    }

    private Path getPath(int path1Id,
                         int path2Id,
                         float tween,
                         float start,
                         float end) {
        if (tween == 0.0f) {
            return getPath(path1Id, start, end);
        }
        if (tween == 1.0f) {
            return getPath(path2Id, start, end);
        }
        AndroidRemoteContext androidContext = (AndroidRemoteContext) mContext;
        float[] data1 =
                (float[]) androidContext.mRemoteComposeState.getFromId(path1Id);
        float[] data2 =
                (float[]) androidContext.mRemoteComposeState.getFromId(path2Id);
        float[] tmp = new float[data2.length];
        for (int i = 0; i < tmp.length; i++) {
            if (Float.isNaN(data1[i]) || Float.isNaN(data2[i])) {
                tmp[i] = data1[i];
            } else {
                tmp[i] = (data2[i] - data1[i]) * tween + data1[i];
            }
        }
        Path path = new Path();
        FloatsToPath.genPath(path, tmp, start, end);
        return path;
    }

    private Path getPath(int id, float start, float end) {
        AndroidRemoteContext androidContext = (AndroidRemoteContext) mContext;
        Path path = new Path();
        if (androidContext.mRemoteComposeState.containsId(id)) {
            float[] data =
                    (float[]) androidContext.mRemoteComposeState.getFromId(id);
            FloatsToPath.genPath(path, data, start, end);
        }
        return path;
    }

    private String getText(int id) {
        return (String) mContext.mRemoteComposeState.getFromId(id);
    }

    private ShaderData getShaderData(int id) {
        return (ShaderData) mContext.mRemoteComposeState.getFromId(id);
    }
}

