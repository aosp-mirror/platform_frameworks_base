/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Class used to identify settings associated with the player on AG.
 *
 * {@hide}
 */
public final class BluetoothAvrcpPlayerSettings implements Parcelable {
    public static final String TAG = "BluetoothAvrcpPlayerSettings";

    /**
     * Equalizer setting.
     */
    public static final int SETTING_EQUALIZER    = 0x01;

    /**
     * Repeat setting.
     */
    public static final int SETTING_REPEAT       = 0x02;

    /**
     * Shuffle setting.
     */
    public static final int SETTING_SHUFFLE      = 0x04;

    /**
     * Scan mode setting.
     */
    public static final int SETTING_SCAN         = 0x08;

    /**
     * Invalid state.
     *
     * Used for returning error codes.
     */
    public static final int STATE_INVALID = -1;

    /**
     * OFF state.
     *
     * Denotes a general OFF state. Applies to all settings.
     */
    public static final int STATE_OFF = 0x00;

    /**
     * ON state.
     *
     * Applies to {@link SETTING_EQUALIZER}.
     */
    public static final int STATE_ON = 0x01;

    /**
     * Single track repeat.
     *
     * Applies only to {@link SETTING_REPEAT}.
     */
    public static final int STATE_SINGLE_TRACK = 0x02;

    /**
     * All track repeat/shuffle.
     *
     * Applies to {@link SETTING_REPEAT}, {@link SETTING_SHUFFLE} and {@link SETTING_SCAN}.
     */
    public static final int STATE_ALL_TRACK    = 0x03;

    /**
     * Group repeat/shuffle.
     *
     * Applies to {@link SETTING_REPEAT}, {@link SETTING_SHUFFLE} and {@link SETTING_SCAN}.
     */
    public static final int STATE_GROUP        = 0x04;

    /**
     * List of supported settings ORed.
     */
    private int mSettings;

    /**
     * Hash map of current capability values.
     */
    private Map<Integer, Integer> mSettingsValue = new HashMap<Integer, Integer>();

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mSettings);
        out.writeInt(mSettingsValue.size());
        for (int k : mSettingsValue.keySet()) {
            out.writeInt(k);
            out.writeInt(mSettingsValue.get(k));
        }
    }

    public static final Parcelable.Creator<BluetoothAvrcpPlayerSettings> CREATOR
            = new Parcelable.Creator<BluetoothAvrcpPlayerSettings>() {
        public BluetoothAvrcpPlayerSettings createFromParcel(Parcel in) {
            return new BluetoothAvrcpPlayerSettings(in);
        }

        public BluetoothAvrcpPlayerSettings[] newArray(int size) {
            return new BluetoothAvrcpPlayerSettings[size];
        }
    };

    private BluetoothAvrcpPlayerSettings(Parcel in) {
        mSettings = in.readInt();
        int numSettings = in.readInt();
        for (int i = 0; i < numSettings; i++) {
            mSettingsValue.put(in.readInt(), in.readInt());
        }
    }

    /**
     * Create a new player settings object.
     *
     * @param settings a ORed value of SETTINGS_* defined above.
     */
    public BluetoothAvrcpPlayerSettings(int settings) {
        mSettings = settings;
    }

    /**
     * Get the supported settings.
     *
     * @return int ORed value of supported settings.
     */
    public int getSettings() {
        return mSettings;
    }

    /**
     * Add a setting value.
     *
     * The setting must be part of possible settings in {@link getSettings()}.
     * @param setting setting config.
     * @param value value for the setting.
     * @throws IllegalStateException if the setting is not supported.
     */
    public void addSettingValue(int setting, int value) {
        if ((setting & mSettings) == 0) {
            Log.e(TAG, "Setting not supported: " + setting + " " + mSettings);
            throw new IllegalStateException("Setting not supported: " + setting);
        }
        mSettingsValue.put(setting, value);
    }

    /**
     * Get a setting value.
     *
     * The setting must be part of possible settings in {@link getSettings()}.
     * @param setting setting config.
     * @return value value for the setting.
     * @throws IllegalStateException if the setting is not supported.
     */
    public int getSettingValue(int setting) {
        if ((setting & mSettings) == 0) {
            Log.e(TAG, "Setting not supported: " + setting + " " + mSettings);
            throw new IllegalStateException("Setting not supported: " + setting);
        }
        Integer i = mSettingsValue.get(setting);
        if (i == null) return -1;
        return i;
    }
}
