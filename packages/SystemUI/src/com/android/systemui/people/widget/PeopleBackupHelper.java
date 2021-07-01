/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.people.widget;

import static com.android.systemui.people.PeopleBackupFollowUpJob.SHARED_FOLLOW_UP;
import static com.android.systemui.people.PeopleSpaceUtils.DEBUG;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import android.app.backup.BackupDataInputStream;
import android.app.backup.BackupDataOutput;
import android.app.backup.SharedPreferencesBackupHelper;
import android.app.people.IPeopleManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.people.PeopleBackupFollowUpJob;
import com.android.systemui.people.SharedPreferencesHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Helper class to backup and restore Conversations widgets storage.
 * It is used by SystemUI's BackupHelper agent.
 * TODO(b/192334798): Lock access to storage using PeopleSpaceWidgetManager's lock.
 */
public class PeopleBackupHelper extends SharedPreferencesBackupHelper {
    private static final String TAG = "PeopleBackupHelper";

    public static final String ADD_USER_ID_TO_URI = "add_user_id_to_uri_";
    public static final String SHARED_BACKUP = "shared_backup";

    private final Context mContext;
    private final UserHandle mUserHandle;
    private final PackageManager mPackageManager;
    private final IPeopleManager mIPeopleManager;
    private final AppWidgetManager mAppWidgetManager;

    /**
     * Types of entries stored in the default SharedPreferences file for Conversation widgets.
     * Widget ID corresponds to a pair [widgetId, contactURI].
     * PeopleTileKey corresponds to a pair [PeopleTileKey, {widgetIds}].
     * Contact URI corresponds to a pair [Contact URI, {widgetIds}].
     */
    enum SharedFileEntryType {
        UNKNOWN,
        WIDGET_ID,
        PEOPLE_TILE_KEY,
        CONTACT_URI
    }

    /**
     * Returns the file names that should be backed up and restored by SharedPreferencesBackupHelper
     * infrastructure.
     */
    public static List<String> getFilesToBackup() {
        return Collections.singletonList(SHARED_BACKUP);
    }

    public PeopleBackupHelper(Context context, UserHandle userHandle,
            String[] sharedPreferencesKey) {
        super(context, sharedPreferencesKey);
        mContext = context;
        mUserHandle = userHandle;
        mPackageManager = context.getPackageManager();
        mIPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mAppWidgetManager = AppWidgetManager.getInstance(context);
    }

    @VisibleForTesting
    public PeopleBackupHelper(Context context, UserHandle userHandle,
            String[] sharedPreferencesKey, PackageManager packageManager,
            IPeopleManager peopleManager) {
        super(context, sharedPreferencesKey);
        mContext = context;
        mUserHandle = userHandle;
        mPackageManager = packageManager;
        mIPeopleManager = peopleManager;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
    }

    /**
     * Reads values from default storage, backs them up appropriately to a specified backup file,
     * and calls super's performBackup, which backs up the values of the backup file.
     */
    @Override
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        if (DEBUG) Log.d(TAG, "Backing up conversation widgets, writing to: " + SHARED_BACKUP);
        // Open default value for readings values.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        if (sp.getAll().isEmpty()) {
            if (DEBUG) Log.d(TAG, "No information to be backed up, finishing.");
            return;
        }

        // Open backup file for writing.
        SharedPreferences backupSp = mContext.getSharedPreferences(
                SHARED_BACKUP, Context.MODE_PRIVATE);
        SharedPreferences.Editor backupEditor = backupSp.edit();
        backupEditor.clear();

        // Fetch Conversations widgets corresponding to this user.
        List<String> existingWidgets = getExistingWidgetsForUser(mUserHandle.getIdentifier());
        if (existingWidgets.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No existing Conversations widgets, returning.");
            return;
        }

        // Writes each entry to backup file.
        sp.getAll().entrySet().forEach(entry -> backupKey(entry, backupEditor, existingWidgets));
        backupEditor.apply();

        super.performBackup(oldState, data, newState);
    }

    /**
     * Restores backed up values to backup file via super's restoreEntity, then transfers them
     * back to regular storage. Restore operations for each users are done in sequence, so we can
     * safely use the same backup file names.
     */
    @Override
    public void restoreEntity(BackupDataInputStream data) {
        if (DEBUG) Log.d(TAG, "Restoring Conversation widgets.");
        super.restoreEntity(data);

        // Open backup file for reading values.
        SharedPreferences backupSp = mContext.getSharedPreferences(
                SHARED_BACKUP, Context.MODE_PRIVATE);

        // Open default file and follow-up file for writing.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        SharedPreferences followUp = mContext.getSharedPreferences(
                SHARED_FOLLOW_UP, Context.MODE_PRIVATE);
        SharedPreferences.Editor followUpEditor = followUp.edit();

        // Writes each entry back to default value.
        boolean shouldScheduleJob = false;
        for (Map.Entry<String, ?> entry : backupSp.getAll().entrySet()) {
            boolean restored = restoreKey(entry, editor, followUpEditor, backupSp);
            if (!restored) {
                shouldScheduleJob = true;
            }
        }

        editor.apply();
        followUpEditor.apply();
        SharedPreferencesHelper.clear(backupSp);

        // If any of the widgets is not yet available, schedule a follow-up job to check later.
        if (shouldScheduleJob) {
            if (DEBUG) Log.d(TAG, "At least one shortcut is not available, scheduling follow-up.");
            PeopleBackupFollowUpJob.scheduleJob(mContext);
        }

        updateWidgets(mContext);
    }

    /** Backs up an entry from default file to backup file. */
    public void backupKey(Map.Entry<String, ?> entry, SharedPreferences.Editor backupEditor,
            List<String> existingWidgets) {
        String key = entry.getKey();
        if (TextUtils.isEmpty(key)) {
            return;
        }

        SharedFileEntryType entryType = getEntryType(entry);
        switch(entryType) {
            case WIDGET_ID:
                backupWidgetIdKey(key, String.valueOf(entry.getValue()), backupEditor,
                        existingWidgets);
                break;
            case PEOPLE_TILE_KEY:
                backupPeopleTileKey(key, (Set<String>) entry.getValue(), backupEditor,
                        existingWidgets);
                break;
            case CONTACT_URI:
                backupContactUriKey(key, (Set<String>) entry.getValue(), backupEditor);
                break;
            case UNKNOWN:
            default:
                Log.w(TAG, "Key not identified, skipping: " + key);
        }
    }

    /**
     * Tries to restore an entry from backup file to default file.
     * Returns true if restore is finished, false if it needs to be checked later.
     */
    boolean restoreKey(Map.Entry<String, ?> entry, SharedPreferences.Editor editor,
            SharedPreferences.Editor followUpEditor, SharedPreferences backupSp) {
        String key = entry.getKey();
        SharedFileEntryType keyType = getEntryType(entry);
        int storedUserId = backupSp.getInt(ADD_USER_ID_TO_URI + key, INVALID_USER_ID);
        switch (keyType) {
            case WIDGET_ID:
                restoreWidgetIdKey(key, String.valueOf(entry.getValue()), editor, storedUserId);
                return true;
            case PEOPLE_TILE_KEY:
                return restorePeopleTileKeyAndCorrespondingWidgetFile(
                        key, (Set<String>) entry.getValue(), editor, followUpEditor);
            case CONTACT_URI:
                restoreContactUriKey(key, (Set<String>) entry.getValue(), editor, storedUserId);
                return true;
            case UNKNOWN:
            default:
                Log.e(TAG, "Key not identified, skipping:" + key);
                return true;
        }
    }

    /**
     * Backs up a [widgetId, contactURI] pair, if widget id corresponds to current user.
     * If contact URI has a user id, stores it so it can be re-added on restore.
     */
    private void backupWidgetIdKey(String key, String uriString, SharedPreferences.Editor editor,
            List<String> existingWidgets) {
        if (!existingWidgets.contains(key)) {
            if (DEBUG) Log.d(TAG, "Widget: " + key + " does't correspond to this user, skipping.");
            return;
        }
        Uri uri = Uri.parse(uriString);
        if (ContentProvider.uriHasUserId(uri)) {
            if (DEBUG) Log.d(TAG, "Contact URI value has user ID, removing from: " + uri);
            int userId = ContentProvider.getUserIdFromUri(uri);
            editor.putInt(ADD_USER_ID_TO_URI + key, userId);
            uri = ContentProvider.getUriWithoutUserId(uri);
        }
        if (DEBUG) Log.d(TAG, "Backing up widgetId key: " + key + " . Value: " + uri.toString());
        editor.putString(key, uri.toString());
    }

    /** Restores a [widgetId, contactURI] pair, and a potential {@code storedUserId}. */
    private void restoreWidgetIdKey(String key, String uriString, SharedPreferences.Editor editor,
            int storedUserId) {
        Uri uri = Uri.parse(uriString);
        if (storedUserId != INVALID_USER_ID) {
            uri = ContentProvider.createContentUriForUser(uri, UserHandle.of(storedUserId));
            if (DEBUG) Log.d(TAG, "UserId was removed from URI on back up, re-adding as:" + uri);

        }
        if (DEBUG) Log.d(TAG, "Restoring widgetId key: " + key + " . Value: " + uri.toString());
        editor.putString(key, uri.toString());
    }

    /**
     * Backs up a [PeopleTileKey, {widgetIds}] pair, if PeopleTileKey's user is the same as current
     * user, stripping out the user id.
     */
    private void backupPeopleTileKey(String key, Set<String> widgetIds,
            SharedPreferences.Editor editor, List<String> existingWidgets) {
        PeopleTileKey peopleTileKey = PeopleTileKey.fromString(key);
        if (peopleTileKey.getUserId() != mUserHandle.getIdentifier()) {
            if (DEBUG) Log.d(TAG, "PeopleTileKey corresponds to different user, skipping backup.");
            return;
        }

        Set<String> filteredWidgets = widgetIds.stream()
                .filter(id -> existingWidgets.contains(id))
                .collect(Collectors.toSet());
        if (filteredWidgets.isEmpty()) {
            return;
        }

        peopleTileKey.setUserId(INVALID_USER_ID);
        if (DEBUG) {
            Log.d(TAG, "Backing up PeopleTileKey key: " + peopleTileKey.toString() + ". Value: "
                    + filteredWidgets);
        }
        editor.putStringSet(peopleTileKey.toString(), filteredWidgets);
    }

    /**
     * Restores a [PeopleTileKey, {widgetIds}] pair, restoring the user id. Checks if the
     * corresponding shortcut exists, and if not, we should schedule a follow up to check later.
     * Also restores corresponding [widgetId, PeopleTileKey], which is not backed up since the
     * information can be inferred from this.
     * Returns true if restore is finished, false if we should check if shortcut is available later.
     */
    private boolean restorePeopleTileKeyAndCorrespondingWidgetFile(String key,
            Set<String> widgetIds, SharedPreferences.Editor editor,
            SharedPreferences.Editor followUpEditor) {
        PeopleTileKey peopleTileKey = PeopleTileKey.fromString(key);
        // Should never happen, as type of key has been checked.
        if (peopleTileKey == null) {
            if (DEBUG) Log.d(TAG, "PeopleTileKey key to be restored is null, skipping.");
            return true;
        }

        peopleTileKey.setUserId(mUserHandle.getIdentifier());
        if (!PeopleTileKey.isValid(peopleTileKey)) {
            if (DEBUG) Log.d(TAG, "PeopleTileKey key to be restored is not valid, skipping.");
            return true;
        }

        boolean restored = isReadyForRestore(
                mIPeopleManager, mPackageManager, peopleTileKey);
        if (!restored) {
            if (DEBUG) Log.d(TAG, "Adding key to follow-up storage: " + peopleTileKey.toString());
            // Follow-up file stores shortcuts that need to be checked later, and possibly wiped
            // from our storage.
            followUpEditor.putStringSet(peopleTileKey.toString(), widgetIds);
        }

        if (DEBUG) {
            Log.d(TAG, "Restoring PeopleTileKey key: " + peopleTileKey.toString() + " . Value: "
                    + widgetIds);
        }
        editor.putStringSet(peopleTileKey.toString(), widgetIds);
        restoreWidgetIdFiles(mContext, widgetIds, peopleTileKey);
        return restored;
    }

    /**
     * Backs up a [contactURI, {widgetIds}] pair. If contactURI contains a userId, we back up
     * this entry in the corresponding user. If it doesn't, we back it up as user 0.
     * If contact URI has a user id, stores it so it can be re-added on restore.
     * We do not take existing widgets for this user into consideration.
     */
    private void backupContactUriKey(String key, Set<String> widgetIds,
            SharedPreferences.Editor editor) {
        Uri uri = Uri.parse(String.valueOf(key));
        if (ContentProvider.uriHasUserId(uri)) {
            int userId = ContentProvider.getUserIdFromUri(uri);
            if (DEBUG) Log.d(TAG, "Contact URI has user Id: " + userId);
            if (userId == mUserHandle.getIdentifier()) {
                uri = ContentProvider.getUriWithoutUserId(uri);
                if (DEBUG) {
                    Log.d(TAG, "Backing up contactURI key: " + uri.toString() + " . Value: "
                            + widgetIds);
                }
                editor.putInt(ADD_USER_ID_TO_URI + uri.toString(), userId);
                editor.putStringSet(uri.toString(), widgetIds);
            } else {
                if (DEBUG) Log.d(TAG, "ContactURI corresponds to different user, skipping.");
            }
        } else if (mUserHandle.isSystem()) {
            if (DEBUG) {
                Log.d(TAG, "Backing up contactURI key: " + uri.toString() + " . Value: "
                        + widgetIds);
            }
            editor.putStringSet(uri.toString(), widgetIds);
        }
    }

    /** Restores a [contactURI, {widgetIds}] pair, and a potential {@code storedUserId}. */
    private void restoreContactUriKey(String key, Set<String> widgetIds,
            SharedPreferences.Editor editor, int storedUserId) {
        Uri uri = Uri.parse(key);
        if (storedUserId != INVALID_USER_ID) {
            uri = ContentProvider.createContentUriForUser(uri, UserHandle.of(storedUserId));
            if (DEBUG) Log.d(TAG, "UserId was removed from URI on back up, re-adding as:" + uri);
        }
        if (DEBUG) {
            Log.d(TAG, "Restoring contactURI key: " + uri.toString() + " . Value: " + widgetIds);
        }
        editor.putStringSet(uri.toString(), widgetIds);
    }

    /** Restores the widget-specific files that contain PeopleTileKey information. */
    public static void restoreWidgetIdFiles(Context context, Set<String> widgetIds,
            PeopleTileKey key) {
        for (String id : widgetIds) {
            if (DEBUG) Log.d(TAG, "Restoring widget Id file: " + id + " . Value: " + key);
            SharedPreferences dest = context.getSharedPreferences(id, Context.MODE_PRIVATE);
            SharedPreferencesHelper.setPeopleTileKey(dest, key);
        }
    }

    private List<String> getExistingWidgetsForUser(int userId) {
        List<String> existingWidgets = new ArrayList<>();
        int[] ids = mAppWidgetManager.getAppWidgetIds(
                new ComponentName(mContext, PeopleSpaceWidgetProvider.class));
        for (int id : ids) {
            String idString = String.valueOf(id);
            SharedPreferences sp = mContext.getSharedPreferences(idString, Context.MODE_PRIVATE);
            if (sp.getInt(USER_ID, INVALID_USER_ID) == userId) {
                existingWidgets.add(idString);
            }
        }
        if (DEBUG) Log.d(TAG, "Existing widgets: " + existingWidgets);
        return existingWidgets;
    }

    /**
     * Returns whether {@code key} corresponds to a shortcut that is ready for restore, either
     * because it is available or because it never will be. If not ready, we schedule a job to check
     * again later.
     */
    public static boolean isReadyForRestore(IPeopleManager peopleManager,
            PackageManager packageManager, PeopleTileKey key) {
        if (DEBUG) Log.d(TAG, "Checking if we should schedule a follow up job : " + key);
        if (!PeopleTileKey.isValid(key)) {
            if (DEBUG) Log.d(TAG, "Key is invalid, should not follow up.");
            return true;
        }

        try {
            PackageInfo info = packageManager.getPackageInfoAsUser(
                    key.getPackageName(), 0, key.getUserId());
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) Log.d(TAG, "Package is not installed, should follow up.");
            return false;
        }

        try {
            boolean isConversation = peopleManager.isConversation(
                    key.getPackageName(), key.getUserId(), key.getShortcutId());
            if (DEBUG) {
                Log.d(TAG, "Checked if shortcut exists, should follow up: " + !isConversation);
            }
            return isConversation;
        } catch (Exception e) {
            if (DEBUG) Log.d(TAG, "Error checking if backed up info is a shortcut.");
            return false;
        }
    }

    /** Parses default file {@code entry} to determine the entry's type.*/
    public static SharedFileEntryType getEntryType(Map.Entry<String, ?> entry) {
        String key = entry.getKey();
        if (key == null) {
            return SharedFileEntryType.UNKNOWN;
        }

        try {
            int id = Integer.parseInt(key);
            try {
                String contactUri = (String) entry.getValue();
            } catch (Exception e) {
                Log.w(TAG, "Malformed value, skipping:" + entry.getValue());
                return SharedFileEntryType.UNKNOWN;
            }
            return SharedFileEntryType.WIDGET_ID;
        } catch (NumberFormatException ignored) { }

        try {
            Set<String> widgetIds = (Set<String>) entry.getValue();
        } catch (Exception e) {
            Log.w(TAG, "Malformed value, skipping:" + entry.getValue());
            return SharedFileEntryType.UNKNOWN;
        }

        PeopleTileKey peopleTileKey = PeopleTileKey.fromString(key);
        if (peopleTileKey != null) {
            return SharedFileEntryType.PEOPLE_TILE_KEY;
        }

        try {
            Uri uri = Uri.parse(key);
            return SharedFileEntryType.CONTACT_URI;
        } catch (Exception e) {
            return SharedFileEntryType.UNKNOWN;
        }
    }

    /** Sends a broadcast to update the existing Conversation widgets. */
    public static void updateWidgets(Context context) {
        int[] widgetIds = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, PeopleSpaceWidgetProvider.class));
        if (DEBUG) {
            for (int id : widgetIds) {
                Log.d(TAG, "Calling update to widget: " + id);
            }
        }
        if (widgetIds != null && widgetIds.length != 0) {
            Intent intent = new Intent(context, PeopleSpaceWidgetProvider.class);
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds);
            context.sendBroadcast(intent);
        }
    }
}
