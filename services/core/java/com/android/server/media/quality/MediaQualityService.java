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

import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_ENABLED;
import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_DISABLED;
import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_METADATA_AVAILABLE;
import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_INTERRUPTED;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.tv.mediaquality.AmbientBacklightColorFormat;
import android.hardware.tv.mediaquality.IMediaQuality;
import android.hardware.tv.mediaquality.IPictureProfileAdjustmentListener;
import android.hardware.tv.mediaquality.IPictureProfileChangedListener;
import android.hardware.tv.mediaquality.ISoundProfileAdjustmentListener;
import android.hardware.tv.mediaquality.ISoundProfileChangedListener;
import android.hardware.tv.mediaquality.ParamCapability;
import android.hardware.tv.mediaquality.ParameterRange;
import android.hardware.tv.mediaquality.PictureParameter;
import android.hardware.tv.mediaquality.PictureParameters;
import android.hardware.tv.mediaquality.SoundParameter;
import android.hardware.tv.mediaquality.SoundParameters;
import android.hardware.tv.mediaquality.VendorParamCapability;
import android.media.quality.AmbientBacklightEvent;
import android.media.quality.AmbientBacklightMetadata;
import android.media.quality.AmbientBacklightSettings;
import android.media.quality.IAmbientBacklightCallback;
import android.media.quality.IMediaQualityManager;
import android.media.quality.IPictureProfileCallback;
import android.media.quality.ISoundProfileCallback;
import android.media.quality.MediaQualityContract.BaseParameters;
import android.media.quality.MediaQualityManager;
import android.media.quality.ParameterCapability;
import android.media.quality.PictureProfile;
import android.media.quality.PictureProfileHandle;
import android.media.quality.SoundProfile;
import android.media.quality.SoundProfileHandle;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * This service manage picture profile and sound profile for TV setting. Also communicates with the
 * database to save, update the profiles.
 */
public class MediaQualityService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String TAG = "MediaQualityService";
    private static final String ALLOWLIST = "allowlist";
    private static final String PICTURE_PROFILE_PREFERENCE = "picture_profile_preference";
    private static final String SOUND_PROFILE_PREFERENCE = "sound_profile_preference";
    private static final String COMMA_DELIMITER = ",";
    private final Context mContext;
    private final MediaQualityDbHelper mMediaQualityDbHelper;
    private final BiMap<Long, String> mPictureProfileTempIdMap;
    private final BiMap<Long, String> mSoundProfileTempIdMap;
    private IMediaQuality mMediaQuality;
    private IPictureProfileAdjustmentListener mPpAdjustmentListener;
    private ISoundProfileAdjustmentListener mSpAdjustmentListener;
    private IPictureProfileChangedListener mPpChangedListener;
    private ISoundProfileChangedListener mSpChangedListener;
    private final HalAmbientBacklightCallback mHalAmbientBacklightCallback;
    private final Map<String, AmbientBacklightCallbackRecord> mCallbackRecords = new HashMap<>();
    private final PackageManager mPackageManager;
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    private SharedPreferences mPictureProfileSharedPreference;
    private SharedPreferences mSoundProfileSharedPreference;

    // A global lock for picture profile objects.
    private final Object mPictureProfileLock = new Object();
    // A global lock for sound profile objects.
    private final Object mSoundProfileLock = new Object();
    // A global lock for ambient backlight objects.
    private final Object mAmbientBacklightLock = new Object();

    public MediaQualityService(Context context) {
        super(context);
        mContext = context;
        mHalAmbientBacklightCallback = new HalAmbientBacklightCallback();
        mPackageManager = mContext.getPackageManager();
        mPictureProfileTempIdMap = new BiMap<>();
        mSoundProfileTempIdMap = new BiMap<>();
        mMediaQualityDbHelper = new MediaQualityDbHelper(mContext);
        mMediaQualityDbHelper.setWriteAheadLoggingEnabled(true);
        mMediaQualityDbHelper.setIdleConnectionTimeout(30);

        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        final Context deviceContext = mContext.createDeviceProtectedStorageContext();
        final File pictureProfilePrefs = new File(Environment.getDataSystemDirectory(),
                PICTURE_PROFILE_PREFERENCE);
        mPictureProfileSharedPreference = deviceContext.getSharedPreferences(
                pictureProfilePrefs, Context.MODE_PRIVATE);
        final File soundProfilePrefs = new File(Environment.getDataSystemDirectory(),
                SOUND_PROFILE_PREFERENCE);
        mSoundProfileSharedPreference = deviceContext.getSharedPreferences(
                soundProfilePrefs, Context.MODE_PRIVATE);
    }

    @Override
    public void onStart() {
        IBinder binder = ServiceManager.getService(IMediaQuality.DESCRIPTOR + "/default");
        if (binder == null) {
            Slogf.d(TAG, "Binder is null");
            return;
        }
        Slogf.d(TAG, "Binder is not null");

        mPpAdjustmentListener = new IPictureProfileAdjustmentListener.Stub() {
                @Override
                public void onPictureProfileAdjusted(
                        android.hardware.tv.mediaquality.PictureProfile pictureProfile)
                        throws RemoteException {
                    // TODO
                }

                @Override
                public void onParamCapabilityChanged(long pictureProfileId, ParamCapability[] caps)
                        throws RemoteException {
                    // TODO
                }

                @Override
                public void onVendorParamCapabilityChanged(long pictureProfileId,
                        VendorParamCapability[] caps) throws RemoteException {
                    // TODO
                }

                @Override
                public void requestPictureParameters(long pictureProfileId) throws RemoteException {
                    // TODO
                }

                @Override
                public void onStreamStatusChanged(long pictureProfileId, byte status)
                        throws RemoteException {
                    // TODO
                }

                @Override
                public int getInterfaceVersion() throws RemoteException {
                    return 0;
                }

                @Override
                public String getInterfaceHash() throws RemoteException {
                    return null;
                }
            };
        mSpAdjustmentListener = new ISoundProfileAdjustmentListener.Stub() {

                @Override
                public void onSoundProfileAdjusted(
                        android.hardware.tv.mediaquality.SoundProfile soundProfile)
                        throws RemoteException {
                    // TODO
                }

                @Override
                public void onParamCapabilityChanged(long soundProfileId, ParamCapability[] caps)
                        throws RemoteException {
                    // TODO
                }

                @Override
                public void onVendorParamCapabilityChanged(long soundProfileId,
                        VendorParamCapability[] caps) throws RemoteException {
                    // TODO
                }

                @Override
                public void requestSoundParameters(long soundProfileId) throws RemoteException {
                    // TODO
                }

                @Override
                public int getInterfaceVersion() throws RemoteException {
                    return 0;
                }

                @Override
                public String getInterfaceHash() throws RemoteException {
                    return null;
                }
            };

        mMediaQuality = IMediaQuality.Stub.asInterface(binder);
        if (mMediaQuality != null) {
            try {
                mMediaQuality.setAmbientBacklightCallback(mHalAmbientBacklightCallback);
                mMediaQuality.setPictureProfileAdjustmentListener(mPpAdjustmentListener);
                mMediaQuality.setSoundProfileAdjustmentListener(mSpAdjustmentListener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set ambient backlight detector callback", e);
            }
        }

        mPpChangedListener = IPictureProfileChangedListener.Stub.asInterface(binder);
        mSpChangedListener = ISoundProfileChangedListener.Stub.asInterface(binder);

        publishBinderService(Context.MEDIA_QUALITY_SERVICE, new BinderService());
    }

    // TODO: Add additional APIs. b/373951081
    private final class BinderService extends IMediaQualityManager.Stub {

        @GuardedBy("mPictureProfileLock")
        @Override
        public PictureProfile createPictureProfile(PictureProfile pp, UserHandle user) {
            if ((pp.getPackageName() != null && !pp.getPackageName().isEmpty()
                    && !incomingPackageEqualsCallingUidPackage(pp.getPackageName()))
                    && !hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mPictureProfileLock) {
                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();

                ContentValues values = MediaQualityUtils.getContentValues(null,
                        pp.getProfileType(),
                        pp.getName(),
                        pp.getPackageName() == null || pp.getPackageName().isEmpty()
                                ? getPackageOfCallingUid() : pp.getPackageName(),
                        pp.getInputId(),
                        pp.getParameters());

                // id is auto-generated by SQLite upon successful insertion of row
                Long id = db.insert(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                        null, values);
                MediaQualityUtils.populateTempIdMap(mPictureProfileTempIdMap, id);
                String value = mPictureProfileTempIdMap.getValue(id);
                pp.setProfileId(value);
                notifyOnPictureProfileAdded(value, pp, Binder.getCallingUid(),
                        Binder.getCallingPid());
                return pp;
            }
        }

        private void notifyHalOnPictureProfileChange(Long dbId, PersistableBundle params) {
            // TODO: only notify HAL when the profile is active / being used
            try {
                mPpChangedListener.onPictureProfileChanged(convertToHalPictureProfile(dbId,
                        params));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify HAL on picture profile change.", e);
            }
        }

        private android.hardware.tv.mediaquality.PictureProfile convertToHalPictureProfile(Long id,
                PersistableBundle params) {
            PictureParameters pictureParameters = new PictureParameters();
            pictureParameters.pictureParameters =
                    MediaQualityUtils.convertPersistableBundleToPictureParameterList(
                            params);

            android.hardware.tv.mediaquality.PictureProfile toReturn =
                    new android.hardware.tv.mediaquality.PictureProfile();
            toReturn.pictureProfileId = id;
            toReturn.parameters = pictureParameters;

            return toReturn;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void updatePictureProfile(String id, PictureProfile pp, UserHandle user) {
            Long dbId = mPictureProfileTempIdMap.getKey(id);
            if (!hasPermissionToUpdatePictureProfile(dbId, pp)) {
                notifyOnPictureProfileError(id, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mPictureProfileLock) {
                ContentValues values = MediaQualityUtils.getContentValues(dbId,
                        pp.getProfileType(),
                        pp.getName(),
                        pp.getPackageName(),
                        pp.getInputId(),
                        pp.getParameters());

            SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
            db.replace(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                    null, values);
            notifyOnPictureProfileUpdated(mPictureProfileTempIdMap.getValue(dbId),
                    getPictureProfile(dbId), Binder.getCallingUid(), Binder.getCallingPid());
            notifyHalOnPictureProfileChange(dbId, pp.getParameters());
            }
        }

        private boolean hasPermissionToUpdatePictureProfile(Long dbId, PictureProfile toUpdate) {
            PictureProfile fromDb = getPictureProfile(dbId);
            return fromDb.getProfileType() == toUpdate.getProfileType()
                    && fromDb.getPackageName().equals(toUpdate.getPackageName())
                    && fromDb.getName().equals(toUpdate.getName())
                    && fromDb.getName().equals(getPackageOfCallingUid());
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void removePictureProfile(String id, UserHandle user) {
            synchronized (mPictureProfileLock) {
                Long dbId = mPictureProfileTempIdMap.getKey(id);

                PictureProfile toDelete = getPictureProfile(dbId);
                if (!hasPermissionToRemovePictureProfile(toDelete)) {
                    notifyOnPictureProfileError(id, PictureProfile.ERROR_NO_PERMISSION,
                            Binder.getCallingUid(), Binder.getCallingPid());
                }

                if (dbId != null) {
                    SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                    String selection = BaseParameters.PARAMETER_ID + " = ?";
                    String[] selectionArgs = {Long.toString(dbId)};
                    int result = db.delete(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                            selection, selectionArgs);
                    if (result == 0) {
                        notifyOnPictureProfileError(id, PictureProfile.ERROR_INVALID_ARGUMENT,
                                Binder.getCallingUid(), Binder.getCallingPid());
                    }
                    notifyOnPictureProfileRemoved(mPictureProfileTempIdMap.getValue(dbId), toDelete,
                            Binder.getCallingUid(), Binder.getCallingPid());
                    mPictureProfileTempIdMap.remove(dbId);
                    notifyHalOnPictureProfileChange(dbId, null);
                }
            }
        }

        private boolean hasPermissionToRemovePictureProfile(PictureProfile toDelete) {
            if (toDelete != null) {
                return toDelete.getName().equalsIgnoreCase(getPackageOfCallingUid());
            }
            return false;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public PictureProfile getPictureProfile(int type, String name, Bundle options,
                UserHandle user) {
            boolean includeParams =
                    options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_NAME + " = ? AND "
                    + BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {Integer.toString(type), name, getPackageOfCallingUid()};

            synchronized (mPictureProfileLock) {
                try (
                        Cursor cursor = getCursorAfterQuerying(
                                mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                                MediaQualityUtils.getMediaProfileColumns(includeParams), selection,
                                selectionArguments)
                ) {
                    int count = cursor.getCount();
                    if (count == 0) {
                        return null;
                    }
                    if (count > 1) {
                        Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d "
                                        + "entries found for type=%d and name=%s in %s. Should"
                                        + " only ever be 0 or 1.", count, type, name,
                                mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                        return null;
                    }
                    cursor.moveToFirst();
                    return MediaQualityUtils.convertCursorToPictureProfileWithTempId(cursor,
                            mPictureProfileTempIdMap);
                }
            }
        }

        private PictureProfile getPictureProfile(Long dbId) {
            String selection = BaseParameters.PARAMETER_ID + " = ?";
            String[] selectionArguments = {Long.toString(dbId)};

            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                            MediaQualityUtils.getMediaProfileColumns(false), selection,
                            selectionArguments)
            ) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d entries "
                                    + "found for id=%d in %s. Should only ever be 0 or 1.",
                            count, dbId, mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return MediaQualityUtils.convertCursorToPictureProfileWithTempId(cursor,
                        mPictureProfileTempIdMap);
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<PictureProfile> getPictureProfilesByPackage(
                String packageName, Bundle options, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mPictureProfileLock) {
                boolean includeParams =
                        options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
                String selection = BaseParameters.PARAMETER_PACKAGE + " = ?";
                String[] selectionArguments = {packageName};
                return getPictureProfilesBasedOnConditions(MediaQualityUtils
                                .getMediaProfileColumns(includeParams),
                        selection, selectionArguments);
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<PictureProfile> getAvailablePictureProfiles(Bundle options, UserHandle user) {
            String packageName = getPackageOfCallingUid();
            if (packageName != null) {
                return getPictureProfilesByPackage(packageName, options, user);
            }
            return new ArrayList<>();
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public boolean setDefaultPictureProfile(String profileId, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(profileId, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            PictureProfile pictureProfile = getPictureProfile(
                    mPictureProfileTempIdMap.getKey(profileId));
            PersistableBundle params = pictureProfile.getParameters();

            try {
                if (mMediaQuality != null) {
                    PictureParameter[] pictureParameters = MediaQualityUtils
                            .convertPersistableBundleToPictureParameterList(params);

                    PictureParameters pp = new PictureParameters();
                    pp.pictureParameters = pictureParameters;

                    mMediaQuality.sendDefaultPictureParameters(pp);
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set default picture profile", e);
            }
            return false;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<String> getPictureProfilePackageNames(UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            String [] column = {BaseParameters.PARAMETER_PACKAGE};
            synchronized (mPictureProfileLock) {
                List<PictureProfile> pictureProfiles = getPictureProfilesBasedOnConditions(column,
                        null, null);
                return pictureProfiles.stream()
                        .map(PictureProfile::getPackageName)
                        .distinct()
                        .collect(Collectors.toList());
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<PictureProfileHandle> getPictureProfileHandle(String[] ids, UserHandle user) {
            List<PictureProfileHandle> toReturn = new ArrayList<>();
            synchronized (mPictureProfileLock) {
                for (String id : ids) {
                    Long key = mPictureProfileTempIdMap.getKey(id);
                    if (key != null) {
                        toReturn.add(new PictureProfileHandle(key));
                    } else {
                        toReturn.add(null);
                    }
                }
            }
            return toReturn;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<SoundProfileHandle> getSoundProfileHandle(String[] ids, UserHandle user) {
            List<SoundProfileHandle> toReturn = new ArrayList<>();
            synchronized (mSoundProfileLock) {
                for (String id : ids) {
                    Long key = mSoundProfileTempIdMap.getKey(id);
                    if (key != null) {
                        toReturn.add(new SoundProfileHandle(key));
                    } else {
                        toReturn.add(null);
                    }
                }
            }
            return toReturn;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public SoundProfile createSoundProfile(SoundProfile sp, UserHandle user) {
            if ((sp.getPackageName() != null && !sp.getPackageName().isEmpty()
                    && !incomingPackageEqualsCallingUidPackage(sp.getPackageName()))
                    && !hasGlobalPictureQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mSoundProfileLock) {
                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();

                ContentValues values = MediaQualityUtils.getContentValues(null,
                        sp.getProfileType(),
                        sp.getName(),
                        sp.getPackageName() == null || sp.getPackageName().isEmpty()
                                ? getPackageOfCallingUid() : sp.getPackageName(),
                        sp.getInputId(),
                        sp.getParameters());

                // id is auto-generated by SQLite upon successful insertion of row
                Long id = db.insert(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                        null, values);
                MediaQualityUtils.populateTempIdMap(mSoundProfileTempIdMap, id);
                String value = mSoundProfileTempIdMap.getValue(id);
                sp.setProfileId(value);
                notifyOnSoundProfileAdded(value, sp, Binder.getCallingUid(),
                        Binder.getCallingPid());
                return sp;
            }
        }

        private void notifyHalOnSoundProfileChange(Long dbId, PersistableBundle params) {
            // TODO: only notify HAL when the profile is active / being used
            try {
                mSpChangedListener.onSoundProfileChanged(convertToHalSoundProfile(dbId, params));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify HAL on sound profile change.", e);
            }
        }

        private android.hardware.tv.mediaquality.SoundProfile convertToHalSoundProfile(Long id,
                PersistableBundle params) {
            SoundParameters soundParameters = new SoundParameters();
            soundParameters.soundParameters =
                    MediaQualityUtils.convertPersistableBundleToSoundParameterList(params);

            android.hardware.tv.mediaquality.SoundProfile toReturn =
                    new android.hardware.tv.mediaquality.SoundProfile();
            toReturn.soundProfileId = id;
            toReturn.parameters = soundParameters;

            return toReturn;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void updateSoundProfile(String id, SoundProfile sp, UserHandle user) {
            Long dbId = mSoundProfileTempIdMap.getKey(id);
            if (!hasPermissionToUpdateSoundProfile(dbId, sp)) {
                notifyOnSoundProfileError(id, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mSoundProfileLock) {
                ContentValues values = MediaQualityUtils.getContentValues(dbId,
                        sp.getProfileType(),
                        sp.getName(),
                        sp.getPackageName(),
                        sp.getInputId(),
                        sp.getParameters());

                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                db.replace(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                        null, values);
                notifyOnSoundProfileUpdated(mSoundProfileTempIdMap.getValue(dbId),
                        getSoundProfile(dbId), Binder.getCallingUid(), Binder.getCallingPid());
                notifyHalOnSoundProfileChange(dbId, sp.getParameters());
            }
        }

        private boolean hasPermissionToUpdateSoundProfile(Long dbId, SoundProfile sp) {
            SoundProfile fromDb = getSoundProfile(dbId);
            return fromDb.getProfileType() == sp.getProfileType()
                    && fromDb.getPackageName().equals(sp.getPackageName())
                    && fromDb.getName().equals(sp.getName())
                    && fromDb.getName().equals(getPackageOfCallingUid());
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void removeSoundProfile(String id, UserHandle user) {
            synchronized (mSoundProfileLock) {
                Long dbId = mSoundProfileTempIdMap.getKey(id);
                SoundProfile toDelete = getSoundProfile(dbId);
                if (!hasPermissionToRemoveSoundProfile(toDelete)) {
                    notifyOnSoundProfileError(id, SoundProfile.ERROR_NO_PERMISSION,
                            Binder.getCallingUid(), Binder.getCallingPid());
                }
                if (dbId != null) {
                    SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                    String selection = BaseParameters.PARAMETER_ID + " = ?";
                    String[] selectionArgs = {Long.toString(dbId)};
                    int result = db.delete(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                            selection,
                            selectionArgs);
                    if (result == 0) {
                        notifyOnSoundProfileError(id, SoundProfile.ERROR_INVALID_ARGUMENT,
                                Binder.getCallingUid(), Binder.getCallingPid());
                    }
                    notifyOnSoundProfileRemoved(mSoundProfileTempIdMap.getValue(dbId), toDelete,
                            Binder.getCallingUid(), Binder.getCallingPid());
                    mSoundProfileTempIdMap.remove(dbId);
                    notifyHalOnSoundProfileChange(dbId, null);
                }
            }
        }

        private boolean hasPermissionToRemoveSoundProfile(SoundProfile toDelete) {
            if (toDelete != null) {
                return toDelete.getName().equalsIgnoreCase(getPackageOfCallingUid());
            }
            return false;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public SoundProfile getSoundProfile(int type, String name, Bundle options,
                UserHandle user) {
            boolean includeParams =
                    options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_NAME + " = ? AND "
                    + BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {String.valueOf(type), name, getPackageOfCallingUid()};

            synchronized (mSoundProfileLock) {
                try (
                        Cursor cursor = getCursorAfterQuerying(
                                mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                                MediaQualityUtils.getMediaProfileColumns(includeParams), selection,
                                selectionArguments)
                ) {
                    int count = cursor.getCount();
                    if (count == 0) {
                        return null;
                    }
                    if (count > 1) {
                        Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d "
                                        + "entries found for name=%s in %s. Should only ever "
                                        + "be 0 or 1.", String.valueOf(count), name,
                                mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                        return null;
                    }
                    cursor.moveToFirst();
                    return MediaQualityUtils.convertCursorToSoundProfileWithTempId(cursor,
                            mSoundProfileTempIdMap);
                }
            }
        }

        private SoundProfile getSoundProfile(Long dbId) {
            String selection = BaseParameters.PARAMETER_ID + " = ?";
            String[] selectionArguments = {Long.toString(dbId)};

            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                            MediaQualityUtils.getMediaProfileColumns(false), selection,
                            selectionArguments)
            ) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d entries "
                                    + "found for id=%s in %s. Should only ever be 0 or 1.", count,
                            dbId, mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return MediaQualityUtils.convertCursorToSoundProfileWithTempId(
                        cursor, mSoundProfileTempIdMap);
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<SoundProfile> getSoundProfilesByPackage(
                String packageName, Bundle options, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mSoundProfileLock) {
                boolean includeParams =
                        options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
                String selection = BaseParameters.PARAMETER_PACKAGE + " = ?";
                String[] selectionArguments = {packageName};
                return getSoundProfilesBasedOnConditions(MediaQualityUtils
                                .getMediaProfileColumns(includeParams),
                        selection, selectionArguments);
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<SoundProfile> getAvailableSoundProfiles(Bundle options, UserHandle user) {
            String packageName = getPackageOfCallingUid();
            if (packageName != null) {
                return getSoundProfilesByPackage(packageName, options, user);
            }
            return new ArrayList<>();
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public boolean setDefaultSoundProfile(String profileId, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(profileId, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            SoundProfile soundProfile = getSoundProfile(mSoundProfileTempIdMap.getKey(profileId));
            PersistableBundle params = soundProfile.getParameters();

            try {
                if (mMediaQuality != null) {
                    SoundParameter[] soundParameters =
                            MediaQualityUtils.convertPersistableBundleToSoundParameterList(params);

                    SoundParameters sp = new SoundParameters();
                    sp.soundParameters = soundParameters;

                    mMediaQuality.sendDefaultSoundParameters(sp);
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set default sound profile", e);
            }
            return false;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<String> getSoundProfilePackageNames(UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            String [] column = {BaseParameters.PARAMETER_NAME};

            synchronized (mSoundProfileLock) {
                List<SoundProfile> soundProfiles = getSoundProfilesBasedOnConditions(column,
                        null, null);
                return soundProfiles.stream()
                        .map(SoundProfile::getPackageName)
                        .distinct()
                        .collect(Collectors.toList());
            }
        }

        private String getPackageOfCallingUid() {
            String[] packageNames = mPackageManager.getPackagesForUid(
                    Binder.getCallingUid());
            if (packageNames != null && packageNames.length == 1 && !packageNames[0].isEmpty()) {
                return packageNames[0];
            }
            return null;
        }

        private boolean incomingPackageEqualsCallingUidPackage(String incomingPackage) {
            return incomingPackage.equalsIgnoreCase(getPackageOfCallingUid());
        }

        private boolean hasGlobalPictureQualityServicePermission() {
            return mPackageManager.checkPermission(android.Manifest.permission
                            .MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE,
                    mContext.getPackageName()) == mPackageManager.PERMISSION_GRANTED;
        }

        private boolean hasGlobalSoundQualityServicePermission() {
            return mPackageManager.checkPermission(android.Manifest.permission
                            .MANAGE_GLOBAL_SOUND_QUALITY_SERVICE,
                    mContext.getPackageName()) == mPackageManager.PERMISSION_GRANTED;
        }

        private boolean hasReadColorZonesPermission() {
            return mPackageManager.checkPermission(android.Manifest.permission
                            .READ_COLOR_ZONES,
                    mContext.getPackageName()) == mPackageManager.PERMISSION_GRANTED;
        }

        private Cursor getCursorAfterQuerying(String table, String[] columns, String selection,
                String[] selectionArgs) {
            SQLiteDatabase db = mMediaQualityDbHelper.getReadableDatabase();
            return db.query(table, columns, selection, selectionArgs,
                    /*groupBy=*/ null, /*having=*/ null, /*orderBy=*/ null);
        }

        private List<PictureProfile> getPictureProfilesBasedOnConditions(String[] columns,
                String selection, String[] selectionArguments) {
            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME, columns, selection,
                            selectionArguments)
            ) {
                List<PictureProfile> pictureProfiles = new ArrayList<>();
                while (cursor.moveToNext()) {
                    pictureProfiles.add(MediaQualityUtils.convertCursorToPictureProfileWithTempId(
                            cursor, mPictureProfileTempIdMap));
                }
                return pictureProfiles;
            }
        }

        private List<SoundProfile> getSoundProfilesBasedOnConditions(String[] columns,
                String selection, String[] selectionArguments) {
            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME, columns, selection,
                            selectionArguments)
            ) {
                List<SoundProfile> soundProfiles = new ArrayList<>();
                while (cursor.moveToNext()) {
                    soundProfiles.add(MediaQualityUtils.convertCursorToSoundProfileWithTempId(
                            cursor, mSoundProfileTempIdMap));
                }
                return soundProfiles;
            }
        }

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        private @interface ProfileModes {
            int ADD = 1;
            int UPDATE = 2;
            int REMOVE = 3;
            int ERROR = 4;
        }

        private void notifyOnPictureProfileAdded(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.ADD, profileId, profile, null, uid, pid);
        }

        private void notifyOnPictureProfileUpdated(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.UPDATE, profileId, profile, null, uid, pid);
        }

        private void notifyOnPictureProfileRemoved(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.REMOVE, profileId, profile, null, uid, pid);
        }

        private void notifyOnPictureProfileError(String profileId, int errorCode,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.ERROR, profileId, null, errorCode, uid, pid);
        }

        private void notifyPictureProfileHelper(int mode, String profileId,
                PictureProfile profile, Integer errorCode, int uid, int pid) {
            UserState userState = getOrCreateUserStateLocked(UserHandle.USER_SYSTEM);
            int n = userState.mPictureProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    IPictureProfileCallback callback = userState.mPictureProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mPictureProfileCallbackPidUidMap
                            .get(callback);

                    if (pidUid.first == pid && pidUid.second == uid) {
                        if (mode == ProfileModes.ADD) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onPictureProfileAdded(profileId, profile);
                        } else if (mode == ProfileModes.UPDATE) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onPictureProfileUpdated(profileId, profile);
                        } else if (mode == ProfileModes.REMOVE) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onPictureProfileRemoved(profileId, profile);
                        } else if (mode == ProfileModes.ERROR) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onError(profileId, errorCode);
                        }
                    }
                } catch (RemoteException e) {
                    if (mode == ProfileModes.ADD) {
                        Slog.e(TAG, "Failed to report added picture profile to callback", e);
                    } else if (mode == ProfileModes.UPDATE) {
                        Slog.e(TAG, "Failed to report updated picture profile to callback", e);
                    } else if (mode == ProfileModes.REMOVE) {
                        Slog.e(TAG, "Failed to report removed picture profile to callback", e);
                    } else if (mode == ProfileModes.ERROR) {
                        Slog.e(TAG, "Failed to report picture profile error to callback", e);
                    }
                }
            }
            userState.mPictureProfileCallbacks.finishBroadcast();
        }

        private void notifyOnSoundProfileAdded(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.ADD, profileId, profile, null, uid, pid);
        }

        private void notifyOnSoundProfileUpdated(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.UPDATE, profileId, profile, null, uid, pid);
        }

        private void notifyOnSoundProfileRemoved(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.REMOVE, profileId, profile, null, uid, pid);
        }

        private void notifyOnSoundProfileError(String profileId, int errorCode, int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.ERROR, profileId, null, errorCode, uid, pid);
        }

        private void notifySoundProfileHelper(int mode, String profileId,
                SoundProfile profile, Integer errorCode, int uid, int pid) {
            UserState userState = getOrCreateUserStateLocked(UserHandle.USER_SYSTEM);
            int n = userState.mSoundProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    ISoundProfileCallback callback = userState.mSoundProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mSoundProfileCallbackPidUidMap
                            .get(callback);

                    if (pidUid.first == pid && pidUid.second == uid) {
                        if (mode == ProfileModes.ADD) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onSoundProfileAdded(profileId, profile);
                        } else if (mode == ProfileModes.UPDATE) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onSoundProfileUpdated(profileId, profile);
                        } else if (mode == ProfileModes.REMOVE) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onSoundProfileRemoved(profileId, profile);
                        } else if (mode == ProfileModes.ERROR) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onError(profileId, errorCode);
                        }
                    }
                } catch (RemoteException e) {
                    if (mode == ProfileModes.ADD) {
                        Slog.e(TAG, "Failed to report added sound profile to callback", e);
                    } else if (mode == ProfileModes.UPDATE) {
                        Slog.e(TAG, "Failed to report updated sound profile to callback", e);
                    } else if (mode == ProfileModes.REMOVE) {
                        Slog.e(TAG, "Failed to report removed sound profile to callback", e);
                    } else if (mode == ProfileModes.ERROR) {
                        Slog.e(TAG, "Failed to report sound profile error to callback", e);
                    }
                }
            }
            userState.mSoundProfileCallbacks.finishBroadcast();
        }

        //TODO: need lock here?
        @Override
        public void registerPictureProfileCallback(final IPictureProfileCallback callback) {
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();

            UserState userState = getOrCreateUserStateLocked(Binder.getCallingUid());
            userState.mPictureProfileCallbackPidUidMap.put(callback,
                    Pair.create(callingPid, callingUid));
        }

        //TODO: need lock here?
        @Override
        public void registerSoundProfileCallback(final ISoundProfileCallback callback) {
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();

            UserState userState = getOrCreateUserStateLocked(Binder.getCallingUid());
            userState.mSoundProfileCallbackPidUidMap.put(callback,
                    Pair.create(callingPid, callingUid));
        }

        @Override
        public void registerAmbientBacklightCallback(IAmbientBacklightCallback callback) {
            if (DEBUG) {
                Slogf.d(TAG, "registerAmbientBacklightCallback");
            }

            if (!hasReadColorZonesPermission()) {
                //TODO: error handling
            }

            String callingPackageName = getPackageOfCallingUid();

            synchronized (mCallbackRecords) {
                AmbientBacklightCallbackRecord record = mCallbackRecords.get(callingPackageName);
                if (record != null) {
                    if (record.mCallback.asBinder().equals(callback.asBinder())) {
                        Slog.w(TAG, "AmbientBacklight Callback already registered");
                        return;
                    }
                    record.release();
                    mCallbackRecords.remove(callingPackageName);
                }
                mCallbackRecords.put(callingPackageName,
                        new AmbientBacklightCallbackRecord(callingPackageName, callback));
            }
        }

        @GuardedBy("mAmbientBacklightLock")
        @Override
        public void setAmbientBacklightSettings(
                AmbientBacklightSettings settings, UserHandle user) {
            if (DEBUG) {
                Slogf.d(TAG, "setAmbientBacklightSettings " + settings);
            }

            if (!hasReadColorZonesPermission()) {
                //TODO: error handling
            }

            try {
                if (mMediaQuality != null) {
                    android.hardware.tv.mediaquality.AmbientBacklightSettings halSettings =
                            new android.hardware.tv.mediaquality.AmbientBacklightSettings();
                    halSettings.uid = Binder.getCallingUid();
                    halSettings.source = (byte) settings.getSource();
                    halSettings.maxFramerate = settings.getMaxFps();
                    halSettings.colorFormat = (byte) settings.getColorFormat();
                    halSettings.hZonesNumber = settings.getHorizontalZonesCount();
                    halSettings.vZonesNumber = settings.getVerticalZonesCount();
                    halSettings.hasLetterbox = settings.isLetterboxOmitted();
                    halSettings.colorThreshold = settings.getThreshold();

                    mMediaQuality.setAmbientBacklightDetector(halSettings);

                    mHalAmbientBacklightCallback.setAmbientBacklightClientPackageName(
                            getPackageOfCallingUid());

                    if (DEBUG) {
                        Slogf.d(TAG, "set ambient settings package: " + halSettings.uid);
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set ambient backlight settings", e);
            }
        }

        @GuardedBy("mAmbientBacklightLock")
        @Override
        public void setAmbientBacklightEnabled(boolean enabled, UserHandle user) {
            if (DEBUG) {
                Slogf.d(TAG, "setAmbientBacklightEnabled " + enabled);
            }
            if (!hasReadColorZonesPermission()) {
                //TODO: error handling
            }
            try {
                if (mMediaQuality != null) {
                    mMediaQuality.setAmbientBacklightDetectionEnabled(enabled);
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set ambient backlight enabled", e);
            }
        }

        //TODO: do I need a lock here?
        @Override
        public List<ParameterCapability> getParameterCapabilities(
                List<String> names, UserHandle user) {
            byte[] byteArray = MediaQualityUtils.convertParameterToByteArray(names);
            ParamCapability[] caps = new ParamCapability[byteArray.length];
            try {
                mMediaQuality.getParamCaps(byteArray, caps);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get parameter capabilities", e);
            }

            return getListParameterCapability(caps);
        }

        private List<ParameterCapability> getListParameterCapability(ParamCapability[] caps) {
            List<ParameterCapability> pcList = new ArrayList<>();
            for (ParamCapability pcHal : caps) {
                String name = MediaQualityUtils.getParameterName(pcHal.name);
                boolean isSupported = pcHal.isSupported;
                int type = pcHal.defaultValue == null ? 0 : pcHal.defaultValue.getTag() + 1;
                Bundle bundle = convertToCaps(pcHal.range);

                pcList.add(new ParameterCapability(name, isSupported, type, bundle));
            }
            return pcList;
        }

        private Bundle convertToCaps(ParameterRange range) {
            Bundle bundle = new Bundle();
            if (range == null || range.numRange == null) {
                return bundle;
            }
            bundle.putObject("INT_MIN_MAX", range.numRange.getIntMinMax());
            bundle.putObject("INT_VALUES_SUPPORTED", range.numRange.getIntValuesSupported());
            bundle.putObject("DOUBLE_MIN_MAX", range.numRange.getDoubleMinMax());
            bundle.putObject("DOUBLE_VALUES_SUPPORTED", range.numRange.getDoubleValuesSupported());
            bundle.putObject("LONG_MIN_MAX", range.numRange.getLongMinMax());
            bundle.putObject("LONG_VALUES_SUPPORTED", range.numRange.getLongValuesSupported());
            return bundle;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<String> getPictureProfileAllowList(UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            String allowlist = mPictureProfileSharedPreference.getString(ALLOWLIST, null);
            if (allowlist != null) {
                String[] stringArray = allowlist.split(COMMA_DELIMITER);
                return new ArrayList<>(Arrays.asList(stringArray));
            }
            return new ArrayList<>();
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void setPictureProfileAllowList(List<String> packages, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            SharedPreferences.Editor editor = mPictureProfileSharedPreference.edit();
            editor.putString(ALLOWLIST, String.join(COMMA_DELIMITER, packages));
            editor.commit();
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<String> getSoundProfileAllowList(UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            String allowlist = mSoundProfileSharedPreference.getString(ALLOWLIST, null);
            if (allowlist != null) {
                String[] stringArray = allowlist.split(COMMA_DELIMITER);
                return new ArrayList<>(Arrays.asList(stringArray));
            }
            return new ArrayList<>();
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void setSoundProfileAllowList(List<String> packages, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            SharedPreferences.Editor editor = mSoundProfileSharedPreference.edit();
            editor.putString(ALLOWLIST, String.join(COMMA_DELIMITER, packages));
            editor.commit();
        }

        @Override
        public boolean isSupported(UserHandle user) {
            return false;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void setAutoPictureQualityEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            synchronized (mPictureProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoPqSupported()) {
                            mMediaQuality.setAutoPqEnabled(enabled);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to set auto picture quality", e);
                }
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public boolean isAutoPictureQualityEnabled(UserHandle user) {
            synchronized (mPictureProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoPqSupported()) {
                            return mMediaQuality.getAutoPqEnabled();
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get auto picture quality", e);
                }
                return false;
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void setSuperResolutionEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            synchronized (mPictureProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoSrSupported()) {
                            mMediaQuality.setAutoSrEnabled(enabled);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to set super resolution", e);
                }
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public boolean isSuperResolutionEnabled(UserHandle user) {
            synchronized (mPictureProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoSrSupported()) {
                            return mMediaQuality.getAutoSrEnabled();
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get super resolution", e);
                }
                return false;
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void setAutoSoundQualityEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            synchronized (mSoundProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoAqSupported()) {
                            mMediaQuality.setAutoAqEnabled(enabled);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to set auto sound quality", e);
                }
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public boolean isAutoSoundQualityEnabled(UserHandle user) {
            synchronized (mSoundProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoAqSupported()) {
                            return mMediaQuality.getAutoAqEnabled();
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get auto sound quality", e);
                }
                return false;
            }
        }

        @GuardedBy("mAmbientBacklightLock")
        @Override
        public boolean isAmbientBacklightEnabled(UserHandle user) {
            return false;
        }
    }

    private class MediaQualityManagerPictureProfileCallbackList extends
            RemoteCallbackList<IPictureProfileCallback> {
        @Override
        public void onCallbackDied(IPictureProfileCallback callback) {
            synchronized (mPictureProfileLock) {
                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    UserState userState = getOrCreateUserStateLocked(userId);
                    userState.mPictureProfileCallbackPidUidMap.remove(callback);
                }
            }
        }
    }

    private class MediaQualityManagerSoundProfileCallbackList extends
            RemoteCallbackList<ISoundProfileCallback> {
        @Override
        public void onCallbackDied(ISoundProfileCallback callback) {
            synchronized (mSoundProfileLock) {
                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    UserState userState = getOrCreateUserStateLocked(userId);
                    userState.mSoundProfileCallbackPidUidMap.remove(callback);
                }
            }
        }
    }

    private final class UserState {
        // A list of callbacks.
        private final MediaQualityManagerPictureProfileCallbackList mPictureProfileCallbacks =
                new MediaQualityManagerPictureProfileCallbackList();

        private final MediaQualityManagerSoundProfileCallbackList mSoundProfileCallbacks =
                new MediaQualityManagerSoundProfileCallbackList();

        private final Map<IPictureProfileCallback, Pair<Integer, Integer>>
                mPictureProfileCallbackPidUidMap = new HashMap<>();

        private final Map<ISoundProfileCallback, Pair<Integer, Integer>>
                mSoundProfileCallbackPidUidMap = new HashMap<>();

        private UserState(Context context, int userId) {

        }
    }

    //TODO: used by both picture and sound. can i add both locks?
    private UserState getOrCreateUserStateLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            userState = new UserState(mContext, userId);
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    //TODO: used by both picture and sound. can i add both locks?
    private UserState getUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    private final class AmbientBacklightCallbackRecord implements IBinder.DeathRecipient {
        final String mPackageName;
        final IAmbientBacklightCallback mCallback;

        AmbientBacklightCallbackRecord(@NonNull String pkgName,
                @NonNull IAmbientBacklightCallback cb) {
            mPackageName = pkgName;
            mCallback = cb;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to link to death", e);
            }
        }

        void release() {
            try {
                mCallback.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Slog.e(TAG, "Failed to unlink to death", e);
            }
        }

        @Override
        public void binderDied() {
            synchronized (mCallbackRecords) {
                mCallbackRecords.remove(mPackageName);
            }
        }
    }

    private final class HalAmbientBacklightCallback
            extends android.hardware.tv.mediaquality.IMediaQualityCallback.Stub {
        private final Object mLock = new Object();
        private String mAmbientBacklightClientPackageName;

        void setAmbientBacklightClientPackageName(@NonNull String packageName) {
            synchronized (mLock) {
                if (TextUtils.equals(mAmbientBacklightClientPackageName, packageName)) {
                    return;
                }
                handleAmbientBacklightInterrupted();
                mAmbientBacklightClientPackageName = packageName;
            }
        }

        void handleAmbientBacklightInterrupted() {
            synchronized (mCallbackRecords) {
                if (mAmbientBacklightClientPackageName == null) {
                    Slog.e(TAG, "Invalid package name in interrupted event");
                    return;
                }
                AmbientBacklightCallbackRecord record = mCallbackRecords.get(
                        mAmbientBacklightClientPackageName);
                if (record == null) {
                    Slog.e(TAG, "Callback record not found for ambient backlight");
                    return;
                }
                AmbientBacklightEvent event =
                        new AmbientBacklightEvent(
                                AMBIENT_BACKLIGHT_EVENT_INTERRUPTED, null);
                try {
                    record.mCallback.onAmbientBacklightEvent(event);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Deliver ambient backlight interrupted event failed", e);
                }
            }
        }

        void handleAmbientBacklightEnabled(boolean enabled) {
            AmbientBacklightEvent event =
                    new AmbientBacklightEvent(
                            enabled ? AMBIENT_BACKLIGHT_EVENT_ENABLED :
                                    AMBIENT_BACKLIGHT_EVENT_DISABLED, null);
            synchronized (mCallbackRecords) {
                for (AmbientBacklightCallbackRecord record : mCallbackRecords.values()) {
                    try {
                        record.mCallback.onAmbientBacklightEvent(event);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Deliver ambient backlight enabled event failed", e);
                    }
                }
            }
        }

        void handleAmbientBacklightMetadataEvent(
                @NonNull android.hardware.tv.mediaquality.AmbientBacklightMetadata
                        halMetadata) {
            String halPackageName = mContext.getPackageManager()
                                    .getNameForUid(halMetadata.settings.uid);
            if (!TextUtils.equals(mAmbientBacklightClientPackageName, halPackageName)) {
                Slog.e(TAG, "Invalid package name in metadata event");
                return;
            }

            AmbientBacklightColorFormat[] zonesColorsUnion = halMetadata.zonesColors;
            int[] zonesColorsInt = new int[zonesColorsUnion.length];

            for (int i = 0; i < zonesColorsUnion.length; i++) {
                zonesColorsInt[i] = zonesColorsUnion[i].RGB888;
            }

            AmbientBacklightMetadata metadata =
                    new AmbientBacklightMetadata(
                            halPackageName,
                            halMetadata.compressAlgorithm,
                            halMetadata.settings.source,
                            halMetadata.settings.colorFormat,
                            halMetadata.settings.hZonesNumber,
                            halMetadata.settings.vZonesNumber,
                            zonesColorsInt);
            AmbientBacklightEvent event =
                    new AmbientBacklightEvent(
                            AMBIENT_BACKLIGHT_EVENT_METADATA_AVAILABLE, metadata);

            synchronized (mCallbackRecords) {
                AmbientBacklightCallbackRecord record = mCallbackRecords
                                                .get(halPackageName);
                if (record == null) {
                    Slog.e(TAG, "Callback record not found for ambient backlight metadata");
                    return;
                }

                try {
                    record.mCallback.onAmbientBacklightEvent(event);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Deliver ambient backlight metadata event failed", e);
                }
            }
        }

        @Override
        public void notifyAmbientBacklightEvent(
                android.hardware.tv.mediaquality.AmbientBacklightEvent halEvent) {
            synchronized (mLock) {
                if (halEvent.getTag() == android.hardware.tv.mediaquality
                                .AmbientBacklightEvent.Tag.enabled) {
                    boolean enabled = halEvent.getEnabled();
                    if (enabled) {
                        handleAmbientBacklightEnabled(true);
                    } else {
                        handleAmbientBacklightEnabled(false);
                    }
                } else if (halEvent.getTag() == android.hardware.tv.mediaquality
                                    .AmbientBacklightEvent.Tag.metadata) {
                    handleAmbientBacklightMetadataEvent(halEvent.getMetadata());
                } else {
                    Slog.e(TAG, "Invalid event type in ambient backlight event");
                }
            }
        }

        @Override
        public synchronized String getInterfaceHash() throws android.os.RemoteException {
            return android.hardware.tv.mediaquality.IMediaQualityCallback.Stub.HASH;
        }

        @Override
        public int getInterfaceVersion() throws android.os.RemoteException {
            return android.hardware.tv.mediaquality.IMediaQualityCallback.Stub.VERSION;
        }
    }
}
