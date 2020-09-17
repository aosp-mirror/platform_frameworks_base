/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.hardware.soundtrigger.V2_0.ISoundTriggerHw;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This is an implementation of the ISoundTriggerMiddlewareService interface.
 * <p>
 * <b>Important conventions:</b>
 * <ul>
 * <li>Correct usage is assumed. This implementation does not attempt to gracefully handle invalid
 * usage, and such usage will result in undefined behavior. If this service is to be offered to an
 * untrusted client, it must be wrapped with input and state validation.
 * <li>There is no binder instance associated with this implementation. Do not call asBinder().
 * <li>The implementation may throw a {@link RecoverableException} to indicate non-fatal,
 * recoverable faults. The error code would one of the
 * {@link android.media.soundtrigger_middleware.Status}
 * constants. Any other exception thrown should be regarded as a bug in the implementation or one
 * of its dependencies (assuming correct usage).
 * <li>The implementation is designed for testibility by featuring dependency injection (the
 * underlying HAL driver instances are passed to the ctor) and by minimizing dependencies on
 * Android runtime.
 * <li>The implementation is thread-safe.
 * </ul>
 *
 * @hide
 */
public class SoundTriggerMiddlewareImpl implements ISoundTriggerMiddlewareInternal {
    static private final String TAG = "SoundTriggerMiddlewareImpl";
    private final SoundTriggerModule[] mModules;

    /**
     * Interface to the audio system, which can allocate capture session handles.
     * SoundTrigger uses those sessions in order to associate a recognition session with an optional
     * capture from the same device that triggered the recognition.
     */
    public static abstract class AudioSessionProvider {
        public static final class AudioSession {
            final int mSessionHandle;
            final int mIoHandle;
            final int mDeviceHandle;

            AudioSession(int sessionHandle, int ioHandle, int deviceHandle) {
                mSessionHandle = sessionHandle;
                mIoHandle = ioHandle;
                mDeviceHandle = deviceHandle;
            }
        }

        public abstract AudioSession acquireSession();

        public abstract void releaseSession(int sessionHandle);
    }

    /**
     * Constructor - gets an array of HAL driver factories.
     */
    public SoundTriggerMiddlewareImpl(@NonNull HalFactory[] halFactories,
            @NonNull AudioSessionProvider audioSessionProvider) {
        List<SoundTriggerModule> modules = new ArrayList<>(halFactories.length);

        for (int i = 0; i < halFactories.length; ++i) {
            try {
                modules.add(new SoundTriggerModule(halFactories[i], audioSessionProvider));
            } catch (Exception e) {
                Log.e(TAG, "Failed to add a SoundTriggerModule instance", e);
            }
        }

        mModules = modules.toArray(new SoundTriggerModule[modules.size()]);
    }

    /**
     * Convenience constructor - gets a single HAL factory.
     */
    public SoundTriggerMiddlewareImpl(@NonNull HalFactory factory,
            @NonNull AudioSessionProvider audioSessionProvider) {
        this(new HalFactory[]{factory}, audioSessionProvider);
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() {
        SoundTriggerModuleDescriptor[] result = new SoundTriggerModuleDescriptor[mModules.length];

        for (int i = 0; i < mModules.length; ++i) {
            SoundTriggerModuleDescriptor desc = new SoundTriggerModuleDescriptor();
            desc.handle = i;
            desc.properties = mModules[i].getProperties();
            result[i] = desc;
        }
        return result;
    }

    @Override
    public @NonNull
    ISoundTriggerModule attach(int handle, @NonNull ISoundTriggerCallback callback) {
        return mModules[handle].attach(callback);
    }

    @Override
    public void setCaptureState(boolean active) {
        for (SoundTriggerModule module : mModules) {
            module.setExternalCaptureState(active);
        }
    }

    @Override
    public @NonNull
    IBinder asBinder() {
        throw new UnsupportedOperationException(
                "This implementation is not inteded to be used directly with Binder.");
    }
}