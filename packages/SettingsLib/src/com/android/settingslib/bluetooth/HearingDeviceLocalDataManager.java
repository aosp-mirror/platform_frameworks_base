/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static com.android.settingslib.bluetooth.HearingDeviceLocalDataManager.Data.INVALID_VOLUME;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.utils.ThreadUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The class to manage hearing device local data from Settings.
 *
 * <p><b>Note:</b> Before calling any methods to get or change the local data, you must first call
 * the {@code start()} method to load the data from Settings. Whenever the data is modified, you
 * must call the {@code stop()} method to save the data into Settings. After calling {@code stop()},
 * you should not call any methods to get or change the local data without again calling
 * {@code start()}.
 */
public class HearingDeviceLocalDataManager {
    private static final String TAG = "HearingDeviceDataMgr";
    private static final boolean DEBUG = true;

    /** Interface for listening hearing device local data changed */
    public interface OnDeviceLocalDataChangeListener {
        /**
         * The method is called when the local data of the device with the address is changed.
         *
         * @param address the device anonymized address
         * @param data    the updated data
         */
        void onDeviceLocalDataChange(@NonNull String address, @Nullable Data data);
    }

    static final String KEY_ADDR = "addr";
    static final String KEY_AMBIENT = "ambient";
    static final String KEY_GROUP_AMBIENT = "group_ambient";
    static final String KEY_AMBIENT_CONTROL_EXPANDED = "control_expanded";
    static final String LOCAL_AMBIENT_VOLUME_SETTINGS =
            Settings.Global.HEARING_DEVICE_LOCAL_AMBIENT_VOLUME;

    private static final Object sLock = new Object();

    private final Context mContext;
    private Executor mListenerExecutor;
    @GuardedBy("sLock")
    private final Map<String, Data> mAddrToDataMap = new HashMap<>();
    private OnDeviceLocalDataChangeListener mListener;
    private SettingsObserver mSettingsObserver;
    private boolean mIsStarted = false;

    public HearingDeviceLocalDataManager(@NonNull Context context) {
        mContext = context;
        mSettingsObserver = new SettingsObserver(ThreadUtils.getUiThreadHandler());
    }

    /** Starts the manager. Loads the data from Settings and start observing any changes. */
    public synchronized void start() {
        if (mIsStarted) {
            return;
        }
        mIsStarted = true;
        getLocalDataFromSettings();
        mSettingsObserver.register(mContext.getContentResolver());
    }

    /** Stops the manager. Flushes the data into Settings and stop observing. */
    public synchronized void stop() {
        if (!mIsStarted) {
            return;
        }
        putAmbientVolumeSettings();
        mSettingsObserver.unregister(mContext.getContentResolver());
        mIsStarted = false;
    }

    /**
     * Sets a listener which will be be notified when hearing device local data is changed.
     *
     * @param listener the listener to be notified
     * @param executor the executor to run the
     *                 {@link OnDeviceLocalDataChangeListener#onDeviceLocalDataChange(String,
     *                 Data)} callback
     */
    public void setOnDeviceLocalDataChangeListener(
            @NonNull OnDeviceLocalDataChangeListener listener, @NonNull Executor executor) {
        mListener = listener;
        mListenerExecutor = executor;
    }

    /**
     * Gets the local data of the corresponding hearing device. This should be called after
     * {@link #start()} is called().
     *
     * @param device the device to query the local data
     */
    @NonNull
    public Data get(@NonNull BluetoothDevice device) {
        if (!mIsStarted) {
            Log.w(TAG, "Manager is not started. Please call start() first.");
            return new Data();
        }
        synchronized (sLock) {
            return mAddrToDataMap.getOrDefault(device.getAnonymizedAddress(), new Data());
        }
    }

    /**
     * Puts the local data of the corresponding hearing device.
     *
     * @param device the device to update the local data
     */
    private void put(BluetoothDevice device, Data data) {
        if (device == null) {
            return;
        }
        synchronized (sLock) {
            final String addr = device.getAnonymizedAddress();
            mAddrToDataMap.put(addr, data);
            if (mListener != null && mListenerExecutor != null) {
                mListenerExecutor.execute(() -> mListener.onDeviceLocalDataChange(addr, data));
            }
        }
    }

    /**
     * Updates the ambient volume of the corresponding hearing device. This should be called after
     * {@link #start()} is called().
     *
     * @param device the device to update
     * @param value  the ambient value
     * @return if the local data is updated
     */
    public boolean updateAmbient(@Nullable BluetoothDevice device, int value) {
        if (!mIsStarted) {
            Log.w(TAG, "Manager is not started. Please call start() first.");
            return false;
        }
        if (device == null) {
            return false;
        }
        synchronized (sLock) {
            Data data = get(device);
            if (value == data.ambient) {
                return false;
            }
            put(device, new Data.Builder(data).ambient(value).build());
            return true;
        }
    }

    /**
     * Updates the group ambient volume of the corresponding hearing device. This should be called
     * after {@link #start()} is called().
     *
     * @param device the device to update
     * @param value  the group ambient value
     * @return if the local data is updated
     */
    public boolean updateGroupAmbient(@Nullable BluetoothDevice device, int value) {
        if (!mIsStarted) {
            Log.w(TAG, "Manager is not started. Please call start() first.");
            return false;
        }
        if (device == null) {
            return false;
        }
        synchronized (sLock) {
            Data data = get(device);
            if (value == data.groupAmbient) {
                return false;
            }
            put(device, new Data.Builder(data).groupAmbient(value).build());
            return true;
        }
    }

    /**
     * Updates the ambient control is expanded or not of the corresponding hearing device. This
     * should be called after {@link #start()} is called().
     *
     * @param device   the device to update
     * @param expanded the ambient control is expanded or not
     * @return if the local data is updated
     */
    public boolean updateAmbientControlExpanded(@Nullable BluetoothDevice device,
            boolean expanded) {
        if (!mIsStarted) {
            Log.w(TAG, "Manager is not started. Please call start() first.");
            return false;
        }
        if (device == null) {
            return false;
        }
        synchronized (sLock) {
            Data data = get(device);
            if (expanded == data.ambientControlExpanded) {
                return false;
            }
            put(device, new Data.Builder(data).ambientControlExpanded(expanded).build());
            return true;
        }
    }

    void getLocalDataFromSettings() {
        synchronized (sLock) {
            Map<String, Data> updatedAddrToDataMap = parseFromSettings();
            notifyIfDataChanged(mAddrToDataMap, updatedAddrToDataMap);
            mAddrToDataMap.clear();
            mAddrToDataMap.putAll(updatedAddrToDataMap);
            if (DEBUG) {
                Log.v(TAG, "getLocalDataFromSettings, " + mAddrToDataMap + ", manager: " + this);
            }
        }
    }

    void putAmbientVolumeSettings() {
        synchronized (sLock) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, Data> entry : mAddrToDataMap.entrySet()) {
                builder.append(KEY_ADDR).append("=").append(entry.getKey());
                builder.append(entry.getValue().toSettingsFormat()).append(";");
            }
            if (DEBUG) {
                Log.v(TAG, "putAmbientVolumeSettings, " + builder + ", manager: " + this);
            }
            Settings.Global.putStringForUser(mContext.getContentResolver(),
                    LOCAL_AMBIENT_VOLUME_SETTINGS, builder.toString(),
                    UserHandle.USER_SYSTEM);
        }
    }

    @GuardedBy("sLock")
    private Map<String, Data> parseFromSettings() {
        String settings = Settings.Global.getStringForUser(mContext.getContentResolver(),
                LOCAL_AMBIENT_VOLUME_SETTINGS, UserHandle.USER_SYSTEM);
        Map<String, Data> addrToDataMap = new ArrayMap<>();
        if (settings != null && !settings.isEmpty()) {
            String[] localDataArray = settings.split(";");
            for (String localData : localDataArray) {
                KeyValueListParser parser = new KeyValueListParser(',');
                parser.setString(localData);
                String address = parser.getString(KEY_ADDR, "");
                if (!address.isEmpty()) {
                    Data data = new Data.Builder()
                            .ambient(parser.getInt(KEY_AMBIENT, INVALID_VOLUME))
                            .groupAmbient(parser.getInt(KEY_GROUP_AMBIENT, INVALID_VOLUME))
                            .ambientControlExpanded(
                                    parser.getBoolean(KEY_AMBIENT_CONTROL_EXPANDED, false))
                            .build();
                    addrToDataMap.put(address, data);
                }
            }
        }
        return addrToDataMap;
    }

    @GuardedBy("sLock")
    private void notifyIfDataChanged(Map<String, Data> oldAddrToDataMap,
            Map<String, Data> newAddrToDataMap) {
        newAddrToDataMap.forEach((addr, data) -> {
            Data oldData = oldAddrToDataMap.get(addr);
            if (oldData == null || !oldData.equals(data)) {
                if (mListener != null) {
                    mListenerExecutor.execute(() -> mListener.onDeviceLocalDataChange(addr, data));
                }
            }
        });
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mAmbientVolumeUri = Settings.Global.getUriFor(
                LOCAL_AMBIENT_VOLUME_SETTINGS);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mAmbientVolumeUri, false, this,
                    UserHandle.USER_SYSTEM);
        }

        void unregister(ContentResolver contentResolver) {
            contentResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri) {
            if (mAmbientVolumeUri.equals(uri)) {
                Log.v(TAG, "Local data on change, manager: " + HearingDeviceLocalDataManager.this);
                getLocalDataFromSettings();
            }
        }
    }

    public record Data(int ambient, int groupAmbient, boolean ambientControlExpanded) {

        public static int INVALID_VOLUME = Integer.MIN_VALUE;

        private Data() {
            this(INVALID_VOLUME, INVALID_VOLUME, false);
        }

        /**
         * Return {@code true} if one of {@link #ambient} or {@link #groupAmbient} is assigned to
         * a valid value.
         */
        public boolean hasAmbientData() {
            return ambient != INVALID_VOLUME || groupAmbient != INVALID_VOLUME;
        }

        /**
         * @return the composed string which is used to store the local data in
         * {@link Settings.Global#HEARING_DEVICE_LOCAL_AMBIENT_VOLUME}
         */
        @NonNull
        public String toSettingsFormat() {
            String string = "";
            if (ambient != INVALID_VOLUME) {
                string += ("," + KEY_AMBIENT + "=" + ambient);
            }
            if (groupAmbient != INVALID_VOLUME) {
                string += ("," + KEY_GROUP_AMBIENT + "=" + groupAmbient);
            }
            string += ("," + KEY_AMBIENT_CONTROL_EXPANDED + "=" + ambientControlExpanded);
            return string;
        }

        /** Builder for a Data object */
        public static final class Builder {
            private int mAmbient;
            private int mGroupAmbient;
            private boolean mAmbientControlExpanded;

            public Builder() {
                this.mAmbient = INVALID_VOLUME;
                this.mGroupAmbient = INVALID_VOLUME;
                this.mAmbientControlExpanded = false;
            }

            public Builder(@NonNull Data other) {
                this.mAmbient = other.ambient;
                this.mGroupAmbient = other.groupAmbient;
                this.mAmbientControlExpanded = other.ambientControlExpanded;
            }

            /** Sets the ambient volume */
            @NonNull
            public Builder ambient(int ambient) {
                this.mAmbient = ambient;
                return this;
            }

            /** Sets the group ambient volume */
            @NonNull
            public Builder groupAmbient(int groupAmbient) {
                this.mGroupAmbient = groupAmbient;
                return this;
            }

            /** Sets the ambient control expanded */
            @NonNull
            public Builder ambientControlExpanded(boolean ambientControlExpanded) {
                this.mAmbientControlExpanded = ambientControlExpanded;
                return this;
            }

            /** Build the Data object */
            @NonNull
            public Data build() {
                return new Data(mAmbient, mGroupAmbient, mAmbientControlExpanded);
            }
        }
    }
}
