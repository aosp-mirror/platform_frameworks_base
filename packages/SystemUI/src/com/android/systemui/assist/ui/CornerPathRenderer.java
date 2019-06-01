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

package com.android.systemui.assist.ui;

import android.graphics.Path;
import android.graphics.PointF;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles paths along device corners.
 */
public abstract class CornerPathRenderer {

    // The maximum delta between the corner curve and points approximating the corner curve.
    private static final float ACCEPTABLE_ERROR = 0.1f;

    /**
     * For convenience, labels the four device corners.
     *
     * Corners must be listed in CCW order, otherwise we'll break rotation.
     */
    public enum Corner {
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        TOP_RIGHT,
        TOP_LEFT
    }

    /**
     * Returns the path along the inside of a corner (centered insetAmountPx from the corner's
     * edge).
     */
    public Path getInsetPath(Corner corner, float insetAmountPx) {
        return approximateInnerPath(getCornerPath(corner), -insetAmountPx);
    }

    /**
     * Returns the path of a corner (centered on the exact corner). Must be implemented by extending
     * classes, based on the device-specific rounded corners. A default implementation for circular
     * corners is provided by CircularCornerPathRenderer.
     */
    public abstract Path getCornerPath(Corner corner);

    private Path approximateInnerPath(Path input, float delta) {
        List<PointF> points = shiftBy(getApproximatePoints(input), delta);
        return toPath(points);
    }

    private ArrayList<PointF> getApproximatePoints(Path path) {
        float[] rawInput = path.approximate(ACCEPTABLE_ERROR);

        ArrayList<PointF> output = new ArrayList<>();

        for (int i = 0; i < rawInput.length; i = i + 3) {
            output.add(new PointF(rawInput[i + 1], rawInput[i + 2]));
        }

        return output;
    }

    private ArrayList<PointF> shiftBy(ArrayList<PointF> input, float delta) {
        ArrayList<PointF> output = new ArrayList<>();

        for (int i = 0; i < input.size(); i++) {
            PointF point = input.get(i);
            PointF normal = normalAt(input, i);
            PointF shifted =
                    new PointF(point.x + (normal.x * delta), point.y + (normal.y * delta));
            output.add(shifted);
        }
        return output;
    }

    private Path toPath(List<PointF> points) {
        Path path = new Path();
        if (points.size() > 0) {
            path.moveTo(points.get(0).x, points.get(0).y);
            for (PointF point : points.subList(1, points.size())) {
                path.lineTo(point.x, point.y);
            }
        }
        return path;
    }

    private PointF normalAt(List<PointF> points, int index) {
        PointF d1;
        if (index == 0) {
            d1 = new PointF(0, 0);
        } else {
            PointF point = points.get(index);
            PointF previousPoint = points.get(index - 1);
            d1 = new PointF((point.x - previousPoint.x), (point.y - previousPoint.y));
        }

        PointF d2;
        if (index == (points.size() - 1)) {
            d2 = new PointF(0, 0);
        } else {
            PointF point = points.get(index);
            PointF nextPoint = points.get(index + 1);
            d2 = new PointF((nextPoint.x - point.x), (nextPoint.y - point.y));
        }

        return rotate90Ccw(normalize(new PointF(d1.x + d2.x, d1.y + d2.y)));
    }

    private PointF rotate90Ccw(PointF input) {
        return new PointF(-input.y, input.x);
    }

    private float magnitude(PointF point) {
        return (float) Math.sqrt((point.x * point.x) + (point.y * point.y));
    }

    private PointF normalize(PointF point) {
        float magnitude = magnitude(point);
        if (magnitude == 0.f) {
            return point;
        }

        float normal = 1 / magnitude;
        return new PointF((point.x * normal), (point.y * normal));
    }
}
