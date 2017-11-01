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
import static android.hardware.soundtrigger.SoundTrigger.STATUS_ERROR;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_OK;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.SoundModel;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.soundtrigger.SoundTriggerManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.internal.app.ISoundTriggerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.TreeMap;
import java.util.UUID;

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
    private final TreeMap<UUID, LocalSoundTriggerRecognitionStatusCallback> mIntentCallbacks;
    private PowerManager.WakeLock mWakelock;

    public SoundTriggerService(Context context) {
        super(context);
        mContext = context;
        mServiceStub = new SoundTriggerServiceStub();
        mLocalSoundTriggerService = new LocalSoundTriggerService(context);
        mLoadedModels = new TreeMap<UUID, SoundModel>();
        mIntentCallbacks = new TreeMap<UUID, LocalSoundTriggerRecognitionStatusCallback>();
        mLock = new Object();
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

            GenericSoundModel model = getSoundModel(parcelUuid);
            if (model == null) {
                Slog.e(TAG, "Null model in database for id: " + parcelUuid);
                return STATUS_ERROR;
            }

            return mSoundTriggerHelper.startGenericRecognition(parcelUuid.getUuid(), model,
                    callback, config);
        }

        @Override
        public int stopRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "stopRecognition(): Uuid : " + parcelUuid);
            }
            if (!isInitialized()) return STATUS_ERROR;
            return mSoundTriggerHelper.stopGenericRecognition(parcelUuid.getUuid(), callback);
        }

        @Override
        public SoundTrigger.GenericSoundModel getSoundModel(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "getSoundModel(): id = " + soundModelId);
            }
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
            mDbHelper.updateGenericSoundModel(soundModel);
        }

        @Override
        public void deleteSoundModel(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "deleteSoundModel(): id = " + soundModelId);
            }
            // Unload the model if it is loaded.
            mSoundTriggerHelper.unloadGenericSoundModel(soundModelId.getUuid());
            mDbHelper.deleteGenericSoundModel(soundModelId.getUuid());
        }

        @Override
        public int loadGenericSoundModel(GenericSoundModel soundModel) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (soundModel == null || soundModel.uuid == null) {
                Slog.e(TAG, "Invalid sound model");
                return STATUS_ERROR;
            }
            if (DEBUG) {
                Slog.i(TAG, "loadGenericSoundModel(): id = " + soundModel.uuid);
            }
            synchronized (mLock) {
                SoundModel oldModel = mLoadedModels.get(soundModel.uuid);
                // If the model we're loading is actually different than what we had loaded, we
                // should unload that other model now. We don't care about return codes since we
                // don't know if the other model is loaded.
                if (oldModel != null && !oldModel.equals(soundModel)) {
                    mSoundTriggerHelper.unloadGenericSoundModel(soundModel.uuid);
                    mIntentCallbacks.remove(soundModel.uuid);
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
                return STATUS_ERROR;
            }
            if (soundModel.keyphrases == null || soundModel.keyphrases.length != 1) {
                Slog.e(TAG, "Only one keyphrase per model is currently supported.");
                return STATUS_ERROR;
            }
            if (DEBUG) {
                Slog.i(TAG, "loadKeyphraseSoundModel(): id = " + soundModel.uuid);
            }
            synchronized (mLock) {
                SoundModel oldModel = mLoadedModels.get(soundModel.uuid);
                // If the model we're loading is actually different than what we had loaded, we
                // should unload that other model now. We don't care about return codes since we
                // don't know if the other model is loaded.
                if (oldModel != null && !oldModel.equals(soundModel)) {
                    mSoundTriggerHelper.unloadKeyphraseSoundModel(soundModel.keyphrases[0].id);
                    mIntentCallbacks.remove(soundModel.uuid);
                }
                mLoadedModels.put(soundModel.uuid, soundModel);
            }
            return STATUS_OK;
        }

        @Override
        public int startRecognitionForIntent(ParcelUuid soundModelId, PendingIntent callbackIntent,
                SoundTrigger.RecognitionConfig config) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (DEBUG) {
                Slog.i(TAG, "startRecognition(): id = " + soundModelId);
            }

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");
                    return STATUS_ERROR;
                }
                LocalSoundTriggerRecognitionStatusCallback callback = mIntentCallbacks.get(
                        soundModelId.getUuid());
                if (callback != null) {
                    Slog.e(TAG, soundModelId + " is already running");
                    return STATUS_ERROR;
                }
                callback = new LocalSoundTriggerRecognitionStatusCallback(soundModelId.getUuid(),
                        callbackIntent, config);
                int ret;
                switch (soundModel.type) {
                    case SoundModel.TYPE_KEYPHRASE: {
                        KeyphraseSoundModel keyphraseSoundModel = (KeyphraseSoundModel) soundModel;
                        ret = mSoundTriggerHelper.startKeyphraseRecognition(
                                keyphraseSoundModel.keyphrases[0].id, keyphraseSoundModel, callback,
                                config);
                    } break;
                    case SoundModel.TYPE_GENERIC_SOUND:
                        ret = mSoundTriggerHelper.startGenericRecognition(soundModel.uuid,
                                (GenericSoundModel) soundModel, callback, config);
                        break;
                    default:
                        Slog.e(TAG, "Unknown model type");
                        return STATUS_ERROR;
                }

                if (ret != STATUS_OK) {
                    Slog.e(TAG, "Failed to start model: " + ret);
                    return ret;
                }
                mIntentCallbacks.put(soundModelId.getUuid(), callback);
            }
            return STATUS_OK;
        }

        @Override
        public int stopRecognitionForIntent(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (!isInitialized()) return STATUS_ERROR;
            if (DEBUG) {
                Slog.i(TAG, "stopRecognition(): id = " + soundModelId);
            }

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");
                    return STATUS_ERROR;
                }
                LocalSoundTriggerRecognitionStatusCallback callback = mIntentCallbacks.get(
                        soundModelId.getUuid());
                if (callback == null) {
                    Slog.e(TAG, soundModelId + " is not running");
                    return STATUS_ERROR;
                }
                int ret;
                switch (soundModel.type) {
                    case SoundModel.TYPE_KEYPHRASE:
                        ret = mSoundTriggerHelper.stopKeyphraseRecognition(
                                ((KeyphraseSoundModel)soundModel).keyphrases[0].id, callback);
                        break;
                    case SoundModel.TYPE_GENERIC_SOUND:
                        ret = mSoundTriggerHelper.stopGenericRecognition(soundModel.uuid, callback);
                        break;
                    default:
                        Slog.e(TAG, "Unknown model type");
                        return STATUS_ERROR;
                }

                if (ret != STATUS_OK) {
                    Slog.e(TAG, "Failed to stop model: " + ret);
                    return ret;
                }
                mIntentCallbacks.remove(soundModelId.getUuid());
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

            synchronized (mLock) {
                SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                if (soundModel == null) {
                    Slog.e(TAG, soundModelId + " is not loaded");
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
                        return STATUS_ERROR;
                }
                if (ret != STATUS_OK) {
                    Slog.e(TAG, "Failed to unload model");
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
            synchronized (mLock) {
                LocalSoundTriggerRecognitionStatusCallback callback =
                        mIntentCallbacks.get(parcelUuid.getUuid());
                if (callback == null) {
                    return false;
                }
                return mSoundTriggerHelper.isRecognitionRequested(parcelUuid.getUuid());
            }
        }
    }

    private final class LocalSoundTriggerRecognitionStatusCallback
            extends IRecognitionStatusCallback.Stub {
        private UUID mUuid;
        private PendingIntent mCallbackIntent;
        private RecognitionConfig mRecognitionConfig;

        public LocalSoundTriggerRecognitionStatusCallback(UUID modelUuid,
                PendingIntent callbackIntent,
                RecognitionConfig config) {
            mUuid = modelUuid;
            mCallbackIntent = callbackIntent;
            mRecognitionConfig = config;
        }

        @Override
        public boolean pingBinder() {
            return mCallbackIntent != null;
        }

        @Override
        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent event) {
            if (mCallbackIntent == null) {
                return;
            }
            grabWakeLock();

            Slog.w(TAG, "Keyphrase sound trigger event: " + event);
            Intent extras = new Intent();
            extras.putExtra(SoundTriggerManager.EXTRA_MESSAGE_TYPE,
                    SoundTriggerManager.FLAG_MESSAGE_TYPE_RECOGNITION_EVENT);
            extras.putExtra(SoundTriggerManager.EXTRA_RECOGNITION_EVENT, event);
            try {
                mCallbackIntent.send(mContext, 0, extras, mCallbackCompletedHandler, null);
                if (!mRecognitionConfig.allowMultipleTriggers) {
                    removeCallback(/*releaseWakeLock=*/false);
                }
            } catch (PendingIntent.CanceledException e) {
                removeCallback(/*releaseWakeLock=*/true);
            }
        }

        @Override
        public void onGenericSoundTriggerDetected(SoundTrigger.GenericRecognitionEvent event) {
            if (mCallbackIntent == null) {
                return;
            }
            grabWakeLock();

            Slog.w(TAG, "Generic sound trigger event: " + event);
            Intent extras = new Intent();
            extras.putExtra(SoundTriggerManager.EXTRA_MESSAGE_TYPE,
                    SoundTriggerManager.FLAG_MESSAGE_TYPE_RECOGNITION_EVENT);
            extras.putExtra(SoundTriggerManager.EXTRA_RECOGNITION_EVENT, event);
            try {
                mCallbackIntent.send(mContext, 0, extras, mCallbackCompletedHandler, null);
                if (!mRecognitionConfig.allowMultipleTriggers) {
                    removeCallback(/*releaseWakeLock=*/false);
                }
            } catch (PendingIntent.CanceledException e) {
                removeCallback(/*releaseWakeLock=*/true);
            }
        }

        @Override
        public void onError(int status) {
            if (mCallbackIntent == null) {
                return;
            }
            grabWakeLock();

            Slog.i(TAG, "onError: " + status);
            Intent extras = new Intent();
            extras.putExtra(SoundTriggerManager.EXTRA_MESSAGE_TYPE,
                    SoundTriggerManager.FLAG_MESSAGE_TYPE_RECOGNITION_ERROR);
            extras.putExtra(SoundTriggerManager.EXTRA_STATUS, status);
            try {
                mCallbackIntent.send(mContext, 0, extras, mCallbackCompletedHandler, null);
                // Remove the callback, but wait for the intent to finish before we let go of the
                // wake lock
                removeCallback(/*releaseWakeLock=*/false);
            } catch (PendingIntent.CanceledException e) {
                removeCallback(/*releaseWakeLock=*/true);
            }
        }

        @Override
        public void onRecognitionPaused() {
            if (mCallbackIntent == null) {
                return;
            }
            grabWakeLock();

            Slog.i(TAG, "onRecognitionPaused");
            Intent extras = new Intent();
            extras.putExtra(SoundTriggerManager.EXTRA_MESSAGE_TYPE,
                    SoundTriggerManager.FLAG_MESSAGE_TYPE_RECOGNITION_PAUSED);
            try {
                mCallbackIntent.send(mContext, 0, extras, mCallbackCompletedHandler, null);
            } catch (PendingIntent.CanceledException e) {
                removeCallback(/*releaseWakeLock=*/true);
            }
        }

        @Override
        public void onRecognitionResumed() {
            if (mCallbackIntent == null) {
                return;
            }
            grabWakeLock();

            Slog.i(TAG, "onRecognitionResumed");
            Intent extras = new Intent();
            extras.putExtra(SoundTriggerManager.EXTRA_MESSAGE_TYPE,
                    SoundTriggerManager.FLAG_MESSAGE_TYPE_RECOGNITION_RESUMED);
            try {
                mCallbackIntent.send(mContext, 0, extras, mCallbackCompletedHandler, null);
            } catch (PendingIntent.CanceledException e) {
                removeCallback(/*releaseWakeLock=*/true);
            }
        }

        private void removeCallback(boolean releaseWakeLock) {
            mCallbackIntent = null;
            synchronized (mLock) {
                mIntentCallbacks.remove(mUuid);
                if (releaseWakeLock) {
                    mWakelock.release();
                }
            }
        }
    }

    private void grabWakeLock() {
        synchronized (mLock) {
            if (mWakelock == null) {
                PowerManager pm = ((PowerManager) mContext.getSystemService(Context.POWER_SERVICE));
                mWakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            }
            mWakelock.acquire();
        }
    }

    private PendingIntent.OnFinished mCallbackCompletedHandler = new PendingIntent.OnFinished() {
        @Override
        public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode,
                String resultData, Bundle resultExtras) {
            // We're only ever invoked when the callback is done, so release the lock.
            synchronized (mLock) {
                mWakelock.release();
            }
        }
    };

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
        }

        private synchronized boolean isInitialized() {
            if (mSoundTriggerHelper == null ) {
                Slog.e(TAG, "SoundTriggerHelper not initialized.");
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
}
