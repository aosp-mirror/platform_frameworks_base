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

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.light.HwLight;
import android.hardware.light.HwLightState;
import android.hardware.light.ILights;
import android.hardware.lights.ILightsManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class LightsService extends SystemService {
    static final String TAG = "LightsService";
    static final boolean DEBUG = false;

    private final LightImpl[] mLightsByType = new LightImpl[LightsManager.LIGHT_ID_COUNT];
    private final SparseArray<LightImpl> mLightsById = new SparseArray<>();

    @Nullable
    private final Supplier<ILights> mVintfLights;

    @VisibleForTesting
    final LightsManagerBinderService mManagerService;

    private Handler mH;

    private final class LightsManagerBinderService extends ILightsManager.Stub {
        LightsManagerBinderService() {
            super(PermissionEnforcer.fromContext(getContext()));
        }

        private final class Session implements Comparable<Session> {
            final IBinder mToken;
            final SparseArray<LightState> mRequests = new SparseArray<>();
            final int mPriority;

            Session(IBinder token, int priority) {
                mToken = token;
                mPriority = priority;
            }

            void setRequest(int lightId, LightState state) {
                if (state != null) {
                    mRequests.put(lightId, state);
                } else {
                    mRequests.remove(lightId);
                }
            }

            @Override
            public int compareTo(Session otherSession) {
                // Sort descending by priority
                return Integer.compare(otherSession.mPriority, mPriority);
            }
        }

        @GuardedBy("LightsService.this")
        private final List<Session> mSessions = new ArrayList<>();

        /**
         * Returns the lights available for apps to control on the device. Only lights that aren't
         * reserved for system use are available to apps.
         */
        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public List<Light> getLights() {
            getLights_enforcePermission();

            synchronized (LightsService.this) {
                final List<Light> lights = new ArrayList<Light>();
                for (int i = 0; i < mLightsById.size(); i++) {
                    if (!mLightsById.valueAt(i).isSystemLight()) {
                        HwLight hwLight = mLightsById.valueAt(i).mHwLight;
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
        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void setLightStates(IBinder token, int[] lightIds, LightState[] lightStates) {
            setLightStates_enforcePermission();
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

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public @Nullable LightState getLightState(int lightId) {
            getLightState_enforcePermission();

            synchronized (LightsService.this) {
                final LightImpl light = mLightsById.get(lightId);
                if (light == null || light.isSystemLight()) {
                    throw new IllegalArgumentException("Invalid light: " + lightId);
                }
                return new LightState(light.getColor());
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void openSession(IBinder token, int priority) {
            openSession_enforcePermission();
            Preconditions.checkNotNull(token);

            synchronized (LightsService.this) {
                Preconditions.checkState(getSessionLocked(token) == null, "already registered");
                try {
                    token.linkToDeath(() -> closeSessionInternal(token), 0);
                    mSessions.add(new Session(token, priority));
                    Collections.sort(mSessions);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't open session, client already died" , e);
                    throw new IllegalArgumentException("Client is already dead.");
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void closeSession(IBinder token) {
            closeSession_enforcePermission();
            Preconditions.checkNotNull(token);
            closeSessionInternal(token);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            synchronized (LightsService.this) {
                if (mVintfLights != null) {
                    pw.println("Service: aidl (" + mVintfLights.get() + ")");
                } else {
                    pw.println("Service: hidl");
                }

                pw.println("Lights:");
                for (int i = 0; i < mLightsById.size(); i++) {
                    final LightImpl light = mLightsById.valueAt(i);
                    pw.println(String.format("  Light id=%d ordinal=%d color=%08x",
                            light.mHwLight.id, light.mHwLight.ordinal, light.getColor()));
                }

                pw.println("Session clients:");
                for (Session session : mSessions) {
                    pw.println("  Session token=" + session.mToken);
                    for (int i = 0; i < session.mRequests.size(); i++) {
                        pw.println(String.format("    Request id=%d color=%08x",
                                session.mRequests.keyAt(i),
                                session.mRequests.valueAt(i).getColor()));
                    }
                }
            }
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
            for (int lightId : lightIds) {
                final LightImpl light = mLightsById.get(lightId);
                Preconditions.checkState(light != null && !light.isSystemLight(),
                        "Invalid lightId " + lightId);
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
                if (!light.isSystemLight()) {
                    LightState state = states.get(light.mHwLight.id);
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

        private LightImpl(Context context, HwLight hwLight) {
            mHwLight = hwLight;
        }

        @Override
        public void setBrightness(float brightness) {
            setBrightness(brightness, BRIGHTNESS_MODE_USER);
        }

        @Override
        public void setBrightness(float brightness, int brightnessMode) {
            if (Float.isNaN(brightness)) {
                Slog.w(TAG, "Brightness is not valid: " + brightness);
                return;
            }
            synchronized (this) {
                // LOW_PERSISTENCE cannot be manually set
                if (brightnessMode == BRIGHTNESS_MODE_LOW_PERSISTENCE) {
                    Slog.w(TAG, "setBrightness with LOW_PERSISTENCE unexpected #" + mHwLight.id
                            + ": brightness=" + brightness);
                    return;
                }
                int brightnessInt = BrightnessSynchronizer.brightnessFloatToInt(brightness);
                int color = brightnessInt & 0x000000ff;
                color = 0xff000000 | (color << 16) | (color << 8) | color;
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
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
            try {
                if (mVintfLights != null) {
                    HwLightState lightState = new HwLightState();
                    lightState.color = color;
                    lightState.flashMode = (byte) mode;
                    lightState.flashOnMs = onMS;
                    lightState.flashOffMs = offMS;
                    lightState.brightnessMode = (byte) brightnessMode;
                    mVintfLights.get().setLightState(mHwLight.id, lightState);
                } else {
                    setLight_native(mHwLight.id, color, mode, onMS, offMS, brightnessMode);
                }
            } catch (RemoteException | UnsupportedOperationException ex) {
                Slog.e(TAG, "Failed issuing setLightState", ex);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }

        private boolean shouldBeInLowPersistenceMode() {
            return mVrModeEnabled && mUseLowPersistenceForVR;
        }

        /**
         * Returns whether a light is system-use-only or should be accessible to
         * applications using the {@link android.hardware.lights.LightsManager} API.
         */
        private boolean isSystemLight() {
            // LIGHT_ID_COUNT comes from the 2.0 HIDL HAL and only contains system lights.
            // Newly-added lights are made available via the public LightsManager API.
            return (0 <= mHwLight.type && mHwLight.type < LightsManager.LIGHT_ID_COUNT);
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
        this(context, new VintfHalCache(), Looper.myLooper());
    }

    @VisibleForTesting
    LightsService(Context context, Supplier<ILights> service, Looper looper) {
        super(context);
        mH = new Handler(looper);
        mVintfLights = service.get() != null ? service : null;

        populateAvailableLights(context);
        mManagerService = new LightsManagerBinderService();
    }

    private void populateAvailableLights(Context context) {
        if (mVintfLights != null) {
            populateAvailableLightsFromAidl(context);
        } else {
            populateAvailableLightsFromHidl(context);
        }

        for (int i = mLightsById.size() - 1; i >= 0; i--) {
            final int type = mLightsById.keyAt(i);
            if (0 <= type && type < mLightsByType.length) {
                mLightsByType[type] = mLightsById.valueAt(i);
            }
        }
    }

    private void populateAvailableLightsFromAidl(Context context) {
        try {
            for (HwLight hwLight : mVintfLights.get().getLights()) {
                mLightsById.put(hwLight.id, new LightImpl(context, hwLight));
            }
        } catch (RemoteException ex) {
            Slog.e(TAG, "Unable to get lights from HAL", ex);
        }
    }

    private void populateAvailableLightsFromHidl(Context context) {
        for (int i = 0; i < mLightsByType.length; i++) {
            HwLight hwLight = new HwLight();
            hwLight.id = (byte) i;
            hwLight.ordinal = 1;
            hwLight.type = (byte) i;
            mLightsById.put(hwLight.id, new LightImpl(context, hwLight));
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
            if (mLightsByType != null && 0 <= lightType && lightType < mLightsByType.length) {
                return mLightsByType[lightType];
            } else {
                return null;
            }
        }
    };

    private static class VintfHalCache implements Supplier<ILights>, IBinder.DeathRecipient {
        @GuardedBy("this")
        private ILights mInstance = null;

        @Override
        public synchronized ILights get() {
            if (mInstance == null) {
                IBinder binder = Binder.allowBlocking(
                        ServiceManager.waitForDeclaredService(ILights.DESCRIPTOR + "/default"));
                if (binder != null) {
                    mInstance = ILights.Stub.asInterface(binder);
                    try {
                        binder.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to register DeathRecipient for " + mInstance);
                    }
                }
            }
            return mInstance;
        }

        @Override
        public synchronized void binderDied() {
            mInstance = null;
        }
    }

    static native void setLight_native(int light, int color, int mode,
            int onMS, int offMS, int brightnessMode);
}
