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

package com.android.systemui.people.widget;

import static android.Manifest.permission.READ_CONTACTS;
import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.Notification.EXTRA_PEOPLE_LIST;

import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.augmentTileFromNotification;
import static com.android.systemui.people.PeopleSpaceUtils.getMessagingStyleMessages;
import static com.android.systemui.people.PeopleSpaceUtils.getStoredWidgetIds;
import static com.android.systemui.people.PeopleSpaceUtils.updateAppWidgetOptionsAndView;
import static com.android.systemui.people.PeopleSpaceUtils.updateAppWidgetViews;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.notification.NotificationEntryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Manager for People Space widget. */
@Singleton
public class PeopleSpaceWidgetManager {
    private static final String TAG = "PeopleSpaceWidgetMgr";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private final Object mLock = new Object();
    private final Context mContext;
    private LauncherApps mLauncherApps;
    private AppWidgetManager mAppWidgetManager;
    private IPeopleManager mIPeopleManager;
    private SharedPreferences mSharedPrefs;
    private PeopleManager mPeopleManager;
    private NotificationEntryManager mNotificationEntryManager;
    private PackageManager mPackageManager;
    public UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    @GuardedBy("mLock")
    public static Map<PeopleTileKey, PeopleSpaceWidgetProvider.TileConversationListener>
            mListeners = new HashMap<>();

    @GuardedBy("mLock")
    // Map of notification key mapped to widget IDs previously updated by the contact Uri field.
    // This is required because on notification removal, the contact Uri field is stripped and we
    // only have the notification key to determine which widget IDs should be updated.
    private Map<String, Set<String>> mNotificationKeyToWidgetIdsMatchedByUri = new HashMap<>();
    private boolean mIsForTesting;

    @Inject
    public PeopleSpaceWidgetManager(Context context) {
        if (DEBUG) Log.d(TAG, "constructor");
        mContext = context;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
        mIPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mPeopleManager = mContext.getSystemService(PeopleManager.class);
        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mPackageManager = mContext.getPackageManager();
    }

    /**
     * AppWidgetManager setter used for testing.
     */
    @VisibleForTesting
    protected void setAppWidgetManager(
            AppWidgetManager appWidgetManager, IPeopleManager iPeopleManager,
            PeopleManager peopleManager, LauncherApps launcherApps,
            NotificationEntryManager notificationEntryManager, PackageManager packageManager,
            boolean isForTesting) {
        mAppWidgetManager = appWidgetManager;
        mIPeopleManager = iPeopleManager;
        mPeopleManager = peopleManager;
        mLauncherApps = launcherApps;
        mNotificationEntryManager = notificationEntryManager;
        mPackageManager = packageManager;
        mIsForTesting = isForTesting;
    }

    /**
     * Updates People Space widgets.
     */
    public void updateWidgets(int[] widgetIds) {
        try {
            if (DEBUG) Log.d(TAG, "updateWidgets called");
            if (widgetIds.length == 0) {
                if (DEBUG) Log.d(TAG, "no widgets to update");
                return;
            }

            if (DEBUG) Log.d(TAG, "updating " + widgetIds.length + " widgets");
            synchronized (mLock) {
                updateSingleConversationWidgets(widgetIds);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /**
     * Updates {@code appWidgetIds} with their associated conversation stored, handling a
     * notification being posted or removed.
     */
    public void updateSingleConversationWidgets(int[] appWidgetIds) {
        Map<Integer, PeopleSpaceTile> widgetIdToTile = new HashMap<>();
        for (int appWidgetId : appWidgetIds) {
            PeopleSpaceTile tile = getTileForExistingWidget(appWidgetId);
            if (tile == null) {
                if (DEBUG) Log.d(TAG, "Matching conversation not found for shortcut ID");
                //TODO: Delete app widget id when crash is fixed (b/172932636)
                continue;
            }
            Bundle options = mAppWidgetManager.getAppWidgetOptions(appWidgetId);
            updateAppWidgetViews(mAppWidgetManager, mContext, appWidgetId, tile, options);
            widgetIdToTile.put(appWidgetId, tile);
        }
        PeopleSpaceUtils.getBirthdaysOnBackgroundThread(
                mContext, mAppWidgetManager, widgetIdToTile, appWidgetIds);
    }

    /**
     * Returns a {@link PeopleSpaceTile} based on the {@code appWidgetId}.
     * Widget already exists, so fetch {@link PeopleTileKey} from {@link SharedPreferences}.
     */
    @Nullable
    public PeopleSpaceTile getTileForExistingWidget(int appWidgetId) {
        // First, check if tile is cached in AppWidgetOptions.
        PeopleSpaceTile tile = AppWidgetOptionsHelper.getPeopleTile(mAppWidgetManager, appWidgetId);
        if (tile != null) {
            if (DEBUG) Log.d(TAG, "People Tile is cached for widget: " + appWidgetId);
            return tile;
        }

        // If tile is null, we need to retrieve from persistent storage.
        if (DEBUG) Log.d(TAG, "Fetching key from sharedPreferences: " + appWidgetId);
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(appWidgetId),
                Context.MODE_PRIVATE);
        PeopleTileKey key = new PeopleTileKey(
                widgetSp.getString(SHORTCUT_ID, EMPTY_STRING),
                widgetSp.getInt(USER_ID, INVALID_USER_ID),
                widgetSp.getString(PACKAGE_NAME, EMPTY_STRING));

        return getTileFromPersistentStorage(key);
    }

    /**
     * Returns a {@link PeopleSpaceTile} based on the {@code appWidgetId}.
     * If a {@link PeopleTileKey} is not provided, fetch one from {@link SharedPreferences}.
     */
    @Nullable
    public PeopleSpaceTile getTileFromPersistentStorage(PeopleTileKey key) {
        if (!key.isValid()) {
            Log.e(TAG, "PeopleTileKey invalid: " + key);
            return null;
        }

        if (mIPeopleManager == null || mLauncherApps == null) {
            Log.d(TAG, "System services are null");
            return null;
        }

        try {
            if (DEBUG) Log.d(TAG, "Retrieving Tile from storage: " + key.toString());
            ConversationChannel channel = mIPeopleManager.getConversation(
                    key.getPackageName(), key.getUserId(), key.getShortcutId());
            if (channel == null) {
                Log.d(TAG, "Could not retrieve conversation from storage");
                return null;
            }

            return new PeopleSpaceTile.Builder(channel, mLauncherApps).build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve conversation for tile: " + e);
            return null;
        }
    }

    /**
     * Check if any existing People tiles match the incoming notification change, and store the
     * change in the tile if so.
     */
    public void updateWidgetsWithNotificationChanged(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction notificationAction) {
        if (DEBUG) Log.d(TAG, "updateWidgetsWithNotificationChanged called");
        if (mIsForTesting) {
            updateWidgetsWithNotificationChangedInBackground(sbn, notificationAction);
            return;
        }
        ThreadUtils.postOnBackgroundThread(
                () -> updateWidgetsWithNotificationChangedInBackground(sbn, notificationAction));
    }

    private void updateWidgetsWithNotificationChangedInBackground(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction action) {
        try {
            String sbnShortcutId = sbn.getShortcutId();
            if (sbnShortcutId == null) {
                if (DEBUG) Log.d(TAG, "Sbn shortcut id is null");
                return;
            }
            int[] widgetIds = mAppWidgetManager.getAppWidgetIds(
                    new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
            );
            if (widgetIds.length == 0) {
                Log.d(TAG, "No app widget ids returned");
                return;
            }
            PeopleTileKey key = new PeopleTileKey(
                    sbnShortcutId,
                    sbn.getUser().getIdentifier(),
                    sbn.getPackageName());
            if (!key.isValid()) {
                Log.d(TAG, "Invalid key");
                return;
            }
            synchronized (mLock) {
                // First, update People Tiles associated with the Notification's package/shortcut.
                Set<String> tilesUpdatedByKey = getStoredWidgetIds(mSharedPrefs, key);
                updateWidgetIdsForNotificationAction(tilesUpdatedByKey, sbn, action);

                // Then, update People Tiles across other packages that use the same Uri.
                updateTilesByUri(key, sbn, action);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /** Updates {@code widgetIdsToUpdate} with {@code action}. */
    private void updateWidgetIdsForNotificationAction(Set<String> widgetIdsToUpdate,
            StatusBarNotification sbn, PeopleSpaceUtils.NotificationAction action) {
        for (String widgetIdString : widgetIdsToUpdate) {
            int widgetId = Integer.parseInt(widgetIdString);
            PeopleSpaceTile storedTile = getTileForExistingWidget(widgetId);
            if (storedTile == null) {
                if (DEBUG) Log.d(TAG, "Could not find stored tile for notification");
                continue;
            }
            if (DEBUG) Log.d(TAG, "Storing notification change, key:" + sbn.getKey());
            updateStorageAndViewWithNotificationData(sbn, action, widgetId,
                    storedTile);
        }
    }

    /**
     * Updates tiles with matched Uris, dependent on the {@code action}.
     *
     * <p>If the notification was added, adds the notification based on the contact Uri within
     * {@code sbn}.
     * <p>If the notification was removed, removes the notification based on the in-memory map of
     * widgets previously updated by Uri (since the contact Uri is stripped from the {@code sbn}).
     */
    private void updateTilesByUri(PeopleTileKey key, StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction action) {
        if (action.equals(PeopleSpaceUtils.NotificationAction.POSTED)) {
            Set<String> widgetIdsUpdatedByUri = supplementTilesByUri(sbn, action, key);
            if (widgetIdsUpdatedByUri != null && !widgetIdsUpdatedByUri.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Added due to uri: " + widgetIdsUpdatedByUri);
                mNotificationKeyToWidgetIdsMatchedByUri.put(sbn.getKey(), widgetIdsUpdatedByUri);
            }
        } else {
            // Remove the notification on any widgets where the notification was added
            // purely based on the Uri.
            Set<String> widgetsPreviouslyUpdatedByUri =
                    mNotificationKeyToWidgetIdsMatchedByUri.remove(sbn.getKey());
            if (widgetsPreviouslyUpdatedByUri != null && !widgetsPreviouslyUpdatedByUri.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Remove due to uri: " + widgetsPreviouslyUpdatedByUri);
                updateWidgetIdsForNotificationAction(widgetsPreviouslyUpdatedByUri, sbn,
                        action);
            }
        }
    }

    /**
     * Retrieves from storage any tiles with the same contact Uri as linked via the {@code sbn}.
     * Supplements the tiles with the notification content only if they still have {@link
     * android.Manifest.permission.READ_CONTACTS} permission.
     */
    @Nullable
    private Set<String> supplementTilesByUri(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction notificationAction, PeopleTileKey key) {
        if (!shouldMatchNotificationByUri(sbn)) {
            if (DEBUG) Log.d(TAG, "Should not supplement conversation");
            return null;
        }

        // Try to get the Contact Uri from the Missed Call notification directly.
        String contactUri = getContactUri(sbn);
        if (contactUri == null) {
            if (DEBUG) Log.d(TAG, "No contact uri");
            return null;
        }

        // Supplement any tiles with the same Uri.
        Set<String> storedWidgetIdsByUri =
                new HashSet<>(mSharedPrefs.getStringSet(contactUri, new HashSet<>()));
        if (storedWidgetIdsByUri.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No tiles for contact");
            return null;
        }

        if (mPackageManager.checkPermission(READ_CONTACTS,
                sbn.getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            if (DEBUG) Log.d(TAG, "Notifying app missing permissions");
            return null;
        }

        Set<String> widgetIdsUpdatedByUri = new HashSet<>();
        for (String widgetIdString : storedWidgetIdsByUri) {
            int widgetId = Integer.parseInt(widgetIdString);
            PeopleSpaceTile storedTile = getTileForExistingWidget(widgetId);
            // Don't update a widget already updated.
            if (key.equals(new PeopleTileKey(storedTile))) {
                continue;
            }
            if (storedTile == null || mPackageManager.checkPermission(READ_CONTACTS,
                    storedTile.getPackageName()) != PackageManager.PERMISSION_GRANTED) {
                if (DEBUG) Log.d(TAG, "Cannot supplement tile: " + storedTile.getUserName());
                continue;
            }
            if (DEBUG) Log.d(TAG, "Adding notification by uri: " + sbn.getKey());
            updateStorageAndViewWithNotificationData(sbn, notificationAction,
                    widgetId, storedTile);
            widgetIdsUpdatedByUri.add(String.valueOf(widgetId));
        }
        return widgetIdsUpdatedByUri;
    }

    /**
     * Try to retrieve a valid Uri via {@code sbn}, falling back to the {@code
     * contactUriFromShortcut} if valid.
     */
    @Nullable
    private String getContactUri(StatusBarNotification sbn) {
        // First, try to get a Uri from the Person directly set on the Notification.
        ArrayList<Person> people = sbn.getNotification().extras.getParcelableArrayList(
                EXTRA_PEOPLE_LIST);
        if (people != null && people.get(0) != null) {
            String contactUri = people.get(0).getUri();
            if (contactUri != null && !contactUri.isEmpty()) {
                return contactUri;
            }
        }

        // Then, try to get a Uri from the Person set on the Notification message.
        List<Notification.MessagingStyle.Message> messages =
                getMessagingStyleMessages(sbn.getNotification());
        if (messages != null && !messages.isEmpty()) {
            Notification.MessagingStyle.Message message = messages.get(0);
            Person sender = message.getSenderPerson();
            if (sender != null && sender.getUri() != null && !sender.getUri().isEmpty()) {
                return sender.getUri();
            }
        }

        return null;
    }

    /**
     * Returns whether a notification should be matched to other Tiles by Uri.
     *
     * <p>Currently only matches missed calls.
     */
    private boolean shouldMatchNotificationByUri(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            if (DEBUG) Log.d(TAG, "Notification is null");
            return false;
        }
        if (!Objects.equals(notification.category, CATEGORY_MISSED_CALL)) {
            if (DEBUG) Log.d(TAG, "Not missed call");
            return false;
        }
        return true;
    }

    /**
     * Update the tiles associated with the incoming conversation update.
     */
    public void updateWidgetsWithConversationChanged(ConversationChannel conversation) {
        ShortcutInfo info = conversation.getShortcutInfo();
        synchronized (mLock) {
            PeopleTileKey key = new PeopleTileKey(
                    info.getId(), info.getUserId(), info.getPackage());
            Set<String> storedWidgetIds = getStoredWidgetIds(mSharedPrefs, key);
            for (String widgetIdString : storedWidgetIds) {
                if (DEBUG) {
                    Log.d(TAG,
                            "Conversation update for widget " + widgetIdString + " , "
                                    + info.getLabel());
                }
                updateStorageAndViewWithConversationData(conversation,
                        Integer.valueOf(widgetIdString));
            }
        }
    }

    /**
     * Update {@code appWidgetId} with the new data provided by {@code conversation}.
     */
    private void updateStorageAndViewWithConversationData(ConversationChannel conversation,
            int appWidgetId) {
        PeopleSpaceTile storedTile = getTileForExistingWidget(appWidgetId);
        if (storedTile == null) {
            if (DEBUG) Log.d(TAG, "Could not find stored tile to add conversation to");
            return;
        }
        ShortcutInfo info = conversation.getShortcutInfo();
        Uri uri = null;
        if (info.getPersons() != null && info.getPersons().length > 0) {
            Person person = info.getPersons()[0];
            uri = person.getUri() == null ? null : Uri.parse(person.getUri());
        }
        storedTile = storedTile.toBuilder()
                .setUserName(info.getLabel())
                .setUserIcon(
                        PeopleSpaceTile.convertDrawableToIcon(mLauncherApps.getShortcutIconDrawable(
                                info, 0)))
                .setContactUri(uri)
                .setStatuses(conversation.getStatuses())
                .setLastInteractionTimestamp(conversation.getLastEventTimestamp())
                .setIsImportantConversation(conversation.getParentNotificationChannel() != null
                        && conversation.getParentNotificationChannel().isImportantConversation())
                .build();
        updateAppWidgetOptionsAndView(mAppWidgetManager, mContext, appWidgetId, storedTile);
    }

    /**
     * Update {@code appWidgetId} with the new data provided by {@code sbn}.
     */
    private void updateStorageAndViewWithNotificationData(
            StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction notificationAction,
            int appWidgetId, PeopleSpaceTile storedTile) {
        if (notificationAction == PeopleSpaceUtils.NotificationAction.POSTED) {
            if (DEBUG) Log.i(TAG, "Adding notification to storage, appWidgetId: " + appWidgetId);
            storedTile = augmentTileFromNotification(mContext, storedTile, sbn);
        } else if (storedTile.getNotificationKey().equals(sbn.getKey())) {
            if (DEBUG) {
                Log.i(TAG, "Removing notification from storage, appWidgetId: " + appWidgetId);
            }
            storedTile = storedTile
                    .toBuilder()
                    // Reset notification content.
                    .setNotificationKey(null)
                    .setNotificationContent(null)
                    .setNotificationDataUri(null)
                    .setMessagesCount(0)
                    // Reset missed calls category.
                    .setNotificationCategory(null)
                    .build();
        }
        updateAppWidgetOptionsAndView(mAppWidgetManager, mContext, appWidgetId, storedTile);
    }

    /**
     * Attaches the manager to the pipeline, making it ready to receive events. Should only be
     * called once.
     */
    public void attach(NotificationListener listenerService) {
        if (DEBUG) Log.d(TAG, "attach");
        listenerService.addNotificationHandler(mListener);
    }

    private final NotificationHandler mListener = new NotificationHandler() {
        @Override
        public void onNotificationPosted(
                StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
            updateWidgetsWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.POSTED);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap
        ) {
            updateWidgetsWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.REMOVED);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap,
                int reason) {
            updateWidgetsWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.REMOVED);
        }

        @Override
        public void onNotificationRankingUpdate(
                NotificationListenerService.RankingMap rankingMap) {
        }

        @Override
        public void onNotificationsInitialized() {
            if (DEBUG) Log.d(TAG, "onNotificationsInitialized");
        }

        @Override
        public void onNotificationChannelModified(
                String pkgName,
                UserHandle user,
                NotificationChannel channel,
                int modificationType) {
            if (channel.isConversation()) {
                updateWidgets(mAppWidgetManager.getAppWidgetIds(
                        new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
                ));
            }
        }
    };

    /**
     * Checks if this widget has been added externally, and this the first time we are learning
     * about the widget. If so, the widget adder should have populated options with PeopleTileKey
     * arguments.
     */
    public void onAppWidgetOptionsChanged(int appWidgetId, Bundle newOptions) {
        // Check if this widget has been added externally, and this the first time we are
        // learning about the widget. If so, the widget adder should have populated options with
        // PeopleTileKey arguments.
        if (DEBUG) Log.d(TAG, "onAppWidgetOptionsChanged called for widget: " + appWidgetId);
        PeopleTileKey optionsKey = AppWidgetOptionsHelper.getPeopleTileKeyFromBundle(newOptions);
        if (optionsKey.isValid()) {
            if (DEBUG) {
                Log.d(TAG, "PeopleTileKey was present in Options, shortcutId: "
                        + optionsKey.getShortcutId());
            }
            AppWidgetOptionsHelper.removePeopleTileKey(mAppWidgetManager, appWidgetId);
            addNewWidget(appWidgetId, optionsKey);
        }
        // Update views for new widget dimensions.
        updateWidgets(new int[]{appWidgetId});
    }

    /** Adds a widget based on {@code key} mapped to {@code appWidgetId}. */
    public void addNewWidget(int appWidgetId, PeopleTileKey key) {
        if (DEBUG) Log.d(TAG, "addNewWidget called with key for appWidgetId: " + appWidgetId);
        PeopleSpaceTile tile = getTileFromPersistentStorage(key);
        tile = PeopleSpaceUtils.augmentSingleTileFromVisibleNotifications(
                mContext, tile, mNotificationEntryManager);
        if (tile != null) {
            addNewWidget(appWidgetId, tile);
        }
    }

    /**
     * Adds a widget based on {@code tile} mapped to {@code appWidgetId}.
     * The tile provided should already be augmented.
     */
    public void addNewWidget(int appWidgetId, PeopleSpaceTile tile) {
        if (DEBUG) Log.d(TAG, "addNewWidget called for appWidgetId: " + appWidgetId);
        if (tile == null) {
            return;
        }

        mUiEventLogger.log(PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_ADDED);
        synchronized (mLock) {
            if (DEBUG) Log.d(TAG, "Add storage for : " + tile.getId());
            PeopleTileKey key = new PeopleTileKey(tile);
            PeopleSpaceUtils.setSharedPreferencesStorageForTile(mContext, key, appWidgetId,
                    tile.getContactUri());
        }
        try {
            if (DEBUG) Log.d(TAG, "Caching shortcut for PeopleTile: " + tile.getId());
            mLauncherApps.cacheShortcuts(tile.getPackageName(),
                    Collections.singletonList(tile.getId()),
                    tile.getUserHandle(), LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS);
        } catch (Exception e) {
            Log.w(TAG, "Exception caching shortcut:" + e);
        }

        PeopleSpaceUtils.updateAppWidgetOptionsAndView(
                mAppWidgetManager, mContext, appWidgetId, tile);
        PeopleSpaceWidgetProvider provider = new PeopleSpaceWidgetProvider();
        provider.onUpdate(mContext, mAppWidgetManager, new int[]{appWidgetId});
    }

    /** Registers a conversation listener for {@code appWidgetId} if not already registered. */
    public void registerConversationListenerIfNeeded(int widgetId,
            PeopleSpaceWidgetProvider.TileConversationListener newListener) {
        // Retrieve storage needed for registration.
        PeopleTileKey key;
        synchronized (mLock) {
            SharedPreferences widgetSp = mContext.getSharedPreferences(String.valueOf(widgetId),
                    Context.MODE_PRIVATE);
            key = new PeopleTileKey(
                    widgetSp.getString(SHORTCUT_ID, EMPTY_STRING),
                    widgetSp.getInt(USER_ID, INVALID_USER_ID),
                    widgetSp.getString(PACKAGE_NAME, EMPTY_STRING));
            if (!key.isValid()) {
                if (DEBUG) Log.w(TAG, "Could not register listener for widget: " + widgetId);
                return;
            }
        }
        synchronized (mListeners) {
            if (mListeners.containsKey(key)) {
                if (DEBUG) Log.d(TAG, "Already registered listener");
                return;
            }
            if (DEBUG) Log.d(TAG, "Register listener for " + widgetId + " with " + key);
            mListeners.put(key, newListener);
        }
        mPeopleManager.registerConversationListener(key.getPackageName(),
                key.getUserId(),
                key.getShortcutId(), newListener,
                mContext.getMainExecutor());
    }

    /** Deletes all storage, listeners, and caching for {@code appWidgetIds}. */
    public void deleteWidgets(int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            if (DEBUG) Log.d(TAG, "Widget removed: " + widgetId);
            mUiEventLogger.log(PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_DELETED);
            // Retrieve storage needed for widget deletion.
            PeopleTileKey key;
            Set<String> storedWidgetIdsForKey;
            String contactUriString;
            synchronized (mLock) {
                SharedPreferences widgetSp = mContext.getSharedPreferences(String.valueOf(widgetId),
                        Context.MODE_PRIVATE);
                key = new PeopleTileKey(
                        widgetSp.getString(SHORTCUT_ID, null),
                        widgetSp.getInt(USER_ID, INVALID_USER_ID),
                        widgetSp.getString(PACKAGE_NAME, null));
                if (!key.isValid()) {
                    if (DEBUG) Log.e(TAG, "Could not delete " + widgetId);
                    return;
                }
                storedWidgetIdsForKey = new HashSet<>(
                        mSharedPrefs.getStringSet(key.toString(), new HashSet<>()));
                contactUriString = mSharedPrefs.getString(String.valueOf(widgetId), null);
            }
            synchronized (mLock) {
                PeopleSpaceUtils.removeSharedPreferencesStorageForTile(mContext, key, widgetId,
                        contactUriString);
            }
            // If another tile with the conversation is still stored, we need to keep the listener.
            if (DEBUG) Log.d(TAG, "Stored widget IDs: " + storedWidgetIdsForKey.toString());
            if (storedWidgetIdsForKey.contains(String.valueOf(widgetId))
                    && storedWidgetIdsForKey.size() == 1) {
                if (DEBUG) Log.d(TAG, "Remove caching and listener");
                unregisterConversationListener(key, widgetId);
                uncacheConversationShortcut(key);
            }
        }
    }

    /** Unregisters the conversation listener for {@code appWidgetId}. */
    private void unregisterConversationListener(PeopleTileKey key, int appWidgetId) {
        PeopleSpaceWidgetProvider.TileConversationListener registeredListener;
        synchronized (mListeners) {
            registeredListener = mListeners.get(key);
            if (registeredListener == null) {
                if (DEBUG) Log.d(TAG, "Cannot find listener to unregister");
                return;
            }
            if (DEBUG) Log.d(TAG, "Unregister listener for " + appWidgetId + " with " + key);
            mListeners.remove(key);
        }
        mPeopleManager.unregisterConversationListener(registeredListener);
    }

    /** Uncaches the conversation shortcut. */
    private void uncacheConversationShortcut(PeopleTileKey key) {
        try {
            if (DEBUG) Log.d(TAG, "Uncaching shortcut for PeopleTile: " + key.getShortcutId());
            mLauncherApps.uncacheShortcuts(key.getPackageName(),
                    Collections.singletonList(key.getShortcutId()),
                    UserHandle.of(key.getUserId()),
                    LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS);
        } catch (Exception e) {
            Log.d(TAG, "Exception uncaching shortcut:" + e);
        }
    }

    /**
     * Builds a request to pin a People Tile app widget, with a preview and storing necessary
     * information as the callback.
     */
    public boolean requestPinAppWidget(ShortcutInfo shortcutInfo) {
        if (DEBUG) Log.d(TAG, "Requesting pin widget, shortcutId: " + shortcutInfo.getId());

        RemoteViews widgetPreview = PeopleSpaceUtils.getPreview(mContext, mIPeopleManager,
                mLauncherApps, mNotificationEntryManager, shortcutInfo.getId(),
                shortcutInfo.getUserHandle(), shortcutInfo.getPackage());
        if (widgetPreview == null) {
            Log.w(TAG, "Skipping pinning widget: no tile for shortcutId: " + shortcutInfo.getId());
            return false;
        }
        Bundle extras = new Bundle();
        extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, widgetPreview);

        PendingIntent successCallback =
                PeopleSpaceWidgetPinnedReceiver.getPendingIntent(mContext, shortcutInfo);

        ComponentName componentName = new ComponentName(mContext, PeopleSpaceWidgetProvider.class);
        return mAppWidgetManager.requestPinAppWidget(componentName, extras, successCallback);
    }
}
