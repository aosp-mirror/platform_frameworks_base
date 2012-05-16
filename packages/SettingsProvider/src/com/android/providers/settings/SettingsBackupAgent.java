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

import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.CharArrayReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Performs backup and restore of the System and Secure settings.
 * List of settings that are backed up are stored in the Settings.java file
 */
public class SettingsBackupAgent extends BackupAgentHelper {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_BACKUP = DEBUG || false;

    private static final String KEY_SYSTEM = "system";
    private static final String KEY_SECURE = "secure";
    private static final String KEY_LOCALE = "locale";

    //Version 2 adds STATE_WIFI_CONFIG
    private static final int STATE_VERSION_1       = 1;
    private static final int STATE_VERSION_1_SIZE  = 4;

    // Versioning of the state file.  Increment this version
    // number any time the set of state items is altered.
    private static final int STATE_VERSION = 2;

    private static final int STATE_SYSTEM          = 0;
    private static final int STATE_SECURE          = 1;
    private static final int STATE_LOCALE          = 2;
    private static final int STATE_WIFI_SUPPLICANT = 3;
    private static final int STATE_WIFI_CONFIG     = 4;
    private static final int STATE_SIZE            = 5; // The number of state items

    // Versioning of the 'full backup' format
    private static final int FULL_BACKUP_VERSION = 1;

    private static final int INTEGER_BYTE_COUNT = Integer.SIZE / Byte.SIZE;

    private static final byte[] EMPTY_DATA = new byte[0];

    private static final String TAG = "SettingsBackupAgent";

    private static final int COLUMN_NAME = 1;
    private static final int COLUMN_VALUE = 2;

    private static final String[] PROJECTION = {
        Settings.NameValueTable._ID,
        Settings.NameValueTable.NAME,
        Settings.NameValueTable.VALUE
    };

    private static final String FILE_WIFI_SUPPLICANT = "/data/misc/wifi/wpa_supplicant.conf";
    private static final String FILE_WIFI_SUPPLICANT_TEMPLATE =
            "/system/etc/wifi/wpa_supplicant.conf";

    // the key to store the WIFI data under, should be sorted as last, so restore happens last.
    // use very late unicode character to quasi-guarantee last sort position.
    private static final String KEY_WIFI_SUPPLICANT = "\uffedWIFI";
    private static final String KEY_WIFI_CONFIG = "\uffedCONFIG_WIFI";

    // Name of the temporary file we use during full backup/restore.  This is
    // stored in the full-backup tarfile as well, so should not be changed.
    private static final String STAGE_FILE = "flattened-data";

    private SettingsHelper mSettingsHelper;
    private WifiManager mWfm;
    private static String mWifiConfigFile;

    // Class for capturing a network definition from the wifi supplicant config file
    static class Network {
        String ssid = "";  // equals() and hashCode() need these to be non-null
        String key_mgmt = "";
        final ArrayList<String> rawLines = new ArrayList<String>();

        public static Network readFromStream(BufferedReader in) {
            final Network n = new Network();
            String line;
            try {
                while (in.ready()) {
                    line = in.readLine();
                    if (line == null || line.startsWith("}")) {
                        break;
                    }
                    n.rememberLine(line);
                }
            } catch (IOException e) {
                return null;
            }
            return n;
        }

        void rememberLine(String line) {
            // can't rely on particular whitespace patterns so strip leading/trailing
            line = line.trim();
            if (line.isEmpty()) return; // only whitespace; drop the line
            rawLines.add(line);

            // remember the ssid and key_mgmt lines for duplicate culling
            if (line.startsWith("ssid")) {
                ssid = line;
            } else if (line.startsWith("key_mgmt")) {
                key_mgmt = line;
            }
        }

        public void write(Writer w) throws IOException {
            w.write("\nnetwork={\n");
            for (String line : rawLines) {
                w.write("\t" + line + "\n");
            }
            w.write("}\n");
        }

        public void dump() {
            Log.v(TAG, "network={");
            for (String line : rawLines) {
                Log.v(TAG, "   " + line);
            }
            Log.v(TAG, "}");
        }

        // Same approach as Pair.equals() and Pair.hashCode()
        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof Network)) return false;
            final Network other;
            try {
                other = (Network) o;
            } catch (ClassCastException e) {
                return false;
            }
            return ssid.equals(other.ssid) && key_mgmt.equals(other.key_mgmt);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + ssid.hashCode();
            result = 31 * result + key_mgmt.hashCode();
            return result;
        }
    }

    // Ingest multiple wifi config file fragments, looking for network={} blocks
    // and eliminating duplicates
    class WifiNetworkSettings {
        // One for fast lookup, one for maintaining ordering
        final HashSet<Network> mKnownNetworks = new HashSet<Network>();
        final ArrayList<Network> mNetworks = new ArrayList<Network>(8);

        public void readNetworks(BufferedReader in) {
            try {
                String line;
                while (in.ready()) {
                    line = in.readLine();
                    if (line != null) {
                        // Parse out 'network=' decls so we can ignore duplicates
                        if (line.startsWith("network")) {
                            Network net = Network.readFromStream(in);
                            if (! mKnownNetworks.contains(net)) {
                                if (DEBUG_BACKUP) {
                                    Log.v(TAG, "Adding " + net.ssid + " / " + net.key_mgmt);
                                }
                                mKnownNetworks.add(net);
                                mNetworks.add(net);
                            } else {
                                if (DEBUG_BACKUP) {
                                    Log.v(TAG, "Dupe; skipped " + net.ssid + " / " + net.key_mgmt);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // whatever happened, we're done now
            }
        }

        public void write(Writer w) throws IOException {
            for (Network net : mNetworks) {
                net.write(w);
            }
        }

        public void dump() {
            for (Network net : mNetworks) {
                net.dump();
            }
        }
    }

    @Override
    public void onCreate() {
        if (DEBUG_BACKUP) Log.d(TAG, "onCreate() invoked");

        mSettingsHelper = new SettingsHelper(this);
        super.onCreate();

        WifiManager mWfm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (mWfm != null) mWifiConfigFile = mWfm.getConfigFile();
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {

        byte[] systemSettingsData = getSystemSettings();
        byte[] secureSettingsData = getSecureSettings();
        byte[] locale = mSettingsHelper.getLocaleData();
        byte[] wifiSupplicantData = getWifiSupplicant(FILE_WIFI_SUPPLICANT);
        byte[] wifiConfigData = getFileData(mWifiConfigFile);

        long[] stateChecksums = readOldChecksums(oldState);

        stateChecksums[STATE_SYSTEM] =
            writeIfChanged(stateChecksums[STATE_SYSTEM], KEY_SYSTEM, systemSettingsData, data);
        stateChecksums[STATE_SECURE] =
            writeIfChanged(stateChecksums[STATE_SECURE], KEY_SECURE, secureSettingsData, data);
        stateChecksums[STATE_LOCALE] =
            writeIfChanged(stateChecksums[STATE_LOCALE], KEY_LOCALE, locale, data);
        stateChecksums[STATE_WIFI_SUPPLICANT] =
            writeIfChanged(stateChecksums[STATE_WIFI_SUPPLICANT], KEY_WIFI_SUPPLICANT,
                    wifiSupplicantData, data);
        stateChecksums[STATE_WIFI_CONFIG] =
            writeIfChanged(stateChecksums[STATE_WIFI_CONFIG], KEY_WIFI_CONFIG, wifiConfigData,
                    data);

        writeNewChecksums(stateChecksums, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState) throws IOException {

        while (data.readNextHeader()) {
            final String key = data.getKey();
            final int size = data.getDataSize();
            if (KEY_SYSTEM.equals(key)) {
                restoreSettings(data, Settings.System.CONTENT_URI);
                mSettingsHelper.applyAudioSettings();
            } else if (KEY_SECURE.equals(key)) {
                restoreSettings(data, Settings.Secure.CONTENT_URI);
            } else if (KEY_WIFI_SUPPLICANT.equals(key)) {
                int retainedWifiState = enableWifi(false);
                restoreWifiSupplicant(FILE_WIFI_SUPPLICANT, data);
                FileUtils.setPermissions(FILE_WIFI_SUPPLICANT,
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                        FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                        Process.myUid(), Process.WIFI_UID);
                // retain the previous WIFI state.
                enableWifi(retainedWifiState == WifiManager.WIFI_STATE_ENABLED ||
                        retainedWifiState == WifiManager.WIFI_STATE_ENABLING);
            } else if (KEY_LOCALE.equals(key)) {
                byte[] localeData = new byte[size];
                data.readEntityData(localeData, 0, size);
                mSettingsHelper.setLocaleData(localeData, size);
            } else if (KEY_WIFI_CONFIG.equals(key)) {
                restoreFileData(mWifiConfigFile, data);
             } else {
                data.skipEntityData();
            }
        }
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data)  throws IOException {
        byte[] systemSettingsData = getSystemSettings();
        byte[] secureSettingsData = getSecureSettings();
        byte[] locale = mSettingsHelper.getLocaleData();
        byte[] wifiSupplicantData = getWifiSupplicant(FILE_WIFI_SUPPLICANT);
        byte[] wifiConfigData = getFileData(mWifiConfigFile);

        // Write the data to the staging file, then emit that as our tarfile
        // representation of the backed-up settings.
        String root = getFilesDir().getAbsolutePath();
        File stage = new File(root, STAGE_FILE);
        try {
            FileOutputStream filestream = new FileOutputStream(stage);
            BufferedOutputStream bufstream = new BufferedOutputStream(filestream);
            DataOutputStream out = new DataOutputStream(bufstream);

            if (DEBUG_BACKUP) Log.d(TAG, "Writing flattened data version " + FULL_BACKUP_VERSION);
            out.writeInt(FULL_BACKUP_VERSION);

            if (DEBUG_BACKUP) Log.d(TAG, systemSettingsData.length + " bytes of settings data");
            out.writeInt(systemSettingsData.length);
            out.write(systemSettingsData);
            if (DEBUG_BACKUP) Log.d(TAG, secureSettingsData.length + " bytes of secure settings data");
            out.writeInt(secureSettingsData.length);
            out.write(secureSettingsData);
            if (DEBUG_BACKUP) Log.d(TAG, locale.length + " bytes of locale data");
            out.writeInt(locale.length);
            out.write(locale);
            if (DEBUG_BACKUP) Log.d(TAG, wifiSupplicantData.length + " bytes of wifi supplicant data");
            out.writeInt(wifiSupplicantData.length);
            out.write(wifiSupplicantData);
            if (DEBUG_BACKUP) Log.d(TAG, wifiConfigData.length + " bytes of wifi config data");
            out.writeInt(wifiConfigData.length);
            out.write(wifiConfigData);

            out.flush();    // also flushes downstream

            // now we're set to emit the tar stream
            fullBackupFile(stage, data);
        } finally {
            stage.delete();
        }
    }

    @Override
    public void onRestoreFile(ParcelFileDescriptor data, long size,
            int type, String domain, String relpath, long mode, long mtime)
            throws IOException {
        if (DEBUG_BACKUP) Log.d(TAG, "onRestoreFile() invoked");
        // Our data is actually a blob of flattened settings data identical to that
        // produced during incremental backups.  Just unpack and apply it all in
        // turn.
        FileInputStream instream = new FileInputStream(data.getFileDescriptor());
        DataInputStream in = new DataInputStream(instream);

        int version = in.readInt();
        if (DEBUG_BACKUP) Log.d(TAG, "Flattened data version " + version);
        if (version == FULL_BACKUP_VERSION) {
            // system settings data first
            int nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of settings data");
            byte[] buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            restoreSettings(buffer, nBytes, Settings.System.CONTENT_URI);

            // secure settings
            nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of secure settings data");
            if (nBytes > buffer.length) buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            restoreSettings(buffer, nBytes, Settings.Secure.CONTENT_URI);

            // locale
            nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of locale data");
            if (nBytes > buffer.length) buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            mSettingsHelper.setLocaleData(buffer, nBytes);

            // wifi supplicant
            nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of wifi supplicant data");
            if (nBytes > buffer.length) buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            int retainedWifiState = enableWifi(false);
            restoreWifiSupplicant(FILE_WIFI_SUPPLICANT, buffer, nBytes);
            FileUtils.setPermissions(FILE_WIFI_SUPPLICANT,
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                    FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                    Process.myUid(), Process.WIFI_UID);
            // retain the previous WIFI state.
            enableWifi(retainedWifiState == WifiManager.WIFI_STATE_ENABLED ||
                    retainedWifiState == WifiManager.WIFI_STATE_ENABLING);

            // wifi config
            nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of wifi config data");
            if (nBytes > buffer.length) buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            restoreFileData(mWifiConfigFile, buffer, nBytes);

            if (DEBUG_BACKUP) Log.d(TAG, "Full restore complete.");
        } else {
            data.close();
            throw new IOException("Invalid file schema");
        }
    }

    private long[] readOldChecksums(ParcelFileDescriptor oldState) throws IOException {
        long[] stateChecksums = new long[STATE_SIZE];

        DataInputStream dataInput = new DataInputStream(
                new FileInputStream(oldState.getFileDescriptor()));

        try {
            int stateVersion = dataInput.readInt();
            if (stateVersion == STATE_VERSION_1) {
                for (int i = 0; i < STATE_VERSION_1_SIZE; i++) {
                    stateChecksums[i] = dataInput.readLong();
                }
            } else if (stateVersion == STATE_VERSION) {
                for (int i = 0; i < STATE_SIZE; i++) {
                    stateChecksums[i] = dataInput.readLong();
                }
            }
        } catch (EOFException eof) {
            // With the default 0 checksum we'll wind up forcing a backup of
            // any unhandled data sets, which is appropriate.
        }
        dataInput.close();
        return stateChecksums;
    }

    private void writeNewChecksums(long[] checksums, ParcelFileDescriptor newState)
            throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(
                new FileOutputStream(newState.getFileDescriptor()));

        dataOutput.writeInt(STATE_VERSION);
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
        Cursor cursor = getContentResolver().query(Settings.System.CONTENT_URI, PROJECTION, null,
                null, null);
        try {
            return extractRelevantValues(cursor, Settings.System.SETTINGS_TO_BACKUP);
        } finally {
            cursor.close();
        }
    }

    private byte[] getSecureSettings() {
        Cursor cursor = getContentResolver().query(Settings.Secure.CONTENT_URI, PROJECTION, null,
                null, null);
        try {
            return extractRelevantValues(cursor, Settings.Secure.SETTINGS_TO_BACKUP);
        } finally {
            cursor.close();
        }
    }

    private void restoreSettings(BackupDataInput data, Uri contentUri) {
        byte[] settings = new byte[data.getDataSize()];
        try {
            data.readEntityData(settings, 0, settings.length);
        } catch (IOException ioe) {
            Log.e(TAG, "Couldn't read entity data");
            return;
        }
        restoreSettings(settings, settings.length, contentUri);
    }

    private void restoreSettings(byte[] settings, int bytes, Uri contentUri) {
        if (DEBUG) {
            Log.i(TAG, "restoreSettings: " + contentUri);
        }

        // Figure out the white list.
        String[] whitelist = null;
        if (contentUri.equals(Settings.Secure.CONTENT_URI)) {
            whitelist = Settings.Secure.SETTINGS_TO_BACKUP;
        } else if (contentUri.equals(Settings.System.CONTENT_URI)) {
            whitelist = Settings.System.SETTINGS_TO_BACKUP;
        } else {
            throw new IllegalArgumentException("Unknown URI: " + contentUri);
        }

        // Restore only the white list data.
        int pos = 0;
        Map<String, String> cachedEntries = new HashMap<String, String>();
        ContentValues contentValues = new ContentValues(2);
        SettingsHelper settingsHelper = mSettingsHelper;

        final int whiteListSize = whitelist.length;
        for (int i = 0; i < whiteListSize; i++) {
            String key = whitelist[i];
            String value = cachedEntries.remove(key);

            // If the value not cached, let us look it up.
            if (value == null) {
                while (pos < bytes) {
                    int length = readInt(settings, pos);
                    pos += INTEGER_BYTE_COUNT;
                    String dataKey = length > 0 ? new String(settings, pos, length) : null;
                    pos += length;
                    length = readInt(settings, pos);
                    pos += INTEGER_BYTE_COUNT;
                    String dataValue = length > 0 ? new String(settings, pos, length) : null;
                    pos += length;
                    if (key.equals(dataKey)) {
                        value = dataValue;
                        break;
                    }
                    cachedEntries.put(dataKey, dataValue);
                }
            }

            if (value == null) {
                continue;
            }

            if (settingsHelper.restoreValue(key, value)) {
                contentValues.clear();
                contentValues.put(Settings.NameValueTable.NAME, key);
                contentValues.put(Settings.NameValueTable.VALUE, value);
                getContentResolver().insert(contentUri, contentValues);
            }

            if (DEBUG) {
                Log.d(TAG, "Restored setting: " + key + "=" + value);
            }
        }
    }

    /**
     * Given a cursor and a set of keys, extract the required keys and
     * values and write them to a byte array.
     *
     * @param cursor A cursor with settings data.
     * @param settings The settings to extract.
     * @return The byte array of extracted values.
     */
    private byte[] extractRelevantValues(Cursor cursor, String[] settings) {
        final int settingsCount = settings.length;
        byte[][] values = new byte[settingsCount * 2][]; // keys and values
        if (!cursor.moveToFirst()) {
            Log.e(TAG, "Couldn't read from the cursor");
            return new byte[0];
        }

        // Obtain the relevant data in a temporary array.
        int totalSize = 0;
        int backedUpSettingIndex = 0;
        Map<String, String> cachedEntries = new HashMap<String, String>();
        for (int i = 0; i < settingsCount; i++) {
            String key = settings[i];
            String value = cachedEntries.remove(key);

            // If the value not cached, let us look it up.
            if (value == null) {
                while (!cursor.isAfterLast()) {
                    String cursorKey = cursor.getString(COLUMN_NAME);
                    String cursorValue = cursor.getString(COLUMN_VALUE);
                    cursor.moveToNext();
                    if (key.equals(cursorKey)) {
                        value = cursorValue;
                        break;
                    }
                    cachedEntries.put(cursorKey, cursorValue);
                }
            }

            if (value == null) {
                continue;
            }

            // Write the key and value in the intermediary array.
            byte[] keyBytes = key.getBytes();
            totalSize += INTEGER_BYTE_COUNT + keyBytes.length;
            values[backedUpSettingIndex * 2] = keyBytes;

            byte[] valueBytes = value.getBytes();
            totalSize += INTEGER_BYTE_COUNT + valueBytes.length;
            values[backedUpSettingIndex * 2 + 1] = valueBytes;

            backedUpSettingIndex++;

            if (DEBUG) {
                Log.d(TAG, "Backed up setting: " + key + "=" + value);
            }
        }

        // Aggregate the result.
        byte[] result = new byte[totalSize];
        int pos = 0;
        final int keyValuePairCount = backedUpSettingIndex * 2;
        for (int i = 0; i < keyValuePairCount; i++) {
            pos = writeInt(result, pos, values[i].length);
            pos = writeBytes(result, pos, values[i]);
        }
        return result;
    }

    private byte[] getFileData(String filename) {
        InputStream is = null;
        try {
            File file = new File(filename);
            is = new FileInputStream(file);

            //Will truncate read on a very long file,
            //should not happen for a config file
            byte[] bytes = new byte[(int)file.length()];

            int offset = 0;
            int numRead = 0;
            while (offset < bytes.length
                    && (numRead=is.read(bytes, offset, bytes.length-offset)) >= 0) {
                offset += numRead;
            }

            //read failure
            if (offset < bytes.length) {
                Log.w(TAG, "Couldn't backup " + filename);
                return EMPTY_DATA;
            }
            return bytes;
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't backup " + filename);
            return EMPTY_DATA;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }

    }

    private void restoreFileData(String filename, BackupDataInput data) {
        byte[] bytes = new byte[data.getDataSize()];
        if (bytes.length <= 0) return;
        try {
            data.readEntityData(bytes, 0, data.getDataSize());
            restoreFileData(filename, bytes, bytes.length);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read file data for " + filename);
        }
    }

    private void restoreFileData(String filename, byte[] bytes, int size) {
        try {
            File file = new File(filename);
            if (file.exists()) file.delete();

            OutputStream os = new BufferedOutputStream(new FileOutputStream(filename, true));
            os.write(bytes, 0, size);
            os.close();
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't restore " + filename);
        }
    }


    private byte[] getWifiSupplicant(String filename) {
        BufferedReader br = null;
        try {
            File file = new File(filename);
            if (file.exists()) {
                br = new BufferedReader(new FileReader(file));
                StringBuffer relevantLines = new StringBuffer();
                boolean started = false;
                String line;
                while ((line = br.readLine()) != null) {
                    if (!started && line.startsWith("network")) {
                        started = true;
                    }
                    if (started) {
                        relevantLines.append(line).append("\n");
                    }
                }
                if (relevantLines.length() > 0) {
                    return relevantLines.toString().getBytes();
                } else {
                    return EMPTY_DATA;
                }
            } else {
                return EMPTY_DATA;
            }
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't backup " + filename);
            return EMPTY_DATA;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void restoreWifiSupplicant(String filename, BackupDataInput data) {
        byte[] bytes = new byte[data.getDataSize()];
        if (bytes.length <= 0) return;
        try {
            data.readEntityData(bytes, 0, data.getDataSize());
            restoreWifiSupplicant(filename, bytes, bytes.length);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read supplicant data");
        }
    }

    private void restoreWifiSupplicant(String filename, byte[] bytes, int size) {
        try {
            WifiNetworkSettings supplicantImage = new WifiNetworkSettings();

            File supplicantFile = new File(FILE_WIFI_SUPPLICANT);
            if (supplicantFile.exists()) {
                // Retain the existing APs; we'll append the restored ones to them
                BufferedReader in = new BufferedReader(new FileReader(FILE_WIFI_SUPPLICANT));
                supplicantImage.readNetworks(in);
                in.close();

                supplicantFile.delete();
            }

            // Incorporate the restore AP information
            if (size > 0) {
                char[] restoredAsBytes = new char[size];
                for (int i = 0; i < size; i++) restoredAsBytes[i] = (char) bytes[i];
                BufferedReader in = new BufferedReader(new CharArrayReader(restoredAsBytes));
                supplicantImage.readNetworks(in);

                if (DEBUG_BACKUP) {
                    Log.v(TAG, "Final AP list:");
                    supplicantImage.dump();
                }
            }

            // Install the correct default template
            BufferedWriter bw = new BufferedWriter(new FileWriter(FILE_WIFI_SUPPLICANT));
            copyWifiSupplicantTemplate(bw);

            // Write the restored supplicant config and we're done
            supplicantImage.write(bw);
            bw.close();
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't restore " + filename);
        }
    }

    private void copyWifiSupplicantTemplate(BufferedWriter bw) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(FILE_WIFI_SUPPLICANT_TEMPLATE));
            char[] temp = new char[1024];
            int size;
            while ((size = br.read(temp)) > 0) {
                bw.write(temp, 0, size);
            }
            br.close();
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't copy wpa_supplicant file");
        }
    }

    /**
     * Write an int in BigEndian into the byte array.
     * @param out byte array
     * @param pos current pos in array
     * @param value integer to write
     * @return the index after adding the size of an int (4) in bytes.
     */
    private int writeInt(byte[] out, int pos, int value) {
        out[pos + 0] = (byte) ((value >> 24) & 0xFF);
        out[pos + 1] = (byte) ((value >> 16) & 0xFF);
        out[pos + 2] = (byte) ((value >>  8) & 0xFF);
        out[pos + 3] = (byte) ((value >>  0) & 0xFF);
        return pos + INTEGER_BYTE_COUNT;
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

    private int enableWifi(boolean enable) {
        if (mWfm == null) {
            mWfm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        }
        if (mWfm != null) {
            int state = mWfm.getWifiState();
            mWfm.setWifiEnabled(enable);
            return state;
        } else {
            Log.e(TAG, "Failed to fetch WifiManager instance");
        }
        return WifiManager.WIFI_STATE_UNKNOWN;
    }
}
