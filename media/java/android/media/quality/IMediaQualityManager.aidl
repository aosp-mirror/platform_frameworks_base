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
import android.media.quality.SoundProfileHandle;
import android.media.quality.SoundProfile;
import android.os.UserHandle;

/**
 * Interface for Media Quality Manager
 * @hide
 */
interface IMediaQualityManager {
    PictureProfile createPictureProfile(in PictureProfile pp, in UserHandle user);
    void updatePictureProfile(in String id, in PictureProfile pp, in UserHandle user);
    void removePictureProfile(in String id, in UserHandle user);
    boolean setDefaultPictureProfile(in String id, in UserHandle user);
    PictureProfile getPictureProfile(
            in int type, in String name, in boolean includeParams, in UserHandle user);
    List<PictureProfile> getPictureProfilesByPackage(
            in String packageName, in boolean includeParams, in UserHandle user);
    List<PictureProfile> getAvailablePictureProfiles(in boolean includeParams, in UserHandle user);
    List<String> getPictureProfilePackageNames(in UserHandle user);
    List<String> getPictureProfileAllowList(in UserHandle user);
    void setPictureProfileAllowList(in List<String> packages, in UserHandle user);
    List<PictureProfileHandle> getPictureProfileHandle(in String[] id, in UserHandle user);

    SoundProfile createSoundProfile(in SoundProfile pp, in UserHandle user);
    void updateSoundProfile(in String id, in SoundProfile pp, in UserHandle user);
    void removeSoundProfile(in String id, in UserHandle user);
    boolean setDefaultSoundProfile(in String id, in UserHandle user);
    SoundProfile getSoundProfile(
            in int type, in String name, in boolean includeParams, in UserHandle user);
    List<SoundProfile> getSoundProfilesByPackage(
            in String packageName, in boolean includeParams, in UserHandle user);
    List<SoundProfile> getAvailableSoundProfiles(in boolean includeParams, in UserHandle user);
    List<String> getSoundProfilePackageNames(in UserHandle user);
    List<String> getSoundProfileAllowList(in UserHandle user);
    void setSoundProfileAllowList(in List<String> packages, in UserHandle user);
    List<SoundProfileHandle> getSoundProfileHandle(in String[] id, in UserHandle user);

    void registerPictureProfileCallback(in IPictureProfileCallback cb);
    void registerSoundProfileCallback(in ISoundProfileCallback cb);
    void registerAmbientBacklightCallback(in IAmbientBacklightCallback cb);

    List<ParamCapability> getParamCapabilities(in List<String> names, in UserHandle user);

    boolean isSupported(in UserHandle user);
    void setAutoPictureQualityEnabled(in boolean enabled, in UserHandle user);
    boolean isAutoPictureQualityEnabled(in UserHandle user);
    void setSuperResolutionEnabled(in boolean enabled, in UserHandle user);
    boolean isSuperResolutionEnabled(in UserHandle user);
    void setAutoSoundQualityEnabled(in boolean enabled, in UserHandle user);
    boolean isAutoSoundQualityEnabled(in UserHandle user);

    void setAmbientBacklightSettings(in AmbientBacklightSettings settings, in UserHandle user);
    void setAmbientBacklightEnabled(in boolean enabled, in UserHandle user);
    boolean isAmbientBacklightEnabled(in UserHandle user);
}
