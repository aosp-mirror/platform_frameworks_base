/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class LetterRecognizer {

    private static final String LOGTAG = "LetterRecognizer";

    public final static int LATTIN_LOWERCASE = 0;

    private SigmoidUnit[] mHiddenLayer;

    private SigmoidUnit[] mOutputLayer;

    private final String[] mClasses;

    private final int mInputCount;

    private class SigmoidUnit {

        private float[] mWeights;

        private SigmoidUnit(float[] weights) {
            mWeights = weights;
        }

        private float compute(float[] inputs) {
            float sum = 0;
            int count = inputs.length;
            float[] weights = mWeights;
            for (int i = 0; i < count; i++) {
                sum += inputs[i] * weights[i];
            }
            sum += weights[weights.length - 1];
            return 1 / (float)(1 + Math.exp(-sum));
        }
    }

    private LetterRecognizer(int numOfInput, int numOfHidden, String[] classes) {
        mInputCount = (int)Math.sqrt(numOfInput);
        mHiddenLayer = new SigmoidUnit[numOfHidden];
        mClasses = classes;
        mOutputLayer = new SigmoidUnit[classes.length];
    }

    public static LetterRecognizer getLetterRecognizer(Context context, int type) {
        switch (type) {
            case LATTIN_LOWERCASE: {
                return createFromResource(context, com.android.internal.R.raw.lattin_lowercase);
            }
        }
        return null;
    }

    public ArrayList<Prediction> recognize(Gesture gesture) {
        return this.classify(GestureUtils.spatialSampling(gesture, mInputCount));
    }

    private ArrayList<Prediction> classify(float[] vector) {
        float[] intermediateOutput = compute(mHiddenLayer, vector);
        float[] output = compute(mOutputLayer, intermediateOutput);
        ArrayList<Prediction> predictions = new ArrayList<Prediction>();
        double sum = 0;
        int count = mClasses.length;
        for (int i = 0; i < count; i++) {
            String name = mClasses[i];
            double score = output[i];
            sum += score;
            predictions.add(new Prediction(name, score));
        }

        for (int i = 0; i < count; i++) {
            Prediction name = predictions.get(i);
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
        return predictions;
    }

    private float[] compute(SigmoidUnit[] layer, float[] input) {
        float[] output = new float[layer.length];
        int count = layer.length;
        for (int i = 0; i < count; i++) {
            output[i] = layer[i].compute(input);
        }
        return output;
    }

    private static LetterRecognizer createFromResource(Context context, int resourceID) {
        Resources resources = context.getResources();
        InputStream stream = resources.openRawResource(resourceID);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            String line = reader.readLine();
            int startIndex = 0;
            int endIndex = -1;
            endIndex = line.indexOf(" ", startIndex);
            int iCount = Integer.parseInt(line.substring(startIndex, endIndex));

            startIndex = endIndex + 1;
            endIndex = line.indexOf(" ", startIndex);
            int hCount = Integer.parseInt(line.substring(startIndex, endIndex));

            startIndex = endIndex + 1;
            endIndex = line.length();
            int oCount = Integer.parseInt(line.substring(startIndex, endIndex));

            String[] classes = new String[oCount];
            line = reader.readLine();
            startIndex = 0;
            endIndex = -1;
            for (int i = 0; i < oCount; i++) {
                endIndex = line.indexOf(" ", startIndex);
                classes[i] = line.substring(startIndex, endIndex);
                startIndex = endIndex + 1;
            }

            LetterRecognizer classifier = new LetterRecognizer(iCount, hCount, classes);
            SigmoidUnit[] hiddenLayer = new SigmoidUnit[hCount];
            SigmoidUnit[] outputLayer = new SigmoidUnit[oCount];

            for (int i = 0; i < hCount; i++) {
                float[] weights = new float[iCount];
                line = reader.readLine();
                startIndex = 0;
                for (int j = 0; j < iCount; j++) {
                    endIndex = line.indexOf(" ", startIndex);
                    weights[j] = Float.parseFloat(line.substring(startIndex, endIndex));
                    startIndex = endIndex + 1;
                }
                hiddenLayer[i] = classifier.new SigmoidUnit(weights);
            }

            for (int i = 0; i < oCount; i++) {
                float[] weights = new float[hCount];
                line = reader.readLine();
                startIndex = 0;
                for (int j = 0; j < hCount; j++) {
                    endIndex = line.indexOf(" ", startIndex);
                    weights[j] = Float.parseFloat(line.substring(startIndex, endIndex));
                    startIndex = endIndex + 1;
                }
                outputLayer[i] = classifier.new SigmoidUnit(weights);
            }

            reader.close();

            classifier.mHiddenLayer = hiddenLayer;
            classifier.mOutputLayer = outputLayer;

            return classifier;

        } catch (IOException ex) {
            Log.d(LOGTAG, "Failed to save gestures:", ex);
        }
        return null;
    }
}
