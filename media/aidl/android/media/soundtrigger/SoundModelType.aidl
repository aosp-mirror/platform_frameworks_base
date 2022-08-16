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
 * Sound model type.
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum SoundModelType {
    /**
     * Used as default value in parcelables to indicate that a value was not set.
     * Should never be considered a valid setting, except for backward compatibility scenarios.
     */
    INVALID = -1,
    /** Key phrase sound models */
    KEYPHRASE = 0,
    /** All models other than keyphrase */
    GENERIC = 1,
}
