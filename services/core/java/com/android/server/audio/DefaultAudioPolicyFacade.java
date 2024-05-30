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

import android.media.IAudioPolicyService;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Default implementation of a facade to IAudioPolicyService which fulfills AudioService
 * dependencies. This forwards calls as-is to IAudioPolicyService.
 */
public class DefaultAudioPolicyFacade implements AudioPolicyFacade {

    private static final String AUDIO_POLICY_SERVICE_NAME = "media.audio_policy";

    private final ServiceHolder<IAudioPolicyService> mServiceHolder;

    /**
     * @param e - Executor for service start tasks
     */
    public DefaultAudioPolicyFacade(Executor e) {
        mServiceHolder =
                new ServiceHolder(
                        AUDIO_POLICY_SERVICE_NAME,
                        (Function<IBinder, IAudioPolicyService>)
                                IAudioPolicyService.Stub::asInterface,
                        e);
    }

    @Override
    public boolean isHotwordStreamSupported(boolean lookbackAudio) {
        IAudioPolicyService ap = mServiceHolder.waitForService();
        try {
            return ap.isHotwordStreamSupported(lookbackAudio);
        } catch (RemoteException e) {
            mServiceHolder.attemptClear(ap.asBinder());
            throw new IllegalStateException();
        }
    }
}
