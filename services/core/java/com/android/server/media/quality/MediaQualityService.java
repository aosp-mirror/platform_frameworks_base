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
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.tv.mediaquality.AmbientBacklightColorFormat;
import android.hardware.tv.mediaquality.IMediaQuality;
import android.hardware.tv.mediaquality.PictureParameter;
import android.hardware.tv.mediaquality.PictureParameters;
import android.hardware.tv.mediaquality.SoundParameter;
import android.hardware.tv.mediaquality.SoundParameters;
import android.media.quality.AmbientBacklightEvent;
import android.media.quality.AmbientBacklightMetadata;
import android.media.quality.AmbientBacklightSettings;
import android.media.quality.IAmbientBacklightCallback;
import android.media.quality.IMediaQualityManager;
import android.media.quality.IPictureProfileCallback;
import android.media.quality.ISoundProfileCallback;
import android.media.quality.MediaQualityContract.BaseParameters;
import android.media.quality.MediaQualityContract.PictureQuality;
import android.media.quality.MediaQualityContract.SoundQuality;
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
import android.text.TextUtils;
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
import java.util.NoSuchElementException;
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
    private IMediaQuality mMediaQuality;
    private final HalAmbientBacklightCallback mHalAmbientBacklightCallback;
    private final Map<String, AmbientBacklightCallbackRecord> mCallbackRecords = new HashMap<>();
    private final PackageManager mPackageManager;
    private final SparseArray<UserState> mUserStates = new SparseArray<>();

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
    }

    @Override
    public void onStart() {
        IBinder binder = ServiceManager.getService(IMediaQuality.DESCRIPTOR + "/default");
        if (binder != null) {
            Slogf.d(TAG, "binder is not null");
            mMediaQuality = IMediaQuality.Stub.asInterface(binder);
            if (mMediaQuality != null) {
                try {
                    mMediaQuality.setAmbientBacklightCallback(mHalAmbientBacklightCallback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to set ambient backlight detector callback", e);
                }
            }
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
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
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
            String value = mPictureProfileTempIdMap.getValue(id);
            pp.setProfileId(value);
            notifyOnPictureProfileAdded(value, pp, Binder.getCallingUid(), Binder.getCallingPid());
            return pp;
        }

        @Override
        public void updatePictureProfile(String id, PictureProfile pp, UserHandle user) {
            Long dbId = mPictureProfileTempIdMap.getKey(id);
            if (!hasPermissionToUpdatePictureProfile(dbId, pp)) {
                notifyOnPictureProfileError(id, PictureProfile.ERROR_NO_PERMISSION,
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
            notifyOnPictureProfileUpdated(mPictureProfileTempIdMap.getValue(dbId),
                    getPictureProfile(dbId), Binder.getCallingUid(), Binder.getCallingPid());
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

            PictureProfile toDelete = getPictureProfile(dbId);
            if (!hasPermissionToRemovePictureProfile(toDelete)) {
                notifyOnPictureProfileError(id, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            if (dbId != null) {
                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                String selection = BaseParameters.PARAMETER_ID + " = ?";
                String[] selectionArgs = {Long.toString(dbId)};
                int result = db.delete(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME, selection,
                        selectionArgs);
                if (result == 0) {
                    notifyOnPictureProfileError(id, PictureProfile.ERROR_INVALID_ARGUMENT,
                            Binder.getCallingUid(), Binder.getCallingPid());
                }
                notifyOnPictureProfileRemoved(mPictureProfileTempIdMap.getValue(dbId), toDelete,
                        Binder.getCallingUid(), Binder.getCallingPid());
                mPictureProfileTempIdMap.remove(dbId);
            }
        }

        private boolean hasPermissionToRemovePictureProfile(PictureProfile toDelete) {
            if (toDelete != null) {
                return toDelete.getName().equalsIgnoreCase(getPackageOfCallingUid());
            }
            return false;
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
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
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
                notifyOnPictureProfileError(profileId, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            PictureProfile pictureProfile = getPictureProfile(
                    mPictureProfileTempIdMap.getKey(profileId));
            PersistableBundle params = pictureProfile.getParameters();

            try {
                if (mMediaQuality != null) {
                    PictureParameter[] pictureParameters =
                            convertPersistableBundleToPictureParameterList(params);

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

        private PictureParameter[] convertPersistableBundleToPictureParameterList(
                PersistableBundle params) {
            List<PictureParameter> pictureParams = new ArrayList<>();
            if (params.containsKey(PictureQuality.PARAMETER_BRIGHTNESS)) {
                pictureParams.add(PictureParameter.brightness(params.getLong(
                        PictureQuality.PARAMETER_BRIGHTNESS)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_CONTRAST)) {
                pictureParams.add(PictureParameter.contrast(params.getInt(
                        PictureQuality.PARAMETER_CONTRAST)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_SHARPNESS)) {
                pictureParams.add(PictureParameter.sharpness(params.getInt(
                        PictureQuality.PARAMETER_SHARPNESS)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_SATURATION)) {
                pictureParams.add(PictureParameter.saturation(params.getInt(
                        PictureQuality.PARAMETER_SATURATION)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_HUE)) {
                pictureParams.add(PictureParameter.hue(params.getInt(
                        PictureQuality.PARAMETER_HUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS)) {
                pictureParams.add(PictureParameter.colorTunerBrightness(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION)) {
                pictureParams.add(PictureParameter.colorTunerSaturation(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE)) {
                pictureParams.add(PictureParameter.colorTunerHue(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET)) {
                pictureParams.add(PictureParameter.colorTunerRedOffset(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET)) {
                pictureParams.add(PictureParameter.colorTunerGreenOffset(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET)) {
                pictureParams.add(PictureParameter.colorTunerBlueOffset(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)) {
                pictureParams.add(PictureParameter.colorTunerRedGain(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)) {
                pictureParams.add(PictureParameter.colorTunerGreenGain(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)) {
                pictureParams.add(PictureParameter.colorTunerBlueGain(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_NOISE_REDUCTION)) {
                pictureParams.add(PictureParameter.noiseReduction(
                        (byte) params.getInt(PictureQuality.PARAMETER_NOISE_REDUCTION)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION)) {
                pictureParams.add(PictureParameter.mpegNoiseReduction(
                        (byte) params.getInt(PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_FLESH_TONE)) {
                pictureParams.add(PictureParameter.fleshTone(
                        (byte) params.getInt(PictureQuality.PARAMETER_FLESH_TONE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_DECONTOUR)) {
                pictureParams.add(PictureParameter.deContour(
                        (byte) params.getInt(PictureQuality.PARAMETER_DECONTOUR)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL)) {
                pictureParams.add(PictureParameter.dynamicLumaControl(
                        (byte) params.getInt(PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_FILM_MODE)) {
                pictureParams.add(PictureParameter.filmMode(params.getBoolean(
                        PictureQuality.PARAMETER_FILM_MODE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_BLUE_STRETCH)) {
                pictureParams.add(PictureParameter.blueStretch(params.getBoolean(
                        PictureQuality.PARAMETER_BLUE_STRETCH)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNE)) {
                pictureParams.add(PictureParameter.colorTune(params.getBoolean(
                        PictureQuality.PARAMETER_COLOR_TUNE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE)) {
                pictureParams.add(PictureParameter.colorTemperature(
                        (byte) params.getInt(
                                PictureQuality.PARAMETER_COLOR_TEMPERATURE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_GLOBAL_DIMMING)) {
                pictureParams.add(PictureParameter.globeDimming(params.getBoolean(
                        PictureQuality.PARAMETER_GLOBAL_DIMMING)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED)) {
                pictureParams.add(PictureParameter.autoPictureQualityEnabled(params.getBoolean(
                        PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED)) {
                pictureParams.add(PictureParameter.autoSuperResolutionEnabled(params.getBoolean(
                        PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)) {
                pictureParams.add(PictureParameter.colorTemperatureRedGain(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)) {
                pictureParams.add(PictureParameter.colorTemperatureGreenGain(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)) {
                pictureParams.add(PictureParameter.colorTemperatureBlueGain(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)));
            }

            /**
             * TODO: add conversion for following after adding to MediaQualityContract
             *
             * PictureParameter.levelRange
             * PictureParameter.gamutMapping
             * PictureParameter.pcMode
             * PictureParameter.lowLatency
             * PictureParameter.vrr
             * PictureParameter.cvrr
             * PictureParameter.hdmiRgbRange
             * PictureParameter.colorSpace
             * PictureParameter.panelInitMaxLuminceNits
             * PictureParameter.panelInitMaxLuminceValid
             * PictureParameter.gamma
             * PictureParameter.colorTemperatureRedOffset
             * PictureParameter.colorTemperatureGreenOffset
             * PictureParameter.colorTemperatureBlueOffset
             * PictureParameter.elevenPointRed
             * PictureParameter.elevenPointGreen
             * PictureParameter.elevenPointBlue
             * PictureParameter.lowBlueLight
             * PictureParameter.LdMode
             * PictureParameter.osdRedGain
             * PictureParameter.osdGreenGain
             * PictureParameter.osdBlueGain
             * PictureParameter.osdRedOffset
             * PictureParameter.osdGreenOffset
             * PictureParameter.osdBlueOffset
             * PictureParameter.osdHue
             * PictureParameter.osdSaturation
             * PictureParameter.osdContrast
             * PictureParameter.colorTunerSwitch
             * PictureParameter.colorTunerHueRed
             * PictureParameter.colorTunerHueGreen
             * PictureParameter.colorTunerHueBlue
             * PictureParameter.colorTunerHueCyan
             * PictureParameter.colorTunerHueMagenta
             * PictureParameter.colorTunerHueYellow
             * PictureParameter.colorTunerHueFlesh
             * PictureParameter.colorTunerSaturationRed
             * PictureParameter.colorTunerSaturationGreen
             * PictureParameter.colorTunerSaturationBlue
             * PictureParameter.colorTunerSaturationCyan
             * PictureParameter.colorTunerSaturationMagenta
             * PictureParameter.colorTunerSaturationYellow
             * PictureParameter.colorTunerSaturationFlesh
             * PictureParameter.colorTunerLuminanceRed
             * PictureParameter.colorTunerLuminanceGreen
             * PictureParameter.colorTunerLuminanceBlue
             * PictureParameter.colorTunerLuminanceCyan
             * PictureParameter.colorTunerLuminanceMagenta
             * PictureParameter.colorTunerLuminanceYellow
             * PictureParameter.colorTunerLuminanceFlesh
             * PictureParameter.activeProfile
             * PictureParameter.pictureQualityEventType
             */
            return  (PictureParameter[]) pictureParams.toArray();
        }

        @Override
        public List<String> getPictureProfilePackageNames(UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
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
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
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
            String value = mSoundProfileTempIdMap.getValue(id);
            sp.setProfileId(value);
            notifyOnSoundProfileAdded(value, sp, Binder.getCallingUid(), Binder.getCallingPid());
            return sp;
        }

        @Override
        public void updateSoundProfile(String id, SoundProfile sp, UserHandle user) {
            Long dbId = mSoundProfileTempIdMap.getKey(id);
            if (!hasPermissionToUpdateSoundProfile(dbId, sp)) {
                notifyOnSoundProfileError(id, SoundProfile.ERROR_NO_PERMISSION,
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
            notifyOnSoundProfileUpdated(mSoundProfileTempIdMap.getValue(dbId),
                    getSoundProfile(dbId), Binder.getCallingUid(), Binder.getCallingPid());
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
            SoundProfile toDelete = getSoundProfile(dbId);
            if (!hasPermissionToRemoveSoundProfile(toDelete)) {
                notifyOnSoundProfileError(id, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            if (dbId != null) {
                SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                String selection = BaseParameters.PARAMETER_ID + " = ?";
                String[] selectionArgs = {Long.toString(dbId)};
                int result = db.delete(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME, selection,
                        selectionArgs);
                if (result == 0) {
                    notifyOnSoundProfileError(id, SoundProfile.ERROR_INVALID_ARGUMENT,
                            Binder.getCallingUid(), Binder.getCallingPid());
                }
                notifyOnSoundProfileRemoved(mSoundProfileTempIdMap.getValue(dbId), toDelete,
                        Binder.getCallingUid(), Binder.getCallingPid());
                mSoundProfileTempIdMap.remove(dbId);
            }
        }

        private boolean hasPermissionToRemoveSoundProfile(SoundProfile toDelete) {
            if (toDelete != null) {
                return toDelete.getName().equalsIgnoreCase(getPackageOfCallingUid());
            }
            return false;
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
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
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
                notifyOnSoundProfileError(profileId, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

            SoundProfile soundProfile = getSoundProfile(mSoundProfileTempIdMap.getKey(profileId));
            PersistableBundle params = soundProfile.getParameters();

            try {
                if (mMediaQuality != null) {
                    SoundParameter[] soundParameters =
                            convertPersistableBundleToSoundParameterList(params);

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

        private SoundParameter[] convertPersistableBundleToSoundParameterList(
                PersistableBundle params) {
            List<SoundParameter> soundParams = new ArrayList<>();
            if (params.containsKey(SoundQuality.PARAMETER_BALANCE)) {
                soundParams.add(SoundParameter.balance(params.getInt(
                        SoundQuality.PARAMETER_BALANCE)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_BASS)) {
                soundParams.add(SoundParameter.bass(params.getInt(SoundQuality.PARAMETER_BASS)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_TREBLE)) {
                soundParams.add(SoundParameter.treble(params.getInt(
                        SoundQuality.PARAMETER_TREBLE)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_SURROUND_SOUND)) {
                soundParams.add(SoundParameter.surroundSoundEnabled(params.getBoolean(
                        SoundQuality.PARAMETER_SURROUND_SOUND)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_SPEAKERS)) {
                soundParams.add(SoundParameter.speakersEnabled(params.getBoolean(
                        SoundQuality.PARAMETER_SPEAKERS)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS)) {
                soundParams.add(SoundParameter.speakersDelayMs(params.getInt(
                        SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL)) {
                soundParams.add(SoundParameter.autoVolumeControl(params.getBoolean(
                        SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_DTS_DRC)) {
                soundParams.add(SoundParameter.dtsDrc(params.getBoolean(
                        SoundQuality.PARAMETER_DTS_DRC)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS)) {
                soundParams.add(SoundParameter.surroundSoundEnabled(params.getBoolean(
                        SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS)));
            }
            //TODO: equalizerDetail
            //TODO: downmixMode
            //TODO: enhancedAudioReturnChannelEnabled
            //TODO: dolbyAudioProcessing
            //TODO: dolbyDialogueEnhancer
            //TODO: dtsVirtualX
            //TODO: digitalOutput
            //TODO: activeProfile
            //TODO: soundStyle
            return  (SoundParameter[]) soundParams.toArray();
        }

        @Override
        public List<String> getSoundProfilePackageNames(UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
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

        enum Mode {
            ADD,
            UPDATE,
            REMOVE,
            ERROR
        }

        private void notifyOnPictureProfileAdded(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(Mode.ADD, profileId, profile, null, uid, pid);
        }

        private void notifyOnPictureProfileUpdated(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(Mode.UPDATE, profileId, profile, null, uid, pid);
        }

        private void notifyOnPictureProfileRemoved(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(Mode.REMOVE, profileId, profile, null, uid, pid);
        }

        private void notifyOnPictureProfileError(String profileId, int errorCode,
                int uid, int pid) {
            notifyPictureProfileHelper(Mode.ERROR, profileId, null, errorCode, uid, pid);
        }

        private void notifyPictureProfileHelper(Mode mode, String profileId, PictureProfile profile,
                Integer errorCode, int uid, int pid) {
            UserState userState = getOrCreateUserStateLocked(UserHandle.USER_SYSTEM);
            int n = userState.mPictureProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    IPictureProfileCallback callback = userState.mPictureProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mPictureProfileCallbackPidUidMap
                            .get(callback);

                    if (pidUid.first == pid && pidUid.second == uid) {
                        if (mode == Mode.ADD) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onPictureProfileAdded(profileId, profile);
                        } else if (mode == Mode.UPDATE) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onPictureProfileUpdated(profileId, profile);
                        } else if (mode == Mode.REMOVE) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onPictureProfileRemoved(profileId, profile);
                        } else if (mode == Mode.ERROR) {
                            userState.mPictureProfileCallbacks.getBroadcastItem(i)
                                    .onError(profileId, errorCode);
                        }
                    }
                } catch (RemoteException e) {
                    if (mode == Mode.ADD) {
                        Slog.e(TAG, "Failed to report added picture profile to callback", e);
                    } else if (mode == Mode.UPDATE) {
                        Slog.e(TAG, "Failed to report updated picture profile to callback", e);
                    } else if (mode == Mode.REMOVE) {
                        Slog.e(TAG, "Failed to report removed picture profile to callback", e);
                    } else if (mode == Mode.ERROR) {
                        Slog.e(TAG, "Failed to report picture profile error to callback", e);
                    }
                }
            }
            userState.mPictureProfileCallbacks.finishBroadcast();
        }

        private void notifyOnSoundProfileAdded(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(Mode.ADD, profileId, profile, null, uid, pid);
        }

        private void notifyOnSoundProfileUpdated(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(Mode.UPDATE, profileId, profile, null, uid, pid);
        }

        private void notifyOnSoundProfileRemoved(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(Mode.REMOVE, profileId, profile, null, uid, pid);
        }

        private void notifyOnSoundProfileError(String profileId, int errorCode, int uid, int pid) {
            notifySoundProfileHelper(Mode.ERROR, profileId, null, errorCode, uid, pid);
        }

        private void notifySoundProfileHelper(Mode mode, String profileId, SoundProfile profile,
                Integer errorCode, int uid, int pid) {
            UserState userState = getOrCreateUserStateLocked(UserHandle.USER_SYSTEM);
            int n = userState.mSoundProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    ISoundProfileCallback callback = userState.mSoundProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mSoundProfileCallbackPidUidMap
                            .get(callback);

                    if (pidUid.first == pid && pidUid.second == uid) {
                        if (mode == Mode.ADD) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onSoundProfileAdded(profileId, profile);
                        } else if (mode == Mode.UPDATE) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onSoundProfileUpdated(profileId, profile);
                        } else if (mode == Mode.REMOVE) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onSoundProfileRemoved(profileId, profile);
                        } else if (mode == Mode.ERROR) {
                            userState.mSoundProfileCallbacks.getBroadcastItem(i)
                                    .onError(profileId, errorCode);
                        }
                    }
                } catch (RemoteException e) {
                    if (mode == Mode.ADD) {
                        Slog.e(TAG, "Failed to report added sound profile to callback", e);
                    } else if (mode == Mode.UPDATE) {
                        Slog.e(TAG, "Failed to report updated sound profile to callback", e);
                    } else if (mode == Mode.REMOVE) {
                        Slog.e(TAG, "Failed to report removed sound profile to callback", e);
                    } else if (mode == Mode.ERROR) {
                        Slog.e(TAG, "Failed to report sound profile error to callback", e);
                    }
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

        @Override
        public List<ParameterCapability> getParameterCapabilities(
                List<String> names, UserHandle user) {
            return new ArrayList<>();
        }

        @Override
        public List<String> getPictureProfileAllowList(UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            return new ArrayList<>();
        }

        @Override
        public void setPictureProfileAllowList(List<String> packages, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
        }

        @Override
        public List<String> getSoundProfileAllowList(UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }
            return new ArrayList<>();
        }

        @Override
        public void setSoundProfileAllowList(List<String> packages, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
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
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

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

        @Override
        public boolean isAutoPictureQualityEnabled(UserHandle user) {
            try {
                if (mMediaQuality != null) {
                    if (mMediaQuality.isAutoPqSupported()) {
                        mMediaQuality.getAutoPqEnabled();
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get auto picture quality", e);
            }
            return false;
        }

        @Override
        public void setSuperResolutionEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalPictureQualityServicePermission()) {
                notifyOnPictureProfileError(null, PictureProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

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

        @Override
        public boolean isSuperResolutionEnabled(UserHandle user) {
            try {
                if (mMediaQuality != null) {
                    if (mMediaQuality.isAutoSrSupported()) {
                        mMediaQuality.getAutoSrEnabled();
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get super resolution", e);
            }
            return false;
        }

        @Override
        public void setAutoSoundQualityEnabled(boolean enabled, UserHandle user) {
            if (!hasGlobalSoundQualityServicePermission()) {
                notifyOnSoundProfileError(null, SoundProfile.ERROR_NO_PERMISSION,
                        Binder.getCallingUid(), Binder.getCallingPid());
            }

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

        @Override
        public boolean isAutoSoundQualityEnabled(UserHandle user) {
            try {
                if (mMediaQuality != null) {
                    if (mMediaQuality.isAutoAqSupported()) {
                        mMediaQuality.getAutoAqEnabled();
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get auto sound quality", e);
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
