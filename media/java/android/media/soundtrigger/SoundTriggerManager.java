/**
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

package android.media.soundtrigger;

import static android.hardware.soundtrigger.SoundTrigger.STATUS_ERROR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.SoundModel;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.app.ISoundTriggerService;
import com.android.internal.util.Preconditions;

import java.util.HashMap;
import java.util.UUID;

/**
 * This class provides management of non-voice (general sound trigger) based sound recognition
 * models. Usage of this class is restricted to system or signature applications only. This allows
 * OEMs to write apps that can manage non-voice based sound trigger models.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.SOUND_TRIGGER_SERVICE)
public final class SoundTriggerManager {
    private static final boolean DBG = false;
    private static final String TAG = "SoundTriggerManager";

    private final Context mContext;
    private final ISoundTriggerService mSoundTriggerService;

    // Stores a mapping from the sound model UUID to the SoundTriggerInstance created by
    // the createSoundTriggerDetector() call.
    private final HashMap<UUID, SoundTriggerDetector> mReceiverInstanceMap;

    /**
     * @hide
     */
    public SoundTriggerManager(Context context, ISoundTriggerService soundTriggerService ) {
        if (DBG) {
            Slog.i(TAG, "SoundTriggerManager created.");
        }
        mSoundTriggerService = soundTriggerService;
        mContext = context;
        mReceiverInstanceMap = new HashMap<UUID, SoundTriggerDetector>();
    }

    /**
     * Updates the given sound trigger model.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public void updateModel(Model model) {
        try {
            mSoundTriggerService.updateSoundModel(model.getGenericSoundModel());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the sound trigger model represented by the given UUID. An instance of {@link Model}
     * is returned.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public Model getModel(UUID soundModelId) {
        try {
            return new Model(mSoundTriggerService.getSoundModel(
                    new ParcelUuid(soundModelId)));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the sound model represented by the provided UUID.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public void deleteModel(UUID soundModelId) {
        try {
            mSoundTriggerService.deleteSoundModel(new ParcelUuid(soundModelId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates an instance of {@link SoundTriggerDetector} which can be used to start/stop
     * recognition on the model and register for triggers from the model. Note that this call
     * invalidates any previously returned instances for the same sound model Uuid.
     *
     * @param soundModelId UUID of the sound model to create the receiver object for.
     * @param callback Instance of the {@link SoundTriggerDetector#Callback} object for the
     * callbacks for the given sound model.
     * @param handler The Handler to use for the callback operations. A null value will use the
     * current thread's Looper.
     * @return Instance of {@link SoundTriggerDetector} or null on error.
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public SoundTriggerDetector createSoundTriggerDetector(UUID soundModelId,
            @NonNull SoundTriggerDetector.Callback callback, @Nullable Handler handler) {
        if (soundModelId == null) {
            return null;
        }

        SoundTriggerDetector oldInstance = mReceiverInstanceMap.get(soundModelId);
        if (oldInstance != null) {
            // Shutdown old instance.
        }
        SoundTriggerDetector newInstance = new SoundTriggerDetector(mSoundTriggerService,
                soundModelId, callback, handler);
        mReceiverInstanceMap.put(soundModelId, newInstance);
        return newInstance;
    }

    /**
     * Class captures the data and fields that represent a non-keyphrase sound model. Use the
     * factory constructor {@link Model#create()} to create an instance.
     */
    // We use encapsulation to expose the SoundTrigger.GenericSoundModel as a SystemApi. This
    // prevents us from exposing SoundTrigger.GenericSoundModel as an Api.
    public static class Model {

        private SoundTrigger.GenericSoundModel mGenericSoundModel;

        /**
         * @hide
         */
        Model(SoundTrigger.GenericSoundModel soundTriggerModel) {
            mGenericSoundModel = soundTriggerModel;
        }

        /**
         * Factory constructor to create a SoundModel instance for use with methods in this
         * class.
         */
        public static Model create(UUID modelUuid, UUID vendorUuid, byte[] data) {
            return new Model(new SoundTrigger.GenericSoundModel(modelUuid,
                        vendorUuid, data));
        }

        public UUID getModelUuid() {
            return mGenericSoundModel.uuid;
        }

        public UUID getVendorUuid() {
            return mGenericSoundModel.vendorUuid;
        }

        public byte[] getModelData() {
            return mGenericSoundModel.data;
        }

        /**
         * @hide
         */
        SoundTrigger.GenericSoundModel getGenericSoundModel() {
            return mGenericSoundModel;
        }
    }


    /**
     * Default message type.
     * @hide
     */
    public static final int FLAG_MESSAGE_TYPE_UNKNOWN = -1;
    /**
     * Contents of EXTRA_MESSAGE_TYPE extra for a RecognitionEvent.
     * @hide
     */
    public static final int FLAG_MESSAGE_TYPE_RECOGNITION_EVENT = 0;
    /**
     * Contents of EXTRA_MESSAGE_TYPE extra for recognition error events.
     * @hide
     */
    public static final int FLAG_MESSAGE_TYPE_RECOGNITION_ERROR = 1;
    /**
     * Contents of EXTRA_MESSAGE_TYPE extra for a recognition paused events.
     * @hide
     */
    public static final int FLAG_MESSAGE_TYPE_RECOGNITION_PAUSED = 2;
    /**
     * Contents of EXTRA_MESSAGE_TYPE extra for recognition resumed events.
     * @hide
     */
    public static final int FLAG_MESSAGE_TYPE_RECOGNITION_RESUMED = 3;

    /**
     * Extra key in the intent for the type of the message.
     * @hide
     */
    public static final String EXTRA_MESSAGE_TYPE = "android.media.soundtrigger.MESSAGE_TYPE";
    /**
     * Extra key in the intent that holds the RecognitionEvent parcelable.
     * @hide
     */
    public static final String EXTRA_RECOGNITION_EVENT = "android.media.soundtrigger.RECOGNITION_EVENT";
    /**
     * Extra key in the intent that holds the status in an error message.
     * @hide
     */
    public static final String EXTRA_STATUS = "android.media.soundtrigger.STATUS";

    /**
     * Loads a given sound model into the sound trigger. Note the model will be unloaded if there is
     * an error/the system service is restarted.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @UnsupportedAppUsage
    public int loadSoundModel(SoundModel soundModel) {
        if (soundModel == null) {
            return STATUS_ERROR;
        }

        try {
            switch (soundModel.type) {
                case SoundModel.TYPE_GENERIC_SOUND:
                    return mSoundTriggerService.loadGenericSoundModel(
                            (GenericSoundModel) soundModel);
                case SoundModel.TYPE_KEYPHRASE:
                    return mSoundTriggerService.loadKeyphraseSoundModel(
                            (KeyphraseSoundModel) soundModel);
                default:
                    Slog.e(TAG, "Unkown model type");
                    return STATUS_ERROR;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starts recognition for the given model id. All events from the model will be sent to the
     * service.
     *
     * <p>This only supports generic sound trigger events. For keyphrase events, please use
     * {@link android.service.voice.VoiceInteractionService}.
     *
     * @param soundModelId Id of the sound model
     * @param params Opaque data sent to each service call of the service as the {@code params}
     *               argument
     * @param detectionService The component name of the service that should receive the events.
     *                         Needs to subclass {@link SoundTriggerDetectionService}
     * @param config Configures the recognition
     *
     * @return {@link SoundTrigger#STATUS_OK} if the recognition could be started, error code
     *         otherwise
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @UnsupportedAppUsage
    public int startRecognition(@NonNull UUID soundModelId, @Nullable Bundle params,
        @NonNull ComponentName detectionService, @NonNull RecognitionConfig config) {
        Preconditions.checkNotNull(soundModelId);
        Preconditions.checkNotNull(detectionService);
        Preconditions.checkNotNull(config);

        try {
            return mSoundTriggerService.startRecognitionForService(new ParcelUuid(soundModelId),
                params, detectionService, config);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the given model's recognition.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @UnsupportedAppUsage
    public int stopRecognition(UUID soundModelId) {
        if (soundModelId == null) {
            return STATUS_ERROR;
        }
        try {
            return mSoundTriggerService.stopRecognitionForService(new ParcelUuid(soundModelId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the given model from memory. Will also stop any pending recognitions.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @UnsupportedAppUsage
    public int unloadSoundModel(UUID soundModelId) {
        if (soundModelId == null) {
            return STATUS_ERROR;
        }
        try {
            return mSoundTriggerService.unloadSoundModel(
                    new ParcelUuid(soundModelId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the given model has had detection started on it.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @UnsupportedAppUsage
    public boolean isRecognitionActive(UUID soundModelId) {
        if (soundModelId == null) {
            return false;
        }
        try {
            return mSoundTriggerService.isRecognitionActive(
                    new ParcelUuid(soundModelId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the amount of time (in milliseconds) an operation of the
     * {@link ISoundTriggerDetectionService} is allowed to ask.
     *
     * @return The amount of time an sound trigger detection service operation is allowed to last
     */
    public int getDetectionServiceOperationsTimeout() {
        try {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT);
        } catch (Settings.SettingNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Asynchronously get state of the indicated model.  The model state is returned as
     * a recognition event in the callback that was registered in the startRecognition
     * method.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @UnsupportedAppUsage
    public int getModelState(UUID soundModelId) {
        if (soundModelId == null) {
            return STATUS_ERROR;
        }
        try {
            return mSoundTriggerService.getModelState(new ParcelUuid(soundModelId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the hardware sound trigger module properties currently loaded.
     *
     * @return The properties currently loaded. Returns null if no supported hardware loaded.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    @Nullable
    public SoundTrigger.ModuleProperties getModuleProperties() {

        try {
            return mSoundTriggerService.getModuleProperties();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
