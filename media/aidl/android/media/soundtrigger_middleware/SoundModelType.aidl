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
package android.media.soundtrigger_middleware;

/**
 * Sound model type.
 * {@hide}
 */
@Backing(type="int")
enum SoundModelType {
    /** Unspecified sound model type */
    UNKNOWN = -1,
    /** Key phrase sound models */
    KEYPHRASE = 0,
    /** All models other than keyphrase */
    GENERIC = 1,
}
