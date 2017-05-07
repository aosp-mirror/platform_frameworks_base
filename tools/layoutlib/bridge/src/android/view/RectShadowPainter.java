/*
 * Copyright (C) 2015, 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.FillType;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;

import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.layoutlib.bridge.shadowutil.SpotShadow;
import com.android.layoutlib.bridge.shadowutil.ShadowBuffer;

public class RectShadowPainter {

    private static final float SIMPLE_SHADOW_ELEVATION_THRESHOLD = 16f;
    private static final int SIMPLE_SHADOW_START_COLOR = ResourceHelper.getColor("#37000000");
    private static final int SIMPLE_SHADOW_END_COLOR = ResourceHelper.getColor("#03000000");
    private static final float PERPENDICULAR_ANGLE = 90f;

    private static final float SHADOW_STRENGTH = 0.1f;
    private static final int LIGHT_POINTS = 8;

    private static final int QUADRANT_DIVIDED_COUNT = 8;

    private static final int RAY_TRACING_RAYS = 180;
    private static final int RAY_TRACING_LAYERS = 10;

    public static void paintShadow(@NonNull Outline viewOutline, float elevation,
            @NonNull Canvas canvas) {
        Rect outline = new Rect();
        if (!viewOutline.getRect(outline)) {
            assert false : "Outline is not a rect shadow";
            return;
        }

        if (elevation <= 0) {
            // If elevation is 0, we don't need to paint the shadow
            return;
        }

        Rect originCanvasRect = canvas.getClipBounds();
        int saved = modifyCanvas(canvas);
        if (saved == -1) {
            return;
        }
        try {
            float radius = viewOutline.getRadius();
            if (radius <= 0) {
                // We can not paint a shadow with radius 0
                return;
            }

            if (elevation <= SIMPLE_SHADOW_ELEVATION_THRESHOLD) {
                // When elevation is not high, the shadow is very similar to a small outline of
                // View. For the performance reason, we draw the shadow in simple way.
                simpleRectangleShadow(canvas, outline, radius, elevation);
            } else {
                // view's absolute position in this canvas.
                int viewLeft = -originCanvasRect.left + outline.left;
                int viewTop = -originCanvasRect.top + outline.top;
                int viewRight = viewLeft + outline.width();
                int viewBottom = viewTop + outline.height();

                float[][] rectangleCoordinators =
                        generateRectangleCoordinates(viewLeft, viewTop, viewRight, viewBottom,
                                radius, elevation);

                // TODO: get these values from resources.
                float lightPosX = canvas.getWidth() / 2;
                float lightPosY = 0;
                float lightHeight = 1800;
                float lightSize = 200;

                paintGeometricShadow(rectangleCoordinators, lightPosX, lightPosY, lightHeight,
                        lightSize, canvas);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static int modifyCanvas(@NonNull Canvas canvas) {
        Rect rect = canvas.getClipBounds();
        canvas.translate(rect.left, rect.top);
        return canvas.save();
    }

    private static void simpleRectangleShadow(@NonNull Canvas canvas, @NonNull Rect outline,
            float radius, float elevation) {
        float shadowSize = elevation / 2;

        Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        cornerPaint.setStyle(Style.FILL);
        Paint edgePaint = new Paint(cornerPaint);
        edgePaint.setAntiAlias(false);
        float outerArcRadius = radius + shadowSize;
        int[] colors = {SIMPLE_SHADOW_START_COLOR, SIMPLE_SHADOW_START_COLOR,
                SIMPLE_SHADOW_END_COLOR};
        cornerPaint.setShader(new RadialGradient(0, 0, outerArcRadius, colors,
                new float[]{0f, radius / outerArcRadius, 1f}, TileMode.CLAMP));
        edgePaint.setShader(new LinearGradient(0, 0, -shadowSize, 0, SIMPLE_SHADOW_START_COLOR,
                SIMPLE_SHADOW_END_COLOR,
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
        drawSideShadow(canvas, edgePaint, edgeShadowRect, outline.left, inset.top, 0);
        // Right shadow
        drawSideShadow(canvas, edgePaint, edgeShadowRect, outline.right, inset.bottom, 2);
        // Top shadow
        edgeShadowRect.set(-shadowSize, 0, 0, inset.width());
        drawSideShadow(canvas, edgePaint, edgeShadowRect, inset.right, outline.top, 1);
        // bottom shadow. This needs an inset so that blank doesn't appear when the content is
        // moved up.
        edgeShadowRect.set(-shadowSize, 0, shadowSize / 2f, inset.width());
        edgePaint.setShader(
                new LinearGradient(edgeShadowRect.right, 0, edgeShadowRect.left, 0, colors,
                        new float[]{0f, 1 / 3f, 1f}, TileMode.CLAMP));
        drawSideShadow(canvas, edgePaint, edgeShadowRect, inset.left, outline.bottom, 3);

        // Draw corners.
        drawCorner(canvas, cornerPaint, path, inset.right, inset.bottom, outerArcRadius, 0);
        drawCorner(canvas, cornerPaint, path, inset.left, inset.bottom, outerArcRadius, 1);
        drawCorner(canvas, cornerPaint, path, inset.left, inset.top, outerArcRadius, 2);
        drawCorner(canvas, cornerPaint, path, inset.right, inset.top, outerArcRadius, 3);
    }

    private static void drawSideShadow(@NonNull Canvas canvas, @NonNull Paint edgePaint,
            @NonNull RectF shadowRect, float dx, float dy, int rotations) {
        if ((int) shadowRect.left >= (int) shadowRect.right ||
                (int) shadowRect.top >= (int) shadowRect.bottom) {
            // Rect is empty, no need to draw shadow
            return;
        }
        int saved = canvas.save();
        canvas.translate(dx, dy);
        canvas.rotate(rotations * PERPENDICULAR_ANGLE);
        canvas.drawRect(shadowRect, edgePaint);
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
    private static void drawCorner(@NonNull Canvas canvas, @NonNull Paint paint, @NonNull Path path,
            float x, float y, float radius, int rotations) {
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

    @NonNull
    private static float[][] generateRectangleCoordinates(float left, float top, float right,
            float bottom, float radius, float elevation) {
        left = left + radius;
        top = top + radius;
        right = right - radius;
        bottom = bottom - radius;

        final double RADIANS_STEP = 2 * Math.PI / 4 / QUADRANT_DIVIDED_COUNT;

        float[][] ret = new float[QUADRANT_DIVIDED_COUNT * 4][3];

        int points = 0;
        // left-bottom points
        for (int i = 0; i < QUADRANT_DIVIDED_COUNT; i++) {
            ret[points][0] = (float) (left - radius + radius * Math.cos(RADIANS_STEP * i));
            ret[points][1] = (float) (bottom + radius - radius * Math.cos(RADIANS_STEP * i));
            ret[points][2] = elevation;
            points++;
        }
        // left-top points
        for (int i = 0; i < QUADRANT_DIVIDED_COUNT; i++) {
            ret[points][0] = (float) (left + radius - radius * Math.cos(RADIANS_STEP * i));
            ret[points][1] = (float) (top + radius - radius * Math.cos(RADIANS_STEP * i));
            ret[points][2] = elevation;
            points++;
        }
        // right-top points
        for (int i = 0; i < QUADRANT_DIVIDED_COUNT; i++) {
            ret[points][0] = (float) (right + radius - radius * Math.cos(RADIANS_STEP * i));
            ret[points][1] = (float) (top + radius + radius * Math.cos(RADIANS_STEP * i));
            ret[points][2] = elevation;
            points++;
        }
        // right-bottom point
        for (int i = 0; i < QUADRANT_DIVIDED_COUNT; i++) {
            ret[points][0] = (float) (right - radius + radius * Math.cos(RADIANS_STEP * i));
            ret[points][1] = (float) (bottom - radius + radius * Math.cos(RADIANS_STEP * i));
            ret[points][2] = elevation;
            points++;
        }

        return ret;
    }

    private static void paintGeometricShadow(@NonNull float[][] coordinates, float lightPosX,
            float lightPosY, float lightHeight, float lightSize, Canvas canvas) {
        if (canvas == null || canvas.getWidth() == 0 || canvas.getHeight() == 0) {
            return;
        }

        // The polygon of shadow (same as the original item)
        float[] shadowPoly = new float[coordinates.length * 3];
        for (int i = 0; i < coordinates.length; i++) {
            shadowPoly[i * 3 + 0] = coordinates[i][0];
            shadowPoly[i * 3 + 1] = coordinates[i][1];
            shadowPoly[i * 3 + 2] = coordinates[i][2];
        }

        // TODO: calculate the ambient shadow and mix with Spot shadow.

        // Calculate the shadow of SpotLight
        float[] light = SpotShadow.calculateLight(lightSize, LIGHT_POINTS, lightPosX,
                lightPosY, lightHeight);

        int stripSize = 3 * SpotShadow.getStripSize(RAY_TRACING_RAYS, RAY_TRACING_LAYERS);
        if (stripSize < 9) {
            return;
        }
        float[] strip = new float[stripSize];
        SpotShadow.calcShadow(light, LIGHT_POINTS, shadowPoly, coordinates.length, RAY_TRACING_RAYS,
                RAY_TRACING_LAYERS, 1f, strip);

        ShadowBuffer buff = new ShadowBuffer(canvas.getWidth(), canvas.getHeight());
        buff.generateTriangles(strip, SHADOW_STRENGTH);
        buff.draw(canvas);
    }
}
