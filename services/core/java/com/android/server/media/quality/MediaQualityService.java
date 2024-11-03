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

package com.android.server.media.quality;

import android.content.Context;
import android.media.quality.IMediaQualityManager;
import android.media.quality.IPictureProfileCallback;
import android.media.quality.ISoundProfileCallback;
import android.media.quality.ParamCapability;
import android.media.quality.PictureProfile;
import android.media.quality.SoundProfile;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * This service manage picture profile and sound profile for TV setting. Also communicates with the
 * database to save, update the profiles.
 */
public class MediaQualityService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String TAG = "MediaQualityService";
    private final Context mContext;

    public MediaQualityService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_QUALITY_SERVICE, new BinderService());
    }

    // TODO: Add additional APIs. b/373951081
    private final class BinderService extends IMediaQualityManager.Stub {
        @Override
        public PictureProfile createPictureProfile(PictureProfile pp) {
            // TODO: implement
            return pp;
        }
        @Override
        public void updatePictureProfile(long id, PictureProfile pp) {
            // TODO: implement
        }
        @Override
        public void removePictureProfile(long id) {
            // TODO: implement
        }
        @Override
        public PictureProfile getPictureProfileById(long id) {
            return null;
        }
        @Override
        public List<PictureProfile> getPictureProfilesByPackage(String packageName) {
            return new ArrayList<>();
        }
        @Override
        public List<PictureProfile> getAvailablePictureProfiles() {
            return new ArrayList<>();
        }
        @Override
        public List<PictureProfile> getAllPictureProfiles() {
            return new ArrayList<>();
        }

        @Override
        public SoundProfile createSoundProfile(SoundProfile pp) {
            // TODO: implement
            return pp;
        }
        @Override
        public void updateSoundProfile(long id, SoundProfile pp) {
            // TODO: implement
        }
        @Override
        public void removeSoundProfile(long id) {
            // TODO: implement
        }
        @Override
        public SoundProfile getSoundProfileById(long id) {
            return null;
        }
        @Override
        public List<SoundProfile> getSoundProfilesByPackage(String packageName) {
            return new ArrayList<>();
        }
        @Override
        public List<SoundProfile> getAvailableSoundProfiles() {
            return new ArrayList<>();
        }
        @Override
        public List<SoundProfile> getAllSoundProfiles() {
            return new ArrayList<>();
        }


        @Override
        public void registerPictureProfileCallback(final IPictureProfileCallback callback) {
        }
        @Override
        public void registerSoundProfileCallback(final ISoundProfileCallback callback) {
        }


        @Override
        public List<ParamCapability> getParamCapabilities(List<String> names) {
            return new ArrayList<>();
        }


        @Override
        public boolean isSupported() {
            return false;
        }

        @Override
        public void setAutoPictureQualityEnabled(boolean enabled) {
        }

        @Override
        public boolean isAutoPictureQualityEnabled() {
            return false;
        }

        @Override
        public void setSuperResolutionEnabled(boolean enabled) {
        }

        @Override
        public boolean isSuperResolutionEnabled() {
            return false;
        }

        @Override
        public void setAutoSoundQualityEnabled(boolean enabled) {
        }

        @Override
        public boolean isAutoSoundQualityEnabled() {
            return false;
        }
    }
}
