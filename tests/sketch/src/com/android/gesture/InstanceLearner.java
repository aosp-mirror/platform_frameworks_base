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

import android.util.Config;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * An implementation of an instance-based learner
 */

class InstanceLearner extends Learner {

    private static final String LOGTAG = "InstanceLearner";

    @Override
    ArrayList<Prediction> classify(int gestureType, float[] vector) {
        ArrayList<Prediction> predictions = new ArrayList<Prediction>();
        ArrayList<Instance> instances = getInstances();
        int count = instances.size();
        TreeMap<String, Double> label2score = new TreeMap<String, Double>();
        for (int i = 0; i < count; i++) {
            Instance sample = instances.get(i);
            if (sample.vector.length != vector.length) {
                continue;
            }
            double distance;
            if (gestureType == GestureLibrary.SEQUENCE_SENSITIVE) {
                distance = GestureUtilities.cosineDistance(sample.vector, vector);
            } else {
                distance = GestureUtilities.squaredEuclideanDistance(sample.vector, vector);
            }
            double weight;
            if (distance == 0) {
                weight = Double.MAX_VALUE;
            } else {
                weight = 1 / distance;
            }
            Double score = label2score.get(sample.label);
            if (score == null || weight > score) {
                label2score.put(sample.label, weight);
            }
        }

        double sum = 0;
        Iterator<String> lableIterator = label2score.keySet().iterator();
        while (lableIterator.hasNext()) {
            String name = lableIterator.next();
            double score = label2score.get(name);
            sum += score;
            predictions.add(new Prediction(name, score));
        }

        // normalize
        Iterator<Prediction> predictionIterator = predictions.iterator();
        while (predictionIterator.hasNext()) {
            Prediction name = predictionIterator.next();
            name.score /= sum;
        }

        Collections.sort(predictions, new Comparator<Prediction>() {
            public int compare(Prediction object1, Prediction object2) {
                double score1 = object1.score;
                double score2 = object2.score;
                if (score1 > score2) {
                    return -1;
                } else if (score1 < score2) {
                    return 1;
                } else {
                    return 0;
                }
            }
        });

        if (Config.DEBUG) {
            predictionIterator = predictions.iterator();
            while (predictionIterator.hasNext()) {
                Prediction name = predictionIterator.next();
                Log.v(LOGTAG, "prediction [" + name.name + " = " + name.score + "]");
            }
        }

        return predictions;
    }
}
