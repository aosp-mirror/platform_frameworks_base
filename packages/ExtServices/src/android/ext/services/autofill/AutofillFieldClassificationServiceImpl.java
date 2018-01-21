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

import java.util.List;

public class AutofillFieldClassificationServiceImpl extends AutofillFieldClassificationService {

    private static final String TAG = "AutofillFieldClassificationServiceImpl";
    // TODO(b/70291841): set to false before launching
    private static final boolean DEBUG = true;

    @Nullable
    @Override
    public float[][] onGetScores(@Nullable String algorithmName,
            @Nullable Bundle algorithmArgs, @NonNull List<AutofillValue> actualValues,
            @NonNull List<String> userDataValues) {
        if (ArrayUtils.isEmpty(actualValues) || ArrayUtils.isEmpty(userDataValues)) {
            Log.w(TAG, "getScores(): empty currentvalues (" + actualValues + ") or userValues ("
                    + userDataValues + ")");
            // TODO(b/70939974): add unit test
            return null;
        }
        if (algorithmName != null && !algorithmName.equals(EditDistanceScorer.NAME)) {
            Log.w(TAG, "Ignoring invalid algorithm (" + algorithmName + ") and using "
                    + EditDistanceScorer.NAME + " instead");
        }

        final String actualAlgorithmName = EditDistanceScorer.NAME;
        final int actualValuesSize = actualValues.size();
        final int userDataValuesSize = userDataValues.size();
        if (DEBUG) {
            Log.d(TAG, "getScores() will return a " + actualValuesSize + "x"
                    + userDataValuesSize + " matrix for " + actualAlgorithmName);
        }
        final float[][] scores = new float[actualValuesSize][userDataValuesSize];

        final EditDistanceScorer algorithm = EditDistanceScorer.getInstance();
        for (int i = 0; i < actualValuesSize; i++) {
            for (int j = 0; j < userDataValuesSize; j++) {
                final float score = algorithm.getScore(actualValues.get(i), userDataValues.get(j));
                scores[i][j] = score;
            }
        }
        return scores;
    }
}
