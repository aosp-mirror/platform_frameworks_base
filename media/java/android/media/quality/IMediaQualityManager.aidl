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
    PictureProfile createPictureProfile(in PictureProfile pp, int userId);
    void updatePictureProfile(in String id, in PictureProfile pp, int userId);
    void removePictureProfile(in String id, int userId);
    PictureProfile getPictureProfile(in int type, in String name, int userId);
    List<PictureProfile> getPictureProfilesByPackage(in String packageName, int userId);
    List<PictureProfile> getAvailablePictureProfiles(int userId);
    List<String> getPictureProfilePackageNames(int userId);
    List<String> getPictureProfileAllowList(int userId);
    void setPictureProfileAllowList(in List<String> packages, int userId);
    PictureProfileHandle getPictureProfileHandle(in String id, int userId);

    SoundProfile createSoundProfile(in SoundProfile pp, int userId);
    void updateSoundProfile(in String id, in SoundProfile pp, int userId);
    void removeSoundProfile(in String id, int userId);
    SoundProfile getSoundProfile(in int type, in String name, int userId);
    List<SoundProfile> getSoundProfilesByPackage(in String packageName, int userId);
    List<SoundProfile> getAvailableSoundProfiles(int userId);
    List<String> getSoundProfilePackageNames(int userId);
    List<String> getSoundProfileAllowList(int userId);
    void setSoundProfileAllowList(in List<String> packages, int userId);

    void registerPictureProfileCallback(in IPictureProfileCallback cb);
    void registerSoundProfileCallback(in ISoundProfileCallback cb);
    void registerAmbientBacklightCallback(in IAmbientBacklightCallback cb);

    List<ParamCapability> getParamCapabilities(in List<String> names, int userId);

    boolean isSupported(int userId);
    void setAutoPictureQualityEnabled(in boolean enabled, int userId);
    boolean isAutoPictureQualityEnabled(int userId);
    void setSuperResolutionEnabled(in boolean enabled, int userId);
    boolean isSuperResolutionEnabled(int userId);
    void setAutoSoundQualityEnabled(in boolean enabled, int userId);
    boolean isAutoSoundQualityEnabled(int userId);

    void setAmbientBacklightSettings(in AmbientBacklightSettings settings, int userId);
    void setAmbientBacklightEnabled(in boolean enabled, int userId);
    boolean isAmbientBacklightEnabled(int userId);
}
