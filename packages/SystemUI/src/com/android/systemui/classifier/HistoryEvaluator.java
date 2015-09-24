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
 * limitations under the License
 */

package com.android.systemui.classifier;

import java.util.ArrayList;

/**
 * Holds the evaluations for ended strokes and gestures. These values are decreased through time.
 */
public class HistoryEvaluator {
    private static final float INTERVAL = 50.0f;
    private static final float HISTORY_FACTOR = 0.9f;
    private static final float EPSILON = 1e-5f;

    private final ArrayList<Data> mStrokes = new ArrayList<>();
    private final ArrayList<Data> mGestureWeights = new ArrayList<>();
    private long mLastUpdate;

    public HistoryEvaluator() {
        mLastUpdate = System.currentTimeMillis();
    }

    public void addStroke(float evaluation) {
        decayValue();
        mStrokes.add(new Data(evaluation));
    }

    public void addGesture(float evaluation) {
        decayValue();
        mGestureWeights.add(new Data(evaluation));
    }

    /**
     * Calculates the weighted average of strokes and adds to it the weighted average of gestures
     */
    public float getEvaluation() {
        return weightedAverage(mStrokes) + weightedAverage(mGestureWeights);
    }

    private float weightedAverage(ArrayList<Data> list) {
        float sumValue = 0.0f;
        float sumWeight = 0.0f;
        int size = list.size();
        for (int i = 0; i < size; i++) {
            Data data = list.get(i);
            sumValue += data.evaluation * data.weight;
            sumWeight += data.weight;
        }

        if (sumWeight == 0.0f) {
            return 0.0f;
        }

        return sumValue / sumWeight;
    }

    private void decayValue() {
        long currentTimeMillis = System.currentTimeMillis();

        // All weights are multiplied by HISTORY_FACTOR after each INTERVAL milliseconds.
        float factor = (float) Math.pow(HISTORY_FACTOR,
                (float) (currentTimeMillis - mLastUpdate) / INTERVAL);

        decayValue(mStrokes, factor);
        decayValue(mGestureWeights, factor);
        mLastUpdate = currentTimeMillis;
    }

    private void decayValue(ArrayList<Data> list, float factor) {
        int size = list.size();
        for (int i = 0; i < size; i++) {
            list.get(i).weight *= factor;
        }

        // Removing evaluations with such small weights that they do not matter anymore
        while (!list.isEmpty() && isZero(list.get(0).weight)) {
            list.remove(0);
        }
    }

    private boolean isZero(float x) {
        return x <= EPSILON && x >= -EPSILON;
    }

    /**
     * For each stroke it holds its initial value and the current weight. Initially the
     * weight is set to 1.0
     */
    private static class Data {
        public float evaluation;
        public float weight;

        public Data(float evaluation) {
            this.evaluation = evaluation;
            weight = 1.0f;
        }
    }
}
