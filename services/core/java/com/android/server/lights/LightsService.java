/* * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.lights;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.light.HwLight;
import android.hardware.light.HwLightState;
import android.hardware.light.ILights;
import android.hardware.lights.ILightsManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LightsService extends SystemService {
    static final String TAG = "LightsService";
    static final boolean DEBUG = false;

    private LightImpl[] mLights = null;
    private SparseArray<LightImpl> mLightsById = null;

    private ILights mVintfLights = null;

    @VisibleForTesting
    final LightsManagerBinderService mManagerService;

    private Handler mH;

    private final class LightsManagerBinderService extends ILightsManager.Stub {

        private final class Session {
            final IBinder mToken;
            final SparseArray<LightState> mRequests = new SparseArray<>();

            Session(IBinder token) {
                mToken = token;
            }

            void setRequest(int lightId, LightState state) {
                if (state != null) {
                    mRequests.put(lightId, state);
                } else {
                    mRequests.remove(lightId);
                }
            }
        }

        @GuardedBy("LightsService.this")
        private final List<Session> mSessions = new ArrayList<>();

        /**
         * Returns the lights available for apps to control on the device. Only lights that aren't
         * reserved for system use are available to apps.
         */
        @Override
        public List<Light> getLights() {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS,
                    "getLights requires CONTROL_DEVICE_LIGHTS_PERMISSION");

            synchronized (LightsService.this) {
                final List<Light> lights = new ArrayList<Light>();
                for (int i = 0; i < mLightsById.size(); i++) {
                    HwLight hwLight = mLightsById.valueAt(i).getHwLight();
                    if (!isSystemLight(hwLight)) {
                        lights.add(new Light(hwLight.id, hwLight.ordinal, hwLight.type));
                    }
                }
                return lights;
            }
        }

        /**
         * Updates the set of light requests for {@param token} with additions and removals from
         * {@param lightIds} and {@param lightStates}.
         *
         * <p>Null values mean that the request should be removed, and the light turned off if it
         * is not being used by anything else.
         */
        @Override
        public void setLightStates(IBinder token, int[] lightIds, LightState[] lightStates) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS,
                    "setLightStates requires CONTROL_DEVICE_LIGHTS permission");
            Preconditions.checkState(lightIds.length == lightStates.length);

            synchronized (LightsService.this) {
                Session session = getSessionLocked(Preconditions.checkNotNull(token));
                Preconditions.checkState(session != null, "not registered");

                checkRequestIsValid(lightIds);

                for (int i = 0; i < lightIds.length; i++) {
                    session.setRequest(lightIds[i], lightStates[i]);
                }
                invalidateLightStatesLocked();
            }
        }

        @Override
        public @Nullable LightState getLightState(int lightId) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS,
                    "getLightState(@TestApi) requires CONTROL_DEVICE_LIGHTS permission");

            synchronized (LightsService.this) {
                final LightImpl light = mLightsById.get(lightId);
                if (light == null || isSystemLight(light.getHwLight())) {
                    throw new IllegalArgumentException("Invalid light: " + lightId);
                }
                return new LightState(light.getColor());
            }
        }

        @Override
        public void openSession(IBinder token) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS,
                    "openSession requires CONTROL_DEVICE_LIGHTS permission");
            Preconditions.checkNotNull(token);

            synchronized (LightsService.this) {
                Preconditions.checkState(getSessionLocked(token) == null, "already registered");
                try {
                    token.linkToDeath(() -> closeSessionInternal(token), 0);
                    mSessions.add(new Session(token));
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't open session, client already died" , e);
                    throw new IllegalArgumentException("Client is already dead.");
                }
            }
        }

        @Override
        public void closeSession(IBinder token) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.CONTROL_DEVICE_LIGHTS,
                    "closeSession requires CONTROL_DEVICE_LIGHTS permission");
            Preconditions.checkNotNull(token);
            closeSessionInternal(token);
        }

        private void closeSessionInternal(IBinder token) {
            synchronized (LightsService.this) {
                final Session session = getSessionLocked(token);
                if (session != null) {
                    mSessions.remove(session);
                    invalidateLightStatesLocked();
                }
            }
        }

        private void checkRequestIsValid(int[] lightIds) {
            for (int i = 0; i < lightIds.length; i++) {
                final LightImpl light = mLightsById.get(lightIds[i]);
                final HwLight hwLight = light.getHwLight();
                Preconditions.checkState(light != null && !isSystemLight(hwLight),
                        "invalid lightId " + hwLight.id);
            }
        }

        /**
         * Apply light state requests for all light IDs.
         *
         * <p>In case of conflict, the session that started earliest wins.
         */
        private void invalidateLightStatesLocked() {
            final Map<Integer, LightState> states = new HashMap<>();
            for (int i = mSessions.size() - 1; i >= 0; i--) {
                SparseArray<LightState> requests = mSessions.get(i).mRequests;
                for (int j = 0; j < requests.size(); j++) {
                    states.put(requests.keyAt(j), requests.valueAt(j));
                }
            }
            for (int i = 0; i < mLightsById.size(); i++) {
                LightImpl light = mLightsById.valueAt(i);
                HwLight hwLight = light.getHwLight();
                if (!isSystemLight(hwLight)) {
                    LightState state = states.get(hwLight.id);
                    if (state != null) {
                        light.setColor(state.getColor());
                    } else {
                        light.turnOff();
                    }
                }
            }
        }

        private @Nullable Session getSessionLocked(IBinder token) {
            for (int i = 0; i < mSessions.size(); i++) {
                if (token.equals(mSessions.get(i).mToken)) {
                    return mSessions.get(i);
                }
            }
            return null;
        }
    }

    private final class LightImpl extends LogicalLight {
        private final IBinder mDisplayToken;
        private final int mSurfaceControlMaximumBrightness;

        private LightImpl(Context context, HwLight hwLight) {
            mHwLight = hwLight;
            mDisplayToken = SurfaceControl.getInternalDisplayToken();
            final boolean brightnessSupport = SurfaceControl.getDisplayBrightnessSupport(
                    mDisplayToken);
            if (DEBUG) {
                Slog.d(TAG, "Display brightness support: " + brightnessSupport);
            }
            int maximumBrightness = 0;
            if (brightnessSupport) {
                PowerManager pm = context.getSystemService(PowerManager.class);
                if (pm != null) {
                    maximumBrightness = pm.getMaximumScreenBrightnessSetting();
                }
            }
            mSurfaceControlMaximumBrightness = maximumBrightness;
        }

        @Override
        public void setBrightness(int brightness) {
            setBrightness(brightness, BRIGHTNESS_MODE_USER);
        }

        @Override
        public void setBrightness(int brightness, int brightnessMode) {
            synchronized (this) {
                // LOW_PERSISTENCE cannot be manually set
                if (brightnessMode == BRIGHTNESS_MODE_LOW_PERSISTENCE) {
                    Slog.w(TAG, "setBrightness with LOW_PERSISTENCE unexpected #" + mHwLight.id
                            + ": brightness=0x" + Integer.toHexString(brightness));
                    return;
                }
                // Ideally, we'd like to set the brightness mode through the SF/HWC as well, but
                // right now we just fall back to the old path through Lights brightessMode is
                // anything but USER or the device shouldBeInLowPersistenceMode().
                if (brightnessMode == BRIGHTNESS_MODE_USER && !shouldBeInLowPersistenceMode()
                        && mSurfaceControlMaximumBrightness == 255) {
                    // TODO: the last check should be mSurfaceControlMaximumBrightness != 0; the
                    // reason we enforce 255 right now is to stay consistent with the old path. In
                    // the future, the framework should be refactored so that brightness is a float
                    // between 0.0f and 1.0f, and the actual number of supported brightness levels
                    // is determined in the device-specific implementation.
                    if (DEBUG) {
                        Slog.d(TAG, "Using new setBrightness path!");
                    }
                    SurfaceControl.setDisplayBrightness(mDisplayToken,
                            (float) brightness / mSurfaceControlMaximumBrightness);
                } else {
                    int color = brightness & 0x000000ff;
                    color = 0xff000000 | (color << 16) | (color << 8) | color;
                    setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
                }
            }
        }

        @Override
        public void setColor(int color) {
            synchronized (this) {
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        @Override
        public void setFlashing(int color, int mode, int onMS, int offMS) {
            synchronized (this) {
                setLightLocked(color, mode, onMS, offMS, BRIGHTNESS_MODE_USER);
            }
        }

        @Override
        public void pulse() {
            pulse(0x00ffffff, 7);
        }

        @Override
        public void pulse(int color, int onMS) {
            synchronized (this) {
                if (mColor == 0 && !mFlashing) {
                    setLightLocked(color, LIGHT_FLASH_HARDWARE, onMS, 1000,
                            BRIGHTNESS_MODE_USER);
                    mColor = 0;
                    mH.postDelayed(this::stopFlashing, onMS);
                }
            }
        }

        @Override
        public void turnOff() {
            synchronized (this) {
                setLightLocked(0, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        @Override
        public void setVrMode(boolean enabled) {
            synchronized (this) {
                if (mVrModeEnabled != enabled) {
                    mVrModeEnabled = enabled;

                    mUseLowPersistenceForVR =
                            (getVrDisplayMode() == Settings.Secure.VR_DISPLAY_MODE_LOW_PERSISTENCE);
                    if (shouldBeInLowPersistenceMode()) {
                        mLastBrightnessMode = mBrightnessMode;
                    }

                    // NOTE: We do not trigger a call to setLightLocked here.  We do not know the
                    // current brightness or other values when leaving VR so we avoid any incorrect
                    // jumps. The code that calls this method will immediately issue a brightness
                    // update which is when the change will occur.
                }
            }
        }

        private void stopFlashing() {
            synchronized (this) {
                setLightLocked(mColor, LIGHT_FLASH_NONE, 0, 0, BRIGHTNESS_MODE_USER);
            }
        }

        private void setLightLocked(int color, int mode, int onMS, int offMS, int brightnessMode) {
            if (shouldBeInLowPersistenceMode()) {
                brightnessMode = BRIGHTNESS_MODE_LOW_PERSISTENCE;
            } else if (brightnessMode == BRIGHTNESS_MODE_LOW_PERSISTENCE) {
                brightnessMode = mLastBrightnessMode;
            }

            if (!mInitialized || color != mColor || mode != mMode || onMS != mOnMS ||
                    offMS != mOffMS || mBrightnessMode != brightnessMode) {
                if (DEBUG) {
                    Slog.v(TAG, "setLight #" + mHwLight.id + ": color=#"
                            + Integer.toHexString(color) + ": brightnessMode=" + brightnessMode);
                }
                mInitialized = true;
                mLastColor = mColor;
                mColor = color;
                mMode = mode;
                mOnMS = onMS;
                mOffMS = offMS;
                mBrightnessMode = brightnessMode;
                setLightUnchecked(color, mode, onMS, offMS, brightnessMode);
            }
        }

        private void setLightUnchecked(int color, int mode, int onMS, int offMS,
                int brightnessMode) {
            Trace.traceBegin(Trace.TRACE_TAG_POWER, "setLightState(" + mHwLight.id + ", 0x"
                    + Integer.toHexString(color) + ")");
            if (mVintfLights != null) {
                HwLightState lightState = new HwLightState();
                lightState.color = color;
                lightState.flashMode = (byte) mode;
                lightState.flashOnMs = onMS;
                lightState.flashOffMs = offMS;
                lightState.brightnessMode = (byte) brightnessMode;
                try {
                    mVintfLights.setLightState(mHwLight.id, lightState);
                } catch (RemoteException | UnsupportedOperationException ex) {
                    Slog.e(TAG, "Failed issuing setLightState", ex);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_POWER);
                }
            } else {
                try {
                    setLight_native(mHwLight.id, color, mode, onMS, offMS, brightnessMode);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_POWER);
                }
            }
        }

        private boolean shouldBeInLowPersistenceMode() {
            return mVrModeEnabled && mUseLowPersistenceForVR;
        }

        private HwLight getHwLight() {
            return mHwLight;
        }

        private int getColor() {
            return mColor;
        }

        private HwLight mHwLight;
        private int mColor;
        private int mMode;
        private int mOnMS;
        private int mOffMS;
        private boolean mFlashing;
        private int mBrightnessMode;
        private int mLastBrightnessMode;
        private int mLastColor;
        private boolean mVrModeEnabled;
        private boolean mUseLowPersistenceForVR;
        private boolean mInitialized;
    }

    public LightsService(Context context) {
        this(context,
                ILights.Stub.asInterface(
                        ServiceManager.getService("android.hardware.light.ILights/default")),
                Looper.myLooper());
    }

    @VisibleForTesting
    LightsService(Context context, ILights service, Looper looper) {
        super(context);
        mH = new Handler(looper);
        mVintfLights = service;
        mManagerService = new LightsManagerBinderService();
        populateAvailableLights(context);
    }

    private void populateAvailableLights(Context context) {
        mLights = new LightImpl[LightsManager.LIGHT_ID_COUNT];
        mLightsById = new SparseArray<>();

        if (mVintfLights != null) {
            try {
                for (HwLight availableLight : mVintfLights.getLights()) {
                    LightImpl light = new LightImpl(context, availableLight);
                    int type = (int) availableLight.type;
                    if (0 <= type && type < mLights.length && mLights[type] == null) {
                        mLights[type] = light;
                    }
                    mLightsById.put(availableLight.id, light);
                }
            } catch (RemoteException ex) {
                Slog.e(TAG, "Unable to get lights for initialization", ex);
            }
        }

        // In the case where only the old HAL is available, all lights will be initialized here
        for (int i = 0; i < mLights.length; i++) {
            if (mLights[i] == null) {
                // The ordinal can be anything if there is only 1 light of each type. Set it to 1.
                HwLight light = new HwLight();
                light.id = (byte) i;
                light.ordinal = 1;
                light.type = (byte) i;

                mLights[i] = new LightImpl(context, light);
                mLightsById.put(i, mLights[i]);
            }
        }
    }

    @Override
    public void onStart() {
        publishLocalService(LightsManager.class, mService);
        publishBinderService(Context.LIGHTS_SERVICE, mManagerService);
    }

    @Override
    public void onBootPhase(int phase) {
    }

    private int getVrDisplayMode() {
        int currentUser = ActivityManager.getCurrentUser();
        return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.VR_DISPLAY_MODE,
                /*default*/Settings.Secure.VR_DISPLAY_MODE_LOW_PERSISTENCE,
                currentUser);
    }

    private final LightsManager mService = new LightsManager() {
        @Override
        public LogicalLight getLight(int lightType) {
            if (mLights != null && 0 <= lightType && lightType < mLights.length) {
                return mLights[lightType];
            } else {
                return null;
            }
        }
    };

    /**
     * Returns whether a light is system-use-only or should be accessible to
     * applications using the {@link android.hardware.lights.LightsManager} API.
     */
    private static boolean isSystemLight(HwLight light) {
        // LIGHT_ID_COUNT comes from the 2.0 HIDL HAL and only contains system
        // lights. Newly added lights will be made available via the
        // LightsManager API.
        return 0 <= light.type && light.type < LightsManager.LIGHT_ID_COUNT;
    }

    static native void setLight_native(int light, int color, int mode,
            int onMS, int offMS, int brightnessMode);
}
