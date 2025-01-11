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

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.tv.mediaquality.IMediaQuality;
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
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * This service manage picture profile and sound profile for TV setting. Also communicates with the
 * database to save, update the profiles.
 */
public class MediaQualityService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String TAG = "MediaQualityService";
    private static final int MAX_UUID_GENERATION_ATTEMPTS = 10;
    private final Context mContext;
    private final MediaQualityDbHelper mMediaQualityDbHelper;
    private final BiMap<Long, String> mPictureProfileTempIdMap;
    private final BiMap<Long, String> mSoundProfileTempIdMap;
    private final PackageManager mPackageManager;
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    private IMediaQuality mMediaQuality;

    public MediaQualityService(Context context) {
        super(context);
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mPictureProfileTempIdMap = new BiMap<>();
        mSoundProfileTempIdMap = new BiMap<>();
        mMediaQualityDbHelper = new MediaQualityDbHelper(mContext);
        mMediaQualityDbHelper.setWriteAheadLoggingEnabled(true);
        mMediaQualityDbHelper.setIdleConnectionTimeout(30);
    }

    @Override
    public void onStart() {
        IBinder binder = ServiceManager.getService(IMediaQuality.DESCRIPTOR + "/default");
        if (binder != null) {
            Slogf.d(TAG, "binder is not null");
            mMediaQuality = IMediaQuality.Stub.asInterface(binder);
        }

        publishBinderService(Context.MEDIA_QUALITY_SERVICE, new BinderService());
    }

    // TODO: Add additional APIs. b/373951081
    private final class BinderService extends IMediaQualityManager.Stub {

        @Override
        public PictureProfile createPictureProfile(PictureProfile pp, UserHandle user) {
            if ((pp.getPackageName() != null && !pp.getPackageName().isEmpty()
                    && !incomingPackageEqualsCallingUidPackage(pp.getPackageName()))
                    && !hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();

            ContentValues values = getContentValues(null,
                    pp.getProfileType(),
                    pp.getName(),
                    pp.getPackageName() == null || pp.getPackageName().isEmpty()
                            ? getPackageOfCallingUid() : pp.getPackageName(),
                    pp.getInputId(),
                    pp.getParameters());

            // id is auto-generated by SQLite upon successful insertion of row
            Long id = db.insert(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                    null, values);
            populateTempIdMap(mPictureProfileTempIdMap, id);
            pp.setProfileId(mPictureProfileTempIdMap.getValue(id));
            return pp;
        }

        @Override
        public void updatePictureProfile(String id, PictureProfile pp, UserHandle user) {
            Long dbId = mPictureProfileTempIdMap.getKey(id);
            if (!hasPermissionToUpdatePictureProfile(dbId, pp)) {
                notifyPictureProfileError(id, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            ContentValues values = getContentValues(dbId,
                    pp.getProfileType(),
                    pp.getName(),
                    pp.getPackageName(),
                    pp.getInputId(),
                    pp.getParameters());

            SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
            db.replace(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                    null, values);
        }

        private boolean hasPermissionToUpdatePictureProfile(Long dbId, PictureProfile toUpdate) {
            PictureProfile fromDb = getPictureProfile(dbId);
            return fromDb.getProfileType() == toUpdate.getProfileType()
                    && fromDb.getPackageName().equals(toUpdate.getPackageName())
                    && fromDb.getName().equals(toUpdate.getName())
                    && fromDb.getName().equals(getPackageOfCallingUid());
        }

        @Override
        public void removePictureProfile(String id, UserHandle user) {
            Long dbId = mPictureProfileTempIdMap.getKey(id);

            if (!hasPermissionToRemovePictureProfile(dbId)) {
                notifyPictureProfileError(id, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            if (dbId != null) {
                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                String selection = BaseParameters.PARAMETER_ID + " = ?";
                String[] selectionArgs = {Long.toString(dbId)};
                int result = db.delete(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME, selection,
                        selectionArgs);
                if (result == 0) {
                    notifyPictureProfileError(id, PictureProfile.ERROR_INVALID_ARGUMENT,
                            Binder.getCallingUid(), Binder.getCallingPid());
                }
                mPictureProfileTempIdMap.remove(dbId);
            }
        }

        private boolean hasPermissionToRemovePictureProfile(Long dbId) {
            PictureProfile fromDb = getPictureProfile(dbId);
            return fromDb.getName().equalsIgnoreCase(getPackageOfCallingUid());
        }

        @Override
        public PictureProfile getPictureProfile(int type, String name, Bundle options,
                UserHandle user) {
            boolean includeParams =
                    options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_NAME + " = ? AND "
                    + BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {Integer.toString(type), name, getPackageOfCallingUid()};

            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                            getMediaProfileColumns(includeParams), selection, selectionArguments)
            ) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, String.format(Locale.US, "%d entries found for type=%d and name=%s"
                                    + " in %s. Should only ever be 0 or 1.", count, type, name,
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return convertCursorToPictureProfileWithTempId(cursor);
            }
        }

        private PictureProfile getPictureProfile(Long dbId) {
            String selection = BaseParameters.PARAMETER_ID + " = ?";
            String[] selectionArguments = {Long.toString(dbId)};

            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                            getMediaProfileColumns(false), selection, selectionArguments)
            ) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, String.format(Locale.US, "%d entries found for id=%d"
                                    + " in %s. Should only ever be 0 or 1.", count, dbId,
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return convertCursorToPictureProfileWithTempId(cursor);
            }
        }

        @Override
        public List<PictureProfile> getPictureProfilesByPackage(
                String packageName, Bundle options, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            boolean includeParams =
                    options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
            String selection = BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {packageName};
            return getPictureProfilesBasedOnConditions(getMediaProfileColumns(includeParams),
                    selection, selectionArguments);
        }

        @Override
        public List<PictureProfile> getAvailablePictureProfiles(Bundle options, UserHandle user) {
            String packageName = getPackageOfCallingUid();
            if (packageName != null) {
                return getPictureProfilesByPackage(packageName, options, user);
            }
            return new ArrayList<>();
        }

        @Override
        public boolean setDefaultPictureProfile(String profileId, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(profileId, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            // TODO: pass the profile ID to MediaQuality HAL when ready.
            return false;
        }

        @Override
        public List<String> getPictureProfilePackageNames(UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            String [] column = {BaseParameters.PARAMETER_PACKAGE};
            List<PictureProfile> pictureProfiles = getPictureProfilesBasedOnConditions(column,
                    null, null);
            return pictureProfiles.stream()
                    .map(PictureProfile::getPackageName)
                    .distinct()
                    .collect(Collectors.toList());
        }

        @Override
        public List<PictureProfileHandle> getPictureProfileHandle(String[] ids, UserHandle user) {
            List<PictureProfileHandle> toReturn = new ArrayList<>();
            for (String id : ids) {
                Long key = mPictureProfileTempIdMap.getKey(id);
                if (key != null) {
                    toReturn.add(new PictureProfileHandle(key));
                } else {
                    toReturn.add(null);
                }
            }
            return toReturn;
        }

        @Override
        public List<SoundProfileHandle> getSoundProfileHandle(String[] ids, UserHandle user) {
            List<SoundProfileHandle> toReturn = new ArrayList<>();
            for (String id : ids) {
                Long key = mSoundProfileTempIdMap.getKey(id);
                if (key != null) {
                    toReturn.add(new SoundProfileHandle(key));
                } else {
                    toReturn.add(null);
                }
            }
            return toReturn;
        }

        @Override
        public SoundProfile createSoundProfile(SoundProfile sp, UserHandle user) {
            if ((sp.getPackageName() != null && !sp.getPackageName().isEmpty()
                    && !incomingPackageEqualsCallingUidPackage(sp.getPackageName()))
                    && !hasGlobalPictureQualityServicePermission()) {
                notifySoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();

            ContentValues values = getContentValues(null,
                    sp.getProfileType(),
                    sp.getName(),
                    sp.getPackageName() == null || sp.getPackageName().isEmpty()
                            ? getPackageOfCallingUid() : sp.getPackageName(),
                    sp.getInputId(),
                    sp.getParameters());

            // id is auto-generated by SQLite upon successful insertion of row
            Long id = db.insert(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                    null, values);
            populateTempIdMap(mSoundProfileTempIdMap, id);
            sp.setProfileId(mSoundProfileTempIdMap.getValue(id));
            return sp;
        }

        @Override
        public void updateSoundProfile(String id, SoundProfile sp, UserHandle user) {
            Long dbId = mSoundProfileTempIdMap.getKey(id);
            if (!hasPermissionToUpdateSoundProfile(dbId, sp)) {
                notifySoundProfileError(id, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            ContentValues values = getContentValues(dbId,
                    sp.getProfileType(),
                    sp.getName(),
                    sp.getPackageName(),
                    sp.getInputId(),
                    sp.getParameters());

            SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
            db.replace(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME, null, values);
        }

        private boolean hasPermissionToUpdateSoundProfile(Long dbId, SoundProfile sp) {
            SoundProfile fromDb = getSoundProfile(dbId);
            return fromDb.getProfileType() == sp.getProfileType()
                    && fromDb.getPackageName().equals(sp.getPackageName())
                    && fromDb.getName().equals(sp.getName())
                    && fromDb.getName().equals(getPackageOfCallingUid());
        }

        @Override
        public void removeSoundProfile(String id, UserHandle user) {
            Long dbId = mSoundProfileTempIdMap.getKey(id);
            if (!hasPermissionToRemoveSoundProfile(dbId)) {
                notifySoundProfileError(id, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            if (dbId != null) {
                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                String selection = BaseParameters.PARAMETER_ID + " = ?";
                String[] selectionArgs = {Long.toString(dbId)};
                int result = db.delete(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME, selection,
                        selectionArgs);
                if (result == 0) {
                    notifySoundProfileError(id, SoundProfile.ERROR_INVALID_ARGUMENT,
                            Binder.getCallingUid(), Binder.getCallingPid());
                }
                mSoundProfileTempIdMap.remove(dbId);
            }
        }

        private boolean hasPermissionToRemoveSoundProfile(Long dbId) {
            SoundProfile fromDb = getSoundProfile(dbId);
            return fromDb.getName().equalsIgnoreCase(getPackageOfCallingUid());
        }

        @Override
        public SoundProfile getSoundProfile(int type, String name, Bundle options,
                UserHandle user) {
            boolean includeParams =
                    options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_NAME + " = ? AND "
                    + BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {String.valueOf(type), name, getPackageOfCallingUid()};

            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                            getMediaProfileColumns(includeParams), selection, selectionArguments)
            ) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, String.format(Locale.US, "%d entries found for name=%s"
                                    + " in %s. Should only ever be 0 or 1.", count, name,
                            mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return convertCursorToSoundProfileWithTempId(cursor);
            }
        }

        private SoundProfile getSoundProfile(Long dbId) {
            String selection = BaseParameters.PARAMETER_ID + " = ?";
            String[] selectionArguments = {Long.toString(dbId)};

            try (
                    Cursor cursor = getCursorAfterQuerying(
                            mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                            getMediaProfileColumns(false), selection, selectionArguments)
            ) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, String.format(Locale.US, "%d entries found for id=%s "
                                    + "in %s. Should only ever be 0 or 1.", count, dbId,
                            mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return convertCursorToSoundProfileWithTempId(cursor);
            }
        }

        @Override
        public List<SoundProfile> getSoundProfilesByPackage(
                String packageName, Bundle options, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifySoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            boolean includeParams =
                    options.getBoolean(MediaQualityManager.OPTION_INCLUDE_PARAMETERS, false);
            String selection = BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {packageName};
            return getSoundProfilesBasedOnConditions(getMediaProfileColumns(includeParams),
                    selection, selectionArguments);
        }

        @Override
        public List<SoundProfile> getAvailableSoundProfiles(Bundle options, UserHandle user) {
            String packageName = getPackageOfCallingUid();
            if (packageName != null) {
                return getSoundProfilesByPackage(packageName, options, user);
            }
            return new ArrayList<>();
        }

        @Override
        public boolean setDefaultSoundProfile(String profileId, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifySoundProfileError(profileId, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            // TODO: pass the profile ID to MediaQuality HAL when ready.
            return false;
        }

        @Override
        public List<String> getSoundProfilePackageNames(UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifySoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            String [] column = {BaseParameters.PARAMETER_NAME};
            List<SoundProfile> soundProfiles = getSoundProfilesBasedOnConditions(column,
                    null, null);
            return soundProfiles.stream()
                    .map(SoundProfile::getPackageName)
                    .distinct()
                    .collect(Collectors.toList());
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

        private void populateTempIdMap(BiMap<Long, String> map, Long id) {
            if (id != null && map.getValue(id) == null) {
                String uuid;
                int attempts = 0;
                while (attempts < MAX_UUID_GENERATION_ATTEMPTS) {
                    uuid = UUID.randomUUID().toString();
                    if (map.getKey(uuid) == null) {
                        map.put(id, uuid);
                        return;
                    }
                    attempts++;
                }
            }
        }

        private String persistableBundleToJson(PersistableBundle bundle) {
            JSONObject json = new JSONObject();
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                try {
                    if (value instanceof String) {
                        json.put(key, bundle.getString(key));
                    } else if (value instanceof Integer) {
                        json.put(key, bundle.getInt(key));
                    } else if (value instanceof Long) {
                        json.put(key, bundle.getLong(key));
                    } else if (value instanceof Boolean) {
                        json.put(key, bundle.getBoolean(key));
                    } else if (value instanceof Double) {
                        json.put(key, bundle.getDouble(key));
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Unable to serialize ", e);
                }
            }
            return json.toString();
        }

        private PersistableBundle jsonToPersistableBundle(String jsonString) {
            PersistableBundle bundle = new PersistableBundle();
            if (jsonString != null) {
                JSONObject jsonObject = null;
                try {
                    jsonObject = new JSONObject(jsonString);

                    Iterator<String> keys = jsonObject.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = jsonObject.get(key);

                        if (value instanceof String) {
                            bundle.putString(key, (String) value);
                        } else if (value instanceof Integer) {
                            bundle.putInt(key, (Integer) value);
                        } else if (value instanceof Boolean) {
                            bundle.putBoolean(key, (Boolean) value);
                        } else if (value instanceof Double) {
                            bundle.putDouble(key, (Double) value);
                        } else if (value instanceof Long) {
                            bundle.putLong(key, (Long) value);
                        }
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
            return bundle;
        }

        private ContentValues getContentValues(Long dbId, Integer profileType, String name,
                String packageName, String inputId, PersistableBundle params) {
            ContentValues values = new ContentValues();
            if (dbId != null) {
                values.put(BaseParameters.PARAMETER_ID, dbId);
            }
            if (profileType != null) {
                values.put(BaseParameters.PARAMETER_TYPE, profileType);
            }
            if (name != null) {
                values.put(BaseParameters.PARAMETER_NAME, name);
            }
            if (packageName != null) {
                values.put(BaseParameters.PARAMETER_PACKAGE, packageName);
            }
            if (inputId != null) {
                values.put(BaseParameters.PARAMETER_INPUT_ID, inputId);
            }
            if (params != null) {
                values.put(mMediaQualityDbHelper.SETTINGS, persistableBundleToJson(params));
            }
            return values;
        }

        private String[] getMediaProfileColumns(boolean includeParams) {
            ArrayList<String> columns = new ArrayList<>(Arrays.asList(
                    BaseParameters.PARAMETER_ID,
                    BaseParameters.PARAMETER_TYPE,
                    BaseParameters.PARAMETER_NAME,
                    BaseParameters.PARAMETER_INPUT_ID,
                    BaseParameters.PARAMETER_PACKAGE)
            );
            if (includeParams) {
                columns.add(mMediaQualityDbHelper.SETTINGS);
            }
            return columns.toArray(new String[0]);
        }

        private PictureProfile convertCursorToPictureProfileWithTempId(Cursor cursor) {
            return new PictureProfile(
                    getTempId(mPictureProfileTempIdMap, cursor),
                    getType(cursor),
                    getName(cursor),
                    getInputId(cursor),
                    getPackageName(cursor),
                    jsonToPersistableBundle(getSettingsString(cursor)),
                    PictureProfileHandle.NONE
            );
        }

        private SoundProfile convertCursorToSoundProfileWithTempId(Cursor cursor) {
            return new SoundProfile(
                    getTempId(mSoundProfileTempIdMap, cursor),
                    getType(cursor),
                    getName(cursor),
                    getInputId(cursor),
                    getPackageName(cursor),
                    jsonToPersistableBundle(getSettingsString(cursor)),
                    SoundProfileHandle.NONE
            );
        }

        private String getTempId(BiMap<Long, String> map, Cursor cursor) {
            int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_ID);
            Long dbId = colIndex != -1 ? cursor.getLong(colIndex) : null;
            populateTempIdMap(map, dbId);
            return map.getValue(dbId);
        }

        private int getType(Cursor cursor) {
            int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_TYPE);
            return colIndex != -1 ? cursor.getInt(colIndex) : 0;
        }

        private String getName(Cursor cursor) {
            int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_NAME);
            return colIndex != -1 ? cursor.getString(colIndex) : null;
        }

        private String getInputId(Cursor cursor) {
            int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_INPUT_ID);
            return colIndex != -1 ? cursor.getString(colIndex) : null;
        }

        private String getPackageName(Cursor cursor) {
            int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_PACKAGE);
            return colIndex != -1 ? cursor.getString(colIndex) : null;
        }

        private String getSettingsString(Cursor cursor) {
            int colIndex = cursor.getColumnIndex(mMediaQualityDbHelper.SETTINGS);
            return colIndex != -1 ? cursor.getString(colIndex) : null;
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
                    pictureProfiles.add(convertCursorToPictureProfileWithTempId(cursor));
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
                    soundProfiles.add(convertCursorToSoundProfileWithTempId(cursor));
                }
                return soundProfiles;
            }
        }

        private void notifyPictureProfileError(String profileId, int errorCode, int uid, int pid) {
            UserState userState = getOrCreateUserStateLocked(UserHandle.USER_SYSTEM);
            int n = userState.mPictureProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    IPictureProfileCallback callback = userState.mPictureProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mPictureProfileCallbackPidUidMap
                            .get(callback);

                    if (pidUid.first == pid && pidUid.second == uid) {
                        userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                .onError(profileId, errorCode);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "failed to report added input to callback", e);
                }
            }
            userState.mPictureProfileCallbacks.finishBroadcast();
        }

        private void notifySoundProfileError(String profileId, int errorCode, int uid, int pid) {
            UserState userState = getOrCreateUserStateLocked(UserHandle.USER_SYSTEM);
            int n = userState.mSoundProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    ISoundProfileCallback callback = userState.mSoundProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mSoundProfileCallbackPidUidMap
                            .get(callback);

                    if (pidUid.first == pid && pidUid.second == uid) {
                        userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                .onError(profileId, errorCode);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "failed to report added input to callback", e);
                }
            }
            userState.mSoundProfileCallbacks.finishBroadcast();
        }

        @Override
        public void registerPictureProfileCallback(final IPictureProfileCallback callback) {
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();

            UserState userState = getOrCreateUserStateLocked(Binder.getCallingUid());
            userState.mPictureProfileCallbackPidUidMap.put(callback,
                    Pair.create(callingPid, callingUid));
        }

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
            if (!hasReadColorZonesPermission()) {
                //TODO: error handling
            }
        }

        @Override
        public void setAmbientBacklightSettings(
                AmbientBacklightSettings settings, UserHandle user) {
            if (!hasReadColorZonesPermission()) {
                //TODO: error handling
            }
        }

        @Override
        public void setAmbientBacklightEnabled(boolean enabled, UserHandle user) {
            if (!hasReadColorZonesPermission()) {
                //TODO: error handling
            }
        }

        @Override
        public List<ParameterCapability> getParameterCapabilities(
                List<String> names, UserHandle user) {
            return new ArrayList<>();
        }

        @Override
        public List<String> getPictureProfileAllowList(UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            return new ArrayList<>();
        }

        @Override
        public void setPictureProfileAllowList(List<String> packages, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
        }

        @Override
        public List<String> getSoundProfileAllowList(UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifySoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            return new ArrayList<>();
        }

        @Override
        public void setSoundProfileAllowList(List<String> packages, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifySoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
        }

        @Override
        public boolean isSupported(UserHandle user) {
            return false;
        }

        @Override
        public void setAutoPictureQualityEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            try {
                if (mMediaQuality != null) {
                    mMediaQuality.setAutoPqEnabled(enabled);
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set auto picture quality", e);
            }
        }

        @Override
        public boolean isAutoPictureQualityEnabled(UserHandle user) {
            try {
                if (mMediaQuality != null) {
                    return mMediaQuality.getAutoPqEnabled();
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get auto picture quality", e);
            }
            return false;
        }

        @Override
        public void setSuperResolutionEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            try {
                if (mMediaQuality != null) {
                    mMediaQuality.setAutoSrEnabled(enabled);
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set auto super resolution", e);
            }
        }

        @Override
        public boolean isSuperResolutionEnabled(UserHandle user) {
            try {
                if (mMediaQuality != null) {
                    return mMediaQuality.getAutoSrEnabled();
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get auto super resolution", e);
            }
            return false;
        }

        @Override
        public void setAutoSoundQualityEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifySoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            try {
                if (mMediaQuality != null) {
                    mMediaQuality.setAutoAqEnabled(enabled);
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set auto audio quality", e);
            }
        }

        @Override
        public boolean isAutoSoundQualityEnabled(UserHandle user) {
            try {
                if (mMediaQuality != null) {
                    return mMediaQuality.getAutoAqEnabled();
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get auto audio quality", e);
            }
            return false;
        }

        @Override
        public boolean isAmbientBacklightEnabled(UserHandle user) {
            return false;
        }
    }

    private class MediaQualityManagerPictureProfileCallbackList extends
            RemoteCallbackList<IPictureProfileCallback> {
        @Override
        public void onCallbackDied(IPictureProfileCallback callback) {
            //todo
        }
    }

    private class MediaQualityManagerSoundProfileCallbackList extends
            RemoteCallbackList<ISoundProfileCallback> {
        @Override
        public void onCallbackDied(ISoundProfileCallback callback) {
            //todo
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

    private UserState getOrCreateUserStateLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            userState = new UserState(mContext, userId);
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    private UserState getUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }
}
