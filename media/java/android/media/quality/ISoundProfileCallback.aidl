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


package android.media.quality;

import android.media.quality.ParamCapability;
import android.media.quality.SoundProfile;

/**
 * Interface to receive callbacks from IMediaQuality.
 * @hide
 */
oneway interface ISoundProfileCallback {
    void onSoundProfileAdded(in String id, in SoundProfile p);
    void onSoundProfileUpdated(in String id, in SoundProfile p);
    void onSoundProfileRemoved(in String id, in SoundProfile p);
    void onParamCapabilitiesChanged(in String id, in List<ParamCapability> caps);
    void onError(in String id, in int err);
}
