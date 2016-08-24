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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.ISoundTriggerService;

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
}
