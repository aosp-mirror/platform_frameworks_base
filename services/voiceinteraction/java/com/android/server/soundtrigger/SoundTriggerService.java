/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.soundtrigger;

import static android.Manifest.permission.BIND_SOUND_TRIGGER_DETECTION_SERVICE;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.GET_SERVICES;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_ERROR;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_OK;
import static android.provider.Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY;
import static android.provider.Settings.Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.SoundModel;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.soundtrigger.ISoundTriggerDetectionService;
import android.media.soundtrigger.ISoundTriggerDetectionServiceClient;
import android.media.soundtrigger.SoundTriggerDetectionService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ISoundTriggerService;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A single SystemService to manage all sound/voice-based sound models on the DSP.
 * This services provides apis to manage sound trigger-based sound models via
 * the ISoundTriggerService interface. This class also publishes a local interface encapsulating
 * the functionality provided by {@link SoundTriggerHelper} for use by
 * {@link VoiceInteractionManagerService}.
 *
 * @hide
 */
public class SoundTriggerService extends SystemService {
    private static final String TAG = "SoundTriggerService";
    private static final boolean DEBUG = true;

    final Context mContext;
    private Object mLock;
    private final SoundTriggerServiceStub mServiceStub;
    private final LocalSoundTriggerService mLocalSoundTriggerService;
    private SoundTriggerDbHelper mDbHelper;
    private SoundTriggerHelper mSoundTriggerHelper;
    private final TreeMap<UUID, SoundModel> mLoadedModels;
    private Object mCallbacksLock;
    private final TreeMap<UUID, IRecognitionStatusCallback> mCallbacks;

    class SoundModelStatTracker {
        private class SoundModelStat {
            SoundModelStat() {
                mStartCount = 0;
                mTotalTimeMsec = 0;
                mLastStartTimestampMsec = 0;
                mLastStopTimestampMsec = 0;
                mIsStarted = false;
            }
            long mStartCount; // Number of times that given model started
            long mTotalTimeMsec; // Total time (msec) that given model was running since boot
            long mLastStartTimestampMsec; // SystemClock.elapsedRealtime model was last started
            long mLastStopTimestampMsec; // SystemClock.elapsedRealtime model was last stopped
            boolean mIsStarted; // true if model is currently running
        }
        private final TreeMap<UUID, SoundModelStat> mModelStats;

        SoundModelStatTracker() {
            mModelStats = new TreeMap<UUID, SoundModelStat>();
        }

        public synchronized void onStart(UUID id) {
            SoundModelStat stat = mModelStats.get(id);
            if (stat == null) {
                stat = new SoundModelStat();
                mModelStats.put(id, stat);
            }

            if (stat.mIsStarted) {
                Slog.e(TAG, "error onStart(): Model " + id + " already started");
                return;
            }

            stat.mStartCount++;
            stat.mLastStartTimestampMsec = SystemClock.elapsedRealtime();
            stat.mIsStarted = true;
        }

        public synchronized void onStop(UUID id) {
            SoundModelStat stat = mModelStats.get(id);
            if (stat == null) {
                Slog.e(TAG, "error onStop(): Model " + id + " has no stats available");
                return;
            }

            if (!stat.mIsStarted) {
                Slog.e(TAG, "error onStop(): Model " + id + " already stopped");
                return;
            }

            stat.mLastStopTimestampMsec = SystemClock.elapsedRealtime();
            stat.mTotalTimeMsec += stat.mLastStopTimestampMsec - stat.mLastStartTimestampMsec;
            stat.mIsStarted = false;
        }

        public synchronized void dump(PrintWriter pw) {
            long curTime = SystemClock.elapsedRealtime();
            pw.println("Model Stats:");
            for (Map.Entry<UUID, SoundModelStat> entry : mModelStats.entrySet()) {
                UUID uuid = entry.getKey();
                SoundModelStat stat = entry.getValue();
                long totalTimeMsec = stat.mTotalTimeMsec;
                if (stat.mIsStarted) {
                    totalTimeMsec += curTime - stat.mLastStartTimestampMsec;
                }
                pw.println(uuid + ", total_time(msec)=" + totalTimeMsec
                        + ", total_count=" + stat.mStartCount
                        + ", last_start=" + stat.mLastStartTimestampMsec
                        + ", last_stop=" + stat.mLastStopTimestampMsec);
            }
        }
    }

    private final SoundModelStatTracker mSoundModelStatTracker;
    /** Number of ops run by the {@link RemoteSoundTriggerDetectionService} per package name */
    @GuardedBy("mLock")
    private final ArrayMap<String, NumOps> mNumOpsPerPackage = new ArrayMap<>();

    public SoundTriggerService(Context context) {
        super(context);
        mContext = context;
        mServiceStub = new SoundTriggerServiceStub();
        mLocalSoundTriggerService = new LocalSoundTriggerService(context);
        mLoadedModels = new TreeMap<UUID, SoundModel>();
        mCallbacksLock = new Object();
        mCallbacks = new TreeMap<>();
        mLock = new Object();
        mSoundModelStatTracker = new SoundModelStatTracker();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SOUND_TRIGGER_SERVICE, mServiceStub);
        publishLocalService(SoundTriggerInternal.class, mLocalSoundTriggerService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            initSoundTriggerHelper();
            mLocalSoundTriggerService.setSoundTriggerHelper(mSoundTriggerHelper);
        } else if (PHASE_THIRD_PARTY_APPS_CAN_START == phase) {
            mDbHelper = new SoundTriggerDbHelper(mContext);
        }
    }

    @Override
    public void onStartUser(int userHandle) {
    }

    @Override
    public void onSwitchUser(int userHandle) {
    }

    private synchronized void initSoundTriggerHelper() {
        if (mSoundTriggerHelper == null) {
            mSoundTriggerHelper = new SoundTriggerHelper(mContext);
        }
    }

    private synchronized boolean isInitialized() {
        if (mSoundTriggerHelper == null ) {
            Slog.e(TAG, "SoundTriggerHelper not initialized.");
            return false;
        }
        return true;
    }

    class SoundTriggerServiceStub extends ISoundTriggerService.Stub {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                // The activity manager only throws security exceptions, so let's
                // log all others.
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(TAG, "SoundTriggerService Crash", e);
                }
                throw e;
            }
        }

        @Override
        public int startRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback,
                RecognitionConfig config) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (DEBUG) {
                Slog.i(TAG, "startRecognition(): Uuid : " + parcelUuid);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("startRecognition(): Uuid : "
                    + parcelUuid));

            GenericSoundModel model = getSoundModel(parcelUuid);
            if (model == null) {
                Slog.e(TAG, "Null model in database for id: " + parcelUuid);

                sEventLogger.log(new SoundTriggerLogger.StringEvent(
                        "startRecognition(): Null model in database for id: " + parcelUuid));

                return STATUS_ERROR;
            }

            int ret = mSoundTriggerHelper.startGenericRecognition(parcelUuid.getUuid(), model,
                    callback, config);
            if (ret == STATUS_OK) {
                mSoundModelStatTracker.onStart(parcelUuid.getUuid());
            }
            return ret;
        }

        @Override
        public int stopRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "stopRecognition(): Uuid : " + parcelUuid);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("stopRecognition(): Uuid : "
                    + parcelUuid));

            if (!isInitialized()) return STATUS_ERROR;

            int ret = mSoundTriggerHelper.stopGenericRecognition(parcelUuid.getUuid(), callback);
            if (ret == STATUS_OK) {
                mSoundModelStatTracker.onStop(parcelUuid.getUuid());
            }
            return ret;
        }

        @Override
        public SoundTrigger.GenericSoundModel getSoundModel(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "getSoundModel(): id = " + soundModelId);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("getSoundModel(): id = "
                    + soundModelId));

            SoundTrigger.GenericSoundModel model = mDbHelper.getGenericSoundModel(
                    soundModelId.getUuid());
            return model;
        }

        @Override
        public void updateSoundModel(SoundTrigger.GenericSoundModel soundModel) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "updateSoundModel(): model = " + soundModel);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("updateSoundModel(): model = "
                    + soundModel));

            mDbHelper.updateGenericSoundModel(soundModel);
        }

        @Override
        public void deleteSoundModel(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "deleteSoundModel(): id = " + soundModelId);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("deleteSoundModel(): id = "
                    + soundModelId));

            // Unload the model if it is loaded.
            mSoundTriggerHelper.unloadGenericSoundModel(soundModelId.getUuid());
            mDbHelper.deleteGenericSoundModel(soundModelId.getUuid());

            // Stop recognition if it is started.
            mSoundModelStatTracker.onStop(soundModelId.getUuid());
        }

        @Override
        public int loadGenericSoundModel(GenericSoundModel soundModel) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (soundModel == null || soundModel.uuid == null) {
                Slog.e(TAG, "Invalid sound model");

                sEventLogger.log(new SoundTriggerLogger.StringEvent(
                        "loadGenericSoundModel(): Invalid sound model"));

                return STATUS_ERROR;
            }
            if (DEBUG) {
                Slog.i(TAG, "loadGenericSoundModel(): id = " + soundModel.uuid);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("loadGenericSoundModel(): id = "
                    + soundModel.uuid));

            synchronized (mLock) {
                SoundModel oldModel = mLoadedModels.get(soundModel.uuid);
                // If the model we're loading is actually different than what we had loaded, we
                // should unload that other model now. We don't care about return codes since we
                // don't know if the other model is loaded.
                if (oldModel != null && !oldModel.equals(soundModel)) {
                    mSoundTriggerHelper.unloadGenericSoundModel(soundModel.uuid);
                    synchronized (mCallbacksLock) {
                        mCallbacks.remove(soundModel.uuid);
                    }
                }
                mLoadedModels.put(soundModel.uuid, soundModel);
            }
            return STATUS_OK;
        }

        @Override
        public int loadKeyphraseSoundModel(KeyphraseSoundModel soundModel) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (soundModel == null || soundModel.uuid == null) {
                Slog.e(TAG, "Invalid sound model");

                sEventLogger.log(new SoundTriggerLogger.StringEvent(
                        "loadKeyphraseSoundModel(): Invalid sound model"));

                return STATUS_ERROR;
            }
            if (soundModel.keyphrases == null || soundModel.keyphrases.length != 1) {
                Slog.e(TAG, "Only one keyphrase per model is currently supported.");

                sEventLogger.log(new SoundTriggerLogger.StringEvent(
                        "loadKeyphraseSoundModel(): Only one keyphrase per model"
                        + " is currently supported."));

                return STATUS_ERROR;
            }
            if (DEBUG) {
                Slog.i(TAG, "loadKeyphraseSoundModel(): id = " + soundModel.uuid);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("loadKeyphraseSoundModel(): id = "
                    + soundModel.uuid));

            synchronized (mLock) {
                SoundModel oldModel = mLoadedModels.get(soundModel.uuid);
                // If the model we're loading is actually different than what we had loaded, we
                // should unload that other model now. We don't care about return codes since we
                // don't know if the other model is loaded.
                if (oldModel != null && !oldModel.equals(soundModel)) {
                    mSoundTriggerHelper.unloadKeyphraseSoundModel(soundModel.keyphrases[0].id);
                    synchronized (mCallbacksLock) {
                        mCallbacks.remove(soundModel.uuid);
                    }
                }
                mLoadedModels.put(soundModel.uuid, soundModel);
            }
            return STATUS_OK;
        }

        @Override
        public int startRecognitionForService(ParcelUuid soundModelId, Bundle params,
            ComponentName detectionService, SoundTrigger.RecognitionConfig config) {
            Preconditions.checkNotNull(soundModelId);
            Preconditions.checkNotNull(detectionService);
            Preconditions.checkNotNull(config);

            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);

            if (!isInitialized()) return STATUS_ERROR;
            if (DEBUG) {
                Slog.i(TAG, "startRecognition(): id = " + soundModelId);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent(
                    "startRecognitionForService(): id = " + soundModelId));

            IRecognitionStatusCallback callback =
                    new RemoteSoundTriggerDetectionService(soundModelId.getUuid(), params,
                            detectionService, Binder.getCallingUserHandle(), config);

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                            "startRecognitionForService():" + soundModelId + " is not loaded"));

                    return STATUS_ERROR;
                }
                IRecognitionStatusCallback existingCallback = null;
                synchronized (mCallbacksLock) {
                    existingCallback = mCallbacks.get(soundModelId.getUuid());
                }
                if (existingCallback != null) {
                    Slog.e(TAG, soundModelId + " is already running");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                            "startRecognitionForService():"
                            + soundModelId + " is already running"));

                    return STATUS_ERROR;
                }
                int ret;
                switch (soundModel.type) {
                    case SoundModel.TYPE_GENERIC_SOUND:
                        ret = mSoundTriggerHelper.startGenericRecognition(soundModel.uuid,
                            (GenericSoundModel) soundModel, callback, config);
                        break;
                    default:
                        Slog.e(TAG, "Unknown model type");

                        sEventLogger.log(new SoundTriggerLogger.StringEvent(
                                "startRecognitionForService(): Unknown model type"));

                        return STATUS_ERROR;
                }

                if (ret != STATUS_OK) {
                    Slog.e(TAG, "Failed to start model: " + ret);

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                            "startRecognitionForService(): Failed to start model:"));

                    return ret;
                }
                synchronized (mCallbacksLock) {
                    mCallbacks.put(soundModelId.getUuid(), callback);
                }

                mSoundModelStatTracker.onStart(soundModelId.getUuid());
            }
            return STATUS_OK;
        }

        @Override
        public int stopRecognitionForService(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (DEBUG) {
                Slog.i(TAG, "stopRecognition(): id = " + soundModelId);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent(
                    "stopRecognitionForService(): id = " + soundModelId));

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                            "stopRecognitionForService(): " + soundModelId
                            + " is not loaded"));

                    return STATUS_ERROR;
                }
                IRecognitionStatusCallback callback = null;
                synchronized (mCallbacksLock) {
                     callback = mCallbacks.get(soundModelId.getUuid());
                }
                if (callback == null) {
                    Slog.e(TAG, soundModelId + " is not running");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                            "stopRecognitionForService(): " + soundModelId
                            + " is not running"));

                    return STATUS_ERROR;
                }
                int ret;
                switch (soundModel.type) {
                    case SoundModel.TYPE_GENERIC_SOUND:
                        ret = mSoundTriggerHelper.stopGenericRecognition(soundModel.uuid, callback);
                        break;
                    default:
                        Slog.e(TAG, "Unknown model type");

                        sEventLogger.log(new SoundTriggerLogger.StringEvent(
                                "stopRecognitionForService(): Unknown model type"));

                        return STATUS_ERROR;
                }

                if (ret != STATUS_OK) {
                    Slog.e(TAG, "Failed to stop model: " + ret);

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                                "stopRecognitionForService(): Failed to stop model: " + ret));

                    return ret;
                }
                synchronized (mCallbacksLock) {
                    mCallbacks.remove(soundModelId.getUuid());
                }

                mSoundModelStatTracker.onStop(soundModelId.getUuid());
            }
            return STATUS_OK;
        }

        @Override
        public int unloadSoundModel(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (DEBUG) {
                Slog.i(TAG, "unloadSoundModel(): id = " + soundModelId);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("unloadSoundModel(): id = "
                    + soundModelId));

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                                "unloadSoundModel(): " + soundModelId + " is not loaded"));

                    return STATUS_ERROR;
                }
                int ret;
                switch (soundModel.type) {
                    case SoundModel.TYPE_KEYPHRASE:
                        ret = mSoundTriggerHelper.unloadKeyphraseSoundModel(
                                ((KeyphraseSoundModel)soundModel).keyphrases[0].id);
                        break;
                    case SoundModel.TYPE_GENERIC_SOUND:
                        ret = mSoundTriggerHelper.unloadGenericSoundModel(soundModel.uuid);
                        break;
                    default:
                        Slog.e(TAG, "Unknown model type");

                        sEventLogger.log(new SoundTriggerLogger.StringEvent(
                                "unloadSoundModel(): Unknown model type"));

                        return STATUS_ERROR;
                }
                if (ret != STATUS_OK) {
                    Slog.e(TAG, "Failed to unload model");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(
                            "unloadSoundModel(): Failed to unload model"));

                    return ret;
                }
                mLoadedModels.remove(soundModelId.getUuid());
                return STATUS_OK;
            }
        }

        @Override
        public boolean isRecognitionActive(ParcelUuid parcelUuid) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return false;
            synchronized (mCallbacksLock) {
                IRecognitionStatusCallback callback = mCallbacks.get(parcelUuid.getUuid());
                if (callback == null) {
                    return false;
                }
            }
            return mSoundTriggerHelper.isRecognitionRequested(parcelUuid.getUuid());
        }

        @Override
        public int getModelState(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            int ret = STATUS_ERROR;
            if (!isInitialized()) return ret;
            if (DEBUG) {
                Slog.i(TAG, "getModelState(): id = " + soundModelId);
            }

            sEventLogger.log(new SoundTriggerLogger.StringEvent("getModelState(): id = "
                    + soundModelId));

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent("getModelState(): "
                            + soundModelId + " is not loaded"));

                    return ret;
                }
                switch (soundModel.type) {
                    case SoundModel.TYPE_GENERIC_SOUND:
                        ret = mSoundTriggerHelper.getGenericModelState(soundModel.uuid);
                        break;
                    default:
                        // SoundModel.TYPE_KEYPHRASE is not supported to increase privacy.
                        Slog.e(TAG, "Unsupported model type, " + soundModel.type);
                        sEventLogger.log(new SoundTriggerLogger.StringEvent(
                                "getModelState(): Unsupported model type, "
                                + soundModel.type));
                        break;
                }

                return ret;
            }
        }
    }

    /**
     * Counts the number of operations added in the last 24 hours.
     */
    private static class NumOps {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private int[] mNumOps = new int[24];
        @GuardedBy("mLock")
        private long mLastOpsHourSinceBoot;

        /**
         * Clear buckets of new hours that have elapsed since last operation.
         *
         * <p>I.e. when the last operation was triggered at 1:40 and the current operation was
         * triggered at 4:03, the buckets "2, 3, and 4" are cleared.
         *
         * @param currentTime Current elapsed time since boot in ns
         */
        void clearOldOps(long currentTime) {
            synchronized (mLock) {
                long numHoursSinceBoot = TimeUnit.HOURS.convert(currentTime, TimeUnit.NANOSECONDS);

                // Clear buckets of new hours that have elapsed since last operation
                // I.e. when the last operation was triggered at 1:40 and the current
                // operation was triggered at 4:03, the bucket "2, 3, and 4" is cleared
                if (mLastOpsHourSinceBoot != 0) {
                    for (long hour = mLastOpsHourSinceBoot + 1; hour <= numHoursSinceBoot; hour++) {
                        mNumOps[(int) (hour % 24)] = 0;
                    }
                }
            }
        }

        /**
         * Add a new operation.
         *
         * @param currentTime Current elapsed time since boot in ns
         */
        void addOp(long currentTime) {
            synchronized (mLock) {
                long numHoursSinceBoot = TimeUnit.HOURS.convert(currentTime, TimeUnit.NANOSECONDS);

                mNumOps[(int) (numHoursSinceBoot % 24)]++;
                mLastOpsHourSinceBoot = numHoursSinceBoot;
            }
        }

        /**
         * Get the total operations added in the last 24 hours.
         *
         * @return The total number of operations added in the last 24 hours
         */
        int getOpsAdded() {
            synchronized (mLock) {
                int totalOperationsInLastDay = 0;
                for (int i = 0; i < 24; i++) {
                    totalOperationsInLastDay += mNumOps[i];
                }

                return totalOperationsInLastDay;
            }
        }
    }

    /**
     * A single operation run in a {@link RemoteSoundTriggerDetectionService}.
     *
     * <p>Once the remote service is connected either setup + execute or setup + stop is executed.
     */
    private static class Operation {
        private interface ExecuteOp {
            void run(int opId, ISoundTriggerDetectionService service) throws RemoteException;
        }

        private final @Nullable Runnable mSetupOp;
        private final @NonNull ExecuteOp mExecuteOp;
        private final @Nullable Runnable mDropOp;

        private Operation(@Nullable Runnable setupOp, @NonNull ExecuteOp executeOp,
                @Nullable Runnable cancelOp) {
            mSetupOp = setupOp;
            mExecuteOp = executeOp;
            mDropOp = cancelOp;
        }

        private void setup() {
            if (mSetupOp != null) {
                mSetupOp.run();
            }
        }

        void run(int opId, @NonNull ISoundTriggerDetectionService service) throws RemoteException {
            setup();
            mExecuteOp.run(opId, service);
        }

        void drop() {
            setup();

            if (mDropOp != null) {
                mDropOp.run();
            }
        }
    }

    /**
     * Local end for a {@link SoundTriggerDetectionService}. Operations are queued up and executed
     * when the service connects.
     *
     * <p>If operations take too long they are forcefully aborted.
     *
     * <p>This also limits the amount of operations in 24 hours.
     */
    private class RemoteSoundTriggerDetectionService
        extends IRecognitionStatusCallback.Stub implements ServiceConnection {
        private static final int MSG_STOP_ALL_PENDING_OPERATIONS = 1;

        private final Object mRemoteServiceLock = new Object();

        /** UUID of the model the service is started for */
        private final @NonNull ParcelUuid mPuuid;
        /** Params passed into the start method for the service */
        private final @Nullable Bundle mParams;
        /** Component name passed when starting the service */
        private final @NonNull ComponentName mServiceName;
        /** User that started the service */
        private final @NonNull UserHandle mUser;
        /** Configuration of the recognition the service is handling */
        private final @NonNull RecognitionConfig mRecognitionConfig;
        /** Wake lock keeping the remote service alive */
        private final @NonNull PowerManager.WakeLock mRemoteServiceWakeLock;

        private final @NonNull Handler mHandler;

        /** Callbacks that are called by the service */
        private final @NonNull ISoundTriggerDetectionServiceClient mClient;

        /** Operations that are pending because the service is not yet connected */
        @GuardedBy("mRemoteServiceLock")
        private final ArrayList<Operation> mPendingOps = new ArrayList<>();
        /** Operations that have been send to the service but have no yet finished */
        @GuardedBy("mRemoteServiceLock")
        private final ArraySet<Integer> mRunningOpIds = new ArraySet<>();
        /** The number of operations executed in each of the last 24 hours */
        private final NumOps mNumOps;

        /** The service binder if connected */
        @GuardedBy("mRemoteServiceLock")
        private @Nullable ISoundTriggerDetectionService mService;
        /** Whether the service has been bound */
        @GuardedBy("mRemoteServiceLock")
        private boolean mIsBound;
        /** Whether the service has been destroyed */
        @GuardedBy("mRemoteServiceLock")
        private boolean mIsDestroyed;
        /**
         * Set once a final op is scheduled. No further ops can be added and the service is
         * destroyed once the op finishes.
         */
        @GuardedBy("mRemoteServiceLock")
        private boolean mDestroyOnceRunningOpsDone;

        /** Total number of operations performed by this service */
        @GuardedBy("mRemoteServiceLock")
        private int mNumTotalOpsPerformed;

        /**
         * Create a new remote sound trigger detection service. This only binds to the service when
         * operations are in flight. Each operation has a certain time it can run. Once no
         * operations are allowed to run anymore, {@link #stopAllPendingOperations() all operations
         * are aborted and stopped} and the service is disconnected.
         *
         * @param modelUuid The UUID of the model the recognition is for
         * @param params The params passed to each method of the service
         * @param serviceName The component name of the service
         * @param user The user of the service
         * @param config The configuration of the recognition
         */
        public RemoteSoundTriggerDetectionService(@NonNull UUID modelUuid,
            @Nullable Bundle params, @NonNull ComponentName serviceName, @NonNull UserHandle user,
            @NonNull RecognitionConfig config) {
            mPuuid = new ParcelUuid(modelUuid);
            mParams = params;
            mServiceName = serviceName;
            mUser = user;
            mRecognitionConfig = config;
            mHandler = new Handler(Looper.getMainLooper());

            PowerManager pm = ((PowerManager) mContext.getSystemService(Context.POWER_SERVICE));
            mRemoteServiceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "RemoteSoundTriggerDetectionService " + mServiceName.getPackageName() + ":"
                            + mServiceName.getClassName());

            synchronized (mLock) {
                NumOps numOps = mNumOpsPerPackage.get(mServiceName.getPackageName());
                if (numOps == null) {
                    numOps = new NumOps();
                    mNumOpsPerPackage.put(mServiceName.getPackageName(), numOps);
                }
                mNumOps = numOps;
            }

            mClient = new ISoundTriggerDetectionServiceClient.Stub() {
                @Override
                public void onOpFinished(int opId) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (mRemoteServiceLock) {
                            mRunningOpIds.remove(opId);

                            if (mRunningOpIds.isEmpty() && mPendingOps.isEmpty()) {
                                if (mDestroyOnceRunningOpsDone) {
                                    destroy();
                                } else {
                                    disconnectLocked();
                                }
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };
        }

        @Override
        public boolean pingBinder() {
            return !(mIsDestroyed || mDestroyOnceRunningOpsDone);
        }

        /**
         * Disconnect from the service, but allow to re-connect when new operations are triggered.
         */
        @GuardedBy("mRemoteServiceLock")
        private void disconnectLocked() {
            if (mService != null) {
                try {
                    mService.removeClient(mPuuid);
                } catch (Exception e) {
                    Slog.e(TAG, mPuuid + ": Cannot remove client", e);

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                            + ": Cannot remove client"));

                }

                mService = null;
            }

            if (mIsBound) {
                mContext.unbindService(RemoteSoundTriggerDetectionService.this);
                mIsBound = false;

                synchronized (mCallbacksLock) {
                    mRemoteServiceWakeLock.release();
                }
            }
        }

        /**
         * Disconnect, do not allow to reconnect to the service. All further operations will be
         * dropped.
         */
        private void destroy() {
            if (DEBUG) Slog.v(TAG, mPuuid + ": destroy");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid + ": destroy"));

            synchronized (mRemoteServiceLock) {
                disconnectLocked();

                mIsDestroyed = true;
            }

            // The callback is removed before the flag is set
            if (!mDestroyOnceRunningOpsDone) {
                synchronized (mCallbacksLock) {
                    mCallbacks.remove(mPuuid.getUuid());
                }
            }
        }

        /**
         * Stop all pending operations and then disconnect for the service.
         */
        private void stopAllPendingOperations() {
            synchronized (mRemoteServiceLock) {
                if (mIsDestroyed) {
                    return;
                }

                if (mService != null) {
                    int numOps = mRunningOpIds.size();
                    for (int i = 0; i < numOps; i++) {
                        try {
                            mService.onStopOperation(mPuuid, mRunningOpIds.valueAt(i));
                        } catch (Exception e) {
                            Slog.e(TAG, mPuuid + ": Could not stop operation "
                                    + mRunningOpIds.valueAt(i), e);

                            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                                    + ": Could not stop operation " + mRunningOpIds.valueAt(i)));

                        }
                    }

                    mRunningOpIds.clear();
                }

                disconnectLocked();
            }
        }

        /**
         * Verify that the service has the expected properties and then bind to the service
         */
        private void bind() {
            long token = Binder.clearCallingIdentity();
            try {
                Intent i = new Intent();
                i.setComponent(mServiceName);

                ResolveInfo ri = mContext.getPackageManager().resolveServiceAsUser(i,
                        GET_SERVICES | GET_META_DATA | MATCH_DEBUG_TRIAGED_MISSING,
                        mUser.getIdentifier());

                if (ri == null) {
                    Slog.w(TAG, mPuuid + ": " + mServiceName + " not found");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                            + ": " + mServiceName + " not found"));

                    return;
                }

                if (!BIND_SOUND_TRIGGER_DETECTION_SERVICE
                        .equals(ri.serviceInfo.permission)) {
                    Slog.w(TAG, mPuuid + ": " + mServiceName + " does not require "
                            + BIND_SOUND_TRIGGER_DETECTION_SERVICE);

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                            + ": " + mServiceName + " does not require "
                            + BIND_SOUND_TRIGGER_DETECTION_SERVICE));

                    return;
                }

                mIsBound = mContext.bindServiceAsUser(i, this,
                        BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE, mUser);

                if (mIsBound) {
                    mRemoteServiceWakeLock.acquire();
                } else {
                    Slog.w(TAG, mPuuid + ": Could not bind to " + mServiceName);

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                            + ": Could not bind to " + mServiceName));

                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Run an operation (i.e. send it do the service). If the service is not connected, this
         * binds the service and then runs the operation once connected.
         *
         * @param op The operation to run
         */
        private void runOrAddOperation(Operation op) {
            synchronized (mRemoteServiceLock) {
                if (mIsDestroyed || mDestroyOnceRunningOpsDone) {
                    Slog.w(TAG, mPuuid + ": Dropped operation as already destroyed or marked for "
                            + "destruction");

                    sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                            + ":Dropped operation as already destroyed or marked for destruction"));

                    op.drop();
                    return;
                }

                if (mService == null) {
                    mPendingOps.add(op);

                    if (!mIsBound) {
                        bind();
                    }
                } else {
                    long currentTime = System.nanoTime();
                    mNumOps.clearOldOps(currentTime);

                    // Drop operation if too many were executed in the last 24 hours.
                    int opsAllowed = Settings.Global.getInt(mContext.getContentResolver(),
                            MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                            Integer.MAX_VALUE);

                    // As we currently cannot dropping an op safely, disable throttling
                    int opsAdded = mNumOps.getOpsAdded();
                    if (false && mNumOps.getOpsAdded() >= opsAllowed) {
                        try {
                            if (DEBUG || opsAllowed + 10 > opsAdded) {
                                Slog.w(TAG, mPuuid + ": Dropped operation as too many operations "
                                        + "were run in last 24 hours");

                                sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                                        + ": Dropped operation as too many operations "
                                        + "were run in last 24 hours"));

                            }

                            op.drop();
                        } catch (Exception e) {
                            Slog.e(TAG, mPuuid + ": Could not drop operation", e);

                            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                                        + ": Could not drop operation"));

                        }
                    } else {
                        mNumOps.addOp(currentTime);

                        // Find a free opID
                        int opId = mNumTotalOpsPerformed;
                        do {
                            mNumTotalOpsPerformed++;
                        } while (mRunningOpIds.contains(opId));

                        // Run OP
                        try {
                            if (DEBUG) Slog.v(TAG, mPuuid + ": runOp " + opId);

                            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                                        + ": runOp " + opId));

                            op.run(opId, mService);
                            mRunningOpIds.add(opId);
                        } catch (Exception e) {
                            Slog.e(TAG, mPuuid + ": Could not run operation " + opId, e);

                            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                                        + ": Could not run operation " + opId));

                        }
                    }

                    // Unbind from service if no operations are left (i.e. if the operation failed)
                    if (mPendingOps.isEmpty() && mRunningOpIds.isEmpty()) {
                        if (mDestroyOnceRunningOpsDone) {
                            destroy();
                        } else {
                            disconnectLocked();
                        }
                    } else {
                        mHandler.removeMessages(MSG_STOP_ALL_PENDING_OPERATIONS);
                        mHandler.sendMessageDelayed(obtainMessage(
                                RemoteSoundTriggerDetectionService::stopAllPendingOperations, this)
                                        .setWhat(MSG_STOP_ALL_PENDING_OPERATIONS),
                                Settings.Global.getLong(mContext.getContentResolver(),
                                        SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT,
                                        Long.MAX_VALUE));
                    }
                }
            }
        }

        @Override
        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent event) {
            Slog.w(TAG, mPuuid + "->" + mServiceName + ": IGNORED onKeyphraseDetected(" + event
                    + ")");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid + "->" + mServiceName
                    + ": IGNORED onKeyphraseDetected(" + event + ")"));

        }

        /**
         * Create an AudioRecord enough for starting and releasing the data buffered for the event.
         *
         * @param event The event that was received
         * @return The initialized AudioRecord
         */
        private @NonNull AudioRecord createAudioRecordForEvent(
                @NonNull SoundTrigger.GenericRecognitionEvent event) {
            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
            attributesBuilder.setInternalCapturePreset(MediaRecorder.AudioSource.HOTWORD);
            AudioAttributes attributes = attributesBuilder.build();

            // Use same AudioFormat processing as in RecognitionEvent.fromParcel
            AudioFormat originalFormat = event.getCaptureFormat();
            AudioFormat captureFormat = (new AudioFormat.Builder())
                    .setChannelMask(originalFormat.getChannelMask())
                    .setEncoding(originalFormat.getEncoding())
                    .setSampleRate(originalFormat.getSampleRate())
                    .build();

            int bufferSize = AudioRecord.getMinBufferSize(
                    captureFormat.getSampleRate() == AudioFormat.SAMPLE_RATE_UNSPECIFIED
                            ? AudioFormat.SAMPLE_RATE_HZ_MAX
                            : captureFormat.getSampleRate(),
                    captureFormat.getChannelCount() == 2
                            ? AudioFormat.CHANNEL_IN_STEREO
                            : AudioFormat.CHANNEL_IN_MONO,
                    captureFormat.getEncoding());

            sEventLogger.log(new SoundTriggerLogger.StringEvent("createAudioRecordForEvent"));

            return new AudioRecord(attributes, captureFormat, bufferSize,
                    event.getCaptureSession());
        }

        @Override
        public void onGenericSoundTriggerDetected(SoundTrigger.GenericRecognitionEvent event) {
            if (DEBUG) Slog.v(TAG, mPuuid + ": Generic sound trigger event: " + event);

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + ": Generic sound trigger event: " + event));

            runOrAddOperation(new Operation(
                    // always execute:
                    () -> {
                        if (!mRecognitionConfig.allowMultipleTriggers) {
                            // Unregister this remoteService once op is done
                            synchronized (mCallbacksLock) {
                                mCallbacks.remove(mPuuid.getUuid());
                            }
                            mDestroyOnceRunningOpsDone = true;
                        }
                    },
                    // execute if not throttled:
                    (opId, service) -> service.onGenericRecognitionEvent(mPuuid, opId, event),
                    // execute if throttled:
                    () -> {
                        if (event.isCaptureAvailable()) {
                            AudioRecord capturedData = createAudioRecordForEvent(event);

                            // Currently we need to start and release the audio record to reset
                            // the DSP even if we don't want to process the event
                            capturedData.startRecording();
                            capturedData.release();
                        }
                    }));
        }

        @Override
        public void onError(int status) {
            if (DEBUG) Slog.v(TAG, mPuuid + ": onError: " + status);

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + ": onError: " + status));

            runOrAddOperation(
                    new Operation(
                            // always execute:
                            () -> {
                                // Unregister this remoteService once op is done
                                synchronized (mCallbacksLock) {
                                    mCallbacks.remove(mPuuid.getUuid());
                                }
                                mDestroyOnceRunningOpsDone = true;
                            },
                            // execute if not throttled:
                            (opId, service) -> service.onError(mPuuid, opId, status),
                            // nothing to do if throttled
                            null));
        }

        @Override
        public void onRecognitionPaused() {
            Slog.i(TAG, mPuuid + "->" + mServiceName + ": IGNORED onRecognitionPaused");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + "->" + mServiceName + ": IGNORED onRecognitionPaused"));

        }

        @Override
        public void onRecognitionResumed() {
            Slog.i(TAG, mPuuid + "->" + mServiceName + ": IGNORED onRecognitionResumed");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + "->" + mServiceName + ": IGNORED onRecognitionResumed"));

        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Slog.v(TAG, mPuuid + ": onServiceConnected(" + service + ")");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + ": onServiceConnected(" + service + ")"));

            synchronized (mRemoteServiceLock) {
                mService = ISoundTriggerDetectionService.Stub.asInterface(service);

                try {
                    mService.setClient(mPuuid, mParams, mClient);
                } catch (Exception e) {
                    Slog.e(TAG, mPuuid + ": Could not init " + mServiceName, e);
                    return;
                }

                while (!mPendingOps.isEmpty()) {
                    runOrAddOperation(mPendingOps.remove(0));
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Slog.v(TAG, mPuuid + ": onServiceDisconnected");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + ": onServiceDisconnected"));

            synchronized (mRemoteServiceLock) {
                mService = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (DEBUG) Slog.v(TAG, mPuuid + ": onBindingDied");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(mPuuid
                    + ": onBindingDied"));

            synchronized (mRemoteServiceLock) {
                destroy();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Slog.w(TAG, name + " for model " + mPuuid + " returned a null binding");

            sEventLogger.log(new SoundTriggerLogger.StringEvent(name + " for model "
                    + mPuuid + " returned a null binding"));

            synchronized (mRemoteServiceLock) {
                disconnectLocked();
            }
        }
    }

    public final class LocalSoundTriggerService extends SoundTriggerInternal {
        private final Context mContext;
        private SoundTriggerHelper mSoundTriggerHelper;

        LocalSoundTriggerService(Context context) {
            mContext = context;
        }

        synchronized void setSoundTriggerHelper(SoundTriggerHelper helper) {
            mSoundTriggerHelper = helper;
        }

        @Override
        public int startRecognition(int keyphraseId, KeyphraseSoundModel soundModel,
                IRecognitionStatusCallback listener, RecognitionConfig recognitionConfig) {
            if (!isInitialized()) return STATUS_ERROR;
            return mSoundTriggerHelper.startKeyphraseRecognition(keyphraseId, soundModel, listener,
                    recognitionConfig);
        }

        @Override
        public synchronized int stopRecognition(int keyphraseId, IRecognitionStatusCallback listener) {
            if (!isInitialized()) return STATUS_ERROR;
            return mSoundTriggerHelper.stopKeyphraseRecognition(keyphraseId, listener);
        }

        @Override
        public ModuleProperties getModuleProperties() {
            if (!isInitialized()) return null;
            return mSoundTriggerHelper.getModuleProperties();
        }

        @Override
        public int unloadKeyphraseModel(int keyphraseId) {
            if (!isInitialized()) return STATUS_ERROR;
            return mSoundTriggerHelper.unloadKeyphraseSoundModel(keyphraseId);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!isInitialized()) return;
            mSoundTriggerHelper.dump(fd, pw, args);
            // log
            sEventLogger.dump(pw);

            // stats
            mSoundModelStatTracker.dump(pw);
        }

        private synchronized boolean isInitialized() {
            if (mSoundTriggerHelper == null ) {
                Slog.e(TAG, "SoundTriggerHelper not initialized.");

                sEventLogger.log(new SoundTriggerLogger.StringEvent(
                        "SoundTriggerHelper not initialized."));

                return false;
            }
            return true;
        }
    }

    private void enforceCallingPermission(String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold the permission " + permission);
        }
    }

    //=================================================================
    // For logging

    private static final SoundTriggerLogger sEventLogger = new SoundTriggerLogger(200,
            "SoundTrigger activity");

}
