/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.IAudioPolicyService;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.SafeCloseable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/**
 * Default implementation of a facade to IAudioPolicyManager which fulfills AudioService
 * dependencies. This forwards calls as-is to IAudioPolicyManager.
 * Public methods throw IllegalStateException if AudioPolicy is not initialized/available
 */
public class DefaultAudioPolicyFacade implements AudioPolicyFacade, IBinder.DeathRecipient {

    private static final String TAG = "DefaultAudioPolicyFacade";
    private static final String AUDIO_POLICY_SERVICE_NAME = "media.audio_policy";

    private final Object mServiceLock = new Object();
    @GuardedBy("mServiceLock")
    private IAudioPolicyService mAudioPolicy;

    public DefaultAudioPolicyFacade() {
        try {
            getAudioPolicyOrInit();
        } catch (IllegalStateException e) {
            // Log and suppress this exception, we may be able to connect later
            Log.e(TAG, "Failed to initialize APM connection", e);
        }
    }

    @Override
    public boolean isHotwordStreamSupported(boolean lookbackAudio) {
        IAudioPolicyService ap = getAudioPolicyOrInit();
        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            return ap.isHotwordStreamSupported(lookbackAudio);
        } catch (RemoteException e) {
            resetServiceConnection(ap.asBinder());
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void binderDied() {
        Log.wtf(TAG, "Unexpected binderDied without IBinder object");
    }

    @Override
    public void binderDied(@NonNull IBinder who) {
        resetServiceConnection(who);
    }

    private void resetServiceConnection(@Nullable IBinder deadAudioPolicy) {
        synchronized (mServiceLock) {
            if (mAudioPolicy != null && mAudioPolicy.asBinder().equals(deadAudioPolicy)) {
                mAudioPolicy.asBinder().unlinkToDeath(this, 0);
                mAudioPolicy = null;
            }
        }
    }

    private @Nullable IAudioPolicyService getAudioPolicy() {
        synchronized (mServiceLock) {
            return mAudioPolicy;
        }
    }

    /*
     * Does not block.
     * @throws IllegalStateException for any failed connection
     */
    private @NonNull IAudioPolicyService getAudioPolicyOrInit() {
        synchronized (mServiceLock) {
            if (mAudioPolicy != null) {
                return mAudioPolicy;
            }
            // Do not block while attempting to connect to APM. Defer to caller.
            IAudioPolicyService ap = IAudioPolicyService.Stub.asInterface(
                    ServiceManager.checkService(AUDIO_POLICY_SERVICE_NAME));
            if (ap == null) {
                throw new IllegalStateException(TAG + ": Unable to connect to AudioPolicy");
            }
            try {
                ap.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                throw new IllegalStateException(
                        TAG + ": Unable to link deathListener to AudioPolicy", e);
            }
            mAudioPolicy = ap;
            return mAudioPolicy;
        }
    }
}
