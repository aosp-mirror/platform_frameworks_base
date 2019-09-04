/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Implementation of AudioRecordingMonitor interface.
 * @hide
 */
public class AudioRecordingMonitorImpl implements AudioRecordingMonitor {

    private static final String TAG = "android.media.AudioRecordingMonitor";

    private static IAudioService sService; //lazy initialization, use getService()

    private final AudioRecordingMonitorClient mClient;

    AudioRecordingMonitorImpl(@NonNull AudioRecordingMonitorClient client) {
        mClient = client;
    }

    /**
     * Register a callback to be notified of audio capture changes via a
     * {@link AudioManager.AudioRecordingCallback}. A callback is received when the capture path
     * configuration changes (pre-processing, format, sampling rate...) or capture is
     * silenced/unsilenced by the system.
     * @param executor {@link Executor} to handle the callbacks.
     * @param cb non-null callback to register
     */
    public void registerAudioRecordingCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AudioManager.AudioRecordingCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Illegal null Executor");
        }
        synchronized (mRecordCallbackLock) {
            // check if eventCallback already in list
            for (AudioRecordingCallbackInfo arci : mRecordCallbackList) {
                if (arci.mCb == cb) {
                    throw new IllegalArgumentException(
                            "AudioRecordingCallback already registered");
                }
            }
            beginRecordingCallbackHandling();
            mRecordCallbackList.add(new AudioRecordingCallbackInfo(executor, cb));
        }
    }

    /**
     * Unregister an audio recording callback previously registered with
     * {@link #registerAudioRecordingCallback(Executor, AudioManager.AudioRecordingCallback)}.
     * @param cb non-null callback to unregister
     */
    public void unregisterAudioRecordingCallback(@NonNull AudioManager.AudioRecordingCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        }

        synchronized (mRecordCallbackLock) {
            for (AudioRecordingCallbackInfo arci : mRecordCallbackList) {
                if (arci.mCb == cb) {
                    // ok to remove while iterating over list as we exit iteration
                    mRecordCallbackList.remove(arci);
                    if (mRecordCallbackList.size() == 0) {
                        endRecordingCallbackHandling();
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("AudioRecordingCallback was not registered");
        }
    }

    /**
     * Returns the current active audio recording for this audio recorder.
     * @return a valid {@link AudioRecordingConfiguration} if this recorder is active
     * or null otherwise.
     * @see AudioRecordingConfiguration
     */
    public @Nullable AudioRecordingConfiguration getActiveRecordingConfiguration() {
        final IAudioService service = getService();
        try {
            List<AudioRecordingConfiguration> configs = service.getActiveRecordingConfigurations();
            return getMyConfig(configs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class AudioRecordingCallbackInfo {
        final AudioManager.AudioRecordingCallback mCb;
        final Executor mExecutor;
        AudioRecordingCallbackInfo(Executor e, AudioManager.AudioRecordingCallback cb) {
            mExecutor = e;
            mCb = cb;
        }
    }

    private static final int MSG_RECORDING_CONFIG_CHANGE = 1;

    private final Object mRecordCallbackLock = new Object();
    @GuardedBy("mRecordCallbackLock")
    @NonNull private LinkedList<AudioRecordingCallbackInfo> mRecordCallbackList =
            new LinkedList<AudioRecordingCallbackInfo>();
    @GuardedBy("mRecordCallbackLock")
    private @Nullable HandlerThread mRecordingCallbackHandlerThread;
    @GuardedBy("mRecordCallbackLock")
    private @Nullable volatile Handler mRecordingCallbackHandler;

    @GuardedBy("mRecordCallbackLock")
    private final IRecordingConfigDispatcher mRecordingCallback =
            new IRecordingConfigDispatcher.Stub() {
        @Override
        public void dispatchRecordingConfigChange(List<AudioRecordingConfiguration> configs) {
            AudioRecordingConfiguration config = getMyConfig(configs);
            if (config != null) {
                synchronized (mRecordCallbackLock) {
                    if (mRecordingCallbackHandler != null) {
                        final Message m = mRecordingCallbackHandler.obtainMessage(
                                              MSG_RECORDING_CONFIG_CHANGE/*what*/, config /*obj*/);
                        mRecordingCallbackHandler.sendMessage(m);
                    }
                }
            }
        }
    };

    @GuardedBy("mRecordCallbackLock")
    private void beginRecordingCallbackHandling() {
        if (mRecordingCallbackHandlerThread == null) {
            mRecordingCallbackHandlerThread = new HandlerThread(TAG + ".RecordingCallback");
            mRecordingCallbackHandlerThread.start();
            final Looper looper = mRecordingCallbackHandlerThread.getLooper();
            if (looper != null) {
                mRecordingCallbackHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case MSG_RECORDING_CONFIG_CHANGE: {
                                if (msg.obj == null) {
                                    return;
                                }
                                ArrayList<AudioRecordingConfiguration> configs =
                                        new ArrayList<AudioRecordingConfiguration>();
                                configs.add((AudioRecordingConfiguration) msg.obj);

                                final LinkedList<AudioRecordingCallbackInfo> cbInfoList;
                                synchronized (mRecordCallbackLock) {
                                    if (mRecordCallbackList.size() == 0) {
                                        return;
                                    }
                                    cbInfoList = new LinkedList<AudioRecordingCallbackInfo>(
                                        mRecordCallbackList);
                                }

                                final long identity = Binder.clearCallingIdentity();
                                try {
                                    for (AudioRecordingCallbackInfo cbi : cbInfoList) {
                                        cbi.mExecutor.execute(() ->
                                                cbi.mCb.onRecordingConfigChanged(configs));
                                    }
                                } finally {
                                    Binder.restoreCallingIdentity(identity);
                                }
                            } break;
                            default:
                                Log.e(TAG, "Unknown event " + msg.what);
                                break;
                        }
                    }
                };
                final IAudioService service = getService();
                try {
                    service.registerRecordingCallback(mRecordingCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    @GuardedBy("mRecordCallbackLock")
    private void endRecordingCallbackHandling() {
        if (mRecordingCallbackHandlerThread != null) {
            final IAudioService service = getService();
            try {
                service.unregisterRecordingCallback(mRecordingCallback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mRecordingCallbackHandlerThread.quit();
            mRecordingCallbackHandlerThread = null;
        }
    }

    AudioRecordingConfiguration getMyConfig(List<AudioRecordingConfiguration> configs) {
        int portId = mClient.getPortId();
        for (AudioRecordingConfiguration config : configs) {
            if (config.getClientPortId() == portId) {
                return config;
            }
        }
        return null;
    }

    private static IAudioService getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }
}
