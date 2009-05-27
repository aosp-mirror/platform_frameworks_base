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

package android.gesture;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

import static android.gesture.GestureConstants.LOG_TAG;

public class LetterRecognizer {
    public final static int RECOGNIZER_LATIN_LOWERCASE = 0;
    static final String GESTURE_FILE_NAME = "letters.gestures";

    private final static int ADJUST_RANGE = 3;    

    private SigmoidUnit[] mHiddenLayer;
    private SigmoidUnit[] mOutputLayer;

    private final String[] mClasses;

    private final int mPatchSize;
    
    private GestureLibrary mGestureLibrary;

    private final Comparator<Prediction> mComparator = new PredictionComparator();

    private static class SigmoidUnit {
        final float[] mWeights;

        SigmoidUnit(float[] weights) {
            mWeights = weights;
        }

        private float compute(float[] inputs) {
            float sum = 0;

            final int count = inputs.length;
            final float[] weights = mWeights;

            for (int i = 0; i < count; i++) {
                sum += inputs[i] * weights[i];
            }
            sum += weights[weights.length - 1];

            return 1.0f / (float) (1 + Math.exp(-sum));
        }
    }

    public static LetterRecognizer getLetterRecognizer(Context context, int type) {
        switch (type) {
            case RECOGNIZER_LATIN_LOWERCASE: {
                return createFromResource(context, com.android.internal.R.raw.latin_lowercase);
            }
        }
        return null;
    }

    private LetterRecognizer(int numOfInput, int numOfHidden, String[] classes) {
        mPatchSize = (int) Math.sqrt(numOfInput);
        mHiddenLayer = new SigmoidUnit[numOfHidden];
        mClasses = classes;
        mOutputLayer = new SigmoidUnit[classes.length];
    }

    public ArrayList<Prediction> recognize(Gesture gesture) {
        return recognize(gesture, null);
    }

    public ArrayList<Prediction> recognize(Gesture gesture, ArrayList<Prediction> predictions) {
        float[] query = GestureUtilities.spatialSampling(gesture, mPatchSize);
        predictions = classify(query, predictions);
        adjustPrediction(gesture, predictions);
        return predictions;
    }

    private ArrayList<Prediction> classify(float[] vector, ArrayList<Prediction> predictions) {
        if (predictions == null) {
            predictions = new ArrayList<Prediction>();
        } else {
            predictions.clear();
        }

        final float[] intermediateOutput = compute(mHiddenLayer, vector);
        final float[] output = compute(mOutputLayer, intermediateOutput);

        double sum = 0;

        final String[] classes = mClasses;
        final int count = classes.length;

        for (int i = 0; i < count; i++) {
            double score = output[i];
            sum += score;
            predictions.add(new Prediction(classes[i], score));
        }

        for (int i = 0; i < count; i++) {
            predictions.get(i).score /= sum;
        }

        Collections.sort(predictions, mComparator);

        return predictions;
    }

    private float[] compute(SigmoidUnit[] layer, float[] input) {
        final float[] output = new float[layer.length];
        final int count = layer.length;

        for (int i = 0; i < count; i++) {
            output[i] = layer[i].compute(input);
        }

        return output;
    }

    private static LetterRecognizer createFromResource(Context context, int resourceID) {
        final Resources resources = context.getResources();

        DataInputStream in = null;
        LetterRecognizer classifier = null;

        try {
            in = new DataInputStream(new BufferedInputStream(resources.openRawResource(resourceID),
                    GestureConstants.IO_BUFFER_SIZE));

            final int version = in.readShort();

            switch (version) {
                case 1:
                    classifier = readV1(in);
                    break;
                default:
                    Log.d(LOG_TAG, "Couldn't load handwriting data: version " + version +
                            " not supported");
                    break;
            }

        } catch (IOException e) {
            Log.d(LOG_TAG, "Failed to load handwriting data:", e);
        } finally {
            GestureUtilities.closeStream(in);
        }

        return classifier;
    }

    private static LetterRecognizer readV1(DataInputStream in) throws IOException {

        final int iCount = in.readInt();
        final int hCount = in.readInt();
        final int oCount = in.readInt();

        final String[] classes = new String[oCount];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = in.readUTF();
        }

        final LetterRecognizer classifier = new LetterRecognizer(iCount, hCount, classes);
        final SigmoidUnit[] hiddenLayer = new SigmoidUnit[hCount];
        final SigmoidUnit[] outputLayer = new SigmoidUnit[oCount];

        for (int i = 0; i < hCount; i++) {
            final float[] weights = new float[iCount + 1];
            for (int j = 0; j <= iCount; j++) {
                weights[j] = in.readFloat();
            }
            hiddenLayer[i] = new SigmoidUnit(weights);
        }

        for (int i = 0; i < oCount; i++) {
            final float[] weights = new float[hCount + 1];
            for (int j = 0; j <= hCount; j++) {
                weights[j] = in.readFloat();
            }
            outputLayer[i] = new SigmoidUnit(weights);
        }

        classifier.mHiddenLayer = hiddenLayer;
        classifier.mOutputLayer = outputLayer;

        return classifier;
    }

    /**
     * TODO: Publish this API once we figure out where we should save the personzlied
     * gestures, and how to do so across all apps
     *
     * @hide
     */
    public boolean save() {
        if (mGestureLibrary != null) {
            return mGestureLibrary.save();
        }
        return false;
    }

    /**
     * TODO: Publish this API once we figure out where we should save the personzlied
     * gestures, and how to do so across all apps
     *
     * @hide
     */
    public void setPersonalizationEnabled(boolean enabled) {
        if (enabled) {
            mGestureLibrary = new GestureLibrary(GESTURE_FILE_NAME);
            mGestureLibrary.setSequenceType(GestureLibrary.SEQUENCE_INVARIANT);
            mGestureLibrary.load();
        } else {
            mGestureLibrary = null;
        }
    }

    /**
     * TODO: Publish this API once we figure out where we should save the personzlied
     * gestures, and how to do so across all apps
     *
     * @hide
     */
    public void addExample(String letter, Gesture example) {
        if (mGestureLibrary != null) {
            mGestureLibrary.addGesture(letter, example);
        }
    }
    
    private void adjustPrediction(Gesture query, ArrayList<Prediction> predictions) {
        if (mGestureLibrary != null) {
            final ArrayList<Prediction> results = mGestureLibrary.recognize(query);
            final HashMap<String, Prediction> topNList = new HashMap<String, Prediction>();

            for (int j = 0; j < ADJUST_RANGE; j++) {
                Prediction prediction = predictions.remove(0);
                topNList.put(prediction.name, prediction);
            }

            final int count = results.size();
            for (int j = count - 1; j >= 0 && !topNList.isEmpty(); j--) {
                final Prediction item = results.get(j);
                final Prediction original = topNList.get(item.name);
                if (original != null) {
                    predictions.add(0, original);
                    topNList.remove(item.name);
                }
            }
        }
    }

    private static class PredictionComparator implements Comparator<Prediction> {
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
    }
}
