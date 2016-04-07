/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.view;

import com.android.layoutlib.bridge.impl.ResourceHelper;

import android.graphics.Canvas;
import android.graphics.Canvas_Delegate;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.FillType;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region.Op;
import android.graphics.Shader.TileMode;

/**
 * Paints shadow for rounded rectangles. Inspiration from CardView. Couldn't use that directly,
 * since it modifies the size of the content, that we can't do.
 */
public class RectShadowPainter {


    private static final int START_COLOR = ResourceHelper.getColor("#37000000");
    private static final int END_COLOR = ResourceHelper.getColor("#03000000");
    private static final float PERPENDICULAR_ANGLE = 90f;

    public static void paintShadow(Outline viewOutline, float elevation, Canvas canvas) {
        Rect outline = new Rect();
        if (!viewOutline.getRect(outline)) {
            throw new IllegalArgumentException("Outline is not a rect shadow");
        }

        float shadowSize = elevationToShadow(elevation);
        int saved = modifyCanvas(canvas, shadowSize);
        if (saved == -1) {
            return;
        }
        try {
            Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            cornerPaint.setStyle(Style.FILL);
            Paint edgePaint = new Paint(cornerPaint);
            edgePaint.setAntiAlias(false);
            float radius = viewOutline.getRadius();
            float outerArcRadius = radius + shadowSize;
            int[] colors = {START_COLOR, START_COLOR, END_COLOR};
            cornerPaint.setShader(new RadialGradient(0, 0, outerArcRadius, colors,
                    new float[]{0f, radius / outerArcRadius, 1f}, TileMode.CLAMP));
            edgePaint.setShader(new LinearGradient(0, 0, -shadowSize, 0, START_COLOR, END_COLOR,
                    TileMode.CLAMP));
            Path path = new Path();
            path.setFillType(FillType.EVEN_ODD);
            // A rectangle bounding the complete shadow.
            RectF shadowRect = new RectF(outline);
            shadowRect.inset(-shadowSize, -shadowSize);
            // A rectangle with edges corresponding to the straight edges of the outline.
            RectF inset = new RectF(outline);
            inset.inset(radius, radius);
            // A rectangle used to represent the edge shadow.
            RectF edgeShadowRect = new RectF();


            // left and right sides.
            edgeShadowRect.set(-shadowSize, 0f, 0f, inset.height());
            // Left shadow
            sideShadow(canvas, edgePaint, edgeShadowRect, outline.left, inset.top, 0);
            // Right shadow
            sideShadow(canvas, edgePaint, edgeShadowRect, outline.right, inset.bottom, 2);
            // Top shadow
            edgeShadowRect.set(-shadowSize, 0, 0, inset.width());
            sideShadow(canvas, edgePaint, edgeShadowRect, inset.right, outline.top, 1);
            // bottom shadow. This needs an inset so that blank doesn't appear when the content is
            // moved up.
            edgeShadowRect.set(-shadowSize, 0, shadowSize / 2f, inset.width());
            edgePaint.setShader(new LinearGradient(edgeShadowRect.right, 0, edgeShadowRect.left, 0,
                    colors, new float[]{0f, 1 / 3f, 1f}, TileMode.CLAMP));
            sideShadow(canvas, edgePaint, edgeShadowRect, inset.left, outline.bottom, 3);

            // Draw corners.
            drawCorner(canvas, cornerPaint, path, inset.right, inset.bottom, outerArcRadius, 0);
            drawCorner(canvas, cornerPaint, path, inset.left, inset.bottom, outerArcRadius, 1);
            drawCorner(canvas, cornerPaint, path, inset.left, inset.top, outerArcRadius, 2);
            drawCorner(canvas, cornerPaint, path, inset.right, inset.top, outerArcRadius, 3);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static float elevationToShadow(float elevation) {
        // The factor is chosen by eyeballing the shadow size on device and preview.
        return elevation * 0.5f;
    }

    /**
     * Translate canvas by half of shadow size up, so that it appears that light is coming
     * slightly from above. Also, remove clipping, so that shadow is not clipped.
     */
    private static int modifyCanvas(Canvas canvas, float shadowSize) {
        Rect clipBounds = canvas.getClipBounds();
        if (clipBounds.isEmpty()) {
            return -1;
        }
        int saved = canvas.save();
        // Usually canvas has been translated to the top left corner of the view when this is
        // called. So, setting a clip rect at 0,0 will clip the top left part of the shadow.
        // Thus, we just expand in each direction by width and height of the canvas.
        canvas.clipRect(-canvas.getWidth(), -canvas.getHeight(), canvas.getWidth(),
                canvas.getHeight(), Op.REPLACE);
        canvas.translate(0, shadowSize / 2f);
        return saved;
    }

    private static void sideShadow(Canvas canvas, Paint edgePaint,
            RectF edgeShadowRect, float dx, float dy, int rotations) {
        if (isRectEmpty(edgeShadowRect)) {
            return;
        }
        int saved = canvas.save();
        canvas.translate(dx, dy);
        canvas.rotate(rotations * PERPENDICULAR_ANGLE);
        canvas.drawRect(edgeShadowRect, edgePaint);
        canvas.restoreToCount(saved);
    }

    /**
     * @param canvas Canvas to draw the rectangle on.
     * @param paint Paint to use when drawing the corner.
     * @param path A path to reuse. Prevents allocating memory for each path.
     * @param x Center of circle, which this corner is a part of.
     * @param y Center of circle, which this corner is a part of.
     * @param radius radius of the arc
     * @param rotations number of quarter rotations before starting to paint the arc.
     */
    private static void drawCorner(Canvas canvas, Paint paint, Path path, float x, float y,
            float radius, int rotations) {
        int saved = canvas.save();
        canvas.translate(x, y);
        path.reset();
        path.arcTo(-radius, -radius, radius, radius, rotations * PERPENDICULAR_ANGLE,
                PERPENDICULAR_ANGLE, false);
        path.lineTo(0, 0);
        path.close();
        canvas.drawPath(path, paint);
        canvas.restoreToCount(saved);
    }

    /**
     * Differs from {@link RectF#isEmpty()} as this first converts the rect to int and then checks.
     * <p/>
     * This is required because {@link Canvas_Delegate#native_drawRect(long, float, float, float,
     * float, long)} casts the co-ordinates to int and we want to ensure that it doesn't end up
     * drawing empty rectangles, which results in IllegalArgumentException.
     */
    private static boolean isRectEmpty(RectF rect) {
        return (int) rect.left >= (int) rect.right || (int) rect.top >= (int) rect.bottom;
    }
}
