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

import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Phrase;

/**
 * Specialized sound model for key phrase detection.
 * Proprietary representation of key phrases in binary data must match
 * information indicated by phrases field.
 * {@hide}
 */
@VintfStability
parcelable PhraseSoundModel {
    /** Common part of sound model descriptor */
    SoundModel common;
    /** List of descriptors for key phrases supported by this sound model */
    Phrase[] phrases;
}
