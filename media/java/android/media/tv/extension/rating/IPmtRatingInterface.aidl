/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.rating;

import android.media.tv.extension.rating.IPmtRatingListener;

/**
 * @hide
 */
interface IPmtRatingInterface {
    // Get Pmt rating information.
    String getPmtRating(String sessionToken);
    // Register a listener for pmt rating updates.
    void addPmtRatingListener(String clientToken, in IPmtRatingListener listener);
    // Remove the previously added IPmtRatingListener.
    void removePmtRatingListener(in IPmtRatingListener listener);
}
