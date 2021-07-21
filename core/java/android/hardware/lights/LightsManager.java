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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * The LightsManager class allows control over device lights.
 *
 */
@SystemService(Context.LIGHTS_SERVICE)
public abstract class LightsManager {
    private static final String TAG = "LightsManager";

    @NonNull private final Context mContext;
    // These enum values copy the values from {@link com.android.server.lights.LightsManager}
    // and the light HAL. Since 0-7 are lights reserved for system use, only the microphone light
    // and following types are available through this API.
    /** Type for lights that indicate microphone usage
     * @deprecated this has been moved to {@link android.hardware.lights.Light }
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int LIGHT_TYPE_MICROPHONE = 8;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LIGHT_TYPE_"},
        value = {
            LIGHT_TYPE_MICROPHONE,
        })
    public @interface LightType {}

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    public LightsManager(Context context) {
        mContext = Preconditions.checkNotNull(context);
    }

    /**
     * Returns the lights available on the device.
     *
     * @return A list of available lights
     */
    public @NonNull abstract List<Light> getLights();

    /**
     * Returns the state of a specified light.
     *
     */
    public abstract @NonNull LightState getLightState(@NonNull Light light);

    /**
     * Creates a new LightsSession that can be used to control the device lights.
     */
    public abstract @NonNull LightsSession openSession();

    /**
     *
     * Creates a new {@link LightsSession}
     *
     * @param priority the larger this number, the higher the priority of this session when multiple
     *                 light state requests arrive simultaneously.
     *
     * @hide
     */
    @TestApi
    public abstract @NonNull LightsSession openSession(int priority);

    /**
     * Encapsulates a session that can be used to control device lights and represents the lifetime
     * of the requests.
     *
     * <p>Any lights requests always live in a lights session which defines the lifecycle of the
     * lights requests. A lights session is AutoCloseable that will get closed when leaving the
     * session context.
     *
     * <p>Multiple sessions can make lights requests which contains same light. In the case the
     * LightsManager implementation will arbitrate and honor one of the session's request. When
     * the session hold the current light request closed, LightsManager implementation will choose
     * another live session to honor its lights requests.
     */
    public abstract static class LightsSession implements AutoCloseable {
        private final IBinder mToken = new Binder();

        /**
         * @hide to prevent subclassing from outside of the framework
         */
        public LightsSession() {
        }

        /**
         * Sends a request to modify the states of multiple lights.
         *
         * @param request the settings for lights that should change
         */
        public abstract void requestLights(@NonNull LightsRequest request);

        @Override
        public abstract void close();

        /**
         * Get the token of a light session.
         *
         * @return Binder token of the light session.
         * @hide
         */
        public @NonNull IBinder getToken() {
            return mToken;
        }
    }

}
