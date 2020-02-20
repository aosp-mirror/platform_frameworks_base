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

package com.android.server.people;

import android.annotation.UserIdInt;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppTarget;
import android.app.prediction.IPredictionCallback;
import android.content.pm.ParceledListSlice;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.people.data.DataManager;
import com.android.server.people.prediction.AppTargetPredictor;

import java.util.List;

/** Manages the information and callbacks in an app prediction request session. */
class SessionInfo {

    private static final String TAG = "SessionInfo";

    private final AppTargetPredictor mAppTargetPredictor;
    private final RemoteCallbackList<IPredictionCallback> mCallbacks =
            new RemoteCallbackList<>();

    SessionInfo(AppPredictionContext predictionContext, DataManager dataManager,
            @UserIdInt int callingUserId) {
        mAppTargetPredictor = AppTargetPredictor.create(predictionContext,
                this::updatePredictions, dataManager, callingUserId);
    }

    void addCallback(IPredictionCallback callback) {
        mCallbacks.register(callback);
    }

    void removeCallback(IPredictionCallback callback) {
        mCallbacks.unregister(callback);
    }

    AppTargetPredictor getPredictor() {
        return mAppTargetPredictor;
    }

    void onDestroy() {
        mCallbacks.kill();
    }

    private void updatePredictions(List<AppTarget> targets) {
        int callbackCount = mCallbacks.beginBroadcast();
        for (int i = 0; i < callbackCount; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onResult(new ParceledListSlice<>(targets));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to calling callback" + e);
            }
        }
        mCallbacks.finishBroadcast();
    }
}
