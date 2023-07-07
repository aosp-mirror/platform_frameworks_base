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

import android.annotation.Nullable;
import android.media.soundtrigger.Phrase;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger_middleware.IInjectGlobalEvent;
import android.media.soundtrigger_middleware.IInjectModelEvent;
import android.media.soundtrigger_middleware.IInjectRecognitionEvent;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * Service side of the injection interface which enforces a single client.
 * Essentially a facade that presents an ever-present, single injection client to the fake STHAL.
 * Proxies a binder interface, but should never be called as such.
 * @hide
 */

public class SoundTriggerInjection implements ISoundTriggerInjection, IBinder.DeathRecipient {

    private static final String TAG = "SoundTriggerInjection";

    private final Object mClientLock = new Object();
    @GuardedBy("mClientLock")
    private ISoundTriggerInjection mClient = null;
    @GuardedBy("mClientLock")
    private IInjectGlobalEvent mGlobalEventInjection = null;

    /**
     * Register a remote injection client.
     * @param client - The injection client to register
     */
    public void registerClient(ISoundTriggerInjection client) {
        synchronized (mClientLock) {
            Objects.requireNonNull(client);
            if (mClient != null) {
                try {
                    mClient.onPreempted();
                } catch (RemoteException e) {
                    Slog.e(TAG, "RemoteException when handling preemption", e);
                }
                mClient.asBinder().unlinkToDeath(this, 0);
            }
            mClient = client;
            // Register cached global event injection interfaces,
            // in case our client missed them.
            try {
                mClient.asBinder().linkToDeath(this, 0);
                if (mGlobalEventInjection != null) {
                    mClient.registerGlobalEventInjection(mGlobalEventInjection);
                }
            } catch (RemoteException e) {
                mClient = null;
            }

        }
    }

    @Override
    public void binderDied() {
        Slog.wtf(TAG, "Binder died without params");
    }

    // If the binder has died, clear out mClient.
    @Override
    public void binderDied(IBinder who) {
        synchronized (mClientLock) {
            if (mClient != null && who == mClient.asBinder()) {
                mClient = null;
            }
        }
    }

    @Override
    public void registerGlobalEventInjection(IInjectGlobalEvent globalInjection) {
        synchronized (mClientLock) {
            // Cache for late attaching clients
            mGlobalEventInjection = globalInjection;
            if (mClient == null) return;
            try {
                mClient.registerGlobalEventInjection(mGlobalEventInjection);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onRestarted(IInjectGlobalEvent globalSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onRestarted(globalSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onFrameworkDetached(IInjectGlobalEvent globalSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onFrameworkDetached(globalSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onClientAttached(IBinder token, IInjectGlobalEvent globalSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onClientAttached(token, globalSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onClientDetached(IBinder token) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onClientDetached(token);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onSoundModelLoaded(SoundModel model, @Nullable Phrase[] phrases,
            IInjectModelEvent modelInjection, IInjectGlobalEvent globalSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onSoundModelLoaded(model, phrases, modelInjection, globalSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onParamSet(/** ModelParameter **/ int modelParam, int value,
            IInjectModelEvent modelSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onParamSet(modelParam, value, modelSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onRecognitionStarted(int audioSessionToken, RecognitionConfig config,
            IInjectRecognitionEvent recognitionInjection, IInjectModelEvent modelSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onRecognitionStarted(audioSessionToken, config,
                        recognitionInjection, modelSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onRecognitionStopped(IInjectRecognitionEvent recognitionSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onRecognitionStopped(recognitionSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onSoundModelUnloaded(IInjectModelEvent modelSession) {
        synchronized (mClientLock) {
            if (mClient == null) return;
            try {
                mClient.onSoundModelUnloaded(modelSession);
            } catch (RemoteException e) {
                mClient = null;
            }
        }
    }

    @Override
    public void onPreempted() {
        // We are the service, so we can't be preempted.
        Slog.wtf(TAG, "Unexpected preempted!");
    }

    @Override
    public IBinder asBinder() {
        // This class is not a real binder object
        Slog.wtf(TAG, "Unexpected asBinder!");
        throw new UnsupportedOperationException("Calling asBinder on a fake binder object");
    }

}
