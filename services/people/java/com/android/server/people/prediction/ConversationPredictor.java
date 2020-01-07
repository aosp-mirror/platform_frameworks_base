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

package com.android.server.people.prediction;

import android.annotation.MainThread;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Predictor that predicts the conversations or apps the user is most likely to open.
 */
public class ConversationPredictor {

    private final AppPredictionContext mPredictionContext;
    private final Consumer<List<AppTarget>> mUpdatePredictionsMethod;
    private final ExecutorService mCallbackExecutor;

    public ConversationPredictor(AppPredictionContext predictionContext,
            Consumer<List<AppTarget>> updatePredictionsMethod) {
        mPredictionContext = predictionContext;
        mUpdatePredictionsMethod = updatePredictionsMethod;
        mCallbackExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Called by the client app to indicate a target launch.
     */
    @MainThread
    public void onAppTargetEvent(AppTargetEvent event) {
    }

    /**
     * Called by the client app to indicate a particular location has been shown to the user.
     */
    @MainThread
    public void onLaunchLocationShown(String launchLocation, List<AppTargetId> targetIds) {
    }

    /**
     * Called by the client app to request sorting of the provided targets based on the prediction
     * ranking.
     */
    @MainThread
    public void onSortAppTargets(List<AppTarget> targets, Consumer<List<AppTarget>> callback) {
        mCallbackExecutor.execute(() -> callback.accept(targets));
    }

    /**
     * Called by the client app to request target predictions.
     */
    @MainThread
    public void onRequestPredictionUpdate() {
        List<AppTarget> targets = new ArrayList<>();
        mCallbackExecutor.execute(() -> mUpdatePredictionsMethod.accept(targets));
    }

    @VisibleForTesting
    public Consumer<List<AppTarget>> getUpdatePredictionsMethod() {
        return mUpdatePredictionsMethod;
    }
}
