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
 * Recognition mode.
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum RecognitionMode {
    /** Simple voice trigger. */
    VOICE_TRIGGER       = 0x1,
    /** Trigger only if one user in model identified. */
    USER_IDENTIFICATION = 0x2,
    /** Trigger only if one user in model authenticated. */
    USER_AUTHENTICATION = 0x4,
    /** Generic sound trigger. */
    GENERIC_TRIGGER     = 0x8,
}
