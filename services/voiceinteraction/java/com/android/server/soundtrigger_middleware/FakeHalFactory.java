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

import android.media.soundtrigger_middleware.IInjectGlobalEvent;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.soundtrigger_middleware.FakeSoundTriggerHal.ExecutorHolder;


/**
 * Alternate HAL factory which constructs {@link FakeSoundTriggerHal} with addition hooks to
 * observe framework events.
 */
class FakeHalFactory implements HalFactory {

    private static final String TAG = "FakeHalFactory";
    private final ISoundTriggerInjection mInjection;

    FakeHalFactory(ISoundTriggerInjection injection) {
        mInjection = injection;
    }

    /**
     * We override the methods below at the {@link ISoundTriggerHal} level, because
     * they do not represent real HAL events, rather, they are framework exclusive.
     * So, we intercept them here and report them to the injection interface.
     */
    @Override
    public ISoundTriggerHal create() {
        final FakeSoundTriggerHal hal = new FakeSoundTriggerHal(mInjection);
        final IInjectGlobalEvent session = hal.getGlobalEventInjection();
        // The fake hal is a ST3 HAL implementation.
        final ISoundTriggerHal wrapper = new SoundTriggerHw3Compat(hal,
                /* reboot runnable */ () -> {
                    try {
                        session.triggerRestart();
                    }
                    catch (RemoteException e) {
                        Slog.wtf(TAG, "Unexpected RemoteException from same process");
                    }
                }) {
            @Override
            public void detach() {
                ExecutorHolder.INJECTION_EXECUTOR.execute(() -> {
                    try {
                        mInjection.onFrameworkDetached(session);
                    } catch (RemoteException e) {
                        Slog.wtf(TAG, "Unexpected RemoteException from same process");
                    }
                });
            }

            @Override
            public void clientAttached(IBinder token) {
                ExecutorHolder.INJECTION_EXECUTOR.execute(() -> {
                    try {
                        mInjection.onClientAttached(token, session);
                    } catch (RemoteException e) {
                        Slog.wtf(TAG, "Unexpected RemoteException from same process");
                    }
                });
            }

            @Override
            public void clientDetached(IBinder token) {
                ExecutorHolder.INJECTION_EXECUTOR.execute(() -> {
                    try {
                        mInjection.onClientDetached(token);
                    } catch (RemoteException e) {
                        Slog.wtf(TAG, "Unexpected RemoteException from same process");
                    }
                });
            }
        };
        return wrapper;
    }
}
