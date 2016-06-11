/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server;

import android.test.AndroidTestCase;

import java.lang.Exception;
import java.lang.Math;

/**
 * Tests for {@link com.android.server.AnyMotionDetector.Vector3}
 */
public class Vector3Test extends AndroidTestCase {
    private static final float tolerance = 1.0f / (1 << 12);
    private static final float STATIONARY_ANGLE_THRESHOLD = 0.05f;

    private AnyMotionDetector.Vector3 unitXAxis;
    private AnyMotionDetector.Vector3 unitYAxis;
    private AnyMotionDetector.Vector3 unitZAxis;
    private AnyMotionDetector.Vector3 x3;
    private AnyMotionDetector.Vector3 case1A;
    private AnyMotionDetector.Vector3 case1B;
    private AnyMotionDetector.Vector3 case2A;
    private AnyMotionDetector.Vector3 case2B;
    private AnyMotionDetector.Vector3 x1y1;
    private AnyMotionDetector.Vector3 xn1y1;
    private AnyMotionDetector.Vector3 x1z1;
    private AnyMotionDetector.Vector3 y1z1;
    private AnyMotionDetector.Vector3 piOverSixUnitCircle;


    private boolean nearlyEqual(float a, float b) {
        return Math.abs(a - b) <= tolerance;
    }

    public void setUp() throws Exception {
        super.setUp();
        unitXAxis = new AnyMotionDetector.Vector3(0, 1, 0, 0);
        unitYAxis = new AnyMotionDetector.Vector3(0, 0, 1, 0);
        unitZAxis = new AnyMotionDetector.Vector3(0, 0, 0, 1);
        x3 = new AnyMotionDetector.Vector3(0, 3, 0, 0);
        x1y1 = new AnyMotionDetector.Vector3(0, 1, 1, 0);
        xn1y1 = new AnyMotionDetector.Vector3(0, -1, 1, 0);
        x1z1 = new AnyMotionDetector.Vector3(0, 1, 0, 1);
        y1z1 = new AnyMotionDetector.Vector3(0, 0, 1, 1);
        piOverSixUnitCircle = new AnyMotionDetector.Vector3(
                0, (float)Math.sqrt(3)/2, (float)0.5, 0);

        case1A = new AnyMotionDetector.Vector3(0, -9.81f, -0.02f, 0.3f);
        case1B = new AnyMotionDetector.Vector3(0, -9.80f, -0.02f, 0.3f);
        case2A = new AnyMotionDetector.Vector3(0, 1f, 2f, 3f);
        case2B = new AnyMotionDetector.Vector3(0, 4f, 5f, 6f);
    }

    public void testVector3Norm() {
        assertTrue(nearlyEqual(unitXAxis.norm(), 1.0f));
        assertTrue(nearlyEqual(unitYAxis.norm(), 1.0f));
        assertTrue(nearlyEqual(unitZAxis.norm(), 1.0f));
        assertTrue(nearlyEqual(x1y1.norm(), (float)Math.sqrt(2)));
    }

    public void testVector3AngleBetween() {
        // Zero angle.
        assertTrue(nearlyEqual(unitXAxis.angleBetween(unitXAxis), 0.0f));
        assertTrue(nearlyEqual(unitYAxis.angleBetween(unitYAxis), 0.0f));
        assertTrue(nearlyEqual(unitZAxis.angleBetween(unitZAxis), 0.0f));

        // Unit axes should be perpendicular.
        assertTrue(nearlyEqual(unitXAxis.angleBetween(unitYAxis), 90.0f));
        assertTrue(nearlyEqual(unitXAxis.angleBetween(unitZAxis), 90.0f));
        assertTrue(nearlyEqual(unitYAxis.angleBetween(unitZAxis), 90.0f));

        // 45 degree angles.
        assertTrue(nearlyEqual(unitXAxis.angleBetween(x1y1), 45.0f));
        assertTrue(nearlyEqual(unitYAxis.angleBetween(x1y1), 45.0f));

        // 135 degree angles.
        assertTrue(nearlyEqual(xn1y1.angleBetween(unitXAxis), 135.0f));

        // 30 degree angles.
        assertTrue(nearlyEqual(piOverSixUnitCircle.angleBetween(unitXAxis), 30.0f));

        // These vectors are expected to be still.
        assertTrue(case1A.angleBetween(case1A) < STATIONARY_ANGLE_THRESHOLD);
        assertTrue(case1A.angleBetween(case1B) < STATIONARY_ANGLE_THRESHOLD);
        assertTrue(unitXAxis.angleBetween(unitXAxis) < STATIONARY_ANGLE_THRESHOLD);
        assertTrue(unitYAxis.angleBetween(unitYAxis) < STATIONARY_ANGLE_THRESHOLD);
        assertTrue(unitZAxis.angleBetween(unitZAxis) < STATIONARY_ANGLE_THRESHOLD);
    }

    public void testVector3Normalized() {
        AnyMotionDetector.Vector3 unitXAxisNormalized = unitXAxis.normalized();
        assertTrue(nearlyEqual(unitXAxisNormalized.x, unitXAxis.x));
        assertTrue(nearlyEqual(unitXAxisNormalized.y, unitXAxis.y));
        assertTrue(nearlyEqual(unitXAxisNormalized.z, unitXAxis.z));

        // Normalizing the vector created by multiplying the unit vector by 3 gets the unit vector.
        AnyMotionDetector.Vector3 x3Normalized = x3.normalized();
        assertTrue(nearlyEqual(x3Normalized.x, unitXAxis.x));
        assertTrue(nearlyEqual(x3Normalized.y, unitXAxis.y));
        assertTrue(nearlyEqual(x3Normalized.z, unitXAxis.z));
    }

    public void testVector3Cross() {
        AnyMotionDetector.Vector3 xCrossX = unitXAxis.cross(unitXAxis);
        assertTrue(nearlyEqual(xCrossX.x, 0f));
        assertTrue(nearlyEqual(xCrossX.y, 0f));
        assertTrue(nearlyEqual(xCrossX.z, 0f));

        AnyMotionDetector.Vector3 xCrossNx = unitXAxis.cross(unitXAxis.times(-1));
        assertTrue(nearlyEqual(xCrossNx.x, 0f));
        assertTrue(nearlyEqual(xCrossNx.y, 0f));
        assertTrue(nearlyEqual(xCrossNx.z, 0f));

        AnyMotionDetector.Vector3 cross2 = case2A.cross(case2B);
        assertTrue(nearlyEqual(cross2.x, -3));
        assertTrue(nearlyEqual(cross2.y, 6));
        assertTrue(nearlyEqual(cross2.z, -3));
    }

     public void testVector3Times() {
         AnyMotionDetector.Vector3 yTimes2 = unitYAxis.times(2);
         assertTrue(nearlyEqual(yTimes2.x, 0f));
         assertTrue(nearlyEqual(yTimes2.y, 2f));
         assertTrue(nearlyEqual(yTimes2.z, 0f));
     }

     public void testVector3Plus() {
         AnyMotionDetector.Vector3 xPlusY = unitXAxis.plus(unitYAxis);
         assertTrue(nearlyEqual(xPlusY.x, 1f));
         assertTrue(nearlyEqual(xPlusY.y, 1f));
         assertTrue(nearlyEqual(xPlusY.z, 0f));
     }

     public void testVector3Minus() {
         AnyMotionDetector.Vector3 xMinusY = unitXAxis.minus(unitYAxis);
         assertTrue(nearlyEqual(xMinusY.x, 1f));
         assertTrue(nearlyEqual(xMinusY.y, -1f));
         assertTrue(nearlyEqual(xMinusY.z, 0f));
     }

     public void testVector3DotProduct() {
         float xDotX = unitXAxis.dotProduct(unitXAxis);
         float xDotY = unitXAxis.dotProduct(unitYAxis);
         float xDotZ = unitXAxis.dotProduct(unitZAxis);
         assertTrue(nearlyEqual(xDotX, 1f));
         assertTrue(nearlyEqual(xDotY, 0f));
         assertTrue(nearlyEqual(xDotZ, 0f));
     }
}
