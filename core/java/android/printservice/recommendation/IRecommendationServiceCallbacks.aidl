/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.printservice.recommendation;

import android.printservice.recommendation.RecommendationInfo;

/**
 * Callbacks for communication with the print service recommendation service.
 *
 * @see android.print.IPrintServiceRecommendationService
 *
 * @hide
 */
oneway interface IRecommendationServiceCallbacks {
    /**
     * Update the print service recommendations.
     *
     * @param recommendations the new print service recommendations
     */
    void onRecommendationsUpdated(in List<RecommendationInfo> recommendations);
}
