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
package android.ext.services.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.service.autofill.AutofillFieldClassificationService;
import android.util.Log;
import android.view.autofill.AutofillValue;

import com.android.internal.util.ArrayUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutofillFieldClassificationServiceImpl extends AutofillFieldClassificationService {

    private static final String TAG = "AutofillFieldClassificationServiceImpl";

    private static final String DEFAULT_ALGORITHM = REQUIRED_ALGORITHM_EDIT_DISTANCE;

    @Nullable
    @Override
    /** @hide */
    public float[][] onCalculateScores(@NonNull List<AutofillValue> actualValues,
            @NonNull List<String> userDataValues, @NonNull List<String> categoryIds,
            @Nullable String defaultAlgorithm, @Nullable Bundle defaultArgs,
            @Nullable Map algorithms, @Nullable Map args) {
        if (ArrayUtils.isEmpty(actualValues) || ArrayUtils.isEmpty(userDataValues)) {
            Log.w(TAG, "calculateScores(): empty currentvalues (" + actualValues
                    + ") or userValues (" + userDataValues + ")");
            return null;
        }

        return calculateScores(actualValues, userDataValues, categoryIds, defaultAlgorithm,
                defaultArgs, (HashMap<String, String>) algorithms,
                (HashMap<String, Bundle>) args);
    }

    /** @hide */
    public float[][] calculateScores(@NonNull List<AutofillValue> actualValues,
            @NonNull List<String> userDataValues, @NonNull List<String> categoryIds,
            @Nullable String defaultAlgorithm, @Nullable Bundle defaultArgs,
            @Nullable HashMap<String, String> algorithms,
            @Nullable HashMap<String, Bundle> args) {
        final int actualValuesSize = actualValues.size();
        final int userDataValuesSize = userDataValues.size();
        final float[][] scores = new float[actualValuesSize][userDataValuesSize];

        for (int j = 0; j < userDataValuesSize; j++) {
            final String categoryId = categoryIds.get(j);
            String algorithmName = defaultAlgorithm;
            Bundle arg = defaultArgs;
            if (algorithms != null && algorithms.containsKey(categoryId)) {
                algorithmName = algorithms.get(categoryId);
            }
            if (args != null && args.containsKey(categoryId)) {
                arg = args.get(categoryId);
            }

            if (algorithmName == null || (!algorithmName.equals(DEFAULT_ALGORITHM)
                    && !algorithmName.equals(REQUIRED_ALGORITHM_EXACT_MATCH))) {
                Log.w(TAG, "algorithmName is " + algorithmName + ", defaulting to "
                        + DEFAULT_ALGORITHM);
                algorithmName = DEFAULT_ALGORITHM;
            }

            for (int i = 0; i < actualValuesSize; i++) {
                if (algorithmName.equals(DEFAULT_ALGORITHM)) {
                    scores[i][j] = EditDistanceScorer.calculateScore(actualValues.get(i),
                            userDataValues.get(j));
                } else {
                    scores[i][j] = ExactMatch.calculateScore(actualValues.get(i),
                            userDataValues.get(j), arg);
                }
            }
        }
        return scores;
    }
}
