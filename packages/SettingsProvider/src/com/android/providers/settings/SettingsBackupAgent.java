/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.providers.settings;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.CRC32;

import android.backup.BackupDataInput;
import android.backup.BackupDataOutput;
import android.backup.BackupHelperAgent;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * Performs backup and restore of the System and Secure settings.
 * List of settings that are backed up are stored in the Settings.java file
 */
public class SettingsBackupAgent extends BackupHelperAgent {

    private static final String KEY_SYSTEM = "system";
    private static final String KEY_SECURE = "secure";
    private static final String KEY_SYNC = "sync_providers";
    private static final String KEY_LOCALE = "locale";

    private static final int STATE_SYSTEM = 0;
    private static final int STATE_SECURE = 1;
    private static final int STATE_SYNC   = 2;
    private static final int STATE_LOCALE = 3;
    private static final int STATE_WIFI   = 4;
    private static final int STATE_SIZE   = 5; // The number of state items

    private static String[] sortedSystemKeys = null;
    private static String[] sortedSecureKeys = null;

    private static final byte[] EMPTY_DATA = new byte[0];

    private static final String TAG = "SettingsBackupAgent";

    private static final int COLUMN_ID = 0;
    private static final int COLUMN_NAME = 1;
    private static final int COLUMN_VALUE = 2;

    private static final String[] PROJECTION = {
        Settings.NameValueTable._ID,
        Settings.NameValueTable.NAME,
        Settings.NameValueTable.VALUE
    };

    private static final String FILE_WIFI_SUPPLICANT = "/data/misc/wifi/wpa_supplicant.conf";
    private static final String FILE_BT_ROOT = "/data/misc/hcid/";

    private SettingsHelper mSettingsHelper;

    public void onCreate() {
        mSettingsHelper = new SettingsHelper(this);
        super.onCreate();
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {

        byte[] systemSettingsData = getSystemSettings();
        byte[] secureSettingsData = getSecureSettings();
        byte[] syncProviders = mSettingsHelper.getSyncProviders();
        byte[] locale = mSettingsHelper.getLocaleData();
        byte[] wifiData = getFileData(FILE_WIFI_SUPPLICANT);

        long[] stateChecksums = readOldChecksums(oldState);

        stateChecksums[STATE_SYSTEM] =
                writeIfChanged(stateChecksums[STATE_SYSTEM], KEY_SYSTEM, systemSettingsData, data);
        stateChecksums[STATE_SECURE] =
                writeIfChanged(stateChecksums[STATE_SECURE], KEY_SECURE, secureSettingsData, data);
        stateChecksums[STATE_SYNC] =
                writeIfChanged(stateChecksums[STATE_SYNC], KEY_SYNC, syncProviders, data);
        stateChecksums[STATE_LOCALE] =
                writeIfChanged(stateChecksums[STATE_LOCALE], KEY_LOCALE, locale, data);
        stateChecksums[STATE_WIFI] =
                writeIfChanged(stateChecksums[STATE_WIFI], FILE_WIFI_SUPPLICANT, wifiData, data);

        writeNewChecksums(stateChecksums, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState) throws IOException {

        enableWifi(false);
        enableBluetooth(false);

        while (data.readNextHeader()) {
            final String key = data.getKey();
            final int size = data.getDataSize();
            if (KEY_SYSTEM.equals(key)) {
                restoreSettings(data, Settings.System.CONTENT_URI);
                mSettingsHelper.applyAudioSettings();
            } else if (KEY_SECURE.equals(key)) {
                restoreSettings(data, Settings.Secure.CONTENT_URI);
            } else if (FILE_WIFI_SUPPLICANT.equals(key)) {
                restoreFile(FILE_WIFI_SUPPLICANT, data);
                FileUtils.setPermissions(FILE_WIFI_SUPPLICANT,
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                        FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                        Process.myUid(), Process.WIFI_UID);
            } else if (KEY_SYNC.equals(key)) {
                mSettingsHelper.setSyncProviders(data);
            } else if (KEY_LOCALE.equals(key)) {
                byte[] localeData = new byte[size];
                data.readEntityData(localeData, 0, size);
                mSettingsHelper.setLocaleData(localeData);
            } else {
                data.skipEntityData();
            }
        }
    }

    private long[] readOldChecksums(ParcelFileDescriptor oldState) throws IOException {
        long[] stateChecksums = new long[STATE_SIZE];

        DataInputStream dataInput = new DataInputStream(
                new FileInputStream(oldState.getFileDescriptor()));
        for (int i = 0; i < STATE_SIZE; i++) {
            try {
                stateChecksums[i] = dataInput.readLong();
            } catch (EOFException eof) {
                break;
            }
        }
        dataInput.close();
        return stateChecksums;
    }

    private void writeNewChecksums(long[] checksums, ParcelFileDescriptor newState)
            throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(
                new FileOutputStream(newState.getFileDescriptor()));
        for (int i = 0; i < STATE_SIZE; i++) {
            dataOutput.writeLong(checksums[i]);
        }
        dataOutput.close();
    }

    private long writeIfChanged(long oldChecksum, String key, byte[] data,
            BackupDataOutput output) {
        CRC32 checkSummer = new CRC32();
        checkSummer.update(data);
        long newChecksum = checkSummer.getValue();
        if (oldChecksum == newChecksum) {
            return oldChecksum;
        }
        try {
            output.writeEntityHeader(key, data.length);
            output.writeEntityData(data, data.length);
        } catch (IOException ioe) {
            // Bail
        }
        return newChecksum;
    }

    private byte[] getSystemSettings() {
        Cursor sortedCursor = getContentResolver().query(Settings.System.CONTENT_URI, PROJECTION,
                null, null, Settings.NameValueTable.NAME);
        // Copy and sort the array
        if (sortedSystemKeys == null) {
            sortedSystemKeys = copyAndSort(Settings.System.SETTINGS_TO_BACKUP);
        }
        byte[] result = extractRelevantValues(sortedCursor, sortedSystemKeys);
        sortedCursor.close();
        return result;
    }

    private byte[] getSecureSettings() {
        Cursor sortedCursor = getContentResolver().query(Settings.Secure.CONTENT_URI, PROJECTION,
                null, null, Settings.NameValueTable.NAME);
        // Copy and sort the array
        if (sortedSecureKeys == null) {
            sortedSecureKeys = copyAndSort(Settings.Secure.SETTINGS_TO_BACKUP);
        }
        byte[] result = extractRelevantValues(sortedCursor, sortedSecureKeys);
        sortedCursor.close();
        return result;
    }

    private void restoreSettings(BackupDataInput data, Uri contentUri) {
        ContentValues cv = new ContentValues(2);
        byte[] settings = new byte[data.getDataSize()];
        try {
            data.readEntityData(settings, 0, settings.length);
        } catch (IOException ioe) {
            Log.e(TAG, "Couldn't read entity data");
            return;
        }
        int pos = 0;
        while (pos < settings.length) {
            int length = readInt(settings, pos);
            pos += 4;
            String settingName = length > 0? new String(settings, pos, length) : null;
            pos += length;
            length = readInt(settings, pos);
            pos += 4;
            String settingValue = length > 0? new String(settings, pos, length) : null;
            pos += length;
            if (!TextUtils.isEmpty(settingName) && !TextUtils.isEmpty(settingValue)) {
                //Log.i(TAG, "Restore " + settingName + " = " + settingValue);
                if (mSettingsHelper.restoreValue(settingName, settingValue)) {
                    cv.clear();
                    cv.put(Settings.NameValueTable.NAME, settingName);
                    cv.put(Settings.NameValueTable.VALUE, settingValue);
                    getContentResolver().insert(contentUri, cv);
                }
            }
        }
    }

    private String[] copyAndSort(String[] keys) {
        String[] sortedKeys = new String[keys.length];
        System.arraycopy(keys, 0, sortedKeys, 0, keys.length);
        Arrays.sort(sortedKeys);
        return sortedKeys;
    }

    /**
     * Given a cursor sorted by key name and a set of keys sorted by name, 
     * extract the required keys and values and write them to a byte array.
     * @param sortedCursor
     * @param sortedKeys
     * @return
     */
    byte[] extractRelevantValues(Cursor sortedCursor, String[] sortedKeys) {
        byte[][] values = new byte[sortedKeys.length * 2][]; // keys and values
        if (!sortedCursor.moveToFirst()) {
            Log.e(TAG, "Couldn't read from the cursor");
            return new byte[0];
        }
        int keyIndex = 0;
        int totalSize = 0;
        while (!sortedCursor.isAfterLast()) {
            String name = sortedCursor.getString(COLUMN_NAME);
            while (sortedKeys[keyIndex].compareTo(name.toString()) < 0) {
                keyIndex++;
                if (keyIndex == sortedKeys.length) break;
            }
            if (keyIndex < sortedKeys.length && name.equals(sortedKeys[keyIndex])) {
                String value = sortedCursor.getString(COLUMN_VALUE);
                byte[] nameBytes = name.toString().getBytes();
                totalSize += 4 + nameBytes.length;
                values[keyIndex * 2] = nameBytes;
                byte[] valueBytes;
                if (TextUtils.isEmpty(value)) {
                    valueBytes = null;
                    totalSize += 4;
                } else {
                    valueBytes = value.toString().getBytes();
                    totalSize += 4 + valueBytes.length;
                    //Log.i(TAG, "Backing up " + name + " = " + value);
                }
                values[keyIndex * 2 + 1] = valueBytes;
                keyIndex++;
            }
            if (keyIndex == sortedKeys.length || !sortedCursor.moveToNext()) {
                break;
            }
        }

        byte[] result = new byte[totalSize];
        int pos = 0;
        for (int i = 0; i < sortedKeys.length * 2; i++) {
            if (values[i] != null) {
                pos = writeInt(result, pos, values[i].length);
                pos = writeBytes(result, pos, values[i]);
            }
        }
        return result;
    }

    private byte[] getFileData(String filename) {
        try {
            File file = new File(filename);
            if (file.exists()) {
                byte[] bytes = new byte[(int) file.length()];
                FileInputStream fis = new FileInputStream(file);
                int offset = 0;
                int got = 0;
                do {
                    got = fis.read(bytes, offset, bytes.length - offset);
                    if (got > 0) offset += got;
                } while (offset < bytes.length && got > 0);
                return bytes;
            } else {
                return EMPTY_DATA;
            }
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't backup " + filename);
            return EMPTY_DATA;
        }
    }

    private void restoreFile(String filename, BackupDataInput data) {
        byte[] bytes = new byte[data.getDataSize()];
        if (bytes.length <= 0) return;
        try {
            data.readEntityData(bytes, 0, bytes.length);
            FileOutputStream fos = new FileOutputStream(filename);
            fos.write(bytes);
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't restore " + filename);
        }
    }

    /**
     * Write an int in BigEndian into the byte array.
     * @param out byte array
     * @param pos current pos in array
     * @param value integer to write
     * @return the index after adding the size of an int (4)
     */
    private int writeInt(byte[] out, int pos, int value) {
        out[pos + 0] = (byte) ((value >> 24) & 0xFF);
        out[pos + 1] = (byte) ((value >> 16) & 0xFF);
        out[pos + 2] = (byte) ((value >>  8) & 0xFF);
        out[pos + 3] = (byte) ((value >>  0) & 0xFF);
        return pos + 4;
    }

    private int writeBytes(byte[] out, int pos, byte[] value) {
        System.arraycopy(value, 0, out, pos, value.length);
        return pos + value.length;
    }

    private int readInt(byte[] in, int pos) {
        int result =
                ((in[pos    ] & 0xFF) << 24) |
                ((in[pos + 1] & 0xFF) << 16) |
                ((in[pos + 2] & 0xFF) <<  8) |
                ((in[pos + 3] & 0xFF) <<  0);
        return result;
    }

    private void enableWifi(boolean enable) {
        WifiManager wfm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wfm != null) {
            wfm.setWifiEnabled(enable);
        }
    }

    private void enableBluetooth(boolean enable) {
        BluetoothDevice bt = (BluetoothDevice) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bt != null) {
            if (!enable) {
                bt.disable();
            } else {
                bt.enable();
            }
        }
    }
}
