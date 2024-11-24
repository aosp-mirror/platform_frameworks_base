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

import android.media.quality.AmbientBacklightSettings;
import android.media.quality.IAmbientBacklightCallback;
import android.media.quality.IPictureProfileCallback;
import android.media.quality.ISoundProfileCallback;
import android.media.quality.ParamCapability;
import android.media.quality.PictureProfileHandle;
import android.media.quality.PictureProfile;
import android.media.quality.SoundProfile;

/**
 * Interface for Media Quality Manager
 * @hide
 */
interface IMediaQualityManager {
    PictureProfile createPictureProfile(in PictureProfile pp);
    void updatePictureProfile(in String id, in PictureProfile pp);
    void removePictureProfile(in String id);
    PictureProfile getPictureProfile(in int type, in String name);
    List<PictureProfile> getPictureProfilesByPackage(in String packageName);
    List<PictureProfile> getAvailablePictureProfiles();
    List<String> getPictureProfilePackageNames();
    List<String> getPictureProfileAllowList();
    void setPictureProfileAllowList(in List<String> packages);
    PictureProfileHandle getPictureProfileHandle(in String id);

    SoundProfile createSoundProfile(in SoundProfile pp);
    void updateSoundProfile(in String id, in SoundProfile pp);
    void removeSoundProfile(in String id);
    SoundProfile getSoundProfile(in int type, in String name);
    List<SoundProfile> getSoundProfilesByPackage(in String packageName);
    List<SoundProfile> getAvailableSoundProfiles();
    List<String> getSoundProfilePackageNames();
    List<String> getSoundProfileAllowList();
    void setSoundProfileAllowList(in List<String> packages);

    void registerPictureProfileCallback(in IPictureProfileCallback cb);
    void registerSoundProfileCallback(in ISoundProfileCallback cb);
    void registerAmbientBacklightCallback(in IAmbientBacklightCallback cb);

    List<ParamCapability> getParamCapabilities(in List<String> names);

    boolean isSupported();
    void setAutoPictureQualityEnabled(in boolean enabled);
    boolean isAutoPictureQualityEnabled();
    void setSuperResolutionEnabled(in boolean enabled);
    boolean isSuperResolutionEnabled();
    void setAutoSoundQualityEnabled(in boolean enabled);
    boolean isAutoSoundQualityEnabled();

    void setAmbientBacklightSettings(in AmbientBacklightSettings settings);
    void setAmbientBacklightEnabled(in boolean enabled);
    boolean isAmbientBacklightEnabled();
}
