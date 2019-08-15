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

package com.android.systemui.classifier.brightline;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_ZIGZAG_X_PRIMARY_DEVIANCE;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_ZIGZAG_X_SECONDARY_DEVIANCE;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_ZIGZAG_Y_PRIMARY_DEVIANCE;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_ZIGZAG_Y_SECONDARY_DEVIANCE;

import android.graphics.Point;
import android.provider.DeviceConfig;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Penalizes gestures that change direction in either the x or y too much.
 */
class ZigZagClassifier extends FalsingClassifier {

    // Define how far one can move back and forth over one inch of travel before being falsed.
    // `PRIMARY` defines how far one can deviate in the primary direction of travel. I.e. if you're
    // swiping vertically, you shouldn't have a lot of zig zag in the vertical direction. Since
    // most swipes will follow somewhat of a 'C' or 'S' shape, we allow more deviance along the
    // `SECONDARY` axis.
    private static final float MAX_X_PRIMARY_DEVIANCE = .05f;
    private static final float MAX_Y_PRIMARY_DEVIANCE = .05f;
    private static final float MAX_X_SECONDARY_DEVIANCE = .3f;
    private static final float MAX_Y_SECONDARY_DEVIANCE = .3f;

    private final float mMaxXPrimaryDeviance;
    private final float mMaxYPrimaryDeviance;
    private final float mMaxXSecondaryDeviance;
    private final float mMaxYSecondaryDeviance;

    ZigZagClassifier(FalsingDataProvider dataProvider) {
        super(dataProvider);

        mMaxXPrimaryDeviance = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_ZIGZAG_X_PRIMARY_DEVIANCE,
                MAX_X_PRIMARY_DEVIANCE);

        mMaxYPrimaryDeviance = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_ZIGZAG_Y_PRIMARY_DEVIANCE,
                MAX_Y_PRIMARY_DEVIANCE);

        mMaxXSecondaryDeviance = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_ZIGZAG_X_SECONDARY_DEVIANCE,
                MAX_X_SECONDARY_DEVIANCE);

        mMaxYSecondaryDeviance = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_ZIGZAG_Y_SECONDARY_DEVIANCE,
                MAX_Y_SECONDARY_DEVIANCE);

    }

    @Override
    boolean isFalseTouch() {
        List<MotionEvent> motionEvents = getRecentMotionEvents();
        // Rotate horizontal gestures to be horizontal between their first and last point.
        // Rotate vertical gestures to be vertical between their first and last point.
        // Sum the absolute value of every dx and dy along the gesture. Compare this with the dx
        // and dy
        // between the first and last point.
        // For horizontal lines, the difference in the x direction should be small.
        // For vertical lines, the difference in the y direction should be small.

        if (motionEvents.size() < 3) {
            return false;
        }

        List<Point> rotatedPoints;
        if (isHorizontal()) {
            rotatedPoints = rotateHorizontal();
        } else {
            rotatedPoints = rotateVertical();
        }

        float actualDx = Math
                .abs(rotatedPoints.get(0).x - rotatedPoints.get(rotatedPoints.size() - 1).x);
        float actualDy = Math
                .abs(rotatedPoints.get(0).y - rotatedPoints.get(rotatedPoints.size() - 1).y);
        logDebug("Actual: (" + actualDx + "," + actualDy + ")");
        float runningAbsDx = 0;
        float runningAbsDy = 0;
        float pX = 0;
        float pY = 0;
        boolean firstLoop = true;
        for (Point point : rotatedPoints) {
            if (firstLoop) {
                pX = point.x;
                pY = point.y;
                firstLoop = false;
                continue;
            }
            runningAbsDx += Math.abs(point.x - pX);
            runningAbsDy += Math.abs(point.y - pY);
            pX = point.x;
            pY = point.y;
            logDebug("(x, y, runningAbsDx, runningAbsDy) - (" + pX + ", " + pY + ", " + runningAbsDx
                    + ", " + runningAbsDy + ")");
        }

        float devianceX = runningAbsDx - actualDx;
        float devianceY = runningAbsDy - actualDy;
        float distanceXIn = actualDx / getXdpi();
        float distanceYIn = actualDy / getYdpi();
        float totalDistanceIn = (float) Math
                .sqrt(distanceXIn * distanceXIn + distanceYIn * distanceYIn);

        float maxXDeviance;
        float maxYDeviance;
        if (actualDx > actualDy) {
            maxXDeviance = mMaxXPrimaryDeviance * totalDistanceIn * getXdpi();
            maxYDeviance = mMaxYSecondaryDeviance * totalDistanceIn * getYdpi();
        } else {
            maxXDeviance = mMaxXSecondaryDeviance * totalDistanceIn * getXdpi();
            maxYDeviance = mMaxYPrimaryDeviance * totalDistanceIn * getYdpi();
        }

        logDebug("Straightness Deviance: (" + devianceX + "," + devianceY + ") vs "
                + "(" + maxXDeviance + "," + maxYDeviance + ")");
        return devianceX > maxXDeviance || devianceY > maxYDeviance;
    }

    private float getAtan2LastPoint() {
        MotionEvent firstEvent = getFirstMotionEvent();
        MotionEvent lastEvent = getLastMotionEvent();
        float offsetX = firstEvent.getX();
        float offsetY = firstEvent.getY();
        float lastX = lastEvent.getX() - offsetX;
        float lastY = lastEvent.getY() - offsetY;

        return (float) Math.atan2(lastY, lastX);
    }

    private List<Point> rotateVertical() {
        // Calculate the angle relative to the y axis.
        double angle = Math.PI / 2 - getAtan2LastPoint();
        logDebug("Rotating to vertical by: " + angle);
        return rotateMotionEvents(getRecentMotionEvents(), -angle);
    }

    private List<Point> rotateHorizontal() {
        // Calculate the angle relative to the x axis.
        double angle = getAtan2LastPoint();
        logDebug("Rotating to horizontal by: " + angle);
        return rotateMotionEvents(getRecentMotionEvents(), angle);
    }

    private List<Point> rotateMotionEvents(List<MotionEvent> motionEvents, double angle) {
        List<Point> points = new ArrayList<>();
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);
        MotionEvent firstEvent = motionEvents.get(0);
        float offsetX = firstEvent.getX();
        float offsetY = firstEvent.getY();
        for (MotionEvent motionEvent : motionEvents) {
            float x = motionEvent.getX() - offsetX;
            float y = motionEvent.getY() - offsetY;
            double rotatedX = cosAngle * x + sinAngle * y + offsetX;
            double rotatedY = -sinAngle * x + cosAngle * y + offsetY;
            points.add(new Point((int) rotatedX, (int) rotatedY));
        }

        MotionEvent lastEvent = motionEvents.get(motionEvents.size() - 1);
        Point firstPoint = points.get(0);
        Point lastPoint = points.get(points.size() - 1);
        logDebug(
                "Before: (" + firstEvent.getX() + "," + firstEvent.getY() + "), ("
                        + lastEvent.getX() + ","
                        + lastEvent.getY() + ")");
        logDebug(
                "After: (" + firstPoint.x + "," + firstPoint.y + "), (" + lastPoint.x + ","
                        + lastPoint.y
                        + ")");

        return points;
    }

}
