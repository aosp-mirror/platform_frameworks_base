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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;

import java.util.Objects;

/**
 * Class that provides methods to create prediction clients.
 *
 * @hide
 */
@SystemApi
public final class AppPredictionManager {

    private final Context mContext;

    /**
     * @hide
     */
    public AppPredictionManager(Context context) {
        mContext = Objects.requireNonNull(context);
    }

    /**
     * Creates a new app prediction session.
     */
    @NonNull
    public AppPredictor createAppPredictionSession(
            @NonNull AppPredictionContext predictionContext) {
        return new AppPredictor(mContext, predictionContext);
    }
}
