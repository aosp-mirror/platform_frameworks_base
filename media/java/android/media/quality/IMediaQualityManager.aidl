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

import android.media.quality.IPictureProfileCallback;
import android.media.quality.ISoundProfileCallback;
import android.media.quality.PictureProfile;
import android.media.quality.SoundProfile;

/**
 * Interface for Media Quality Manager
 * @hide
 */
interface IMediaQualityManager {
    PictureProfile createPictureProfile(in PictureProfile pp);
    void updatePictureProfile(in long id, in PictureProfile pp);
    void removePictureProfile(in long id);
    PictureProfile getPictureProfileById(in long id);
    List<PictureProfile> getPictureProfilesByPackage(in String packageName);
    List<PictureProfile> getAvailablePictureProfiles();
    List<PictureProfile> getAllPictureProfiles();

    SoundProfile createSoundProfile(in SoundProfile pp);
    void updateSoundProfile(in long id, in SoundProfile pp);
    void removeSoundProfile(in long id);
    SoundProfile getSoundProfileById(in long id);
    List<SoundProfile> getSoundProfilesByPackage(in String packageName);
    List<SoundProfile> getAvailableSoundProfiles();
    List<SoundProfile> getAllSoundProfiles();

    void registerPictureProfileCallback(in IPictureProfileCallback cb);
    void registerSoundProfileCallback(in ISoundProfileCallback cb);
}