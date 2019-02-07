/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.backup;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.backup.BackupDataInputStream;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncAdapterType;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper for backing up account sync settings (whether or not a service should be synced). The
 * sync settings are backed up as a JSON object containing all the necessary information for
 * restoring the sync settings later.
 */
public class AccountSyncSettingsBackupHelper implements BackupHelper {

    private static final String TAG = "AccountSyncSettingsBackupHelper";
    private static final boolean DEBUG = false;

    private static final int STATE_VERSION = 1;
    private static final int MD5_BYTE_SIZE = 16;
    private static final int SYNC_REQUEST_LATCH_TIMEOUT_SECONDS = 1;

    private static final String JSON_FORMAT_HEADER_KEY = "account_data";
    private static final String JSON_FORMAT_ENCODING = "UTF-8";
    private static final int JSON_FORMAT_VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_MASTER_SYNC_ENABLED = "masterSyncEnabled";
    private static final String KEY_ACCOUNTS = "accounts";
    private static final String KEY_ACCOUNT_NAME = "name";
    private static final String KEY_ACCOUNT_TYPE = "type";
    private static final String KEY_ACCOUNT_AUTHORITIES = "authorities";
    private static final String KEY_AUTHORITY_NAME = "name";
    private static final String KEY_AUTHORITY_SYNC_STATE = "syncState";
    private static final String KEY_AUTHORITY_SYNC_ENABLED = "syncEnabled";
    private static final String STASH_FILE = "/backup/unadded_account_syncsettings.json";

    private Context mContext;
    private AccountManager mAccountManager;
    private final int mUserId;

    public AccountSyncSettingsBackupHelper(Context context, int userId) {
        mContext = context;
        mAccountManager = AccountManager.get(mContext);

        mUserId = userId;
    }

    /**
     * Take a snapshot of the current account sync settings and write them to the given output.
     */
    @Override
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput output,
            ParcelFileDescriptor newState) {
        try {
            JSONObject dataJSON = serializeAccountSyncSettingsToJSON(mUserId);

            if (DEBUG) {
                Log.d(TAG, "Account sync settings JSON: " + dataJSON);
            }

            // Encode JSON data to bytes.
            byte[] dataBytes = dataJSON.toString().getBytes(JSON_FORMAT_ENCODING);
            byte[] oldMd5Checksum = readOldMd5Checksum(oldState);
            byte[] newMd5Checksum = generateMd5Checksum(dataBytes);
            if (!Arrays.equals(oldMd5Checksum, newMd5Checksum)) {
                int dataSize = dataBytes.length;
                output.writeEntityHeader(JSON_FORMAT_HEADER_KEY, dataSize);
                output.writeEntityData(dataBytes, dataSize);

                Log.i(TAG, "Backup successful.");
            } else {
                Log.i(TAG, "Old and new MD5 checksums match. Skipping backup.");
            }

            writeNewMd5Checksum(newState, newMd5Checksum);
        } catch (JSONException | IOException | NoSuchAlgorithmException e) {
            Log.e(TAG, "Couldn't backup account sync settings\n" + e);
        }
    }

    /**
     * Fetch and serialize Account and authority information as a JSON Array.
     */
    private JSONObject serializeAccountSyncSettingsToJSON(int userId) throws JSONException {
        Account[] accounts = mAccountManager.getAccountsAsUser(userId);
        SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypesAsUser(userId);

        // Create a map of Account types to authorities. Later this will make it easier for us to
        // generate our JSON.
        HashMap<String, List<String>> accountTypeToAuthorities = new HashMap<String,
                List<String>>();
        for (SyncAdapterType syncAdapter : syncAdapters) {
            // Skip adapters that aren’t visible to the user.
            if (!syncAdapter.isUserVisible()) {
                continue;
            }
            if (!accountTypeToAuthorities.containsKey(syncAdapter.accountType)) {
                accountTypeToAuthorities.put(syncAdapter.accountType, new ArrayList<String>());
            }
            accountTypeToAuthorities.get(syncAdapter.accountType).add(syncAdapter.authority);
        }

        // Generate JSON.
        JSONObject backupJSON = new JSONObject();
        backupJSON.put(KEY_VERSION, JSON_FORMAT_VERSION);
        backupJSON.put(KEY_MASTER_SYNC_ENABLED, ContentResolver.getMasterSyncAutomaticallyAsUser(
                userId));

        JSONArray accountJSONArray = new JSONArray();
        for (Account account : accounts) {
            List<String> authorities = accountTypeToAuthorities.get(account.type);

            // We ignore Accounts that don't have any authorities because there would be no sync
            // settings for us to restore.
            if (authorities == null || authorities.isEmpty()) {
                continue;
            }

            JSONObject accountJSON = new JSONObject();
            accountJSON.put(KEY_ACCOUNT_NAME, account.name);
            accountJSON.put(KEY_ACCOUNT_TYPE, account.type);

            // Add authorities for this Account type and check whether or not sync is enabled.
            JSONArray authoritiesJSONArray = new JSONArray();
            for (String authority : authorities) {
                int syncState = ContentResolver.getIsSyncableAsUser(account, authority, userId);
                boolean syncEnabled = ContentResolver.getSyncAutomaticallyAsUser(account, authority,
                        userId);

                JSONObject authorityJSON = new JSONObject();
                authorityJSON.put(KEY_AUTHORITY_NAME, authority);
                authorityJSON.put(KEY_AUTHORITY_SYNC_STATE, syncState);
                authorityJSON.put(KEY_AUTHORITY_SYNC_ENABLED, syncEnabled);
                authoritiesJSONArray.put(authorityJSON);
            }
            accountJSON.put(KEY_ACCOUNT_AUTHORITIES, authoritiesJSONArray);

            accountJSONArray.put(accountJSON);
        }
        backupJSON.put(KEY_ACCOUNTS, accountJSONArray);

        return backupJSON;
    }

    /**
     * Read the MD5 checksum from the old state.
     *
     * @return the old MD5 checksum
     */
    private byte[] readOldMd5Checksum(ParcelFileDescriptor oldState) throws IOException {
        DataInputStream dataInput = new DataInputStream(
                new FileInputStream(oldState.getFileDescriptor()));

        byte[] oldMd5Checksum = new byte[MD5_BYTE_SIZE];
        try {
            int stateVersion = dataInput.readInt();
            if (stateVersion <= STATE_VERSION) {
                // If the state version is a version we can understand then read the MD5 sum,
                // otherwise we return an empty byte array for the MD5 sum which will force a
                // backup.
                for (int i = 0; i < MD5_BYTE_SIZE; i++) {
                    oldMd5Checksum[i] = dataInput.readByte();
                }
            } else {
                Log.i(TAG, "Backup state version is: " + stateVersion
                        + " (support only up to version " + STATE_VERSION + ")");
            }
        } catch (EOFException eof) {
            // Initial state may be empty.
        }
        // We explicitly don't close 'dataInput' because we must not close the backing fd.
        return oldMd5Checksum;
    }

    /**
     * Write the given checksum to the file descriptor.
     */
    private void writeNewMd5Checksum(ParcelFileDescriptor newState, byte[] md5Checksum)
            throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(newState.getFileDescriptor())));

        dataOutput.writeInt(STATE_VERSION);
        dataOutput.write(md5Checksum);

        // We explicitly don't close 'dataOutput' because we must not close the backing fd.
        // The FileOutputStream will not close it implicitly.

    }

    private byte[] generateMd5Checksum(byte[] data) throws NoSuchAlgorithmException {
        if (data == null) {
            return null;
        }

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        return md5.digest(data);
    }

    /**
     * Restore account sync settings from the given data input stream.
     */
    @Override
    public void restoreEntity(BackupDataInputStream data) {
        byte[] dataBytes = new byte[data.size()];
        try {
            // Read the data and convert it to a String.
            data.read(dataBytes);
            String dataString = new String(dataBytes, JSON_FORMAT_ENCODING);

            // Convert data to a JSON object.
            JSONObject dataJSON = new JSONObject(dataString);
            boolean masterSyncEnabled = dataJSON.getBoolean(KEY_MASTER_SYNC_ENABLED);
            JSONArray accountJSONArray = dataJSON.getJSONArray(KEY_ACCOUNTS);

            boolean currentMasterSyncEnabled = ContentResolver.getMasterSyncAutomaticallyAsUser(
                    mUserId);
            if (currentMasterSyncEnabled) {
                // Disable master sync to prevent any syncs from running.
                ContentResolver.setMasterSyncAutomaticallyAsUser(false, mUserId);
            }

            try {
                restoreFromJsonArray(accountJSONArray, mUserId);
            } finally {
                // Set the master sync preference to the value from the backup set.
                ContentResolver.setMasterSyncAutomaticallyAsUser(masterSyncEnabled, mUserId);
            }
            Log.i(TAG, "Restore successful.");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Couldn't restore account sync settings\n" + e);
        }
    }

    private void restoreFromJsonArray(JSONArray accountJSONArray, int userId)
            throws JSONException {
        Set<Account> currentAccounts = getAccounts(userId);
        JSONArray unaddedAccountsJSONArray = new JSONArray();
        for (int i = 0; i < accountJSONArray.length(); i++) {
            JSONObject accountJSON = (JSONObject) accountJSONArray.get(i);
            String accountName = accountJSON.getString(KEY_ACCOUNT_NAME);
            String accountType = accountJSON.getString(KEY_ACCOUNT_TYPE);

            Account account = null;
            try {
                account = new Account(accountName, accountType);
            } catch (IllegalArgumentException iae) {
                continue;
            }

            // Check if the account already exists. Accounts that don't exist on the device
            // yet won't be restored.
            if (currentAccounts.contains(account)) {
                if (DEBUG) Log.i(TAG, "Restoring Sync Settings for" + accountName);
                restoreExistingAccountSyncSettingsFromJSON(accountJSON, userId);
            } else {
                unaddedAccountsJSONArray.put(accountJSON);
            }
        }

        if (unaddedAccountsJSONArray.length() > 0) {
            try (FileOutputStream fOutput = new FileOutputStream(getStashFile(userId))) {
                String jsonString = unaddedAccountsJSONArray.toString();
                DataOutputStream out = new DataOutputStream(fOutput);
                out.writeUTF(jsonString);
            } catch (IOException ioe) {
                // Error in writing to stash file
                Log.e(TAG, "unable to write the sync settings to the stash file", ioe);
            }
        } else {
            File stashFile = getStashFile(userId);
            if (stashFile.exists()) {
                stashFile.delete();
            }
        }
    }

    /**
     * Restore SyncSettings for all existing accounts from a stashed backup-set
     */
    private void accountAddedInternal(int userId) {
        String jsonString;

        try (FileInputStream fIn = new FileInputStream(getStashFile(userId))) {
            DataInputStream in = new DataInputStream(fIn);
            jsonString = in.readUTF();
        } catch (FileNotFoundException fnfe) {
            // This is expected to happen when there is no accounts info stashed
            if (DEBUG) Log.d(TAG, "unable to find the stash file", fnfe);
            return;
        } catch (IOException ioe) {
            if (DEBUG) Log.d(TAG, "could not read sync settings from stash file", ioe);
            return;
        }

        try {
            JSONArray unaddedAccountsJSONArray = new JSONArray(jsonString);
            restoreFromJsonArray(unaddedAccountsJSONArray, userId);
        } catch (JSONException jse) {
            // Malformed jsonString
            Log.e(TAG, "there was an error with the stashed sync settings", jse);
        }
    }

    /**
     * Restore SyncSettings for all existing accounts from a stashed backup-set
     */
    public static void accountAdded(Context context, int userId) {
        AccountSyncSettingsBackupHelper helper = new AccountSyncSettingsBackupHelper(context,
                userId);
        helper.accountAddedInternal(userId);
    }

    /**
     * Helper method - fetch accounts and return them as a HashSet.
     *
     * @return Accounts in a HashSet.
     */
    private Set<Account> getAccounts(int userId) {
        Account[] accounts = mAccountManager.getAccountsAsUser(userId);
        Set<Account> accountHashSet = new HashSet<Account>();
        for (Account account : accounts) {
            accountHashSet.add(account);
        }
        return accountHashSet;
    }

    /**
     * Restore account sync settings using the given JSON. This function won't work if the account
     * doesn't exist yet.
     * This function will only be called during Setup Wizard, where we are guaranteed that there
     * are no active syncs.
     * There are 2 pieces of data to restore -
     *      isSyncable (corresponds to {@link ContentResolver#getIsSyncable(Account, String)}
     *      syncEnabled (corresponds to {@link ContentResolver#getSyncAutomatically(Account, String)}
     * <strong>The restore favours adapters that were enabled on the old device, and doesn't care
     * about adapters that were disabled.</strong>
     *
     * syncEnabled=true in restore data.
     * syncEnabled will be true on this device. isSyncable will be left as the default in order to
     * give the enabled adapter the chance to run an initialization sync.
     *
     * syncEnabled=false in restore data.
     * syncEnabled will be false on this device. isSyncable will be set to 2, unless it was 0 on the
     * old device in which case it will be set to 0 on this device. This is because isSyncable=0 is
     * a rare state and was probably set to 0 for good reason (historically isSyncable is a way by
     * which adapters control their own sync state independently of sync settings which is
     * toggleable by the user).
     * isSyncable=2 is a new isSyncable state we introduced specifically to allow adapters that are
     * disabled after a restore to run initialization logic when the adapter is later enabled.
     * See com.android.server.content.SyncStorageEngine#setSyncAutomatically
     *
     * The end result is that an adapter that the user had on will be turned on and get an
     * initialization sync, while an adapter that the user had off will be off until the user
     * enables it on this device at which point it will get an initialization sync.
     */
    private void restoreExistingAccountSyncSettingsFromJSON(JSONObject accountJSON, int userId)
            throws JSONException {
        // Restore authorities.
        JSONArray authorities = accountJSON.getJSONArray(KEY_ACCOUNT_AUTHORITIES);
        String accountName = accountJSON.getString(KEY_ACCOUNT_NAME);
        String accountType = accountJSON.getString(KEY_ACCOUNT_TYPE);

        final Account account = new Account(accountName, accountType);
        for (int i = 0; i < authorities.length(); i++) {
            JSONObject authority = (JSONObject) authorities.get(i);
            final String authorityName = authority.getString(KEY_AUTHORITY_NAME);
            boolean wasSyncEnabled = authority.getBoolean(KEY_AUTHORITY_SYNC_ENABLED);
            int wasSyncable = authority.getInt(KEY_AUTHORITY_SYNC_STATE);

            ContentResolver.setSyncAutomaticallyAsUser(
                    account, authorityName, wasSyncEnabled, userId);

            if (!wasSyncEnabled) {
                ContentResolver.setIsSyncableAsUser(
                        account,
                        authorityName,
                        wasSyncable == 0 ?
                                0 /* not syncable */ : 2 /* syncable but needs initialization */,
                        userId);
            }
        }
    }

    @Override
    public void writeNewStateDescription(ParcelFileDescriptor newState) {

    }

    private static File getStashFile(int userId) {
        File baseDir = userId == UserHandle.USER_SYSTEM ? Environment.getDataDirectory()
                : Environment.getDataSystemCeDirectory(userId);
        return new File(baseDir, STASH_FILE);
    }
}
