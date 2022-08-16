/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.util.CloseGuard;

import com.android.internal.util.Preconditions;

import java.lang.ref.Reference;
import java.util.List;

/**
 * LightsManager manages an input device's lights {@link android.hardware.input.Light}.
 */
class InputDeviceLightsManager extends LightsManager {
    private static final String TAG = "InputDeviceLightsManager";
    private static final boolean DEBUG = false;

    private final InputManager mInputManager;

    // The input device ID.
    private final int mDeviceId;
    // Package name
    private final String mPackageName;

    InputDeviceLightsManager(InputManager inputManager, int deviceId) {
        super(ActivityThread.currentActivityThread().getSystemContext());
        mInputManager = inputManager;
        mDeviceId = deviceId;
        mPackageName = ActivityThread.currentPackageName();
    }

    /**
     * Returns the lights available on the device.
     *
     * @return A list of available lights
     */
    @Override
    public @NonNull List<Light> getLights() {
        return mInputManager.getLights(mDeviceId);
    }

    /**
     * Returns the state of a specified light.
     *
     * @hide
     */
    @Override
    public @NonNull LightState getLightState(@NonNull Light light) {
        Preconditions.checkNotNull(light);
        return mInputManager.getLightState(mDeviceId, light);
    }

    /**
     * Creates a new LightsSession that can be used to control the device lights.
     */
    @Override
    public @NonNull LightsSession openSession() {
        final LightsSession session = new InputDeviceLightsSession();
        mInputManager.openLightSession(mDeviceId, mPackageName, session.getToken());
        return session;
    }

    @Override
    public @NonNull LightsSession openSession(int priority) {
        throw new UnsupportedOperationException();
    }

    /**
     * Encapsulates a session that can be used to control device lights and represents the lifetime
     * of the requests.
     */
    public final class InputDeviceLightsSession extends LightsManager.LightsSession
            implements AutoCloseable {

        private final CloseGuard mCloseGuard = new CloseGuard();
        private boolean mClosed = false;

        /**
         * Instantiated by {@link LightsManager#openSession()}.
         */
        private InputDeviceLightsSession() {
            mCloseGuard.open("InputDeviceLightsSession.close");
        }

        /**
         * Sends a request to modify the states of multiple lights.
         *
         * @param request the settings for lights that should change
         */
        @Override
        public void requestLights(@NonNull LightsRequest request) {
            Preconditions.checkNotNull(request);
            Preconditions.checkArgument(!mClosed);

            mInputManager.requestLights(mDeviceId, request, getToken());
        }

        /**
         * Closes the session, reverting all changes made through it.
         */
        @Override
        public void close() {
            if (!mClosed) {
                mInputManager.closeLightSession(mDeviceId, getToken());
                mClosed = true;
                mCloseGuard.close();
            }
            Reference.reachabilityFence(this);
        }

        /** @hide */
        @Override
        protected void finalize() throws Throwable {
            try {
                mCloseGuard.warnIfOpen();
                close();
            } finally {
                super.finalize();
            }
        }
    }

}
