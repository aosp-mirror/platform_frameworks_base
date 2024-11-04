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
import android.annotation.SystemService;
import android.content.Context;
import android.media.tv.flags.Flags;
import android.os.RemoteException;

import androidx.annotation.RequiresPermission;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Expose TV setting APIs for the application to use
 * @hide
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
@SystemService(Context.MEDIA_QUALITY_SERVICE)
public final class MediaQualityManager {
    // TODO: unhide the APIs for api review
    private static final String TAG = "MediaQualityManager";

    private final IMediaQualityManager mService;
    private final Context mContext;
    private final Object mLock = new Object();
    // @GuardedBy("mLock")
    private final List<PictureProfileCallbackRecord> mPpCallbackRecords = new ArrayList<>();
    // @GuardedBy("mLock")
    private final List<SoundProfileCallbackRecord> mSpCallbackRecords = new ArrayList<>();
    // @GuardedBy("mLock")
    private final List<AmbientBacklightCallbackRecord> mAbCallbackRecords = new ArrayList<>();


    /**
     * @hide
     */
    public MediaQualityManager(Context context, IMediaQualityManager service) {
        mContext = context;
        mService = service;
        IPictureProfileCallback ppCallback = new IPictureProfileCallback.Stub() {
            @Override
            public void onPictureProfileAdded(long profileId, PictureProfile profile) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postPictureProfileAdded(profileId, profile);
                    }
                }
            }
            @Override
            public void onPictureProfileUpdated(long profileId, PictureProfile profile) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postPictureProfileUpdated(profileId, profile);
                    }
                }
            }
            @Override
            public void onPictureProfileRemoved(long profileId, PictureProfile profile) {
                synchronized (mLock) {
                    for (PictureProfileCallbackRecord record : mPpCallbackRecords) {
                        // TODO: filter callback record
                        record.postPictureProfileRemoved(profileId, profile);
                    }
                }
            }
        };
        ISoundProfileCallback spCallback = new ISoundProfileCallback.Stub() {
            @Override
            public void onSoundProfileAdded(long profileId, SoundProfile profile) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postSoundProfileAdded(profileId, profile);
                    }
                }
            }
            @Override
            public void onSoundProfileUpdated(long profileId, SoundProfile profile) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postSoundProfileUpdated(profileId, profile);
                    }
                }
            }
            @Override
            public void onSoundProfileRemoved(long profileId, SoundProfile profile) {
                synchronized (mLock) {
                    for (SoundProfileCallbackRecord record : mSpCallbackRecords) {
                        // TODO: filter callback record
                        record.postSoundProfileRemoved(profileId, profile);
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
     * @hide
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
     * @hide
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
     * Gets picture profile by given profile ID.
     * @return the corresponding picture profile if available; {@code null} if the ID doesn't
     *         exist or the profile is not accessible to the caller.
     */
    public PictureProfile getPictureProfileById(long profileId) {
        try {
            return mService.getPictureProfileById(profileId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** @SystemApi gets profiles that available to the given package */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public List<PictureProfile> getPictureProfilesByPackage(String packageName) {
        try {
            return mService.getPictureProfilesByPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Gets profiles that available to the caller package */
    public List<PictureProfile> getAvailablePictureProfiles() {
        try {
            return mService.getAvailablePictureProfiles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @SystemApi all stored picture profiles of all packages */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public List<PictureProfile> getAllPictureProfiles() {
        try {
            return mService.getAllPictureProfiles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Creates a picture profile and store it in the system.
     *
     * @return the stored profile with an assigned profile ID.
     */
    public PictureProfile createPictureProfile(PictureProfile pp) {
        try {
            return mService.createPictureProfile(pp);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Updates an existing picture profile and store it in the system.
     */
    public void updatePictureProfile(long profileId, PictureProfile pp) {
        try {
            mService.updatePictureProfile(profileId, pp);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Removes a picture profile from the system.
     */
    public void removePictureProfile(long profileId) {
        try {
            mService.removePictureProfile(profileId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link SoundProfileCallback}.
     * @hide
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
     * @hide
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
     * Gets sound profile by given profile ID.
     * @return the corresponding sound profile if available; {@code null} if the ID doesn't
     *         exist or the profile is not accessible to the caller.
     */
    public SoundProfile getSoundProfileById(long profileId) {
        try {
            return mService.getSoundProfileById(profileId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** @SystemApi gets profiles that available to the given package */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public List<SoundProfile> getSoundProfilesByPackage(String packageName) {
        try {
            return mService.getSoundProfilesByPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Gets profiles that available to the caller package */
    public List<SoundProfile> getAvailableSoundProfiles() {
        try {
            return mService.getAvailableSoundProfiles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @SystemApi all stored sound profiles of all packages */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public List<SoundProfile> getAllSoundProfiles() {
        try {
            return mService.getAllSoundProfiles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Creates a sound profile and store it in the system.
     *
     * @return the stored profile with an assigned profile ID.
     */
    public SoundProfile createSoundProfile(SoundProfile sp) {
        try {
            return mService.createSoundProfile(sp);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Updates an existing sound profile and store it in the system.
     */
    public void updateSoundProfile(long profileId, SoundProfile sp) {
        try {
            mService.updateSoundProfile(profileId, sp);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Removes a sound profile from the system.
     */
    public void removeSoundProfile(long profileId) {
        try {
            mService.removeSoundProfile(profileId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets capability information of the given parameters.
     */
    public List<ParamCapability> getParamCapabilities(List<String> names) {
        try {
            return mService.getParamCapabilities(names);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if media quality HAL is implemented; {@code false} otherwise.
     */
    public boolean isSupported() {
        try {
            return mService.isSupported();
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
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public void setAutoPictureQualityEnabled(boolean enabled) {
        try {
            mService.setAutoPictureQualityEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if auto picture quality is enabled; {@code false} otherwise.
     */
    public boolean isAutoPictureQualityEnabled() {
        try {
            return mService.isAutoPictureQualityEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables super resolution.
     * <p>Super resolution is a feature to improve resolution.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
    public void setSuperResolutionEnabled(boolean enabled) {
        try {
            mService.setSuperResolutionEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if super resolution is enabled; {@code false} otherwise.
     */
    public boolean isSuperResolutionEnabled() {
        try {
            return mService.isSuperResolutionEnabled();
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
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE)
    public void setAutoSoundQualityEnabled(boolean enabled) {
        try {
            mService.setAutoSoundQualityEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if auto sound quality is enabled; {@code false} otherwise.
     */
    public boolean isAutoSoundQualityEnabled() {
        try {
            return mService.isAutoSoundQualityEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link AmbientBacklightCallback}.
     * @hide
     */
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
     * @hide
     */
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
    public void setAmbientBacklightSettings(
            @NonNull AmbientBacklightSettings settings) {
        Preconditions.checkNotNull(settings);
        try {
            mService.setAmbientBacklightSettings(settings);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables the ambient backlight detection.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     */
    public void setAmbientBacklightEnabled(boolean enabled) {
        try {
            mService.setAmbientBacklightEnabled(enabled);
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

        public void postPictureProfileAdded(final long id, PictureProfile profile) {

            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureProfileAdded(id, profile);
                }
            });
        }

        public void postPictureProfileUpdated(final long id, PictureProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureProfileUpdated(id, profile);
                }
            });
        }

        public void postPictureProfileRemoved(final long id, PictureProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureProfileRemoved(id, profile);
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

        public void postSoundProfileAdded(final long id, SoundProfile profile) {

            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onSoundProfileAdded(id, profile);
                }
            });
        }

        public void postSoundProfileUpdated(final long id, SoundProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onSoundProfileUpdated(id, profile);
                }
            });
        }

        public void postSoundProfileRemoved(final long id, SoundProfile profile) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onSoundProfileRemoved(id, profile);
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
     * Callback used to monitor status of picture profiles.
     * @hide
     */
    public abstract static class PictureProfileCallback {
        /**
         * @hide
         */
        public void onPictureProfileAdded(long id, PictureProfile profile) {
        }
        /**
         * @hide
         */
        public void onPictureProfileUpdated(long id, PictureProfile profile) {
        }
        /**
         * @hide
         */
        public void onPictureProfileRemoved(long id, PictureProfile profile) {
        }
        /**
         * @hide
         */
        public void onError(int errorCode) {
        }
    }

    /**
     * Callback used to monitor status of sound profiles.
     * @hide
     */
    public abstract static class SoundProfileCallback {
        /**
         * @hide
         */
        public void onSoundProfileAdded(long id, SoundProfile profile) {
        }
        /**
         * @hide
         */
        public void onSoundProfileUpdated(long id, SoundProfile profile) {
        }
        /**
         * @hide
         */
        public void onSoundProfileRemoved(long id, SoundProfile profile) {
        }
        /**
         * @hide
         */
        public void onError(int errorCode) {
        }
    }

    /**
     * Callback used to monitor status of ambient backlight.
     * @hide
     */
    public abstract static class AmbientBacklightCallback {
        /**
         * Called when new ambient backlight event is emitted.
         */
        public void onAmbientBacklightEvent(AmbientBacklightEvent event) {
        }
    }
}
