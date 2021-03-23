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
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.people.data.DataManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Predictor that predicts the {@link AppTarget} the user is most likely to open.
 */
public class AppTargetPredictor {

    private static final String UI_SURFACE_SHARE = "share";

    /** Creates a {@link AppTargetPredictor} instance based on the prediction context. */
    public static AppTargetPredictor create(@NonNull AppPredictionContext predictionContext,
            @NonNull Consumer<List<AppTarget>> updatePredictionsMethod,
            @NonNull DataManager dataManager, @UserIdInt int callingUserId, Context context) {
        if (UI_SURFACE_SHARE.equals(predictionContext.getUiSurface())) {
            return new ShareTargetPredictor(predictionContext, updatePredictionsMethod, dataManager,
                    callingUserId, context);
        }
        return new AppTargetPredictor(
                predictionContext, updatePredictionsMethod, dataManager, callingUserId);
    }

    private final AppPredictionContext mPredictionContext;
    private final Consumer<List<AppTarget>> mUpdatePredictionsMethod;
    private final DataManager mDataManager;
    final int mCallingUserId;
    private final ExecutorService mCallbackExecutor;

    AppTargetPredictor(@NonNull AppPredictionContext predictionContext,
            @NonNull Consumer<List<AppTarget>> updatePredictionsMethod,
            @NonNull DataManager dataManager, @UserIdInt int callingUserId) {
        mPredictionContext = predictionContext;
        mUpdatePredictionsMethod = updatePredictionsMethod;
        mDataManager = dataManager;
        mCallingUserId = callingUserId;
        mCallbackExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Called by the client app to indicate a target launch.
     */
    @MainThread
    public void onAppTargetEvent(AppTargetEvent event) {
        mCallbackExecutor.execute(() -> reportAppTargetEvent(event));
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
        mCallbackExecutor.execute(() -> sortTargets(targets, callback));
    }

    /**
     * Called by the client app to request target predictions.
     */
    @MainThread
    public void onRequestPredictionUpdate() {
        mCallbackExecutor.execute(this::predictTargets);
    }

    @VisibleForTesting
    public Consumer<List<AppTarget>> getUpdatePredictionsMethod() {
        return mUpdatePredictionsMethod;
    }

    /** To be overridden by the subclass to report app target event. */
    @WorkerThread
    void reportAppTargetEvent(AppTargetEvent event) {
    }

    /** To be overridden by the subclass to predict the targets. */
    @WorkerThread
    void predictTargets() {
    }

    /**
     * To be overridden by the subclass to sort the provided targets based on the prediction
     * ranking.
     */
    @WorkerThread
    void sortTargets(List<AppTarget> targets, Consumer<List<AppTarget>> callback) {
        callback.accept(targets);
    }

    /** To be overridden by the subclass to recycle resources. */
    @WorkerThread
    void destroy() {
    }

    AppPredictionContext getPredictionContext() {
        return mPredictionContext;
    }

    DataManager getDataManager() {
        return mDataManager;
    }

    void updatePredictions(List<AppTarget> targets) {
        mUpdatePredictionsMethod.accept(targets);
    }
}
