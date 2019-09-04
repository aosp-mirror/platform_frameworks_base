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

package android.service.autofill;

import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.autofill.AutofillValue;
import java.util.List;
import java.util.Map;

/**
 * Service used to calculate match scores for Autofill Field Classification.
 *
 * @hide
 */
oneway interface IAutofillFieldClassificationService {
    void calculateScores(in RemoteCallback callback, in List<AutofillValue> actualValues,
                         in String[] userDataValues, in String[] categoryIds,
                         in String defaultAlgorithm, in Bundle defaultArgs,
                         in Map algorithms, in Map args);
}
