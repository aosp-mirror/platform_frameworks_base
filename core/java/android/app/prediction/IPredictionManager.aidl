/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.prediction;

import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.IPredictionCallback;
import android.content.pm.ParceledListSlice;

/**
 * @hide
 */
interface IPredictionManager {

    void createPredictionSession(in AppPredictionContext context,
            in AppPredictionSessionId sessionId);

    void notifyAppTargetEvent(in AppPredictionSessionId sessionId, in AppTargetEvent event);

    void notifyLocationShown(in AppPredictionSessionId sessionId, in String launchLocation,
            in ParceledListSlice targetIds);

    void sortAppTargets(in AppPredictionSessionId sessionId, in ParceledListSlice targets,
            in IPredictionCallback callback);

    void registerPredictionUpdates(in AppPredictionSessionId sessionId,
            in IPredictionCallback callback);

    void unregisterPredictionUpdates(in AppPredictionSessionId sessionId,
            in IPredictionCallback callback);

    void requestPredictionUpdate(in AppPredictionSessionId sessionId);

    void onDestroyPredictionSession(in AppPredictionSessionId sessionId);
}
