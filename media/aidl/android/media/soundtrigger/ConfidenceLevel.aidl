/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.media.soundtrigger;

/**
 * A recognition confidence level.
 * This type is used to represent either a threshold or an actual detection confidence level.
 *
 * {@hide}
 */
@JavaDerive(equals = true, toString = true)
@VintfStability
parcelable ConfidenceLevel {
    /** user ID. */
    int userId;
    /**
     * Confidence level in percent (0 - 100).
     * <ul>
     * <li>Min level for recognition configuration
     * <li>Detected level for recognition event.
     * </ul>
     */
    int levelPercent;
}
