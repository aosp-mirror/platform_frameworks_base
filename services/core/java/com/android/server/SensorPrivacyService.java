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

package com.android.server;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.ISensorPrivacyListener;
import android.hardware.ISensorPrivacyManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

/** @hide */
public final class SensorPrivacyService extends SystemService {

    private static final String TAG = "SensorPrivacyService";

    private static final String SENSOR_PRIVACY_XML_FILE = "sensor_privacy.xml";
    private static final String XML_TAG_SENSOR_PRIVACY = "sensor-privacy";
    private static final String XML_ATTRIBUTE_ENABLED = "enabled";

    private final SensorPrivacyServiceImpl mSensorPrivacyServiceImpl;

    public SensorPrivacyService(Context context) {
        super(context);
        mSensorPrivacyServiceImpl = new SensorPrivacyServiceImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SENSOR_PRIVACY_SERVICE, mSensorPrivacyServiceImpl);
    }

    class SensorPrivacyServiceImpl extends ISensorPrivacyManager.Stub {

        private final SensorPrivacyHandler mHandler;
        private final Context mContext;
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final AtomicFile mAtomicFile;
        @GuardedBy("mLock")
        private boolean mEnabled;

        SensorPrivacyServiceImpl(Context context) {
            mContext = context;
            mHandler = new SensorPrivacyHandler(FgThread.get().getLooper(), mContext);
            File sensorPrivacyFile = new File(Environment.getDataSystemDirectory(),
                    SENSOR_PRIVACY_XML_FILE);
            mAtomicFile = new AtomicFile(sensorPrivacyFile);
            synchronized (mLock) {
                mEnabled = readPersistedSensorPrivacyEnabledLocked();
            }
        }

        /**
         * Sets the sensor privacy to the provided state and notifies all listeners of the new
         * state.
         */
        @Override
        public void setSensorPrivacy(boolean enable) {
            enforceSensorPrivacyPermission();
            synchronized (mLock) {
                mEnabled = enable;
                FileOutputStream outputStream = null;
                try {
                    XmlSerializer serializer = new FastXmlSerializer();
                    outputStream = mAtomicFile.startWrite();
                    serializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
                    serializer.startDocument(null, true);
                    serializer.startTag(null, XML_TAG_SENSOR_PRIVACY);
                    serializer.attribute(null, XML_ATTRIBUTE_ENABLED, String.valueOf(enable));
                    serializer.endTag(null, XML_TAG_SENSOR_PRIVACY);
                    serializer.endDocument();
                    mAtomicFile.finishWrite(outputStream);
                } catch (IOException e) {
                    Log.e(TAG, "Caught an exception persisting the sensor privacy state: ", e);
                    mAtomicFile.failWrite(outputStream);
                }
            }
            mHandler.onSensorPrivacyChanged(enable);
        }

        /**
         * Enforces the caller contains the necessary permission to change the state of sensor
         * privacy.
         */
        private void enforceSensorPrivacyPermission() {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_SENSOR_PRIVACY) == PERMISSION_GRANTED) {
                return;
            }
            throw new SecurityException(
                    "Changing sensor privacy requires the following permission: "
                            + android.Manifest.permission.MANAGE_SENSOR_PRIVACY);
        }

        /**
         * Returns whether sensor privacy is enabled.
         */
        @Override
        public boolean isSensorPrivacyEnabled() {
            synchronized (mLock) {
                return mEnabled;
            }
        }

        /**
         * Returns the state of sensor privacy from persistent storage.
         */
        private boolean readPersistedSensorPrivacyEnabledLocked() {
            // if the file does not exist then sensor privacy has not yet been enabled on
            // the device.
            if (!mAtomicFile.exists()) {
                return false;
            }
            boolean enabled;
            try (FileInputStream inputStream = mAtomicFile.openRead()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(inputStream, StandardCharsets.UTF_8.name());
                XmlUtils.beginDocument(parser, XML_TAG_SENSOR_PRIVACY);
                parser.next();
                String tagName = parser.getName();
                enabled = Boolean.valueOf(parser.getAttributeValue(null, XML_ATTRIBUTE_ENABLED));
            } catch (IOException | XmlPullParserException e) {
                Log.e(TAG, "Caught an exception reading the state from storage: ", e);
                // Delete the file to prevent the same error on subsequent calls and assume sensor
                // privacy is not enabled.
                mAtomicFile.delete();
                enabled = false;
            }
            return enabled;
        }

        /**
         * Persists the state of sensor privacy.
         */
        private void persistSensorPrivacyState() {
            synchronized (mLock) {
                FileOutputStream outputStream = null;
                try {
                    XmlSerializer serializer = new FastXmlSerializer();
                    outputStream = mAtomicFile.startWrite();
                    serializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
                    serializer.startDocument(null, true);
                    serializer.startTag(null, XML_TAG_SENSOR_PRIVACY);
                    serializer.attribute(null, XML_ATTRIBUTE_ENABLED, String.valueOf(mEnabled));
                    serializer.endTag(null, XML_TAG_SENSOR_PRIVACY);
                    serializer.endDocument();
                    mAtomicFile.finishWrite(outputStream);
                } catch (IOException e) {
                    Log.e(TAG, "Caught an exception persisting the sensor privacy state: ", e);
                    mAtomicFile.failWrite(outputStream);
                }
            }
        }

        /**
         * Registers a listener to be notified when the sensor privacy state changes.
         */
        @Override
        public void addSensorPrivacyListener(ISensorPrivacyListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            mHandler.addListener(listener);
        }

        /**
         * Unregisters a listener from sensor privacy state change notifications.
         */
        @Override
        public void removeSensorPrivacyListener(ISensorPrivacyListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            mHandler.removeListener(listener);
        }
    }

    /**
     * Handles sensor privacy state changes and notifying listeners of the change.
     */
    private final class SensorPrivacyHandler extends Handler {
        private static final int MESSAGE_SENSOR_PRIVACY_CHANGED = 1;

        private final Object mListenerLock = new Object();

        @GuardedBy("mListenerLock")
        private final RemoteCallbackList<ISensorPrivacyListener> mListeners =
                new RemoteCallbackList<>();
        private final ArrayMap<ISensorPrivacyListener, DeathRecipient> mDeathRecipients;
        private final Context mContext;

        SensorPrivacyHandler(Looper looper, Context context) {
            super(looper);
            mDeathRecipients = new ArrayMap<>();
            mContext = context;
        }

        public void onSensorPrivacyChanged(boolean enabled) {
            sendMessage(PooledLambda.obtainMessage(SensorPrivacyHandler::handleSensorPrivacyChanged,
                    this, enabled));
            sendMessage(
                    PooledLambda.obtainMessage(SensorPrivacyServiceImpl::persistSensorPrivacyState,
                            mSensorPrivacyServiceImpl));
        }

        public void addListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                DeathRecipient deathRecipient = new DeathRecipient(listener);
                mDeathRecipients.put(listener, deathRecipient);
                mListeners.register(listener);
            }
        }

        public void removeListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                DeathRecipient deathRecipient = mDeathRecipients.remove(listener);
                if (deathRecipient != null) {
                    deathRecipient.destroy();
                }
                mListeners.unregister(listener);
            }
        }

        public void handleSensorPrivacyChanged(boolean enabled) {
            final int count = mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                ISensorPrivacyListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onSensorPrivacyChanged(enabled);
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught an exception notifying listener " + listener + ": ", e);
                }
            }
            mListeners.finishBroadcast();
            // Handle the state of all sensors managed by this service.
            SensorState.handleSensorPrivacyToggled(mContext, enabled);
        }
    }

    private final class DeathRecipient implements IBinder.DeathRecipient {

        private ISensorPrivacyListener mListener;

        DeathRecipient(ISensorPrivacyListener listener) {
            mListener = listener;
            try {
                mListener.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        @Override
        public void binderDied() {
            mSensorPrivacyServiceImpl.removeSensorPrivacyListener(mListener);
        }

        public void destroy() {
            try {
                mListener.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
            }
        }
    }

    /**
     * Maintains the state of the sensors when sensor privacy is enabled to return them to their
     * original state when sensor privacy is disabled.
     */
    private static final class SensorState {

        private static Object sLock = new Object();
        @GuardedBy("sLock")
        private static SensorState sPreviousState;

        private boolean mAirplaneEnabled;
        private boolean mLocationEnabled;

        SensorState(boolean airplaneEnabled, boolean locationEnabled) {
            mAirplaneEnabled = airplaneEnabled;
            mLocationEnabled = locationEnabled;
        }

        public static void handleSensorPrivacyToggled(Context context, boolean enabled) {
            synchronized (sLock) {
                SensorState state;
                if (enabled) {
                    // if sensor privacy is being enabled then obtain the current state of the
                    // sensors to be persisted and restored when sensor privacy is disabled.
                    state = getCurrentSensorState(context);
                } else {
                    // else obtain the previous sensor state to be restored, first from the saved
                    // state if available, otherwise attempt to read it from Settings.
                    if (sPreviousState != null) {
                        state = sPreviousState;
                    } else {
                        state = getPersistedSensorState(context);
                    }
                    // if the previous state is not available then return without attempting to
                    // modify the sensor state.
                    if (state == null) {
                        return;
                    }
                }
                // The SensorState represents the state of the sensor before sensor privacy was
                // enabled; if airplane mode was not enabled then the state of airplane mode should
                // be the same as the state of sensor privacy.
                if (!state.mAirplaneEnabled) {
                    setAirplaneMode(context, enabled);
                }
                // Similar to airplane mode the state of location should be the opposite of sensor
                // privacy mode, if it was enabled when sensor privacy was enabled then it should be
                // disabled. If location is disabled when sensor privacy is enabled then it will be
                // left disabled when sensor privacy is disabled.
                if (state.mLocationEnabled) {
                    setLocationEnabled(context, !enabled);
                }

                // if sensor privacy is being enabled then persist the current state.
                if (enabled) {
                    sPreviousState = state;
                    persistState(context, sPreviousState);
                }
            }
        }

        public static SensorState getCurrentSensorState(Context context) {
            LocationManager locationManager = (LocationManager) context.getSystemService(
                    Context.LOCATION_SERVICE);
            boolean airplaneEnabled = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
            boolean locationEnabled = locationManager.isLocationEnabled();
            return new SensorState(airplaneEnabled, locationEnabled);
        }

        public static void persistState(Context context, SensorState state) {
            StringBuilder stateValue = new StringBuilder();
            stateValue.append(state.mAirplaneEnabled
                    ? Settings.Secure.MAINTAIN_AIRPLANE_MODE_AFTER_SP_DISABLED
                    : Settings.Secure.DISABLE_AIRPLANE_MODE_AFTER_SP_DISABLED);
            stateValue.append(",");
            stateValue.append(
                    state.mLocationEnabled ? Settings.Secure.REENABLE_LOCATION_AFTER_SP_DISABLED
                            : Settings.Secure.MAINTAIN_LOCATION_AFTER_SP_DISABLED);
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.SENSOR_PRIVACY_SENSOR_STATE, stateValue.toString());
        }

        public static SensorState getPersistedSensorState(Context context) {
            String persistedState = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.SENSOR_PRIVACY_SENSOR_STATE);
            if (persistedState == null) {
                Log.e(TAG, "The persisted sensor state could not be obtained from Settings");
                return null;
            }
            String[] sensorStates = persistedState.split(",");
            if (sensorStates.length < 2) {
                Log.e(TAG, "The persisted sensor state does not contain the expected values: "
                        + persistedState);
                return null;
            }
            boolean airplaneEnabled = sensorStates[0].equals(
                    Settings.Secure.MAINTAIN_AIRPLANE_MODE_AFTER_SP_DISABLED);
            boolean locationEnabled = sensorStates[1].equals(
                    Settings.Secure.REENABLE_LOCATION_AFTER_SP_DISABLED);
            return new SensorState(airplaneEnabled, locationEnabled);
        }

        private static void setAirplaneMode(Context context, boolean enable) {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.setAirplaneMode(enable);
        }

        private static void setLocationEnabled(Context context, boolean enable) {
            LocationManager locationManager = (LocationManager) context.getSystemService(
                    Context.LOCATION_SERVICE);
            locationManager.setLocationEnabledForUser(enable,
                    UserHandle.of(ActivityManager.getCurrentUser()));
        }
    }
}
