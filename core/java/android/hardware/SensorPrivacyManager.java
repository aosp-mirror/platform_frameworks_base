/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.SensorPrivacyIndividualEnabledSensorProto;
import android.service.SensorPrivacySensorProto;
import android.service.SensorPrivacyToggleSourceProto;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.camera.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class provides information about the microphone and camera toggles.
 */
@SystemService(Context.SENSOR_PRIVACY_SERVICE)
public final class SensorPrivacyManager {

    private static final String LOG_TAG = SensorPrivacyManager.class.getSimpleName();

    /**
     * Unique Id of this manager to identify to the service
     * @hide
     */
    private IBinder token = new Binder();

    /**
     * An extra containing a sensor
     * @hide
     */
    public static final String EXTRA_SENSOR = SensorPrivacyManager.class.getName()
            + ".extra.sensor";

    /**
     * An extra containing the notification id that triggered the intent
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_ID = SensorPrivacyManager.class.getName()
            + ".extra.notification_id";

    /**
     * An extra indicating if all sensors are affected
     * @hide
     */
    public static final String EXTRA_ALL_SENSORS = SensorPrivacyManager.class.getName()
            + ".extra.all_sensors";

    /**
     * An extra containing the sensor type
     * @hide
     */
    public static final String EXTRA_TOGGLE_TYPE = SensorPrivacyManager.class.getName()
            + ".extra.toggle_type";

    /**
     * Sensor constants which are used in {@link SensorPrivacyManager}
     */
    public static class Sensors {

        private Sensors() {}

        /**
         * Constant for the microphone
         */
        public static final int MICROPHONE = SensorPrivacySensorProto.MICROPHONE;

        /**
         * Constant for the camera
         */
        public static final int CAMERA = SensorPrivacySensorProto.CAMERA;

        /**
         * Individual sensors not listed in {@link Sensors}
         *
         * @hide
         */
        @IntDef(value = {
                MICROPHONE,
                CAMERA
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Sensor {}
    }

    /**
     * Source through which Privacy Sensor was toggled.
     * @hide
     */
    @TestApi
    public static class Sources {
        private Sources() {}

        /**
         * Constant for the Quick Setting Tile.
         */
        public static final int QS_TILE = SensorPrivacyToggleSourceProto.QS_TILE;

        /**
         * Constant for the Settings.
         */
        public static final int SETTINGS = SensorPrivacyToggleSourceProto.SETTINGS;

        /**
         * Constant for Dialog.
         */
        public static final int DIALOG = SensorPrivacyToggleSourceProto.DIALOG;

        /**
         * Constant for SHELL.
         */
        public static final int SHELL = SensorPrivacyToggleSourceProto.SHELL;

        /**
         * Constant for OTHER.
         */
        public static final int OTHER = SensorPrivacyToggleSourceProto.OTHER;

        /**
         * Constant for SAFETY_CENTER.
         */
        public static final int SAFETY_CENTER = SensorPrivacyToggleSourceProto.SAFETY_CENTER;

        /**
         * Source for toggling sensors
         *
         * @hide
         */
        @IntDef(value = {
                QS_TILE,
                SETTINGS,
                DIALOG,
                SHELL,
                OTHER,
                SAFETY_CENTER
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Source {}

    }


    /**
     * Constant for software toggle.
     */
    public static final int TOGGLE_TYPE_SOFTWARE =
            SensorPrivacyIndividualEnabledSensorProto.SOFTWARE;

    /**
     * Constant for hardware toggle.
     */
    public static final int TOGGLE_TYPE_HARDWARE =
            SensorPrivacyIndividualEnabledSensorProto.HARDWARE;

    /**
     * Types of toggles which can exist for sensor privacy
     *
     * @hide
     */
    @IntDef(value = {
            TOGGLE_TYPE_SOFTWARE,
            TOGGLE_TYPE_HARDWARE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ToggleType {}

    /**
     * Types of state which can exist for the sensor privacy toggle
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public static class StateTypes {
        private StateTypes() {}

        /**
         * Constant indicating privacy is enabled.
         */
        public static final int ENABLED = SensorPrivacyIndividualEnabledSensorProto.ENABLED;

        /**
         * Constant indicating privacy is disabled.
         */
        public static final int DISABLED = SensorPrivacyIndividualEnabledSensorProto.DISABLED;

         /**
         * Constant indicating privacy is enabled except for the automotive driver assistance apps
         * which are required by car manufacturer for driving.
         */
        public static final int ENABLED_EXCEPT_ALLOWLISTED_APPS =
                SensorPrivacyIndividualEnabledSensorProto.ENABLED_EXCEPT_ALLOWLISTED_APPS;

        /**
         * Types of state which can exist for a sensor privacy toggle
         *
         * @hide
         */
        @IntDef(value = {
                ENABLED,
                DISABLED,
                ENABLED_EXCEPT_ALLOWLISTED_APPS
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface StateType {}

    }

    /**
     * A class implementing this interface can register with the {@link
     * android.hardware.SensorPrivacyManager} to receive notification when the sensor privacy
     * state changes.
     *
     * @hide
     */
    @SystemApi
    public interface OnSensorPrivacyChangedListener {
        /**
         * Callback invoked when the sensor privacy state changes.
         *
         * @param params Parameters describing the new state
         */
        default void onSensorPrivacyChanged(@NonNull SensorPrivacyChangedParams params) {
            onSensorPrivacyChanged(params.mSensor, params.mEnabled);
        }

        /**
         * Callback invoked when the sensor privacy state changes.
         *
         * @param sensor the sensor whose state is changing
         * @param enabled true if sensor privacy is enabled, false otherwise.
         *
         * @deprecated Please use
         * {@link #onSensorPrivacyChanged(SensorPrivacyChangedParams)}
         */
        @Deprecated
        void onSensorPrivacyChanged(int sensor, boolean enabled);

        /**
         * A class containing information about what the sensor privacy state has changed to.
         */
        class SensorPrivacyChangedParams {

            private int mToggleType;
            private int mSensor;
            private boolean mEnabled;
            private int mState;

            @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
            private SensorPrivacyChangedParams(int toggleType, int sensor, int state) {
                mToggleType = toggleType;
                mSensor = sensor;
                mState = state;
                if (state == StateTypes.ENABLED) {
                    mEnabled = true;
                } else {
                    mEnabled = false;
                }
            }

            private SensorPrivacyChangedParams(int toggleType, int sensor, boolean enabled) {
                mToggleType = toggleType;
                mSensor = sensor;
                mEnabled = enabled;
            }

            public @ToggleType int getToggleType() {
                return mToggleType;
            }

            public @Sensors.Sensor int getSensor() {
                return mSensor;
            }

            public boolean isEnabled() {
                return mEnabled;
            }

            @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
            public @StateTypes.StateType int getState() {
                return mState;
            }

        }
    }

    private static final Object sInstanceLock = new Object();

    private final Object mLock = new Object();

    @GuardedBy("sInstanceLock")
    private static SensorPrivacyManager sInstance;

    @NonNull
    private final Context mContext;

    @NonNull
    private final ISensorPrivacyManager mService;

    @GuardedBy("mLock")
    private final ArrayMap<Pair<Integer, Integer>, Boolean> mToggleSupportCache = new ArrayMap<>();

    @NonNull
    private final ArrayMap<OnAllSensorPrivacyChangedListener, ISensorPrivacyListener> mListeners;

    /** Registered listeners */
    @GuardedBy("mLock")
    @NonNull
    private final ArrayMap<OnSensorPrivacyChangedListener, Executor> mToggleListeners =
            new ArrayMap<>();

    /** Listeners registered using the deprecated APIs and which
     * OnSensorPrivacyChangedListener they're using. */
    @GuardedBy("mLock")
    @NonNull
    private final ArrayMap<Pair<Integer, OnSensorPrivacyChangedListener>,
            OnSensorPrivacyChangedListener> mLegacyToggleListeners = new ArrayMap<>();

    /** The singleton ISensorPrivacyListener for IPC which will be used to dispatch to local
     * listeners */
    @NonNull
    private final ISensorPrivacyListener mIToggleListener = new ISensorPrivacyListener.Stub() {
        @Override
        public void onSensorPrivacyChanged(int toggleType, int sensor, boolean enabled) {
            synchronized (mLock) {
                for (int i = 0; i < mToggleListeners.size(); i++) {
                    OnSensorPrivacyChangedListener listener = mToggleListeners.keyAt(i);
                    if (Flags.cameraPrivacyAllowlist()) {
                        int state = enabled ?  StateTypes.ENABLED : StateTypes.DISABLED;
                        mToggleListeners.valueAt(i).execute(() -> listener
                                .onSensorPrivacyChanged(new OnSensorPrivacyChangedListener
                                        .SensorPrivacyChangedParams(toggleType, sensor, state)));
                    } else {
                        mToggleListeners.valueAt(i).execute(() -> listener
                                .onSensorPrivacyChanged(new OnSensorPrivacyChangedListener
                                        .SensorPrivacyChangedParams(toggleType, sensor, enabled)));
                    }
                }
            }
        }

        @Override
        @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
        public void onSensorPrivacyStateChanged(@ToggleType int toggleType,
                @Sensors.Sensor int sensor, @StateTypes.StateType int state) {
            synchronized (mLock) {
                for (int i = 0; i < mToggleListeners.size(); i++) {
                    OnSensorPrivacyChangedListener listener = mToggleListeners.keyAt(i);
                    mToggleListeners.valueAt(i).execute(() -> listener
                            .onSensorPrivacyChanged(new OnSensorPrivacyChangedListener
                                    .SensorPrivacyChangedParams(toggleType, sensor, state)));
                }
            }
        }

    };

    /** Whether the singleton ISensorPrivacyListener has been registered */
    @GuardedBy("mLock")
    @NonNull
    private boolean mToggleListenerRegistered = false;

    private Boolean mRequiresAuthentication = null;

    /**
     * Private constructor to ensure only a single instance is created.
     */
    private SensorPrivacyManager(Context context, ISensorPrivacyManager service) {
        mContext = context;
        mService = service;
        mListeners = new ArrayMap<>();
    }

    /**
     * Returns the single instance of the SensorPrivacyManager.
     *
     * @hide
     */
    public static SensorPrivacyManager getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                try {
                    IBinder b = ServiceManager.getServiceOrThrow(Context.SENSOR_PRIVACY_SERVICE);
                    ISensorPrivacyManager service = ISensorPrivacyManager.Stub.asInterface(b);
                    sInstance = new SensorPrivacyManager(context, service);
                } catch (ServiceManager.ServiceNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return sInstance;
        }
    }

    /**
     * Returns the single instance of the SensorPrivacyManager.
     *
     * @hide
     */
    public static SensorPrivacyManager getInstance(Context context, ISensorPrivacyManager service) {
        synchronized (sInstanceLock) {
            sInstance = new SensorPrivacyManager(context, service);
            return sInstance;
        }
    }

    /**
     * Checks if the given toggle is supported on this device
     * @param sensor The sensor to check
     * @return whether the toggle for the sensor is supported on this device.
     */
    public boolean supportsSensorToggle(@Sensors.Sensor int sensor) {
        return supportsSensorToggle(TOGGLE_TYPE_SOFTWARE, sensor);
    }

    /**
     * Checks if the given toggle is supported on this device
     * @param sensor The sensor to check
     * @return whether the toggle for the sensor is supported on this device.
     *
     */
    public boolean supportsSensorToggle(@ToggleType int toggleType, @Sensors.Sensor int sensor) {
        try {
            Pair key = new Pair(toggleType, sensor);
            synchronized (mLock) {
                Boolean val = mToggleSupportCache.get(key);
                if (val == null) {
                    val = mService.supportsSensorToggle(toggleType, sensor);
                    mToggleSupportCache.put(key, val);
                }
                return val;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     *
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     *
     * @param sensor the sensor to listen to changes to
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of sensor
     *                       privacy changes.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void addSensorPrivacyListener(@Sensors.Sensor int sensor,
            @NonNull OnSensorPrivacyChangedListener listener) {
        addSensorPrivacyListener(sensor, mContext.getMainExecutor(), listener);
    }

    /**
     *
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     *
     * @param sensor the sensor to listen to changes to
     * @param userId the user's id
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of sensor
     *                 privacy changes.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void addSensorPrivacyListener(@Sensors.Sensor int sensor, int userId,
            @NonNull OnSensorPrivacyChangedListener listener) {
        addSensorPrivacyListener(sensor, mContext.getMainExecutor(), listener);
    }

    /**
     *
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     *
     * @param sensor the sensor to listen to changes to
     * @param executor the executor to dispatch the callback on
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of sensor
     *                       privacy changes.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void addSensorPrivacyListener(@Sensors.Sensor int sensor, @NonNull Executor executor,
            @NonNull OnSensorPrivacyChangedListener listener) {
        Pair<Integer, OnSensorPrivacyChangedListener> pair = new Pair(sensor, listener);
        OnSensorPrivacyChangedListener toggleListener = new OnSensorPrivacyChangedListener() {
            @Override
            public void onSensorPrivacyChanged(SensorPrivacyChangedParams params) {
                if (params.getSensor() == sensor) {
                    listener.onSensorPrivacyChanged(params);
                }
            }
            @Override
            public void onSensorPrivacyChanged(int sensor, boolean enabled) {
            }
        };

        synchronized (mLock) {
            mLegacyToggleListeners.put(pair, toggleListener);
            addSensorPrivacyListenerLocked(executor, toggleListener);
        }
    }

    /**
     *
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     *
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of
     *                 sensor privacy changes.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void addSensorPrivacyListener(@NonNull OnSensorPrivacyChangedListener listener) {
        addSensorPrivacyListener(mContext.getMainExecutor(), listener);
    }

    /**
     *
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     *
     * @param executor the executor to dispatch the callback on
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of
     *                 sensor privacy changes.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void addSensorPrivacyListener(@NonNull Executor executor,
            @NonNull OnSensorPrivacyChangedListener listener) {
        synchronized (mLock) {
            addSensorPrivacyListenerLocked(executor, listener);
        }
    }

    @GuardedBy("mLock")
    private void addSensorPrivacyListenerLocked(@NonNull Executor executor,
            @NonNull OnSensorPrivacyChangedListener listener) {
        if (!mToggleListenerRegistered) {
            try {
                mService.addToggleSensorPrivacyListener(mIToggleListener);
                mToggleListenerRegistered = true;
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
        if (mToggleListeners.containsKey(listener)) {
            throw new IllegalArgumentException("listener is already registered");
        }
        mToggleListeners.put(listener, executor);
    }

    /**
     * Unregisters the specified listener from receiving notifications when the state of any sensor
     * privacy changes.
     *
     * @param listener the OnSensorPrivacyChangedListener to be unregistered from notifications when
     *                 sensor privacy changes.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void removeSensorPrivacyListener(@Sensors.Sensor int sensor,
            @NonNull OnSensorPrivacyChangedListener listener) {
        Pair<Integer, OnSensorPrivacyChangedListener> pair = new Pair(sensor, listener);
        synchronized (mLock) {
            OnSensorPrivacyChangedListener onToggleSensorPrivacyChangedListener =
                    mLegacyToggleListeners.remove(pair);
            if (onToggleSensorPrivacyChangedListener != null) {
                removeSensorPrivacyListenerLocked(onToggleSensorPrivacyChangedListener);
            }
        }
    }

    /**
     * Unregisters the specified listener from receiving notifications when the state of any sensor
     * privacy changes.
     *
     * @param listener the {@link OnSensorPrivacyChangedListener} to be unregistered from
     *                 notifications when sensor privacy changes.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void removeSensorPrivacyListener(
            @NonNull OnSensorPrivacyChangedListener listener) {
        synchronized (mLock) {
            removeSensorPrivacyListenerLocked(listener);
        }
    }

    @GuardedBy("mLock")
    private void removeSensorPrivacyListenerLocked(
            @NonNull OnSensorPrivacyChangedListener listener) {
        mToggleListeners.remove(listener);
        if (mToggleListeners.size() == 0) {
            try {
                mService.removeToggleSensorPrivacyListener(mIToggleListener);
                mToggleListenerRegistered = false;
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether sensor privacy is currently enabled by software control for a specific
     * sensor.
     *
     * @return true if sensor privacy is currently enabled, false otherwise.
     *
     * @deprecated Prefer to use {@link #isSensorPrivacyEnabled(int, int)}
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public boolean isSensorPrivacyEnabled(@Sensors.Sensor int sensor) {
        return isSensorPrivacyEnabled(TOGGLE_TYPE_SOFTWARE, sensor);
    }

    /**
     * Returns whether sensor privacy is currently enabled for a specific sensor.
     *
     * @return true if sensor privacy is currently enabled, false otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public boolean isSensorPrivacyEnabled(@ToggleType int toggleType,
            @Sensors.Sensor int sensor) {
        try {
            return mService.isToggleSensorPrivacyEnabled(toggleType, sensor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether sensor privacy is currently enabled for a specific sensor.
     * Combines the state of the SW + HW toggles and returns true if either the
     * SOFTWARE or the HARDWARE toggles are enabled.
     *
     * @return true if sensor privacy is currently enabled, false otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public boolean areAnySensorPrivacyTogglesEnabled(@Sensors.Sensor int sensor) {
        try {
            return mService.isCombinedToggleSensorPrivacyEnabled(sensor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns sensor privacy state for a specific sensor.
     *
     * @param toggleType The type of toggle to use
     * @param sensor The sensor to check
     * @return int sensor privacy state.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public @StateTypes.StateType int getSensorPrivacyState(@ToggleType int toggleType,
            @Sensors.Sensor int sensor) {
        try {
            return mService.getToggleSensorPrivacyState(toggleType, sensor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

   /**
     * Returns if camera privacy is enabled for a specific package.
     *
     * @param packageName The package to check
     * @return boolean camera privacy state.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public boolean isCameraPrivacyEnabled(@NonNull String packageName) {
        try {
            return mService.isCameraPrivacyEnabled(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns camera privacy allowlist.
     *
     * @return List of automotive driver assistance packages for
     * privacy allowlisting.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public @NonNull List<String>  getCameraPrivacyAllowlist() {
        synchronized (mLock) {
            try {
                return mService.getCameraPrivacyAllowlist();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets camera privacy allowlist.
     *
     * @param allowlist List of automotive driver assistance packages for
     * privacy allowlisting.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public void setCameraPrivacyAllowlist(@NonNull List<String> allowlist) {
        synchronized (mLock) {
            try {
                mService.setCameraPrivacyAllowlist(allowlist);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor.
     *
     * @param sensor the sensor which to change the state for
     * @param enable the state to which sensor privacy should be set.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setSensorPrivacy(@Sensors.Sensor int sensor,
            boolean enable) {
        setSensorPrivacy(resolveSourceFromCurrentContext(), sensor, enable,
                UserHandle.USER_CURRENT);
    }

    private @Sources.Source int resolveSourceFromCurrentContext() {
        String packageName = mContext.getOpPackageName();
        if (Objects.equals(packageName,
                mContext.getPackageManager().getPermissionControllerPackageName())) {
            return Sources.SAFETY_CENTER;
        }
        return Sources.OTHER;
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor.
     *
     * @param sensor the sensor which to change the state for
     * @param state the state to which sensor privacy should be set.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public void setSensorPrivacyState(@Sensors.Sensor int sensor,
            @StateTypes.StateType int state) {
        setSensorPrivacyState(resolveSourceFromCurrentContext(), sensor, state);
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor.
     *
     * @param sensor the sensor which to change the state for
     * @param enable the state to which sensor privacy should be set.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setSensorPrivacy(@Sources.Source int source, @Sensors.Sensor int sensor,
            boolean enable) {
        setSensorPrivacy(source, sensor, enable, UserHandle.USER_CURRENT);
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor.
     *
     * @param sensor the sensor which to change the state for
     * @param enable the state to which sensor privacy should be set.
     * @param userId the user's id
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setSensorPrivacy(@Sources.Source int source, @Sensors.Sensor int sensor,
            boolean enable, @UserIdInt int userId) {
        try {
            mService.setToggleSensorPrivacy(userId, source, sensor, enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor.
     *
     * @param source the source using which the sensor is toggled
     * @param sensor the sensor which to change the state for
     * @param state the state to which sensor privacy should be set.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public void setSensorPrivacyState(@Sources.Source int source, @Sensors.Sensor int sensor,
            @StateTypes.StateType int state) {
        try {
            mService.setToggleSensorPrivacyState(mContext.getUserId(), source, sensor, state);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor for the profile group of
     * context's user.
     *
     * @param source the source using which the sensor is toggled.
     * @param sensor the sensor which to change the state for
     * @param enable the state to which sensor privacy should be set.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setSensorPrivacyForProfileGroup(@Sources.Source int source,
            @Sensors.Sensor int sensor, boolean enable) {
        setSensorPrivacyForProfileGroup(source , sensor, enable, UserHandle.USER_CURRENT);
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor for the profile group of
     * context's user.
     *
     * @param source the source using which the sensor is toggled.
     * @param sensor the sensor which to change the state for
     * @param enable the state to which sensor privacy should be set.
     * @param userId the user's id
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setSensorPrivacyForProfileGroup(@Sources.Source int source,
            @Sensors.Sensor int sensor, boolean enable, @UserIdInt int userId) {
        try {
            mService.setToggleSensorPrivacyForProfileGroup(userId, source, sensor, enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets sensor privacy to the specified state for an individual sensor for the profile group of
     * context's user.
     *
     * @param source the source using which the sensor is toggled.
     * @param sensor the sensor which to change the state for
     * @param state the state to which sensor privacy should be set.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    public void setSensorPrivacyStateForProfileGroup(@Sources.Source int source,
            @Sensors.Sensor int sensor, @StateTypes.StateType int state) {
        try {
            mService.setToggleSensorPrivacyStateForProfileGroup(mContext.getUserId(), source,
                    sensor, state);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Don't show dialogs to turn off sensor privacy for this package.
     *
     * @param suppress Whether to suppress or re-enable.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void suppressSensorPrivacyReminders(int sensor,
            boolean suppress) {
        suppressSensorPrivacyReminders(sensor, suppress, UserHandle.USER_CURRENT);
    }

    /**
     * Don't show dialogs to turn off sensor privacy for this package.
     *
     * @param suppress Whether to suppress or re-enable.
     * @param userId the user's id
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void suppressSensorPrivacyReminders(int sensor,
            boolean suppress, @UserIdInt int userId) {
        try {
            mService.suppressToggleSensorPrivacyReminders(userId, sensor,
                    token, suppress);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return whether the device is required to be unlocked to change software state.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public boolean requiresAuthentication() {
        if (mRequiresAuthentication == null) {
            try {
                mRequiresAuthentication = mService.requiresAuthentication();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return mRequiresAuthentication;
    }

    /**
     * If sensor privacy for the provided sensor is enabled then this call will show the user the
     * dialog which is shown when an application attempts to use that sensor. If privacy isn't
     * enabled then this does nothing.
     *
     * This call can only be made by the system uid.
     *
     * @throws SecurityException when called by someone other than system uid.
     *
     * @hide
     */
    public void showSensorUseDialog(int sensor) {
        try {
            mService.showSensorUseDialog(sensor);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Received exception while trying to show sensor use dialog", e);
        }
    }

    /**
     * A class implementing this interface can register with the {@link
     * android.hardware.SensorPrivacyManager} to receive notification when the all-sensor privacy
     * state changes.
     *
     * @hide
     */
    public interface OnAllSensorPrivacyChangedListener {
        /**
         * Callback invoked when the sensor privacy state changes.
         *
         * @param enabled true if sensor privacy is enabled, false otherwise.
         */
        void onAllSensorPrivacyChanged(boolean enabled);
    }

    /**
     * Sets all-sensor privacy to the specified state.
     *
     * @param enable the state to which sensor privacy should be set.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setAllSensorPrivacy(boolean enable) {
        try {
            mService.setSensorPrivacy(enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a new listener to receive notification when the state of all-sensor privacy
     * changes.
     *
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of
     *                 all-sensor privacy changes.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void addAllSensorPrivacyListener(
            @NonNull final OnAllSensorPrivacyChangedListener listener) {
        synchronized (mListeners) {
            ISensorPrivacyListener iListener = mListeners.get(listener);
            if (iListener == null) {
                iListener = new ISensorPrivacyListener.Stub() {
                    @Override
                    public void onSensorPrivacyChanged(int toggleType, int sensor,
                            boolean enabled) {
                        listener.onAllSensorPrivacyChanged(enabled);
                    }

                    @Override
                    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
                    public void onSensorPrivacyStateChanged(int toggleType, int sensor,
                            int state) {
                    }
                };
                mListeners.put(listener, iListener);
            }

            try {
                mService.addSensorPrivacyListener(iListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters the specified listener from receiving notifications when the state of all-sensor
     * privacy changes.
     *
     * @param listener the OnAllSensorPrivacyChangedListener to be unregistered from notifications
     *                 when all-sensor privacy changes.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public void removeAllSensorPrivacyListener(
            @NonNull OnAllSensorPrivacyChangedListener listener) {
        synchronized (mListeners) {
            ISensorPrivacyListener iListener = mListeners.get(listener);
            if (iListener != null) {
                mListeners.remove(iListener);
                try {
                    mService.removeSensorPrivacyListener(iListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Returns whether all-sensor privacy is currently enabled.
     *
     * @return true if all-sensor privacy is currently enabled, false otherwise.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY)
    public boolean isAllSensorPrivacyEnabled() {
        try {
            return mService.isSensorPrivacyEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
