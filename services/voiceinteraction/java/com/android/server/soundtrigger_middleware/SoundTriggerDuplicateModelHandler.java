/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * This wrapper prevents a models with the same UUID from being loaded concurrently. This is used to
 * protect STHAL implementations, which don't support concurrent loads of the same model. We reject
 * the duplicate load with {@link Status#RESOURCE_CONTENTION}.
 */
public class SoundTriggerDuplicateModelHandler implements ISoundTriggerHal {
    private final @NonNull ISoundTriggerHal mDelegate;

    private GlobalCallback mGlobalCallback;
    // There are rarely more than two models loaded.
    private final List<ModelData> mModelList = new ArrayList<>();

    private static final class ModelData {
        ModelData(int modelId, String uuid) {
            mModelId = modelId;
            mUuid = uuid;
        }

        int getModelId() {
            return mModelId;
        }

        String getUuid() {
            return mUuid;
        }

        boolean getWasContended() {
            return mWasContended;
        }

        void setWasContended() {
            mWasContended = true;
        }

        private int mModelId;
        private String mUuid;
        private boolean mWasContended = false;
    }

    public SoundTriggerDuplicateModelHandler(@NonNull ISoundTriggerHal delegate) {
        mDelegate = delegate;
    }

    @Override
    public void reboot() {
        mDelegate.reboot();
    }

    @Override
    public void detach() {
        mDelegate.detach();
    }

    @Override
    public Properties getProperties() {
        return mDelegate.getProperties();
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        mGlobalCallback = callback;
        mDelegate.registerCallback(mGlobalCallback);
    }

    @Override
    public int loadSoundModel(SoundModel soundModel, ModelCallback callback) {
        synchronized (this) {
            checkDuplicateModelUuid(soundModel.uuid);
            var result = mDelegate.loadSoundModel(soundModel, callback);
            mModelList.add(new ModelData(result, soundModel.uuid));
            return result;
        }
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel, ModelCallback callback) {
        synchronized (this) {
            checkDuplicateModelUuid(soundModel.common.uuid);
            var result = mDelegate.loadPhraseSoundModel(soundModel, callback);
            mModelList.add(new ModelData(result, soundModel.common.uuid));
            return result;
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        mDelegate.unloadSoundModel(modelHandle);
        for (int i = 0; i < mModelList.size(); i++) {
            if (mModelList.get(i).getModelId() == modelHandle) {
                var modelData = mModelList.remove(i);
                if (modelData.getWasContended()) {
                    mGlobalCallback.onResourcesAvailable();
                }
                // Model ID is unique
                return;
            }
        }
    }

    // Uninteresting delegation calls to follow.
    @Override
    public void stopRecognition(int modelHandle) {
        mDelegate.stopRecognition(modelHandle);
    }

    @Override
    public void startRecognition(
            int modelHandle, int deviceHandle, int ioHandle, RecognitionConfig config) {
        mDelegate.startRecognition(modelHandle, deviceHandle, ioHandle, config);
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) {
        mDelegate.forceRecognitionEvent(modelHandle);
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        return mDelegate.getModelParameter(modelHandle, param);
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        mDelegate.setModelParameter(modelHandle, param, value);
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        return mDelegate.queryParameter(modelHandle, param);
    }

    @Override
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        mDelegate.linkToDeath(recipient);
    }

    @Override
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mDelegate.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() {
        return mDelegate.interfaceDescriptor();
    }

    @Override
    public void flushCallbacks() {
        mDelegate.flushCallbacks();
    }

    @Override
    public void clientAttached(IBinder binder) {
        mDelegate.clientAttached(binder);
    }

    @Override
    public void clientDetached(IBinder binder) {
        mDelegate.clientDetached(binder);
    }

    /**
     * Helper for handling duplicate model. If there is a load attempt for a model with a UUID which
     * is already loaded: 1) Reject with {@link Status.RESOURCE_CONTENTION} 2) Mark the already
     * loaded model as contended, as we need to dispatch a resource available callback following the
     * original model being unloaded.
     */
    private void checkDuplicateModelUuid(String uuid) {
        var model = mModelList.stream().filter(x -> x.getUuid().equals(uuid)).findFirst();
        if (model.isPresent()) {
            model.get().setWasContended();
            throw new RecoverableException(Status.RESOURCE_CONTENTION);
        }
    }
}
