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
 * Model specific parameters to be used with parameter set and get APIs.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum ModelParameter {
    /**
     * Placeholder for invalid model parameter used for returning error or
     * passing an invalid value.
     */
    INVALID = -1,

    /**
     * Controls the sensitivity threshold adjustment factor for a given model.
     * Negative value corresponds to less sensitive model (high threshold) and
     * a positive value corresponds to a more sensitive model (low threshold).
     * Default value is 0.
     */
    THRESHOLD_FACTOR = 0,
}
