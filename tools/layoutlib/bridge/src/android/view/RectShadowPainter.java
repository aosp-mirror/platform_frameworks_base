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
import android.graphics.Outline;
import android.graphics.Rect;
import com.android.layoutlib.bridge.shadowutil.SpotShadow;
import com.android.layoutlib.bridge.shadowutil.ShadowBuffer;

public class RectShadowPainter {

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

            // view's absolute position in this canvas.
            int viewLeft = -originCanvasRect.left + outline.left;
            int viewTop = -originCanvasRect.top + outline.top;
            int viewRight = viewLeft + outline.width();
            int viewBottom = viewTop + outline.height();

            float[][] rectangleCoordinators = generateRectangleCoordinates(viewLeft, viewTop,
                    viewRight, viewBottom, radius, elevation);

            // TODO: get these values from resources.
            float lightPosX = canvas.getWidth() / 2;
            float lightPosY = 0;
            float lightHeight = 1800;
            float lightSize = 200;

            paintGeometricShadow(rectangleCoordinators, lightPosX, lightPosY, lightHeight,
                    lightSize, canvas);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static int modifyCanvas(@NonNull Canvas canvas) {
        Rect rect = canvas.getClipBounds();
        canvas.translate(rect.left, rect.top);
        return canvas.save();
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
