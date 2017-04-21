/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.resolver;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.IBinder;
import android.os.storage.StorageManager;
import android.service.resolver.ResolverRankerService;
import android.service.resolver.ResolverTarget;
import android.util.ArrayMap;
import android.util.Log;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A Logistic Regression based {@link android.service.resolver.ResolverRankerService}, to be used
 * in {@link ResolverComparator}.
 */
public final class LRResolverRankerService extends ResolverRankerService {
    private static final String TAG = "LRResolverRankerService";

    private static final boolean DEBUG = false;

    private static final String PARAM_SHARED_PREF_NAME = "resolver_ranker_params";
    private static final String BIAS_PREF_KEY = "bias";
    private static final String VERSION_PREF_KEY = "version";

    private static final String LAUNCH_SCORE = "launch";
    private static final String TIME_SPENT_SCORE = "timeSpent";
    private static final String RECENCY_SCORE = "recency";
    private static final String CHOOSER_SCORE = "chooser";

    // parameters for a pre-trained model, to initialize the app ranker. When updating the
    // pre-trained model, please update these params, as well as initModel().
    private static final int CURRENT_VERSION = 1;
    private static final float LEARNING_RATE = 0.0001f;
    private static final float REGULARIZER_PARAM = 0.0001f;

    private SharedPreferences mParamSharedPref;
    private ArrayMap<String, Float> mFeatureWeights;
    private float mBias;

    @Override
    public IBinder onBind(Intent intent) {
        initModel();
        return super.onBind(intent);
    }

    @Override
    public void onPredictSharingProbabilities(List<ResolverTarget> targets) {
        final int size = targets.size();
        for (int i = 0; i < size; ++i) {
            ResolverTarget target = targets.get(i);
            ArrayMap<String, Float> features = getFeatures(target);
            target.setSelectProbability(predict(features));
        }
    }

    @Override
    public void onTrainRankingModel(List<ResolverTarget> targets, int selectedPosition) {
        final int size = targets.size();
        if (selectedPosition < 0 || selectedPosition >= size) {
            if (DEBUG) {
                Log.d(TAG, "Invalid Position of Selected App " + selectedPosition);
            }
            return;
        }
        final ArrayMap<String, Float> positive = getFeatures(targets.get(selectedPosition));
        final float positiveProbability = targets.get(selectedPosition).getSelectProbability();
        final int targetSize = targets.size();
        for (int i = 0; i < targetSize; ++i) {
            if (i == selectedPosition) {
                continue;
            }
            final ArrayMap<String, Float> negative = getFeatures(targets.get(i));
            final float negativeProbability = targets.get(i).getSelectProbability();
            if (negativeProbability > positiveProbability) {
                update(negative, negativeProbability, false);
                update(positive, positiveProbability, true);
            }
        }
        commitUpdate();
    }

    private void initModel() {
        mParamSharedPref = getParamSharedPref();
        mFeatureWeights = new ArrayMap<>(4);
        if (mParamSharedPref == null ||
                mParamSharedPref.getInt(VERSION_PREF_KEY, 0) < CURRENT_VERSION) {
            // Initializing the app ranker to a pre-trained model. When updating the pre-trained
            // model, please increment CURRENT_VERSION, and update LEARNING_RATE and
            // REGULARIZER_PARAM.
            mBias = -1.6568f;
            mFeatureWeights.put(LAUNCH_SCORE, 2.5543f);
            mFeatureWeights.put(TIME_SPENT_SCORE, 2.8412f);
            mFeatureWeights.put(RECENCY_SCORE, 0.269f);
            mFeatureWeights.put(CHOOSER_SCORE, 4.2222f);
        } else {
            mBias = mParamSharedPref.getFloat(BIAS_PREF_KEY, 0.0f);
            mFeatureWeights.put(LAUNCH_SCORE, mParamSharedPref.getFloat(LAUNCH_SCORE, 0.0f));
            mFeatureWeights.put(
                    TIME_SPENT_SCORE, mParamSharedPref.getFloat(TIME_SPENT_SCORE, 0.0f));
            mFeatureWeights.put(RECENCY_SCORE, mParamSharedPref.getFloat(RECENCY_SCORE, 0.0f));
            mFeatureWeights.put(CHOOSER_SCORE, mParamSharedPref.getFloat(CHOOSER_SCORE, 0.0f));
        }
    }

    private ArrayMap<String, Float> getFeatures(ResolverTarget target) {
        ArrayMap<String, Float> features = new ArrayMap<>(4);
        features.put(RECENCY_SCORE, target.getRecencyScore());
        features.put(TIME_SPENT_SCORE, target.getTimeSpentScore());
        features.put(LAUNCH_SCORE, target.getLaunchScore());
        features.put(CHOOSER_SCORE, target.getChooserScore());
        return features;
    }

    private float predict(ArrayMap<String, Float> target) {
        if (target == null) {
            return 0.0f;
        }
        final int featureSize = target.size();
        float sum = 0.0f;
        for (int i = 0; i < featureSize; i++) {
            String featureName = target.keyAt(i);
            float weight = mFeatureWeights.getOrDefault(featureName, 0.0f);
            sum += weight * target.valueAt(i);
        }
        return (float) (1.0 / (1.0 + Math.exp(-mBias - sum)));
    }

    private void update(ArrayMap<String, Float> target, float predict, boolean isSelected) {
        if (target == null) {
            return;
        }
        final int featureSize = target.size();
        float error = isSelected ? 1.0f - predict : -predict;
        for (int i = 0; i < featureSize; i++) {
            String featureName = target.keyAt(i);
            float currentWeight = mFeatureWeights.getOrDefault(featureName, 0.0f);
            mBias += LEARNING_RATE * error;
            currentWeight = currentWeight - LEARNING_RATE * REGULARIZER_PARAM * currentWeight +
                    LEARNING_RATE * error * target.valueAt(i);
            mFeatureWeights.put(featureName, currentWeight);
        }
        if (DEBUG) {
            Log.d(TAG, "Weights: " + mFeatureWeights + " Bias: " + mBias);
        }
    }

    private void commitUpdate() {
        try {
            SharedPreferences.Editor editor = mParamSharedPref.edit();
            editor.putFloat(BIAS_PREF_KEY, mBias);
            final int size = mFeatureWeights.size();
            for (int i = 0; i < size; i++) {
                editor.putFloat(mFeatureWeights.keyAt(i), mFeatureWeights.valueAt(i));
            }
            editor.putInt(VERSION_PREF_KEY, CURRENT_VERSION);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to commit update" + e);
        }
    }

    private SharedPreferences getParamSharedPref() {
        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        if (DEBUG) {
            Log.d(TAG, "Context Package Name: " + getPackageName());
        }
        final File prefsFile = new File(new File(
                Environment.getDataUserCePackageDirectory(
                        StorageManager.UUID_PRIVATE_INTERNAL, getUserId(), getPackageName()),
                "shared_prefs"),
                PARAM_SHARED_PREF_NAME + ".xml");
        return getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
    }
}