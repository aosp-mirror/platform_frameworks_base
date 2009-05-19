/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.android.gesture;

/**
 * An instance represents a sample if the label is available or a query if the
 * label is null.
 */
class Instance {

    private static final int SEQUENCE_SAMPLE_SIZE = 16;

    private static final int PATCH_SAMPLE_SIZE = 8;

    private final static float[] ORIENTATIONS = {
            0, 45, 90, 135, 180, -0, -45, -90, -135, -180
    };

    // the feature vector
    final float[] vector;

    // the label can be null
    final String label;

    // the length of the vector
    final float magnitude;

    // the id of the instance
    final long instanceID;

    private Instance(long id, float[] sample, String sampleName) {
        instanceID = id;
        vector = sample;
        label = sampleName;
        float sum = 0;
        int size = sample.length;
        for (int i = 0; i < size; i++) {
            sum += sample[i] * sample[i];
        }
        magnitude = (float) Math.sqrt(sum);
    }

    /**
     * create a learning instance for a single stroke gesture
     * 
     * @param gesture
     * @param label
     * @return the instance
     */
    static Instance createInstance(GestureLibrary gesturelib, Gesture gesture, String label) {
        float[] pts;
        if (gesturelib.getGestureType() == GestureLibrary.SEQUENCE_SENSITIVE) {
            pts = temporalSampler(gesturelib, gesture);
        } else {
            pts = spatialSampler(gesture);
        }
        return new Instance(gesture.getID(), pts, label);
    }

    private static float[] spatialSampler(Gesture gesture) {
        float[] pts = GestureUtils.spatialSampling(gesture, PATCH_SAMPLE_SIZE);
        return pts;
    }

    private static float[] temporalSampler(GestureLibrary gesturelib, Gesture gesture) {
        float[] pts = GestureUtils.temporalSampling(gesture.getStrokes().get(0),
                SEQUENCE_SAMPLE_SIZE);
        float[] center = GestureUtils.computeCentroid(pts);
        float orientation = (float) Math.atan2(pts[1] - center[1], pts[0] - center[0]);
        orientation *= 180 / Math.PI;

        float adjustment = -orientation;
        if (gesturelib.getOrientationStyle() == GestureLibrary.ORIENTATION_SENSITIVE) {
            int count = ORIENTATIONS.length;
            for (int i = 0; i < count; i++) {
                float delta = ORIENTATIONS[i] - orientation;
                if (Math.abs(delta) < Math.abs(adjustment)) {
                    adjustment = delta;
                }
            }
        }

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(-center[0], -center[1]);
        android.graphics.Matrix rotation = new android.graphics.Matrix();
        rotation.setRotate(adjustment);
        m.postConcat(rotation);
        m.mapPoints(pts);
        return pts;
    }

}
