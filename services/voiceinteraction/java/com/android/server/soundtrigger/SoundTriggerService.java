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

import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.internal.app.ISoundTriggerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    static final String TAG = "SoundTriggerService";
    static final boolean DEBUG = false;

    final Context mContext;
    private final SoundTriggerServiceStub mServiceStub;
    private final LocalSoundTriggerService mLocalSoundTriggerService;
    private SoundTriggerDbHelper mDbHelper;

    public SoundTriggerService(Context context) {
        super(context);
        mContext = context;
        mServiceStub = new SoundTriggerServiceStub();
        mLocalSoundTriggerService = new LocalSoundTriggerService(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SOUND_TRIGGER_SERVICE, mServiceStub);
        publishLocalService(SoundTriggerInternal.class, mLocalSoundTriggerService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mLocalSoundTriggerService.initSoundTriggerHelper();
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
        public void startRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "startRecognition(): Uuid : " + parcelUuid);
            }
        }

        @Override
        public void stopRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "stopRecognition(): Uuid : " + parcelUuid);
            }
        }

        @Override
        public SoundTrigger.GenericSoundModel getSoundModel(ParcelUuid soundModelId) {
            enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
            if (DEBUG) {
                Slog.i(TAG, "getSoundModel(): id = " + soundModelId);
            }
            SoundTrigger.GenericSoundModel model = mDbHelper.getGenericSoundModel(soundModelId.getUuid());
            if (model == null) {
                Slog.e(TAG, "Null model in database.");
            }
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
            mDbHelper.deleteGenericSoundModel(soundModelId.getUuid());
        }
    }

    public final class LocalSoundTriggerService extends SoundTriggerInternal {
        private final Context mContext;
        private SoundTriggerHelper mSoundTriggerHelper;

        LocalSoundTriggerService(Context context) {
            mContext = context;
        }

        void initSoundTriggerHelper() {
            if (mSoundTriggerHelper == null) {
                mSoundTriggerHelper = new SoundTriggerHelper(mContext);
            }
        }

        @Override
        public int startRecognition(int keyphraseId, KeyphraseSoundModel soundModel,
                IRecognitionStatusCallback listener, RecognitionConfig recognitionConfig) {
            return mSoundTriggerHelper.startRecognition(keyphraseId, soundModel, listener,
                    recognitionConfig);
        }

        @Override
        public int stopRecognition(int keyphraseId, IRecognitionStatusCallback listener) {
            return mSoundTriggerHelper.stopRecognition(keyphraseId, listener);
        }

        @Override
        public void stopAllRecognitions() {
            mSoundTriggerHelper.stopAllRecognitions();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mSoundTriggerHelper.dump(fd, pw, args);
        }
    }

    private void enforceCallingPermission(String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold the permission " + permission);
        }
    }
}
