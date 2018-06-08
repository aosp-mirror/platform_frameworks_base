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

import static android.ext.services.autofill.EditDistanceScorer.DEFAULT_ALGORITHM;

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

    @Nullable
    @Override
    public float[][] onGetScores(@Nullable String algorithmName,
            @Nullable Bundle algorithmArgs, @NonNull List<AutofillValue> actualValues,
            @NonNull List<String> userDataValues) {
        if (ArrayUtils.isEmpty(actualValues) || ArrayUtils.isEmpty(userDataValues)) {
            Log.w(TAG, "getScores(): empty currentvalues (" + actualValues + ") or userValues ("
                    + userDataValues + ")");
            return null;
        }
        if (algorithmName != null && !algorithmName.equals(DEFAULT_ALGORITHM)) {
            Log.w(TAG, "Ignoring invalid algorithm (" + algorithmName + ") and using "
                    + DEFAULT_ALGORITHM + " instead");
        }

        return EditDistanceScorer.getScores(actualValues, userDataValues);
    }
}
