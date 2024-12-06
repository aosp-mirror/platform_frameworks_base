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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.media.tv.flags.Flags;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.annotation.RequiresPermission;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Central system API to the overall media quality, which arbitrates interaction between
 * applications and media quality service.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
@SystemService(Context.MEDIA_QUALITY_SERVICE)
public final class MediaQualityManager {
    // TODO: unhide the APIs for api review
    private static final String TAG = "MediaQualityManager";

    private final IMediaQualityManager mService;
    private final Context mContext;
    private final UserHandle mUserHandle;
    private final Object mLock = new Object();
    // @GuardedBy("mLock")
    private final List<PictureProfileCallbackRecord> mPpCallbackRecords = new ArrayList<>();
    // @GuardedBy("mLock")
    private final List<SoundProfileCallbackRecord> mSpCallbackRecords = new ArrayList<>();
    // @GuardedBy("mLock")
    private final List<AmbientBacklightCallbackRecord> mAbCallbackRecords = new ArrayList<>();
    // @GuardedBy("mLock")
    private final List<ActiveProcessingPictureListenerRecord> mApListenerRecords =
            new ArrayList<>();


    /**
     * @hide
     */
    public MediaQualityManager(Context context, IMediaQualityManager service) {
        mContext = context;
        mUserHandle = context.getUser();
        mService = service;
        IPictureProfileCallback ppCallback = new IPictureProfileCallback.Stub() {
            @Override
            public void onPictureProfileAdded(String profileId, PictureProfile profile) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postPictureProfileAdded(profileId, profile);
                    }
                }
            }
            @Override
            public void onPictureProfileUpdated(String profileId, PictureProfile profile) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postPictureProfileUpdated(profileId, profile);
                    }
                }
            }
            @Override
            public void onPictureProfileRemoved(String profileId, PictureProfile profile) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postPictureProfileRemoved(profileId, profile);
                    }
                }
            }
            @Override
            public void onParamCapabilitiesChanged(String profileId, List<ParamCapability> caps) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postParamCapabilitiesChanged(profileId, caps);
                    }
                }
            }
            @Override
            public void onError(String profileId, int err) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postError(profileId, err);
                    }
                }
            }
        };
        ISoundProfileCallback spCallback = new ISoundProfileCallback.Stub() {
            @Override
            public void onSoundProfileAdded(String profileId, SoundProfile profile) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postSoundProfileAdded(profileId, profile);
                    }
                }
            }
            @Override
            public void onSoundProfileUpdated(String profileId, SoundProfile profile) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postSoundProfileUpdated(profileId, profile);
                    }
                }
            }
            @Override
            public void onSoundProfileRemoved(String profileId, SoundProfile profile) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postSoundProfileRemoved(profileId, profile);
                    }
                }
            }
            @Override
            public void onParamCapabilitiesChanged(String profileId, List<ParamCapability> caps) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postParamCapabilitiesChanged(profileId, caps);
                    }
                }
            }
            @Override
            public void onError(String profileId, int err) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postError(profileId, err);
                    }
                }
            }
        };
        IAmbientBacklightCallback abCallback = new IAmbientBacklightCallback.Stub() {
            @Override
            public void onAmbientBacklightEvent(AmbientBacklightEvent event) {
                synchronized (mLock) {
                    for (AmbientBacklightCallbackRecord record : mAbCallbackRecords) {
                        record.postAmbientBacklightEvent(event);
                    }
                }
            }
        };

        try {
            if (mService != null) {
                mService.registerPictureProfileCallback(ppCallback);
                mService.registerSoundProfileCallback(spCallback);
                mService.registerAmbientBacklightCallback(abCallback);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link PictureProfileCallback}.
     */
    public void registerPictureProfileCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull PictureProfileCallback callback) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mPpCallbackRecords.add(new PictureProfileCallbackRecord(callback, executor));
        }
    }

    /**
     * Unregisters the existing {@link PictureProfileCallback}.
     */
    public void unregisterPictureProfileCallback(@NonNull final PictureProfileCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<PictureProfileCallbackRecord> it = mPpCallbackRecords.iterator();
                    it.hasNext(); ) {
                PictureProfileCallbackRecord record = it.next();
                if (record.getCallback() == callback) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /**
     * Gets picture profile by given profile type and name.
     *
     * @param type the type of the profile.
     * @param name the name of the profile.
     * @param includeParams {@code true} to include parameters in the profile; {@code false}
     *                      otherwise.
     * @return the corresponding picture profile if available; {@code null} if the name doesn't
     * exist.
     */
    @Nullable
    public PictureProfile getPictureProfile(
            @PictureProfile.ProfileType int type, @NonNull String name, boolean includeParams) {
        try {
            return mService.getPictureProfile(type, name, includeParams, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Gets profiles that available to the given package.
     *
     * @param packageName the package name of the profiles.
     * @param includeParams {@code true} to include parameters in the profile; {@code false}
     *                      otherwise.
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public List<PictureProfile> getPictureProfilesByPackage(
            @NonNull String packageName, boolean includeParams) {
        try {
            return mService.getPictureProfilesByPackage(packageName, includeParams, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets profiles that available to the caller.
     *
     * @param includeParams {@code true} to include parameters in the profile; {@code false}
     *                      otherwise.
     * @return the corresponding picture profile if available; {@code null} if the name doesn't
     * exist.
     */
    @NonNull
    public List<PictureProfile> getAvailablePictureProfiles(boolean includeParams) {
        try {
            return mService.getAvailablePictureProfiles(includeParams, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets preferred default picture profile.
     *
     * @param id the ID of the default profile. {@code null} to unset the default profile.
     * @return {@code true} if it's set successfully; {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public boolean setDefaultPictureProfile(@Nullable String id) {
        try {
            return mService.setDefaultPictureProfile(id, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all package names whose picture profiles are available.
     *
     * @see #getPictureProfilesByPackage(String, boolean)
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public List<String> getPictureProfilePackageNames() {
        try {
            return mService.getPictureProfilePackageNames(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets picture profile handle by profile ID.
     * @hide
     */
    public List<PictureProfileHandle> getPictureProfileHandle(String[] id) {
        try {
            return mService.getPictureProfileHandle(id, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets sound profile handle by profile ID.
     * @hide
     */
    public List<SoundProfileHandle> getSoundProfileHandle(String[] id) {
        try {
            return mService.getSoundProfileHandle(id, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a picture profile and store it in the system.
     *
     * <p>If the profile is created successfully,
     * {@link PictureProfileCallback#onPictureProfileAdded(String, PictureProfile)} is invoked.
     *
     * @param pp the {@link PictureProfile} object to be created.
     */
    public void createPictureProfile(@NonNull PictureProfile pp) {
        try {
            mService.createPictureProfile(pp, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Updates an existing picture profile and store it in the system.
     *
     * @param profileId the id of the object to be updated.
     * @param pp the {@link PictureProfile} object to be updated.
     */
    public void updatePictureProfile(@NonNull String profileId, @NonNull PictureProfile pp) {
        try {
            mService.updatePictureProfile(profileId, pp, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Removes a picture profile from the system.
     *
     * @param profileId the id of the object to be removed.
     */
    public void removePictureProfile(@NonNull String profileId) {
        try {
            mService.removePictureProfile(profileId, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link SoundProfileCallback}.
     */
    public void registerSoundProfileCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SoundProfileCallback callback) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mSpCallbackRecords.add(new SoundProfileCallbackRecord(callback, executor));
        }
    }

    /**
     * Unregisters the existing {@link SoundProfileCallback}.
     */
    public void unregisterSoundProfileCallback(@NonNull final SoundProfileCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<SoundProfileCallbackRecord> it = mSpCallbackRecords.iterator();
                    it.hasNext(); ) {
                SoundProfileCallbackRecord record = it.next();
                if (record.getCallback() == callback) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /**
     * Gets sound profile by given profile type and name.
     *
     * @param type the type of the profile.
     * @param name the name of the profile.
     * @param includeParams {@code true} to include parameters in the profile; {@code false}
     *                      otherwise.
     * @return the corresponding sound profile if available; {@code null} if the name doesn't exist.
     */
    @Nullable
    public SoundProfile getSoundProfile(
            @SoundProfile.ProfileType int type, @NonNull String name, boolean includeParams) {
        try {
            return mService.getSoundProfile(type, name, includeParams, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Gets profiles that available to the given package.
     *
     * @param packageName the package name of the profiles.
     * @param includeParams {@code true} to include parameters in the profile; {@code false}
     *                      otherwise.
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public List<SoundProfile> getSoundProfilesByPackage(
            @NonNull String packageName, boolean includeParams) {
        try {
            return mService.getSoundProfilesByPackage(packageName, includeParams, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets profiles that available to the caller package.
     *
     * @param includeParams {@code true} to include parameters in the profile; {@code false}
     *                      otherwise.
     *
     * @return the corresponding sound profile if available; {@code null} if the none available.
     */
    @NonNull
    public List<SoundProfile> getAvailableSoundProfiles(boolean includeParams) {
        try {
            return mService.getAvailableSoundProfiles(includeParams, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets preferred default sound profile.
     *
     * @param id the ID of the default profile. {@code null} to unset the default profile.
     * @return {@code true} if it's set successfully; {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public boolean setDefaultSoundProfile(@Nullable String id) {
        try {
            return mService.setDefaultSoundProfile(id, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all package names whose sound profiles are available.
     *
     * @see #getSoundProfilesByPackage(String, boolean)
     *
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public List<String> getSoundProfilePackageNames() {
        try {
            return mService.getSoundProfilePackageNames(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Creates a sound profile and store it in the system.
     *
     * <p>If the profile is created successfully,
     * {@link SoundProfileCallback#onSoundProfileAdded(String, SoundProfile)} is invoked.
     *
     * @param sp the {@link SoundProfile} object to be created.
     */
    public void createSoundProfile(@NonNull SoundProfile sp) {
        try {
            mService.createSoundProfile(sp, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Updates an existing sound profile and store it in the system.
     *
     * @param profileId the id of the object to be updated.
     * @param sp the {@link SoundProfile} object to be updated.
     */
    public void updateSoundProfile(@NonNull String profileId, @NonNull SoundProfile sp) {
        try {
            mService.updateSoundProfile(profileId, sp, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Removes a sound profile from the system.
     *
     * @param profileId the id of the object to be removed.
     */
    public void removeSoundProfile(@NonNull String profileId) {
        try {
            mService.removeSoundProfile(profileId, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets capability information of the given parameters.
     */
    @NonNull
    public List<ParamCapability> getParamCapabilities(@NonNull List<String> names) {
        try {
            return mService.getParamCapabilities(names, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the allowlist of packages that can create and removed picture profiles
     *
     * @see #createPictureProfile(PictureProfile)
     * @see #removePictureProfile(String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    @NonNull
    public List<String> getPictureProfileAllowList() {
        try {
            return mService.getPictureProfileAllowList(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the allowlist of packages that can create and removed picture profiles
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public void setPictureProfileAllowList(@NonNull List<String> packageNames) {
        try {
            mService.setPictureProfileAllowList(packageNames, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the allowlist of packages that can create and removed sound profiles
     *
     * @see #createSoundProfile(SoundProfile)
     * @see #removeSoundProfile(String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    @NonNull
    public List<String> getSoundProfileAllowList() {
        try {
            return mService.getSoundProfileAllowList(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the allowlist of packages that can create and removed sound profiles
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public void setSoundProfileAllowList(@NonNull List<String> packageNames) {
        try {
            mService.setSoundProfileAllowList(packageNames, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if media quality HAL is implemented; {@code false} otherwise.
     * @hide
     */
    public boolean isSupported() {
        try {
            return mService.isSupported(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables auto picture quality.
     * <p>If enabled, picture quality parameters can be adjusted dynamically by hardware based on
     * different use cases.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public void setAutoPictureQualityEnabled(boolean enabled) {
        try {
            mService.setAutoPictureQualityEnabled(enabled, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if auto picture quality is enabled; {@code false} otherwise.
     */
    public boolean isAutoPictureQualityEnabled() {
        try {
            return mService.isAutoPictureQualityEnabled(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables super resolution.
     * <p>Super resolution is a feature to improve resolution.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public void setSuperResolutionEnabled(boolean enabled) {
        try {
            mService.setSuperResolutionEnabled(enabled, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if super resolution is enabled; {@code false} otherwise.
     */
    public boolean isSuperResolutionEnabled() {
        try {
            return mService.isSuperResolutionEnabled(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables auto sound quality.
     * <p>If enabled, sound quality parameters can be adjusted dynamically by hardware based on
     * different use cases.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public void setAutoSoundQualityEnabled(boolean enabled) {
        try {
            mService.setAutoSoundQualityEnabled(enabled, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if auto sound quality is enabled; {@code false} otherwise.
     */
    public boolean isAutoSoundQualityEnabled() {
        try {
            return mService.isAutoSoundQualityEnabled(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link AmbientBacklightCallback}.
     */
    @RequiresPermission(android.Manifest.permission.READ_COLOR_ZONES)
    public void registerAmbientBacklightCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AmbientBacklightCallback callback) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mAbCallbackRecords.add(new AmbientBacklightCallbackRecord(callback, executor));
        }
    }

    /**
     * Unregisters the existing {@link AmbientBacklightCallback}.
     */
    @RequiresPermission(android.Manifest.permission.READ_COLOR_ZONES)
    public void unregisterAmbientBacklightCallback(
            @NonNull final AmbientBacklightCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<AmbientBacklightCallbackRecord> it = mAbCallbackRecords.iterator();
                    it.hasNext(); ) {
                AmbientBacklightCallbackRecord record = it.next();
                if (record.getCallback() == callback) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /**
     * Set the ambient backlight settings.
     *
     * @param settings The settings to use for the backlight detector.
     */
    @RequiresPermission(android.Manifest.permission.READ_COLOR_ZONES)
    public void setAmbientBacklightSettings(
            @NonNull AmbientBacklightSettings settings) {
        Preconditions.checkNotNull(settings);
        try {
            mService.setAmbientBacklightSettings(settings, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if ambient backlight is enabled; {@code false} otherwise.
     */
    public boolean isAmbientBacklightEnabled() {
        try {
            return mService.isAmbientBacklightEnabled(mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables the ambient backlight detection.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     */
    @RequiresPermission(android.Manifest.permission.READ_COLOR_ZONES)
    public void setAmbientBacklightEnabled(boolean enabled) {
        try {
            mService.setAmbientBacklightEnabled(enabled, mUserHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    private static final class PictureProfileCallbackRecord {
        private final PictureProfileCallback mCallback;
        private final Executor mExecutor;

        PictureProfileCallbackRecord(PictureProfileCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        public PictureProfileCallback getCallback() {
            return mCallback;
        }

        public void postPictureProfileAdded(final String id, PictureProfile profile) {

            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureProfileAdded(id, profile);
                }
            });
        }

        public void postPictureProfileUpdated(final String id, PictureProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureProfileUpdated(id, profile);
                }
            });
        }

        public void postPictureProfileRemoved(final String id, PictureProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureProfileRemoved(id, profile);
                }
            });
        }

        public void postParamCapabilitiesChanged(final String id, List<ParamCapability> caps) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onParamCapabilitiesChanged(id, caps);
                }
            });
        }

        public void postError(String profileId, int error) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onError(profileId, error);
                }
            });
        }
    }

    private static final class SoundProfileCallbackRecord {
        private final SoundProfileCallback mCallback;
        private final Executor mExecutor;

        SoundProfileCallbackRecord(SoundProfileCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        public SoundProfileCallback getCallback() {
            return mCallback;
        }

        public void postSoundProfileAdded(final String id, SoundProfile profile) {

            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onSoundProfileAdded(id, profile);
                }
            });
        }

        public void postSoundProfileUpdated(final String id, SoundProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onSoundProfileUpdated(id, profile);
                }
            });
        }

        public void postSoundProfileRemoved(final String id, SoundProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onSoundProfileRemoved(id, profile);
                }
            });
        }

        public void postParamCapabilitiesChanged(final String id, List<ParamCapability> caps) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onParamCapabilitiesChanged(id, caps);
                }
            });
        }

        public void postError(String profileId, int error) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onError(profileId, error);
                }
            });
        }
    }

    private static final class AmbientBacklightCallbackRecord {
        private final AmbientBacklightCallback mCallback;
        private final Executor mExecutor;

        AmbientBacklightCallbackRecord(AmbientBacklightCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        public AmbientBacklightCallback getCallback() {
            return mCallback;
        }

        public void postAmbientBacklightEvent(AmbientBacklightEvent event) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAmbientBacklightEvent(event);
                }
            });
        }
    }

    /**
     * Callback used to monitor status of picture profiles
     */
    public abstract static class PictureProfileCallback {
        /**
         * This is invoked when a picture profile has been added.
         *
         * @param profileId the ID of the profile.
         * @param profile the newly added profile.
         */
        public void onPictureProfileAdded(
                @NonNull String profileId, @NonNull PictureProfile profile) {
        }

        /**
         * This is invoked when a picture profile has been updated.
         *
         * @param profileId the ID of the profile.
         * @param profile the profile with updated info.
         */
        public void onPictureProfileUpdated(
                @NonNull String profileId, @NonNull PictureProfile profile) {
        }

        /**
         * This is invoked when a picture profile has been removed.
         *
         * @param profileId the ID of the profile.
         * @param profile the removed profile.
         */
        public void onPictureProfileRemoved(
                @NonNull String profileId, @NonNull PictureProfile profile) {
        }

        /**
         * This is invoked when an issue has occurred.
         *
         * @param profileId the profile ID related to the error. {@code null} if there is no
         *                  associated profile.
         * @param errorCode the error code
         */
        public void onError(@Nullable String profileId, @PictureProfile.ErrorCode int errorCode) {
        }

        /**
         * This is invoked when parameter capabilities has been changed due to status changes of the
         * content.
         *
         * @param profileId the ID of the profile used by the media content. {@code null} if there
         *                  is no associated profile
         * @param updatedCaps the updated capabilities.
         */
        public void onParamCapabilitiesChanged(
                @Nullable String profileId, @NonNull List<ParamCapability> updatedCaps) {
        }
    }

    /**
     * Callback used to monitor status of sound profiles.
     */
    public abstract static class SoundProfileCallback {
        /**
         * This is invoked when a sound profile has been added.
         *
         * @param profileId the ID of the profile.
         * @param profile the newly added profile.
         */
        public void onSoundProfileAdded(
                @NonNull String profileId, @NonNull SoundProfile profile) {
        }

        /**
         * This is invoked when a sound profile has been updated.
         *
         * @param profileId the ID of the profile.
         * @param profile the profile with updated info.
         */
        public void onSoundProfileUpdated(
                @NonNull String profileId, @NonNull SoundProfile profile) {
        }

        /**
         * This is invoked when a sound profile has been removed.
         *
         * @param profileId the ID of the profile.
         * @param profile the removed profile.
         */
        public void onSoundProfileRemoved(
                @NonNull String profileId, @NonNull SoundProfile profile) {
        }

        /**
         * This is invoked when an issue has occurred.
         *
         * @param profileId the profile ID related to the error. {@code null} if there is no
         *                  associated profile.
         * @param errorCode the error code
         */
        public void onError(@Nullable String profileId, @SoundProfile.ErrorCode int errorCode) {
        }

        /**
         * This is invoked when parameter capabilities has been changed due to status changes of the
         * content.
         *
         * @param profileId the ID of the profile used by the media content. {@code null} if there
         *                  is no associated profile
         * @param updatedCaps the updated capabilities.
         */
        public void onParamCapabilitiesChanged(
                @Nullable String profileId, @NonNull List<ParamCapability> updatedCaps) {
        }
    }

    /**
     * Callback used to monitor status of ambient backlight.
     */
    public abstract static class AmbientBacklightCallback {
        /**
         * Called when new ambient backlight event is emitted.
         */
        public void onAmbientBacklightEvent(@NonNull AmbientBacklightEvent event) {
        }
    }

    /**
     * Listener used to monitor status of active pictures.
     */
    public interface ActiveProcessingPictureListener {
        /**
         * Called when active pictures are changed.
         *
         * @param activeProcessingPictures contents currently undergoing picture processing.
         */
        void onActiveProcessingPicturesChanged(
                @NonNull List<ActiveProcessingPicture> activeProcessingPictures);
    }

    /**
     * Adds an active picture listener for the contents owner by the caller.
     */
    public void addActiveProcessingPictureListener(
            @CallbackExecutor @NonNull Executor executor,
            @NonNull ActiveProcessingPictureListener listener) {
        Preconditions.checkNotNull(listener);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mApListenerRecords.add(
                    new ActiveProcessingPictureListenerRecord(listener, executor, false));
        }
    }

    /**
     * Adds an active picture listener for all contents.
     *
     * @hide
     */
    @SuppressLint("PairedRegistration")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public void addGlobalActiveProcessingPictureListener(
            @NonNull Executor executor,
            @NonNull ActiveProcessingPictureListener listener) {
        Preconditions.checkNotNull(listener);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mApListenerRecords.add(
                    new ActiveProcessingPictureListenerRecord(listener, executor, true));
        }
    }


    /**
     * Removes an active picture listener for the contents.
     */
    public void removeActiveProcessingPictureListener(
            @NonNull ActiveProcessingPictureListener listener) {
        Preconditions.checkNotNull(listener);
        synchronized (mLock) {
            for (Iterator<ActiveProcessingPictureListenerRecord> it = mApListenerRecords.iterator();
                    it.hasNext(); ) {
                ActiveProcessingPictureListenerRecord record = it.next();
                if (record.getListener() == listener) {
                    it.remove();
                    break;
                }
            }
        }
    }

    private static final class ActiveProcessingPictureListenerRecord {
        private final ActiveProcessingPictureListener mListener;
        private final Executor mExecutor;
        private final boolean mIsGlobal;

        ActiveProcessingPictureListenerRecord(
                ActiveProcessingPictureListener listener, Executor executor, boolean isGlobal) {
            mListener = listener;
            mExecutor = executor;
            mIsGlobal = isGlobal;
        }

        public ActiveProcessingPictureListener getListener() {
            return mListener;
        }
    }
}
