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

package android.hardware.lights;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.hardware.lights.LightsManager.LightsSession;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.CloseGuard;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.ref.Reference;
import java.util.List;

/**
 * The LightsManager class allows control over device lights.
 *
 * @hide
 */
public final class SystemLightsManager extends LightsManager {
    private static final String TAG = "LightsManager";

    @NonNull private final ILightsManager mService;

    /**
     * Creates a SystemLightsManager.
     *
     * @hide
     */
    public SystemLightsManager(@NonNull Context context) throws ServiceNotFoundException {
        this(context, ILightsManager.Stub.asInterface(
            ServiceManager.getServiceOrThrow(Context.LIGHTS_SERVICE)));
    }

    /**
     * Creates a SystemLightsManager with a provided service implementation.
     *
     * @hide
     */
    @VisibleForTesting
    public SystemLightsManager(@NonNull Context context, @NonNull ILightsManager service) {
        super(context);
        mService = Preconditions.checkNotNull(service);
    }

    /**
     * Returns the lights available on the device.
     *
     * @return A list of available lights
     */
    @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
    @Override
    public @NonNull List<Light> getLights() {
        try {
            return mService.getLights();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the state of a specified light.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
    @Override
    public @NonNull LightState getLightState(@NonNull Light light) {
        Preconditions.checkNotNull(light);
        try {
            return mService.getLightState(light.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a new LightsSession that can be used to control the device lights.
     */
    @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
    @Override
    public @NonNull LightsSession openSession() {
        try {
            final LightsSession session = new SystemLightsSession();
            mService.openSession(session.getToken(), 0);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     *
     * Creates a new {@link LightsSession}
     *
     * @param priority the larger this number, the higher the priority of this session when multiple
     *                 light state requests arrive simultaneously.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
    @Override
    public @NonNull LightsSession openSession(int priority) {
        try {
            final LightsSession session = new SystemLightsSession();
            mService.openSession(session.getToken(), priority);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Encapsulates a session that can be used to control device lights and represents the lifetime
     * of the requests.
     */
    public final class SystemLightsSession extends LightsManager.LightsSession
            implements AutoCloseable {

        private final CloseGuard mCloseGuard = new CloseGuard();
        private boolean mClosed = false;

        /**
         * Instantiated by {@link LightsManager#openSession()}.
         */
        @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
        private SystemLightsSession() {
            mCloseGuard.open("close");
        }

        /**
         * Sends a request to modify the states of multiple lights.
         *
         * <p>This method only controls lights that aren't overridden by higher-priority sessions.
         * Additionally, lights not controlled by this session can be controlled by lower-priority
         * sessions.
         *
         * @param request the settings for lights that should change
         */
        @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void requestLights(@NonNull LightsRequest request) {
            Preconditions.checkNotNull(request);
            if (!mClosed) {
                try {
                    List<Integer> idList = request.getLights();
                    List<LightState> stateList = request.getLightStates();
                    int[] ids = new int[idList.size()];
                    for (int i = 0; i < idList.size(); i++) {
                        ids[i] = idList.get(i);
                    }
                    LightState[] states = new LightState[stateList.size()];
                    for (int i = 0; i < stateList.size(); i++) {
                        states[i] = stateList.get(i);
                    }
                    mService.setLightStates(getToken(), ids, states);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }

        /**
         * Closes the session, reverting all changes made through it.
         */
        @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void close() {
            if (!mClosed) {
                try {
                    mService.closeSession(getToken());
                    mClosed = true;
                    mCloseGuard.close();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
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
