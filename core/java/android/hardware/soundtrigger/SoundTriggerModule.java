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

package android.hardware.soundtrigger;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.Identity;
import android.media.permission.SafeCloseable;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.SoundModel;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

/**
 * The SoundTriggerModule provides APIs to control sound models and sound detection
 * on a given sound trigger hardware module.
 *
 * @hide
 */
public class SoundTriggerModule {
    private static final String TAG = "SoundTriggerModule";

    private static final int EVENT_RECOGNITION = 1;
    private static final int EVENT_SERVICE_DIED = 2;
    private static final int EVENT_RESOURCES_AVAILABLE = 3;
    private static final int EVENT_MODEL_UNLOADED = 4;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mId;
    private EventHandlerDelegate mEventHandlerDelegate;
    private ISoundTriggerModule mService;

    /**
     * This variant is intended for use when the caller is acting an originator, rather than on
     * behalf of a different entity, as far as authorization goes.
     */
    SoundTriggerModule(@NonNull ISoundTriggerMiddlewareService service,
            int moduleId, @NonNull SoundTrigger.StatusListener listener, @NonNull Looper looper,
            @NonNull Identity originatorIdentity)
            throws RemoteException {
        mId = moduleId;
        mEventHandlerDelegate = new EventHandlerDelegate(listener, looper);

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            mService = service.attachAsOriginator(moduleId, originatorIdentity,
                    mEventHandlerDelegate);
        }
        mService.asBinder().linkToDeath(mEventHandlerDelegate, 0);
    }

    /**
     * This variant is intended for use when the caller is acting as a middleman, i.e. on behalf of
     * a different entity, as far as authorization goes.
     */
    SoundTriggerModule(@NonNull ISoundTriggerMiddlewareService service,
            int moduleId, @NonNull SoundTrigger.StatusListener listener, @NonNull Looper looper,
            @NonNull Identity middlemanIdentity, @NonNull Identity originatorIdentity)
            throws RemoteException {
        mId = moduleId;
        mEventHandlerDelegate = new EventHandlerDelegate(listener, looper);

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            mService = service.attachAsMiddleman(moduleId, middlemanIdentity, originatorIdentity,
                    mEventHandlerDelegate);
        }
        mService.asBinder().linkToDeath(mEventHandlerDelegate, 0);
    }

    @Override
    protected void finalize() {
        detach();
    }

    /**
     * Detach from this module. The {@link SoundTrigger.StatusListener} callback will not be called
     * anymore and associated resources will be released.
     * All models must have been unloaded prior to detaching.
     */
    @UnsupportedAppUsage
    public synchronized void detach() {
        try {
            if (mService != null) {
                mService.asBinder().unlinkToDeath(mEventHandlerDelegate, 0);
                mService.detach();
                mService = null;
            }
        } catch (Exception e) {
            SoundTrigger.handleException(e);
        }
    }

    /**
     * Load a {@link SoundTrigger.SoundModel} to the hardware. A sound model must be loaded in
     * order to start listening to a key phrase in this model.
     * @param model The sound model to load.
     * @param soundModelHandle an array of int where the sound model handle will be returned.
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_ERROR} in case of unspecified error
     *         - {@link SoundTrigger#STATUS_BUSY} in case of transient resource constraints
     *         - {@link SoundTrigger#STATUS_PERMISSION_DENIED} if the caller does not have
     *         system permission
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} if parameters are invalid
     *         - {@link SoundTrigger#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *         service fails
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence
     */
    @UnsupportedAppUsage
    public synchronized int loadSoundModel(@NonNull SoundTrigger.SoundModel model,
            @NonNull int[] soundModelHandle) {
        try {
            if (model instanceof SoundTrigger.GenericSoundModel) {
                SoundModel aidlModel = ConversionUtil.api2aidlGenericSoundModel(
                        (SoundTrigger.GenericSoundModel) model);
                soundModelHandle[0] = mService.loadModel(aidlModel);
                return SoundTrigger.STATUS_OK;
            }
            if (model instanceof SoundTrigger.KeyphraseSoundModel) {
                PhraseSoundModel aidlModel = ConversionUtil.api2aidlPhraseSoundModel(
                        (SoundTrigger.KeyphraseSoundModel) model);
                soundModelHandle[0] = mService.loadPhraseModel(aidlModel);
                return SoundTrigger.STATUS_OK;
            }
            return SoundTrigger.STATUS_BAD_VALUE;
        } catch (Exception e) {
            return SoundTrigger.handleException(e);
        }
    }

    /**
     * Unload a {@link SoundTrigger.SoundModel} and abort any pendiong recognition
     * @param soundModelHandle The sound model handle
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_ERROR} in case of unspecified error
     *         - {@link SoundTrigger#STATUS_PERMISSION_DENIED} if the caller does not have
     *         system permission
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} if the sound model handle is invalid
     *         - {@link SoundTrigger#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *         service fails
     */
    @UnsupportedAppUsage
    public synchronized int unloadSoundModel(int soundModelHandle) {
        try {
            mService.unloadModel(soundModelHandle);
            return SoundTrigger.STATUS_OK;
        } catch (Exception e) {
            return SoundTrigger.handleException(e);
        }
    }

    /**
     * Start listening to all key phrases in a {@link SoundTrigger.SoundModel}.
     * Recognition must be restarted after each callback (success or failure) received on
     * the {@link SoundTrigger.StatusListener}.
     * @param soundModelHandle The sound model handle to start listening to
     * @param config contains configuration information for this recognition request:
     *  recognition mode, keyphrases, users, minimum confidence levels...
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_ERROR} in case of unspecified error
     *         - {@link SoundTrigger#STATUS_BUSY} in case of transient resource constraints
     *         - {@link SoundTrigger#STATUS_PERMISSION_DENIED} if the caller does not have
     *         system permission
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} if the sound model handle is invalid
     *         - {@link SoundTrigger#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *         service fails
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence
     */
    @UnsupportedAppUsage
    public synchronized int startRecognition(int soundModelHandle,
            SoundTrigger.RecognitionConfig config) {
        try {
            mService.startRecognition(soundModelHandle,
                    ConversionUtil.api2aidlRecognitionConfig(config));
            return SoundTrigger.STATUS_OK;
        } catch (Exception e) {
            return SoundTrigger.handleException(e);
        }
    }

    /**
     * Stop listening to all key phrases in a {@link SoundTrigger.SoundModel}
     * @param soundModelHandle The sound model handle to stop listening to
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_ERROR} in case of unspecified error
     *         - {@link SoundTrigger#STATUS_PERMISSION_DENIED} if the caller does not have
     *         system permission
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} if the sound model handle is invalid
     *         - {@link SoundTrigger#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *         service fails
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence
     */
    @UnsupportedAppUsage
    public synchronized int stopRecognition(int soundModelHandle) {
        try {
            mService.stopRecognition(soundModelHandle);
            return SoundTrigger.STATUS_OK;
        } catch (Exception e) {
            return SoundTrigger.handleException(e);
        }
    }

    /**
     * Get the current state of a {@link SoundTrigger.SoundModel}.
     * The state will be returned asynchronously as a {@link SoundTrigger.RecognitionEvent}
     * in the callback registered in the
     * {@link SoundTrigger#attachModule(int, SoundTrigger.StatusListener, Handler)} method.
     * @param soundModelHandle The sound model handle indicating which model's state to return
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_ERROR} in case of unspecified error
     *         - {@link SoundTrigger#STATUS_PERMISSION_DENIED} if the caller does not have
     *         system permission
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} if the sound model handle is invalid
     *         - {@link SoundTrigger#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *         service fails
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence
     */
    public synchronized int getModelState(int soundModelHandle) {
        try {
            mService.forceRecognitionEvent(soundModelHandle);
            return SoundTrigger.STATUS_OK;
        } catch (Exception e) {
            return SoundTrigger.handleException(e);
        }
    }

    /**
     * Set a model specific {@link ModelParams} with the given value. This
     * parameter will keep its value for the duration the model is loaded regardless of starting
     * and stopping recognition. Once the model is unloaded, the value will be lost.
     * {@link #queryParameter} should be checked first before calling this method.
     *
     * @param soundModelHandle handle of model to apply parameter
     * @param modelParam       {@link ModelParams}
     * @param value            Value to set
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     * - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     * - {@link SoundTrigger#STATUS_BAD_VALUE} invalid input parameter
     * - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence or
     * if API is not supported by HAL
     */
    public synchronized int setParameter(int soundModelHandle, @ModelParams int modelParam,
            int value) {
        try {
            mService.setModelParameter(soundModelHandle,
                    ConversionUtil.api2aidlModelParameter(modelParam), value);
            return SoundTrigger.STATUS_OK;
        } catch (Exception e) {
            return SoundTrigger.handleException(e);
        }
    }

    /**
     * Get a model specific {@link ModelParams}. This parameter will keep its value
     * for the duration the model is loaded regardless of starting and stopping recognition.
     * Once the model is unloaded, the value will be lost. If the value is not set, a default
     * value is returned. See {@link ModelParams} for parameter default values.
     * {@link #queryParameter} should be checked first before
     * calling this method. Otherwise, an exception can be thrown.
     *
     * @param soundModelHandle handle of model to get parameter
     * @param modelParam       {@link ModelParams}
     * @return value of parameter
     */
    public synchronized int getParameter(int soundModelHandle, @ModelParams int modelParam) {
        try {
            return mService.getModelParameter(soundModelHandle,
                    ConversionUtil.api2aidlModelParameter(modelParam));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query the parameter support and range for a given {@link ModelParams}.
     * This method should be check prior to calling {@link #setParameter} or {@link #getParameter}.
     *
     * @param soundModelHandle handle of model to get parameter
     * @param modelParam       {@link ModelParams}
     * @return supported range of parameter, null if not supported
     */
    @Nullable
    public synchronized SoundTrigger.ModelParamRange queryParameter(int soundModelHandle,
            @ModelParams int modelParam) {
        try {
            return ConversionUtil.aidl2apiModelParameterRange(mService.queryModelParameterSupport(
                    soundModelHandle,
                    ConversionUtil.api2aidlModelParameter(modelParam)));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private class EventHandlerDelegate extends ISoundTriggerCallback.Stub implements
            IBinder.DeathRecipient {
        private final Handler mHandler;

        EventHandlerDelegate(@NonNull final SoundTrigger.StatusListener listener,
                @NonNull Looper looper) {

            // construct the event handler with this looper
            // implement the event handler delegate
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case EVENT_RECOGNITION:
                            listener.onRecognition(
                                    (SoundTrigger.RecognitionEvent) msg.obj);
                            break;
                        case EVENT_RESOURCES_AVAILABLE:
                            listener.onResourcesAvailable();
                            break;
                        case EVENT_MODEL_UNLOADED:
                            listener.onModelUnloaded((Integer) msg.obj);
                            break;
                        case EVENT_SERVICE_DIED:
                            listener.onServiceDied();
                            break;
                        default:
                            Log.e(TAG, "Unknown message: " + msg.toString());
                            break;
                    }
                }
            };
        }

        @Override
        public synchronized void onRecognition(int handle, RecognitionEvent event)
                throws RemoteException {
            Message m = mHandler.obtainMessage(EVENT_RECOGNITION,
                    ConversionUtil.aidl2apiRecognitionEvent(handle, event));
            mHandler.sendMessage(m);
        }

        @Override
        public synchronized void onPhraseRecognition(int handle, PhraseRecognitionEvent event)
                throws RemoteException {
            Message m = mHandler.obtainMessage(EVENT_RECOGNITION,
                    ConversionUtil.aidl2apiPhraseRecognitionEvent(handle, event));
            mHandler.sendMessage(m);
        }

        @Override
        public void onModelUnloaded(int modelHandle) throws RemoteException {
            Message m = mHandler.obtainMessage(EVENT_MODEL_UNLOADED, modelHandle);
            mHandler.sendMessage(m);
        }

        @Override
        public synchronized void onResourcesAvailable() throws RemoteException {
            Message m = mHandler.obtainMessage(EVENT_RESOURCES_AVAILABLE);
            mHandler.sendMessage(m);
        }

        @Override
        public synchronized void onModuleDied() {
            Message m = mHandler.obtainMessage(EVENT_SERVICE_DIED);
            mHandler.sendMessage(m);
        }

        @Override
        public synchronized void binderDied() {
            Message m = mHandler.obtainMessage(EVENT_SERVICE_DIED);
            mHandler.sendMessage(m);
        }
    }
}
