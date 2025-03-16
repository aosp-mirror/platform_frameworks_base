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

import android.media.tv.extension.rating.IProgramRatingInfoListener;
import android.os.Bundle;

/**
 * @hide
 */
interface IProgramRatingInfo {
    // Register a listener to receive notifications when ProgramRatingInfo is updated.
    void addProgramRatingInfoListener(String clientToken, in IProgramRatingInfoListener listener);
    // Remove a listener for ProgramRatingInfo update notifications.
    void removeProgramRatingInfoListener(in IProgramRatingInfoListener listener);
    // Get ProgramRatingInfo that may only be obtained when viewing.
    Bundle getProgramRatingInfo(String sessionToken);
}
