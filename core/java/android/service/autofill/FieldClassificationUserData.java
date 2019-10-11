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
import android.util.ArrayMap;

/**
 * Class used to define a generic UserData for field classification
 *
 * @hide
 */
public interface FieldClassificationUserData {
    /**
     * Gets the name of the default algorithm that is used to calculate
     * {@link FieldClassification.Match#getScore()} match scores}.
     */
    String getFieldClassificationAlgorithm();

    /**
     * Gets the default field classification args.
     */
    Bundle getDefaultFieldClassificationArgs();

    /**
     * Gets the name of the field classification algorithm for a specific category.
     *
     * @param categoryId id of the specific category.
     */
    String getFieldClassificationAlgorithmForCategory(String categoryId);

    /**
     * Gets all field classification algorithms for specific categories.
     */
    ArrayMap<String, String> getFieldClassificationAlgorithms();

    /**
     * Gets all field classification args for specific categories.
     */
    ArrayMap<String, Bundle> getFieldClassificationArgs();

    /**
     * Gets all category ids
     */
    String[] getCategoryIds();

    /**
     * Gets all string values for field classification
     */
    String[] getValues();
}
