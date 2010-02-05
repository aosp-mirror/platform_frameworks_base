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

package android.gesture;


/**
 * An instance represents a sample if the label is available or a query if the
 * label is null.
 */
class Instance {
    private static final int SEQUENCE_SAMPLE_SIZE = 16;

    private static final int PATCH_SAMPLE_SIZE = 16;

    private final static float[] ORIENTATIONS = {
            0, (float) (Math.PI / 4), (float) (Math.PI / 2), (float) (Math.PI * 3 / 4),
            (float) Math.PI, -0, (float) (-Math.PI / 4), (float) (-Math.PI / 2),
            (float) (-Math.PI * 3 / 4), (float) -Math.PI
    };

    // the feature vector
    final float[] vector;

    // the label can be null
    final String label;

    // the id of the instance
    final long id;

    private Instance(long id, float[] sample, String sampleName) {
        this.id = id;
        vector = sample;
        label = sampleName;
    }

    private void normalize() {
        float[] sample = vector;
        float sum = 0;

        int size = sample.length;
        for (int i = 0; i < size; i++) {
            sum += sample[i] * sample[i];
        }

        float magnitude = (float)Math.sqrt(sum);
        for (int i = 0; i < size; i++) {
            sample[i] /= magnitude;
        }
    }

    /**
     * create a learning instance for a single stroke gesture
     * 
     * @param gesture
     * @param label
     * @return the instance
     */
    static Instance createInstance(int sequenceType, int orientationType, Gesture gesture, String label) {
        float[] pts;
        Instance instance;
        if (sequenceType == GestureStore.SEQUENCE_SENSITIVE) {
            pts = temporalSampler(orientationType, gesture);
            instance = new Instance(gesture.getID(), pts, label);
            instance.normalize();
        } else {
            pts = spatialSampler(gesture);
            instance = new Instance(gesture.getID(), pts, label);
        }
        return instance;
    }

    private static float[] spatialSampler(Gesture gesture) {
        return GestureUtils.spatialSampling(gesture, PATCH_SAMPLE_SIZE, false);
    }

    private static float[] temporalSampler(int orientationType, Gesture gesture) {
        float[] pts = GestureUtils.temporalSampling(gesture.getStrokes().get(0),
                SEQUENCE_SAMPLE_SIZE);
        float[] center = GestureUtils.computeCentroid(pts);
        float orientation = (float)Math.atan2(pts[1] - center[1], pts[0] - center[0]);

        float adjustment = -orientation;
        if (orientationType != GestureStore.ORIENTATION_INVARIANT) {
            int count = ORIENTATIONS.length;
            for (int i = 0; i < count; i++) {
                float delta = ORIENTATIONS[i] - orientation;
                if (Math.abs(delta) < Math.abs(adjustment)) {
                    adjustment = delta;
                }
            }
        }

        GestureUtils.translate(pts, -center[0], -center[1]);
        GestureUtils.rotate(pts, adjustment);

        return pts;
    }

}
