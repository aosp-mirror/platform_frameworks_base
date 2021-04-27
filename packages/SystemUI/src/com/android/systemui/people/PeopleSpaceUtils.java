/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.people;

import static com.android.systemui.people.NotificationHelper.getContactUri;
import static com.android.systemui.people.NotificationHelper.getMessagingStyleMessages;
import static com.android.systemui.people.NotificationHelper.getSenderIfGroupConversation;
import static com.android.systemui.people.NotificationHelper.hasReadContactsPermission;
import static com.android.systemui.people.NotificationHelper.isMissedCall;
import static com.android.systemui.people.NotificationHelper.shouldMatchNotificationByUri;

import android.app.Notification;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.database.Cursor;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ArrayUtils;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.people.widget.AppWidgetOptionsHelper;
import com.android.systemui.people.widget.PeopleTileKey;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utils class for People Space. */
public class PeopleSpaceUtils {
    /** Turns on debugging information about People Space. */
    public static final boolean DEBUG = true;
    private static final String TAG = "PeopleSpaceUtils";
    public static final String PACKAGE_NAME = "package_name";
    public static final String USER_ID = "user_id";
    public static final String SHORTCUT_ID = "shortcut_id";

    public static final String EMPTY_STRING = "";
    public static final int INVALID_USER_ID = -1;

    public static final PeopleTileKey EMPTY_KEY =
            new PeopleTileKey(EMPTY_STRING, INVALID_USER_ID, EMPTY_STRING);

    /** Represents whether {@link StatusBarNotification} was posted or removed. */
    public enum NotificationAction {
        POSTED,
        REMOVED
    }

    /**
     * The UiEvent enums that this class can log.
     */
    public enum PeopleSpaceWidgetEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "People space widget deleted")
        PEOPLE_SPACE_WIDGET_DELETED(666),
        @UiEvent(doc = "People space widget added")
        PEOPLE_SPACE_WIDGET_ADDED(667),
        @UiEvent(doc = "People space widget clicked to launch conversation")
        PEOPLE_SPACE_WIDGET_CLICKED(668);

        private final int mId;

        PeopleSpaceWidgetEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /** Returns stored widgets for the conversation specified. */
    public static Set<String> getStoredWidgetIds(SharedPreferences sp, PeopleTileKey key) {
        if (!key.isValid()) {
            return new HashSet<>();
        }
        return new HashSet<>(sp.getStringSet(key.toString(), new HashSet<>()));
    }

    /** Sets all relevant storage for {@code appWidgetId} association to {@code tile}. */
    public static void setSharedPreferencesStorageForTile(Context context, PeopleTileKey key,
            int appWidgetId, Uri contactUri) {
        // Write relevant persisted storage.
        SharedPreferences widgetSp = context.getSharedPreferences(String.valueOf(appWidgetId),
                Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetSp.edit();
        widgetEditor.putString(PeopleSpaceUtils.PACKAGE_NAME, key.getPackageName());
        widgetEditor.putString(PeopleSpaceUtils.SHORTCUT_ID, key.getShortcutId());
        widgetEditor.putInt(PeopleSpaceUtils.USER_ID, key.getUserId());
        widgetEditor.apply();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sp.edit();
        String contactUriString = contactUri == null ? EMPTY_STRING : contactUri.toString();
        editor.putString(String.valueOf(appWidgetId), contactUriString);

        // Don't overwrite existing widgets with the same key.
        addAppWidgetIdForKey(sp, editor, appWidgetId, key.toString());
        addAppWidgetIdForKey(sp, editor, appWidgetId, contactUriString);
        editor.apply();
    }

    /** Removes stored data when tile is deleted. */
    public static void removeSharedPreferencesStorageForTile(Context context, PeopleTileKey key,
            int widgetId, String contactUriString) {
        // Delete widgetId mapping to key.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sp.edit();
        editor.remove(String.valueOf(widgetId));
        removeAppWidgetIdForKey(sp, editor, widgetId, key.toString());
        removeAppWidgetIdForKey(sp, editor, widgetId, contactUriString);
        editor.apply();

        // Delete all data specifically mapped to widgetId.
        SharedPreferences widgetSp = context.getSharedPreferences(String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetSp.edit();
        widgetEditor.remove(PACKAGE_NAME);
        widgetEditor.remove(USER_ID);
        widgetEditor.remove(SHORTCUT_ID);
        widgetEditor.apply();
    }

    private static void addAppWidgetIdForKey(SharedPreferences sp, SharedPreferences.Editor editor,
            int widgetId, String storageKey) {
        Set<String> storedWidgetIdsByKey = new HashSet<>(
                sp.getStringSet(storageKey, new HashSet<>()));
        storedWidgetIdsByKey.add(String.valueOf(widgetId));
        editor.putStringSet(storageKey, storedWidgetIdsByKey);
    }

    private static void removeAppWidgetIdForKey(SharedPreferences sp,
            SharedPreferences.Editor editor,
            int widgetId, String storageKey) {
        Set<String> storedWidgetIds = new HashSet<>(
                sp.getStringSet(storageKey, new HashSet<>()));
        storedWidgetIds.remove(String.valueOf(widgetId));
        editor.putStringSet(storageKey, storedWidgetIds);
    }

    /** Returns notifications that match provided {@code contactUri}. */
    public static List<NotificationEntry> getNotificationsByUri(
            PackageManager packageManager, String contactUri,
            Map<PeopleTileKey, Set<NotificationEntry>> notifications) {
        if (DEBUG) Log.d(TAG, "Getting notifications by contact URI.");
        if (TextUtils.isEmpty(contactUri)) {
            return new ArrayList<>();
        }
        return notifications.entrySet().stream().flatMap(e -> e.getValue().stream())
                .filter(e ->
                        hasReadContactsPermission(packageManager, e.getSbn())
                                && shouldMatchNotificationByUri(e.getSbn())
                                && Objects.equals(contactUri, getContactUri(e.getSbn()))
                )
                .collect(Collectors.toList());
    }

    /** Returns the total messages in {@code notificationEntries}.*/
    public static int getMessagesCount(Set<NotificationEntry> notificationEntries) {
        if (DEBUG) {
            Log.d(TAG, "Calculating messages count from " + notificationEntries.size()
                    + " notifications.");
        }
        int messagesCount = 0;
        for (NotificationEntry entry : notificationEntries) {
            Notification notification = entry.getSbn().getNotification();
            // Should not count messages from missed call notifications.
            if (isMissedCall(notification)) {
                continue;
            }

            List<Notification.MessagingStyle.Message> messages =
                    getMessagingStyleMessages(notification);
            if (messages != null) {
                messagesCount += messages.size();
            }
        }
        return messagesCount;
    }

    /** Removes all notification related fields from {@code tile}. */
    public static PeopleSpaceTile removeNotificationFields(PeopleSpaceTile tile) {
        if (DEBUG) {
            Log.i(TAG, "Removing any notification stored for tile Id: " + tile.getId());
        }
        return tile
                .toBuilder()
                // Reset notification content.
                .setNotificationKey(null)
                .setNotificationContent(null)
                .setNotificationSender(null)
                .setNotificationDataUri(null)
                .setMessagesCount(0)
                // Reset missed calls category.
                .setNotificationCategory(null)
                .build();
    }

    /**
     * Augments {@code tile} with the notification content from {@code notificationEntry} and
     * {@code messagesCount}.
     */
    public static PeopleSpaceTile augmentTileFromNotification(Context context, PeopleSpaceTile tile,
            PeopleTileKey key, NotificationEntry notificationEntry, int messagesCount) {
        if (notificationEntry == null || notificationEntry.getSbn().getNotification() == null) {
            if (DEBUG) Log.d(TAG, "Tile key: " + key.toString() + ". Notification is null");
            return removeNotificationFields(tile);
        }
        Notification notification = notificationEntry.getSbn().getNotification();
        boolean isMissedCall = isMissedCall(notification);
        List<Notification.MessagingStyle.Message> messages =
                getMessagingStyleMessages(notification);

        if (!isMissedCall && ArrayUtils.isEmpty(messages)) {
            if (DEBUG) Log.d(TAG, "Tile key: " + key.toString() + ". Notification has no content");
            return removeNotificationFields(tile);
        }

        // messages are in chronological order from most recent to least.
        Notification.MessagingStyle.Message message = messages != null ? messages.get(0) : null;
        // If it's a missed call notification and it doesn't include content, use fallback value,
        // otherwise, use notification content.
        boolean hasMessageText = message != null && !TextUtils.isEmpty(message.getText());
        CharSequence content = (isMissedCall && !hasMessageText)
                ? context.getString(R.string.missed_call) : message.getText();
        Uri dataUri = message != null ? message.getDataUri() : null;
        if (DEBUG) {
            Log.d(TAG, "Tile key: " + key.toString() + ". Notification message has text: "
                    + hasMessageText);
        }
        CharSequence sender = getSenderIfGroupConversation(notification, message);

        return tile
                .toBuilder()
                .setNotificationKey(notificationEntry.getSbn().getKey())
                .setNotificationCategory(notification.category)
                .setNotificationContent(content)
                .setNotificationSender(sender)
                .setNotificationDataUri(dataUri)
                .setMessagesCount(messagesCount)
                .build();
    }

    /** Returns a list sorted by ascending last interaction time from {@code stream}. */
    public static List<PeopleSpaceTile> getSortedTiles(IPeopleManager peopleManager,
            LauncherApps launcherApps, UserManager userManager,
            Stream<ShortcutInfo> stream) {
        return stream
                .filter(Objects::nonNull)
                .filter(c -> !userManager.isQuietModeEnabled(c.getUserHandle()))
                .map(c -> new PeopleSpaceTile.Builder(c, launcherApps).build())
                .filter(c -> shouldKeepConversation(c))
                .map(c -> c.toBuilder().setLastInteractionTimestamp(
                        getLastInteraction(peopleManager, c)).build())
                .sorted((c1, c2) -> new Long(c2.getLastInteractionTimestamp()).compareTo(
                        new Long(c1.getLastInteractionTimestamp())))
                .collect(Collectors.toList());
    }

    /** Returns {@code PeopleSpaceTile} based on provided  {@ConversationChannel}. */
    public static PeopleSpaceTile getTile(ConversationChannel channel, LauncherApps launcherApps) {
        if (channel == null) {
            Log.i(TAG, "ConversationChannel is null");
            return null;
        }
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(channel, launcherApps).build();
        if (!PeopleSpaceUtils.shouldKeepConversation(tile)) {
            Log.i(TAG, "PeopleSpaceTile is not valid");
            return null;
        }

        return tile;
    }

    /** Returns the last interaction time with the user specified by {@code PeopleSpaceTile}. */
    private static Long getLastInteraction(IPeopleManager peopleManager,
            PeopleSpaceTile tile) {
        try {
            int userId = getUserId(tile);
            String pkg = tile.getPackageName();
            return peopleManager.getLastInteraction(pkg, userId, tile.getId());
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve last interaction time", e);
            return 0L;
        }
    }

    /** Converts {@code drawable} to a {@link Bitmap}. */
    public static Bitmap convertDrawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        Bitmap bitmap;
        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * Returns whether the {@code conversation} should be kept for display in the People Space.
     *
     * <p>A valid {@code conversation} must:
     *     <ul>
     *         <li>Have a non-null {@link PeopleSpaceTile}
     *         <li>Have an associated label in the {@link PeopleSpaceTile}
     *     </ul>
     * </li>
     */
    public static boolean shouldKeepConversation(PeopleSpaceTile tile) {
        return tile != null && !TextUtils.isEmpty(tile.getUserName());
    }

    private static boolean hasBirthdayStatus(PeopleSpaceTile tile, Context context) {
        return tile.getBirthdayText() != null && tile.getBirthdayText().equals(
                context.getString(R.string.birthday_status));
    }

    /** Calls to retrieve birthdays on a background thread. */
    public static void getBirthdaysOnBackgroundThread(Context context,
            AppWidgetManager appWidgetManager,
            Map<Integer, PeopleSpaceTile> peopleSpaceTiles, int[] appWidgetIds) {
        ThreadUtils.postOnBackgroundThread(
                () -> getBirthdays(context, appWidgetManager, peopleSpaceTiles, appWidgetIds));
    }

    /** Queries the Contacts DB for any birthdays today. */
    @VisibleForTesting
    public static void getBirthdays(Context context, AppWidgetManager appWidgetManager,
            Map<Integer, PeopleSpaceTile> widgetIdToTile, int[] appWidgetIds) {
        if (DEBUG) Log.d(TAG, "Get birthdays");
        if (appWidgetIds.length == 0) return;
        List<String> lookupKeysWithBirthdaysToday = getContactLookupKeysWithBirthdaysToday(context);
        for (int appWidgetId : appWidgetIds) {
            PeopleSpaceTile storedTile = widgetIdToTile.get(appWidgetId);
            if (storedTile == null || storedTile.getContactUri() == null) {
                if (DEBUG) Log.d(TAG, "No contact uri for: " + storedTile);
                removeBirthdayStatusIfPresent(appWidgetManager, context, storedTile, appWidgetId);
                continue;
            }
            if (lookupKeysWithBirthdaysToday.isEmpty()) {
                if (DEBUG) Log.d(TAG, "No birthdays today");
                removeBirthdayStatusIfPresent(appWidgetManager, context, storedTile, appWidgetId);
                continue;
            }
            updateTileWithBirthday(context, appWidgetManager, lookupKeysWithBirthdaysToday,
                    storedTile,
                    appWidgetId);
        }
    }

    /** Removes the birthday status if present in {@code storedTile} and pushes the update. */
    private static void removeBirthdayStatusIfPresent(AppWidgetManager appWidgetManager,
            Context context, PeopleSpaceTile storedTile, int appWidgetId) {
        if (hasBirthdayStatus(storedTile, context)) {
            if (DEBUG) Log.d(TAG, "Remove " + storedTile.getUserName() + "'s birthday");
            updateAppWidgetOptionsAndView(appWidgetManager, context, appWidgetId,
                    storedTile.toBuilder()
                            .setBirthdayText(null)
                            .build());
        }
    }

    /**
     * Update {@code storedTile} if the contact has a lookup key matched to any {@code
     * lookupKeysWithBirthdays}.
     */
    private static void updateTileWithBirthday(Context context, AppWidgetManager appWidgetManager,
            List<String> lookupKeysWithBirthdaysToday, PeopleSpaceTile storedTile,
            int appWidgetId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(storedTile.getContactUri(),
                    null, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                String storedLookupKey = cursor.getString(
                        cursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY));
                if (!storedLookupKey.isEmpty() && lookupKeysWithBirthdaysToday.contains(
                        storedLookupKey)) {
                    if (DEBUG) Log.d(TAG, storedTile.getUserName() + "'s birthday today!");
                    updateAppWidgetOptionsAndView(appWidgetManager, context, appWidgetId,
                            storedTile.toBuilder()
                                    .setBirthdayText(context.getString(R.string.birthday_status))
                                    .build());
                    return;
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to query contact: " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        removeBirthdayStatusIfPresent(appWidgetManager, context, storedTile, appWidgetId);
    }

    /** Updates the current widget view with provided {@link PeopleSpaceTile}. */
    public static void updateAppWidgetViews(AppWidgetManager appWidgetManager,
            Context context, int appWidgetId, PeopleSpaceTile tile, Bundle options) {
        if (tile == null) {
            if (DEBUG) Log.d(TAG, "Widget: " + appWidgetId + ". Tile is null, skipping update");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Widget: " + appWidgetId + ", " + tile.getUserName() + ", "
                    + tile.getPackageName() + ". Updating app widget view.");
        }
        RemoteViews views = new PeopleTileViewHelper(context, tile, appWidgetId,
                options).getViews();

        // Tell the AppWidgetManager to perform an update on the current app widget.
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /** Updates tile in app widget options and the current view. */
    public static void updateAppWidgetOptionsAndView(AppWidgetManager appWidgetManager,
            Context context, int appWidgetId, PeopleSpaceTile tile) {
        if (tile == null) {
            if (DEBUG) {
                Log.w(TAG, "Widget: " + appWidgetId + "Tile is null, skipping storage and update.");
            }
            return;
        }
        Bundle options = AppWidgetOptionsHelper.setPeopleTile(appWidgetManager, appWidgetId, tile);
        updateAppWidgetViews(appWidgetManager, context, appWidgetId, tile, options);
    }

    /** Wrapper around {@link #updateAppWidgetOptionsAndView} with optional tile as a parameter. */
    public static void updateAppWidgetOptionsAndView(AppWidgetManager appWidgetManager,
            Context context, int appWidgetId, Optional<PeopleSpaceTile> optionalTile) {
        if (!optionalTile.isPresent()) {
            if (DEBUG) {
                Log.w(TAG, "Widget: " + appWidgetId
                        + "Optional tile is not present, skipping storage and update.");
            }
            return;
        }
        updateAppWidgetOptionsAndView(appWidgetManager, context, appWidgetId, optionalTile.get());
    }

    /**
     * Returns lookup keys for all contacts with a birthday today.
     *
     * <p>Birthdays are queried from a different table within the Contacts DB than the table for
     * the Contact Uri provided by most messaging apps. Matching by the contact ID is then quite
     * fragile as the row IDs across the different tables are not guaranteed to stay aligned, so we
     * match the data by {@link ContactsContract.ContactsColumns#LOOKUP_KEY} key to ensure proper
     * matching across all the Contacts DB tables.
     */
    private static List<String> getContactLookupKeysWithBirthdaysToday(Context context) {
        List<String> lookupKeysWithBirthdaysToday = new ArrayList<>(1);
        String today = new SimpleDateFormat("MM-dd").format(new Date());
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Event.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Event.START_DATE};
        String where =
                ContactsContract.Data.MIMETYPE
                        + "= ? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "="
                        + ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY + " AND substr("
                        + ContactsContract.CommonDataKinds.Event.START_DATE + ",6) = ?";
        String[] selection =
                new String[]{ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, today};
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                    projection, where, selection, null);
            while (cursor != null && cursor.moveToNext()) {
                String lookupKey = cursor.getString(
                        cursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY));
                lookupKeysWithBirthdaysToday.add(lookupKey);
            }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to query birthdays: " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return lookupKeysWithBirthdaysToday;
    }

    /** Returns the userId associated with a {@link PeopleSpaceTile} */
    public static int getUserId(PeopleSpaceTile tile) {
        return tile.getUserHandle().getIdentifier();
    }
}