/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.CloseGuard;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;
import java.util.List;

/**
 * The LightsManager class allows control over device lights.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.LIGHTS_SERVICE)
public final class LightsManager {
    private static final String TAG = "LightsManager";

    // These enum values copy the values from {@link com.android.server.lights.LightsManager}
    // and the light HAL. Since 0-7 are lights reserved for system use, only the microphone light
    // is available through this API.
    /** Type for lights that indicate microphone usage */
    public static final int LIGHT_TYPE_MICROPHONE = 8;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LIGHT_TYPE_"},
        value = {
            LIGHT_TYPE_MICROPHONE,
        })
    public @interface LightType {}

    @NonNull private final Context mContext;
    @NonNull private final ILightsManager mService;

    /**
     * Creates a LightsManager.
     *
     * @hide
     */
    public LightsManager(@NonNull Context context) throws ServiceNotFoundException {
        this(context, ILightsManager.Stub.asInterface(
            ServiceManager.getServiceOrThrow(Context.LIGHTS_SERVICE)));
    }

    /**
     * Creates a LightsManager with a provided service implementation.
     *
     * @hide
     */
    @VisibleForTesting
    public LightsManager(@NonNull Context context, @NonNull ILightsManager service) {
        mContext = Preconditions.checkNotNull(context);
        mService = Preconditions.checkNotNull(service);
    }

    /**
     * Returns the lights available on the device.
     *
     * @return A list of available lights
     */
    @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
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
    @TestApi
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
    public @NonNull LightsSession openSession() {
        try {
            final LightsSession session = new LightsSession();
            mService.openSession(session.mToken);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Encapsulates a session that can be used to control device lights and represents the lifetime
     * of the requests.
     */
    public final class LightsSession implements AutoCloseable {

        private final IBinder mToken = new Binder();

        private final CloseGuard mCloseGuard = new CloseGuard();
        private boolean mClosed = false;

        /**
         * Instantiated by {@link LightsManager#openSession()}.
         */
        @RequiresPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS)
        private LightsSession() {
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
        public void requestLights(@NonNull LightsRequest request) {
            Preconditions.checkNotNull(request);
            if (!mClosed) {
                try {
                    mService.setLightStates(mToken, request.mLightIds, request.mLightStates);
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
                    mService.closeSession(mToken);
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
