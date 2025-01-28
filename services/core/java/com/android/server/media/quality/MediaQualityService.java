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
import android.hardware.tv.mediaquality.DolbyAudioProcessing;
import android.hardware.tv.mediaquality.DtsVirtualX;
import android.hardware.tv.mediaquality.IMediaQuality;
import android.hardware.tv.mediaquality.IPictureProfileAdjustmentListener;
import android.hardware.tv.mediaquality.IPictureProfileChangedListener;
import android.hardware.tv.mediaquality.ISoundProfileAdjustmentListener;
import android.hardware.tv.mediaquality.ISoundProfileChangedListener;
import android.hardware.tv.mediaquality.ParamCapability;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    private static final String ALLOWLIST = "allowlist";
    private static final String PICTURE_PROFILE_PREFERENCE = "picture_profile_preference";
    private static final String SOUND_PROFILE_PREFERENCE = "sound_profile_preference";
    private static final String COMMA_DELIMITER = ",";
    private static final int MAX_UUID_GENERATION_ATTEMPTS = 10;
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
            pictureParameters.pictureParameters = convertPersistableBundleToPictureParameterList(
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
                                getMediaProfileColumns(includeParams), selection,
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
                    return convertCursorToPictureProfileWithTempId(cursor);
                }
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
                    Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d entries "
                                    + "found for id=%d in %s. Should only ever be 0 or 1.",
                            count, dbId, mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return convertCursorToPictureProfileWithTempId(cursor);
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
                return getPictureProfilesBasedOnConditions(getMediaProfileColumns(includeParams),
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
            if (params == null) {
                return null;
            }

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
            if (params.containsKey(PictureQuality.PARAMETER_LEVEL_RANGE)) {
                pictureParams.add(PictureParameter.levelRange(
                        (byte) params.getInt(PictureQuality.PARAMETER_LEVEL_RANGE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_GAMUT_MAPPING)) {
                pictureParams.add(PictureParameter.gamutMapping(params.getBoolean(
                        PictureQuality.PARAMETER_GAMUT_MAPPING)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_PC_MODE)) {
                pictureParams.add(PictureParameter.pcMode(params.getBoolean(
                        PictureQuality.PARAMETER_PC_MODE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_LOW_LATENCY)) {
                pictureParams.add(PictureParameter.lowLatency(params.getBoolean(
                        PictureQuality.PARAMETER_LOW_LATENCY)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_VRR)) {
                pictureParams.add(PictureParameter.vrr(params.getBoolean(
                        PictureQuality.PARAMETER_VRR)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_CVRR)) {
                pictureParams.add(PictureParameter.cvrr(params.getBoolean(
                        PictureQuality.PARAMETER_CVRR)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_HDMI_RGB_RANGE)) {
                pictureParams.add(PictureParameter.hdmiRgbRange(
                        (byte) params.getInt(PictureQuality.PARAMETER_HDMI_RGB_RANGE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_SPACE)) {
                pictureParams.add(PictureParameter.colorSpace(
                        (byte) params.getInt(PictureQuality.PARAMETER_COLOR_SPACE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS)) {
                pictureParams.add(PictureParameter.panelInitMaxLuminceNits(
                        params.getInt(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID)) {
                pictureParams.add(PictureParameter.panelInitMaxLuminceValid(
                        params.getBoolean(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_GAMMA)) {
                pictureParams.add(PictureParameter.gamma(
                        (byte) params.getInt(PictureQuality.PARAMETER_GAMMA)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET)) {
                pictureParams.add(PictureParameter.colorTemperatureRedOffset(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET)) {
                pictureParams.add(PictureParameter.colorTemperatureGreenOffset(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET)) {
                pictureParams.add(PictureParameter.colorTemperatureBlueOffset(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_ELEVEN_POINT_RED)) {
                pictureParams.add(PictureParameter.elevenPointRed(params.getIntArray(
                        PictureQuality.PARAMETER_ELEVEN_POINT_RED)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_ELEVEN_POINT_GREEN)) {
                pictureParams.add(PictureParameter.elevenPointGreen(params.getIntArray(
                        PictureQuality.PARAMETER_ELEVEN_POINT_GREEN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_ELEVEN_POINT_BLUE)) {
                pictureParams.add(PictureParameter.elevenPointBlue(params.getIntArray(
                        PictureQuality.PARAMETER_ELEVEN_POINT_BLUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_LOW_BLUE_LIGHT)) {
                pictureParams.add(PictureParameter.lowBlueLight(
                        (byte) params.getInt(PictureQuality.PARAMETER_LOW_BLUE_LIGHT)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_LD_MODE)) {
                pictureParams.add(PictureParameter.LdMode(
                        (byte) params.getInt(PictureQuality.PARAMETER_LD_MODE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_RED_GAIN)) {
                pictureParams.add(PictureParameter.osdRedGain(params.getInt(
                        PictureQuality.PARAMETER_OSD_RED_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_GREEN_GAIN)) {
                pictureParams.add(PictureParameter.osdGreenGain(params.getInt(
                        PictureQuality.PARAMETER_OSD_GREEN_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_BLUE_GAIN)) {
                pictureParams.add(PictureParameter.osdBlueGain(params.getInt(
                        PictureQuality.PARAMETER_OSD_BLUE_GAIN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_RED_OFFSET)) {
                pictureParams.add(PictureParameter.osdRedOffset(params.getInt(
                        PictureQuality.PARAMETER_OSD_RED_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_GREEN_OFFSET)) {
                pictureParams.add(PictureParameter.osdGreenOffset(params.getInt(
                        PictureQuality.PARAMETER_OSD_GREEN_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_BLUE_OFFSET)) {
                pictureParams.add(PictureParameter.osdBlueOffset(params.getInt(
                        PictureQuality.PARAMETER_OSD_BLUE_OFFSET)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_HUE)) {
                pictureParams.add(PictureParameter.osdHue(params.getInt(
                        PictureQuality.PARAMETER_OSD_HUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_SATURATION)) {
                pictureParams.add(PictureParameter.osdSaturation(params.getInt(
                        PictureQuality.PARAMETER_OSD_SATURATION)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_OSD_CONTRAST)) {
                pictureParams.add(PictureParameter.osdContrast(params.getInt(
                        PictureQuality.PARAMETER_OSD_CONTRAST)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SWITCH)) {
                pictureParams.add(PictureParameter.colorTunerSwitch(params.getBoolean(
                        PictureQuality.PARAMETER_COLOR_TUNER_SWITCH)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED)) {
                pictureParams.add(PictureParameter.colorTunerHueRed(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN)) {
                pictureParams.add(PictureParameter.colorTunerHueGreen(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE)) {
                pictureParams.add(PictureParameter.colorTunerHueBlue(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN)) {
                pictureParams.add(PictureParameter.colorTunerHueCyan(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA)) {
                pictureParams.add(PictureParameter.colorTunerHueMagenta(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW)) {
                pictureParams.add(PictureParameter.colorTunerHueYellow(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH)) {
                pictureParams.add(PictureParameter.colorTunerHueFlesh(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED)) {
                pictureParams.add(PictureParameter.colorTunerSaturationRed(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN)) {
                pictureParams.add(PictureParameter.colorTunerSaturationGreen(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE)) {
                pictureParams.add(PictureParameter.colorTunerSaturationBlue(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN)) {
                pictureParams.add(PictureParameter.colorTunerSaturationCyan(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA)) {
                pictureParams.add(PictureParameter.colorTunerSaturationMagenta(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW)) {
                pictureParams.add(PictureParameter.colorTunerSaturationYellow(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH)) {
                pictureParams.add(PictureParameter.colorTunerSaturationFlesh(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceRed(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceGreen(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceBlue(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceCyan(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceMagenta(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceYellow(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH)) {
                pictureParams.add(PictureParameter.colorTunerLuminanceFlesh(params.getInt(
                        PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH)));
            }
            if (params.containsKey(PictureQuality.PARAMETER_PICTURE_QUALITY_EVENT_TYPE)) {
                pictureParams.add(PictureParameter.pictureQualityEventType(
                        (byte) params.getInt(PictureQuality.PARAMETER_PICTURE_QUALITY_EVENT_TYPE)));
            }
            return  (PictureParameter[]) pictureParams.toArray();
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
            soundParameters.soundParameters = convertPersistableBundleToSoundParameterList(params);

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
                                getMediaProfileColumns(includeParams), selection,
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
                    return convertCursorToSoundProfileWithTempId(cursor);
                }
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
                    Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d entries "
                                    + "found for id=%s in %s. Should only ever be 0 or 1.", count,
                            dbId, mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return convertCursorToSoundProfileWithTempId(cursor);
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
                return getSoundProfilesBasedOnConditions(getMediaProfileColumns(includeParams),
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
            //TODO: set EqualizerDetail
            if (params == null) {
                return null;
            }
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
            if (params.containsKey(SoundQuality.PARAMETER_EARC)) {
                soundParams.add(SoundParameter.enhancedAudioReturnChannelEnabled(params.getBoolean(
                        SoundQuality.PARAMETER_EARC)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_DOWN_MIX_MODE)) {
                soundParams.add(SoundParameter.downmixMode((byte) params.getInt(
                        SoundQuality.PARAMETER_DOWN_MIX_MODE)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_SOUND_STYLE)) {
                soundParams.add(SoundParameter.soundStyle((byte) params.getInt(
                        SoundQuality.PARAMETER_SOUND_STYLE)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE)) {
                soundParams.add(SoundParameter.digitalOutput((byte) params.getInt(
                        SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE)));
            }
            if (params.containsKey(SoundQuality.PARAMETER_DIALOGUE_ENHANCER)) {
                soundParams.add(SoundParameter.dolbyDialogueEnhancer((byte) params.getInt(
                        SoundQuality.PARAMETER_DIALOGUE_ENHANCER)));
            }

            DolbyAudioProcessing dab = new DolbyAudioProcessing();
            dab.soundMode =
                    (byte) params.getInt(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_SOUND_MODE);
            dab.volumeLeveler =
                    params.getBoolean(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_VOLUME_LEVELER);
            dab.surroundVirtualizer = params.getBoolean(
                    SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_SURROUND_VIRTUALIZER);
            dab.dolbyAtmos =
                    params.getBoolean(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_DOLBY_ATMOS);
            soundParams.add(SoundParameter.dolbyAudioProcessing(dab));

            DtsVirtualX dts = new DtsVirtualX();
            dts.tbHdx = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TBHDX);
            dts.limiter = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_LIMITER);
            dts.truSurroundX = params.getBoolean(
                    SoundQuality.PARAMETER_DTS_VIRTUAL_X_TRU_SURROUND_X);
            dts.truVolumeHd = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TRU_VOLUME_HD);
            dts.dialogClarity = params.getBoolean(
                    SoundQuality.PARAMETER_DTS_VIRTUAL_X_DIALOG_CLARITY);
            dts.definition = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_DEFINITION);
            dts.height = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_HEIGHT);
            soundParams.add(SoundParameter.dtsVirtualX(dts));

            return  (SoundParameter[]) soundParams.toArray();
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
            return new ArrayList<>();
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
            synchronized ("mPictureProfileLock") {    //TODO: Change to lock
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
            synchronized ("mSoundProfileLock") {    //TODO: Change to lock
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
