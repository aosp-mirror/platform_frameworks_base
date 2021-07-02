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
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.service.notification.ZenPolicy.CONVERSATION_SENDERS_ANYONE;

import static com.android.systemui.people.NotificationHelper.getContactUri;
import static com.android.systemui.people.NotificationHelper.getHighestPriorityNotification;
import static com.android.systemui.people.NotificationHelper.shouldFilterOut;
import static com.android.systemui.people.NotificationHelper.shouldMatchNotificationByUri;
import static com.android.systemui.people.PeopleBackupFollowUpJob.SHARED_FOLLOW_UP;
import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.augmentTileFromNotification;
import static com.android.systemui.people.PeopleSpaceUtils.getMessagesCount;
import static com.android.systemui.people.PeopleSpaceUtils.getNotificationsByUri;
import static com.android.systemui.people.PeopleSpaceUtils.removeNotificationFields;
import static com.android.systemui.people.widget.PeopleBackupHelper.getEntryType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.backup.BackupManager;
import android.app.job.JobScheduler;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.people.NotificationHelper;
import com.android.systemui.people.PeopleBackupFollowUpJob;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.people.PeopleTileViewHelper;
import com.android.systemui.people.SharedPreferencesHelper;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

/** Manager for People Space widget. */
@SysUISingleton
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
    private INotificationManager mINotificationManager;
    private Optional<Bubbles> mBubblesOptional;
    private UserManager mUserManager;
    private PeopleSpaceWidgetManager mManager;
    private BackupManager mBackupManager;
    public UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    private NotificationManager mNotificationManager;
    private BroadcastDispatcher mBroadcastDispatcher;
    private Executor mBgExecutor;
    @GuardedBy("mLock")
    public static Map<PeopleTileKey, TileConversationListener>
            mListeners = new HashMap<>();

    @GuardedBy("mLock")
    // Map of notification key mapped to widget IDs previously updated by the contact Uri field.
    // This is required because on notification removal, the contact Uri field is stripped and we
    // only have the notification key to determine which widget IDs should be updated.
    private Map<String, Set<String>> mNotificationKeyToWidgetIdsMatchedByUri = new HashMap<>();
    private boolean mRegisteredReceivers;

    @GuardedBy("mLock")
    public static Map<Integer, PeopleSpaceTile> mTiles = new HashMap<>();

    @Inject
    public PeopleSpaceWidgetManager(Context context, LauncherApps launcherApps,
            NotificationEntryManager notificationEntryManager,
            PackageManager packageManager, Optional<Bubbles> bubblesOptional,
            UserManager userManager, NotificationManager notificationManager,
            BroadcastDispatcher broadcastDispatcher, @Background Executor bgExecutor) {
        if (DEBUG) Log.d(TAG, "constructor");
        mContext = context;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
        mIPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mLauncherApps = launcherApps;
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mPeopleManager = context.getSystemService(PeopleManager.class);
        mNotificationEntryManager = notificationEntryManager;
        mPackageManager = packageManager;
        mINotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mBubblesOptional = bubblesOptional;
        mUserManager = userManager;
        mBackupManager = new BackupManager(context);
        mNotificationManager = notificationManager;
        mManager = this;
        mBroadcastDispatcher = broadcastDispatcher;
        mBgExecutor = bgExecutor;
    }

    /** Initializes {@PeopleSpaceWidgetManager}. */
    public void init() {
        synchronized (mLock) {
            if (!mRegisteredReceivers) {
                if (DEBUG) Log.d(TAG, "Register receivers");
                IntentFilter filter = new IntentFilter();
                filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
                filter.addAction(ACTION_BOOT_COMPLETED);
                filter.addAction(Intent.ACTION_LOCALE_CHANGED);
                filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
                filter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
                filter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
                filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
                filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
                filter.addAction(Intent.ACTION_USER_UNLOCKED);
                mBroadcastDispatcher.registerReceiver(mBaseBroadcastReceiver, filter,

                        null /* executor */, UserHandle.ALL);
                IntentFilter perAppFilter = new IntentFilter(ACTION_PACKAGE_REMOVED);
                perAppFilter.addAction(ACTION_PACKAGE_ADDED);
                perAppFilter.addDataScheme("package");
                // BroadcastDispatcher doesn't allow data schemes.
                mContext.registerReceiver(mBaseBroadcastReceiver, perAppFilter);
                IntentFilter bootComplete = new IntentFilter(ACTION_BOOT_COMPLETED);
                bootComplete.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                // BroadcastDispatcher doesn't allow priority.
                mContext.registerReceiver(mBaseBroadcastReceiver, bootComplete);
                mRegisteredReceivers = true;
            }
        }
    }

    /** Listener for the shortcut data changes. */
    public class TileConversationListener implements PeopleManager.ConversationListener {

        @Override
        public void onConversationUpdate(@NonNull ConversationChannel conversation) {
            if (DEBUG) {
                Log.d(TAG,
                        "Received updated conversation: "
                                + conversation.getShortcutInfo().getLabel());
            }
            mBgExecutor.execute(() ->
                    updateWidgetsWithConversationChanged(conversation));
        }
    }

    /**
     * PeopleSpaceWidgetManager setter used for testing.
     */
    @VisibleForTesting
    PeopleSpaceWidgetManager(Context context,
            AppWidgetManager appWidgetManager, IPeopleManager iPeopleManager,
            PeopleManager peopleManager, LauncherApps launcherApps,
            NotificationEntryManager notificationEntryManager, PackageManager packageManager,
            Optional<Bubbles> bubblesOptional, UserManager userManager, BackupManager backupManager,
            INotificationManager iNotificationManager, NotificationManager notificationManager,
            @Background Executor executor) {
        mContext = context;
        mAppWidgetManager = appWidgetManager;
        mIPeopleManager = iPeopleManager;
        mPeopleManager = peopleManager;
        mLauncherApps = launcherApps;
        mNotificationEntryManager = notificationEntryManager;
        mPackageManager = packageManager;
        mBubblesOptional = bubblesOptional;
        mUserManager = userManager;
        mBackupManager = backupManager;
        mINotificationManager = iNotificationManager;
        mNotificationManager = notificationManager;
        mManager = this;
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        mBgExecutor = executor;
    }

    /**
     * Updates People Space widgets.
     */
    public void updateWidgets(int[] widgetIds) {
        mBgExecutor.execute(() -> updateWidgetsInBackground(widgetIds));
    }

    private void updateWidgetsInBackground(int[] widgetIds) {
        try {
            if (DEBUG) Log.d(TAG, "updateWidgets called");
            if (widgetIds.length == 0) {
                if (DEBUG) Log.d(TAG, "no widgets to update");
                return;
            }
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
            if (DEBUG) Log.d(TAG, "Updating widget: " + appWidgetId);
            PeopleSpaceTile tile = getTileForExistingWidget(appWidgetId);
            if (tile == null) {
                Log.e(TAG, "Matching conversation not found for shortcut ID");
            }
            updateAppWidgetOptionsAndView(appWidgetId, tile);
            widgetIdToTile.put(appWidgetId, tile);
            if (tile != null) {
                registerConversationListenerIfNeeded(appWidgetId,
                        new PeopleTileKey(tile));
            }
        }
        PeopleSpaceUtils.getDataFromContactsOnBackgroundThread(
                mContext, mManager, widgetIdToTile, appWidgetIds);
    }

    /** Updates the current widget view with provided {@link PeopleSpaceTile}. */
    private void updateAppWidgetViews(int appWidgetId, PeopleSpaceTile tile, Bundle options) {
        PeopleTileKey key = getKeyFromStorageByWidgetId(appWidgetId);
        if (DEBUG) Log.d(TAG, "Widget: " + appWidgetId + " for: " + key.toString());

        if (!PeopleTileKey.isValid(key)) {
            Log.e(TAG, "Cannot update invalid widget");
            return;
        }
        RemoteViews views = PeopleTileViewHelper.createRemoteViews(mContext, tile, appWidgetId,
                options, key);

        // Tell the AppWidgetManager to perform an update on the current app widget.
        if (DEBUG) Log.d(TAG, "Calling update widget for widgetId: " + appWidgetId);
        mAppWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /** Updates tile in app widget options and the current view. */
    public void updateAppWidgetOptionsAndViewOptional(int appWidgetId,
            Optional<PeopleSpaceTile> tile) {
        if (tile.isPresent()) {
            updateAppWidgetOptionsAndView(appWidgetId, tile.get());
        }
    }

    /** Updates tile in app widget options and the current view. */
    public void updateAppWidgetOptionsAndView(int appWidgetId, PeopleSpaceTile tile) {
        if (tile == null) {
            if (DEBUG) Log.w(TAG, "Storing null tile");
        }
        synchronized (mTiles) {
            mTiles.put(appWidgetId, tile);
        }
        Bundle options = mAppWidgetManager.getAppWidgetOptions(appWidgetId);
        updateAppWidgetViews(appWidgetId, tile, options);
    }

    /**
     * Returns a {@link PeopleSpaceTile} based on the {@code appWidgetId}.
     * Widget already exists, so fetch {@link PeopleTileKey} from {@link SharedPreferences}.
     */
    @Nullable
    public PeopleSpaceTile getTileForExistingWidget(int appWidgetId) {
        try {
            return getTileForExistingWidgetThrowing(appWidgetId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve conversation for tile: " + e);
            return null;
        }
    }

    @Nullable
    private PeopleSpaceTile getTileForExistingWidgetThrowing(int appWidgetId) throws
            PackageManager.NameNotFoundException {
        // First, check if tile is cached in memory.
        PeopleSpaceTile tile;
        synchronized (mTiles) {
            tile = mTiles.get(appWidgetId);
        }
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

        return getTileFromPersistentStorage(key, appWidgetId);
    }

    /**
     * Returns a {@link PeopleSpaceTile} based on the {@code appWidgetId}.
     * If a {@link PeopleTileKey} is not provided, fetch one from {@link SharedPreferences}.
     */
    @Nullable
    public PeopleSpaceTile getTileFromPersistentStorage(PeopleTileKey key, int appWidgetId) throws
            PackageManager.NameNotFoundException {
        if (!PeopleTileKey.isValid(key)) {
            Log.e(TAG, "PeopleTileKey invalid: " + key.toString());
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
                if (DEBUG) Log.d(TAG, "Could not retrieve conversation from storage");
                return null;
            }

            // Get tile from shortcut & conversation storage.
            PeopleSpaceTile.Builder storedTile = new PeopleSpaceTile.Builder(channel,
                    mLauncherApps);
            if (storedTile == null) {
                return storedTile.build();
            }

            // Supplement with our storage.
            String contactUri = mSharedPrefs.getString(String.valueOf(appWidgetId), null);
            if (contactUri != null && storedTile.build().getContactUri() == null) {
                if (DEBUG) Log.d(TAG, "Restore contact uri from storage: " + contactUri);
                storedTile.setContactUri(Uri.parse(contactUri));
            }

            // Add current state.
            return getTileWithCurrentState(storedTile.build(), ACTION_BOOT_COMPLETED);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not retrieve data: " + e);
            return null;
        }
    }

    /**
     * Check if any existing People tiles match the incoming notification change, and store the
     * change in the tile if so.
     */
    public void updateWidgetsWithNotificationChanged(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction notificationAction) {
        if (DEBUG) {
            if (notificationAction == PeopleSpaceUtils.NotificationAction.POSTED) {
                Log.d(TAG, "Notification posted, key: " + sbn.getKey());
            } else {
                Log.d(TAG, "Notification removed, key: " + sbn.getKey());
            }
        }
        mBgExecutor.execute(
                () -> updateWidgetsWithNotificationChangedInBackground(sbn, notificationAction));
    }

    private void updateWidgetsWithNotificationChangedInBackground(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction action) {
        try {
            PeopleTileKey key = new PeopleTileKey(
                    sbn.getShortcutId(), sbn.getUser().getIdentifier(), sbn.getPackageName());
            if (!PeopleTileKey.isValid(key)) {
                Log.d(TAG, "Sbn doesn't contain valid PeopleTileKey: " + key.toString());
                return;
            }
            int[] widgetIds = mAppWidgetManager.getAppWidgetIds(
                    new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
            );
            if (widgetIds.length == 0) {
                Log.d(TAG, "No app widget ids returned");
                return;
            }
            synchronized (mLock) {
                Set<String> tilesUpdated = getMatchingKeyWidgetIds(key);
                Set<String> tilesUpdatedByUri = getMatchingUriWidgetIds(sbn, action);
                if (DEBUG) {
                    Log.d(TAG, "Widgets by key to be updated:" + tilesUpdated.toString());
                    Log.d(TAG, "Widgets by URI to be updated:" + tilesUpdatedByUri.toString());
                }
                tilesUpdated.addAll(tilesUpdatedByUri);
                updateWidgetIdsBasedOnNotifications(tilesUpdated);
            }
        } catch (Exception e) {
            Log.e(TAG, "Throwing exception: " + e);
        }
    }

    /** Updates {@code widgetIdsToUpdate} with {@code action}. */
    private void updateWidgetIdsBasedOnNotifications(Set<String> widgetIdsToUpdate) {
        if (widgetIdsToUpdate.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No widgets to update, returning.");
            return;
        }
        try {
            if (DEBUG) Log.d(TAG, "Fetching grouped notifications");
            Map<PeopleTileKey, Set<NotificationEntry>> groupedNotifications =
                    getGroupedConversationNotifications();

            widgetIdsToUpdate
                    .stream()
                    .map(Integer::parseInt)
                    .collect(Collectors.toMap(
                            Function.identity(),
                            id -> getAugmentedTileForExistingWidget(id, groupedNotifications)))
                    .forEach((id, tile) -> updateAppWidgetOptionsAndViewOptional(id, tile));
        } catch (Exception e) {
            Log.e(TAG, "Exception updating widgets: " + e);
        }
    }

    /**
     * Augments {@code tile} based on notifications returned from {@code notificationEntryManager}.
     */
    public PeopleSpaceTile augmentTileFromNotificationEntryManager(PeopleSpaceTile tile,
            Optional<Integer> appWidgetId) {
        PeopleTileKey key = new PeopleTileKey(tile);
        if (DEBUG) {
            Log.d(TAG,
                    "Augmenting tile from NotificationEntryManager widget: " + key.toString());
        }
        Map<PeopleTileKey, Set<NotificationEntry>> notifications =
                getGroupedConversationNotifications();
        String contactUri = null;
        if (tile.getContactUri() != null) {
            contactUri = tile.getContactUri().toString();
        }
        return augmentTileFromNotifications(tile, key, contactUri, notifications, appWidgetId);
    }

    /** Returns active and pending notifications grouped by {@link PeopleTileKey}. */
    public Map<PeopleTileKey, Set<NotificationEntry>> getGroupedConversationNotifications() {
        List<NotificationEntry> notifications =
                new ArrayList<>(mNotificationEntryManager.getVisibleNotifications());
        Iterable<NotificationEntry> pendingNotifications =
                mNotificationEntryManager.getPendingNotificationsIterator();
        for (NotificationEntry entry : pendingNotifications) {
            notifications.add(entry);
        }
        if (DEBUG) Log.d(TAG, "Number of total notifications: " + notifications.size());
        Map<PeopleTileKey, Set<NotificationEntry>> groupedNotifications =
                notifications
                        .stream()
                        .filter(entry -> NotificationHelper.isValid(entry)
                                && NotificationHelper.isMissedCallOrHasContent(entry)
                                && !shouldFilterOut(mBubblesOptional, entry))
                        .collect(Collectors.groupingBy(
                                PeopleTileKey::new,
                                Collectors.mapping(Function.identity(), Collectors.toSet())));
        if (DEBUG) {
            Log.d(TAG, "Number of grouped conversation notifications keys: "
                    + groupedNotifications.keySet().size());
        }
        return groupedNotifications;
    }

    /** Augments {@code tile} based on {@code notifications}, matching {@code contactUri}. */
    public PeopleSpaceTile augmentTileFromNotifications(PeopleSpaceTile tile, PeopleTileKey key,
            String contactUri,
            Map<PeopleTileKey, Set<NotificationEntry>> notifications,
            Optional<Integer> appWidgetId) {
        if (DEBUG) Log.d(TAG, "Augmenting tile from notifications. Tile key: " + key.toString());
        boolean hasReadContactsPermission = mPackageManager.checkPermission(READ_CONTACTS,
                tile.getPackageName()) == PackageManager.PERMISSION_GRANTED;

        List<NotificationEntry> notificationsByUri = new ArrayList<>();
        if (hasReadContactsPermission) {
            notificationsByUri = getNotificationsByUri(mPackageManager, contactUri, notifications);
            if (!notificationsByUri.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "Number of notifications matched by contact URI: "
                            + notificationsByUri.size());
                }
            }
        }

        Set<NotificationEntry> allNotifications = notifications.get(key);
        if (allNotifications == null) {
            allNotifications = new HashSet<>();
        }
        if (allNotifications.isEmpty() && notificationsByUri.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No existing notifications for tile: " + key.toString());
            return removeNotificationFields(tile);
        }

        // Merge notifications matched by key and by contact URI.
        allNotifications.addAll(notificationsByUri);
        if (DEBUG) Log.d(TAG, "Total notifications matching tile: " + allNotifications.size());

        int messagesCount = getMessagesCount(allNotifications);
        NotificationEntry highestPriority = getHighestPriorityNotification(allNotifications);

        if (DEBUG) Log.d(TAG, "Augmenting tile from notification, key: " + key.toString());
        return augmentTileFromNotification(mContext, tile, key, highestPriority, messagesCount,
                appWidgetId, mBackupManager);
    }

    /** Returns an augmented tile for an existing widget. */
    @Nullable
    public Optional<PeopleSpaceTile> getAugmentedTileForExistingWidget(int widgetId,
            Map<PeopleTileKey, Set<NotificationEntry>> notifications) {
        if (DEBUG) Log.d(TAG, "Augmenting tile for existing widget: " + widgetId);
        PeopleSpaceTile tile = getTileForExistingWidget(widgetId);
        if (tile == null) {
            if (DEBUG) {
                Log.w(TAG, "Widget: " + widgetId
                        + ". Null tile for existing widget, skipping update.");
            }
            return Optional.empty();
        }
        String contactUriString = mSharedPrefs.getString(String.valueOf(widgetId), null);
        // Should never be null, but using ofNullable for extra safety.
        PeopleTileKey key = new PeopleTileKey(tile);
        if (DEBUG) Log.d(TAG, "Existing widget: " + widgetId + ". Tile key: " + key.toString());
        return Optional.ofNullable(
                augmentTileFromNotifications(tile, key, contactUriString, notifications,
                        Optional.of(widgetId)));
    }

    /** Returns stored widgets for the conversation specified. */
    public Set<String> getMatchingKeyWidgetIds(PeopleTileKey key) {
        if (!PeopleTileKey.isValid(key)) {
            return new HashSet<>();
        }
        return new HashSet<>(mSharedPrefs.getStringSet(key.toString(), new HashSet<>()));
    }

    /**
     * Updates in-memory map of tiles with matched Uris, dependent on the {@code action}.
     *
     * <p>If the notification was added, adds the notification based on the contact Uri within
     * {@code sbn}.
     * <p>If the notification was removed, removes the notification based on the in-memory map of
     * widgets previously updated by Uri (since the contact Uri is stripped from the {@code sbn}).
     */
    @Nullable
    private Set<String> getMatchingUriWidgetIds(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction action) {
        if (action.equals(PeopleSpaceUtils.NotificationAction.POSTED)) {
            Set<String> widgetIdsUpdatedByUri = fetchMatchingUriWidgetIds(sbn);
            if (widgetIdsUpdatedByUri != null && !widgetIdsUpdatedByUri.isEmpty()) {
                mNotificationKeyToWidgetIdsMatchedByUri.put(sbn.getKey(), widgetIdsUpdatedByUri);
                return widgetIdsUpdatedByUri;
            }
        } else {
            // Remove the notification on any widgets where the notification was added
            // purely based on the Uri.
            Set<String> widgetsPreviouslyUpdatedByUri =
                    mNotificationKeyToWidgetIdsMatchedByUri.remove(sbn.getKey());
            if (widgetsPreviouslyUpdatedByUri != null && !widgetsPreviouslyUpdatedByUri.isEmpty()) {
                return widgetsPreviouslyUpdatedByUri;
            }
        }
        return new HashSet<>();
    }

    /** Fetches widget Ids that match the contact URI in {@code sbn}. */
    @Nullable
    private Set<String> fetchMatchingUriWidgetIds(StatusBarNotification sbn) {
        // Check if it's a missed call notification
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
        return storedWidgetIdsByUri;
    }

    /**
     * Update the tiles associated with the incoming conversation update.
     */
    public void updateWidgetsWithConversationChanged(ConversationChannel conversation) {
        ShortcutInfo info = conversation.getShortcutInfo();
        synchronized (mLock) {
            PeopleTileKey key = new PeopleTileKey(
                    info.getId(), info.getUserId(), info.getPackage());
            Set<String> storedWidgetIds = getMatchingKeyWidgetIds(key);
            for (String widgetIdString : storedWidgetIds) {
                if (DEBUG) {
                    Log.d(TAG,
                            "Conversation update for widget " + widgetIdString + " , "
                                    + info.getLabel());
                }
                updateStorageAndViewWithConversationData(conversation,
                        Integer.parseInt(widgetIdString));
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
        PeopleSpaceTile.Builder updatedTile = storedTile.toBuilder();
        ShortcutInfo info = conversation.getShortcutInfo();
        Uri uri = null;
        if (info.getPersons() != null && info.getPersons().length > 0) {
            Person person = info.getPersons()[0];
            uri = person.getUri() == null ? null : Uri.parse(person.getUri());
        }
        CharSequence label = info.getLabel();
        if (label != null) {
            updatedTile.setUserName(label);
        }
        Icon icon = PeopleSpaceTile.convertDrawableToIcon(mLauncherApps.getShortcutIconDrawable(
                info, 0));
        if (icon != null) {
            updatedTile.setUserIcon(icon);
        }
        if (DEBUG) Log.d(TAG, "Statuses: " + conversation.getStatuses());
        NotificationChannel channel = conversation.getNotificationChannel();
        if (channel != null) {
            if (DEBUG) Log.d(TAG, "Important:" + channel.isImportantConversation());
            updatedTile.setIsImportantConversation(channel.isImportantConversation());
        }
        updatedTile
                .setContactUri(uri)
                .setStatuses(conversation.getStatuses())
                .setLastInteractionTimestamp(conversation.getLastEventTimestamp());
        updateAppWidgetOptionsAndView(appWidgetId, updatedTile.build());
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
        if (PeopleTileKey.isValid(optionsKey)) {
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
        PeopleSpaceTile tile = null;
        try {
            tile = getTileFromPersistentStorage(key, appWidgetId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot add widget since app was uninstalled");
            return;
        }
        if (tile == null) {
            return;
        }
        tile = augmentTileFromNotificationEntryManager(tile, Optional.of(appWidgetId));

        PeopleTileKey existingKeyIfStored;
        synchronized (mLock) {
            existingKeyIfStored = getKeyFromStorageByWidgetId(appWidgetId);
        }
        // Delete previous storage if the widget already existed and is just reconfigured.
        if (PeopleTileKey.isValid(existingKeyIfStored)) {
            if (DEBUG) Log.d(TAG, "Remove previous storage for widget: " + appWidgetId);
            deleteWidgets(new int[]{appWidgetId});
        } else {
            // Widget newly added.
            mUiEventLogger.log(
                    PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_ADDED);
        }

        synchronized (mLock) {
            if (DEBUG) Log.d(TAG, "Add storage for : " + key.toString());
            PeopleSpaceUtils.setSharedPreferencesStorageForTile(mContext, key, appWidgetId,
                    tile.getContactUri(), mBackupManager);
        }
        if (DEBUG) Log.d(TAG, "Ensure listener is registered for widget: " + appWidgetId);
        registerConversationListenerIfNeeded(appWidgetId, key);
        try {
            if (DEBUG) Log.d(TAG, "Caching shortcut for PeopleTile: " + key.toString());
            mLauncherApps.cacheShortcuts(tile.getPackageName(),
                    Collections.singletonList(tile.getId()),
                    tile.getUserHandle(), LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS);
        } catch (Exception e) {
            Log.w(TAG, "Exception caching shortcut:" + e);
        }
        updateAppWidgetOptionsAndView(appWidgetId, tile);
    }

    /** Registers a conversation listener for {@code appWidgetId} if not already registered. */
    public void registerConversationListenerIfNeeded(int widgetId, PeopleTileKey key) {
        // Retrieve storage needed for registration.
        if (!PeopleTileKey.isValid(key)) {
            if (DEBUG) Log.w(TAG, "Could not register listener for widget: " + widgetId);
            return;
        }
        TileConversationListener newListener = new TileConversationListener();
        synchronized (mListeners) {
            if (mListeners.containsKey(key)) {
                if (DEBUG) Log.d(TAG, "Already registered listener");
                return;
            }
            if (DEBUG) Log.d(TAG, "Register listener for " + widgetId + " with " + key.toString());
            mListeners.put(key, newListener);
        }
        mPeopleManager.registerConversationListener(key.getPackageName(),
                key.getUserId(),
                key.getShortcutId(), newListener,
                mContext.getMainExecutor());
    }

    /**
     * Attempts to get a key from storage for {@code widgetId}, returning null if an invalid key is
     * found.
     */
    private PeopleTileKey getKeyFromStorageByWidgetId(int widgetId) {
        SharedPreferences widgetSp = mContext.getSharedPreferences(String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        PeopleTileKey key = new PeopleTileKey(
                widgetSp.getString(SHORTCUT_ID, EMPTY_STRING),
                widgetSp.getInt(USER_ID, INVALID_USER_ID),
                widgetSp.getString(PACKAGE_NAME, EMPTY_STRING));
        return key;
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
                if (!PeopleTileKey.isValid(key)) {
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
        TileConversationListener registeredListener;
        synchronized (mListeners) {
            registeredListener = mListeners.get(key);
            if (registeredListener == null) {
                if (DEBUG) Log.d(TAG, "Cannot find listener to unregister");
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Unregister listener for " + appWidgetId + " with " + key.toString());
            }
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
    public boolean requestPinAppWidget(ShortcutInfo shortcutInfo, Bundle options) {
        if (DEBUG) Log.d(TAG, "Requesting pin widget, shortcutId: " + shortcutInfo.getId());

        RemoteViews widgetPreview = getPreview(shortcutInfo.getId(),
                shortcutInfo.getUserHandle(), shortcutInfo.getPackage(), options);
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

    /** Returns a list of map entries corresponding to user's priority conversations. */
    @NonNull
    public List<PeopleSpaceTile> getPriorityTiles()
            throws Exception {
        List<ConversationChannelWrapper> conversations =
                mINotificationManager.getConversations(true).getList();
        // Add priority conversations to tiles list.
        Stream<ShortcutInfo> priorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() != null
                        && c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());
        List<PeopleSpaceTile> priorityTiles = PeopleSpaceUtils.getSortedTiles(mIPeopleManager,
                mLauncherApps, mUserManager,
                priorityConversations);
        return priorityTiles;
    }

    /** Returns a list of map entries corresponding to user's recent conversations. */
    @NonNull
    public List<PeopleSpaceTile> getRecentTiles()
            throws Exception {
        if (DEBUG) Log.d(TAG, "Add recent conversations");
        List<ConversationChannelWrapper> conversations =
                mINotificationManager.getConversations(false).getList();
        Stream<ShortcutInfo> nonPriorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() == null
                        || !c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());

        List<ConversationChannel> recentConversationsList =
                mIPeopleManager.getRecentConversations().getList();
        Stream<ShortcutInfo> recentConversations = recentConversationsList
                .stream()
                .map(c -> c.getShortcutInfo());

        Stream<ShortcutInfo> mergedStream = Stream.concat(nonPriorityConversations,
                recentConversations);
        List<PeopleSpaceTile> recentTiles =
                PeopleSpaceUtils.getSortedTiles(mIPeopleManager, mLauncherApps, mUserManager,
                        mergedStream);
        return recentTiles;
    }

    /**
     * Returns a {@link RemoteViews} preview of a Conversation's People Tile. Returns null if one
     * is not available.
     */
    public RemoteViews getPreview(String shortcutId, UserHandle userHandle, String packageName,
            Bundle options) {
        PeopleSpaceTile tile;
        ConversationChannel channel;
        try {
            channel = mIPeopleManager.getConversation(
                    packageName, userHandle.getIdentifier(), shortcutId);
            tile = PeopleSpaceUtils.getTile(channel, mLauncherApps);
        } catch (Exception e) {
            Log.w(TAG, "Exception getting tiles: " + e);
            return null;
        }
        if (tile == null) {
            if (DEBUG) Log.i(TAG, "No tile was returned");
            return null;
        }

        PeopleSpaceTile augmentedTile = augmentTileFromNotificationEntryManager(tile,
                Optional.empty());

        if (DEBUG) Log.i(TAG, "Returning tile preview for shortcutId: " + shortcutId);
        return PeopleTileViewHelper.createRemoteViews(mContext, augmentedTile, 0, options,
                new PeopleTileKey(augmentedTile));
    }

    protected final BroadcastReceiver mBaseBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "Update widgets from: " + intent.getAction());
            mBgExecutor.execute(() -> updateWidgetsFromBroadcastInBackground(intent.getAction()));
        }
    };

    /** Updates any app widget to the current state, triggered by a broadcast update. */
    @VisibleForTesting
    void updateWidgetsFromBroadcastInBackground(String entryPoint) {
        int[] appWidgetIds = mAppWidgetManager.getAppWidgetIds(
                new ComponentName(mContext, PeopleSpaceWidgetProvider.class));
        if (appWidgetIds == null) {
            return;
        }
        for (int appWidgetId : appWidgetIds) {
            if (DEBUG) Log.d(TAG, "Updating widget from broadcast, widget id: " +  appWidgetId);
            PeopleSpaceTile existingTile = null;
            PeopleSpaceTile updatedTile = null;
            try {
                synchronized (mLock) {
                    existingTile = getTileForExistingWidgetThrowing(appWidgetId);
                    if (existingTile == null) {
                        Log.e(TAG, "Matching conversation not found for shortcut ID");
                        continue;
                    }
                    updatedTile = getTileWithCurrentState(existingTile, entryPoint);
                    updateAppWidgetOptionsAndView(appWidgetId, updatedTile);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Delete data for uninstalled widgets.
                Log.e(TAG, "Package no longer found for tile: " + e);
                JobScheduler jobScheduler = mContext.getSystemService(JobScheduler.class);
                if (jobScheduler != null
                        && jobScheduler.getPendingJob(PeopleBackupFollowUpJob.JOB_ID) != null) {
                    if (DEBUG) {
                        Log.d(TAG, "Device was recently restored, wait before deleting storage.");
                    }
                    continue;
                }
                synchronized (mLock) {
                    updateAppWidgetOptionsAndView(appWidgetId, updatedTile);
                }
                deleteWidgets(new int[]{appWidgetId});
            }
        }
    }

    /** Checks the current state of {@code tile} dependencies, modifying fields as necessary. */
    @Nullable
    private PeopleSpaceTile getTileWithCurrentState(PeopleSpaceTile tile,
            String entryPoint) throws
            PackageManager.NameNotFoundException {
        PeopleSpaceTile.Builder updatedTile = tile.toBuilder();
        switch (entryPoint) {
            case NotificationManager
                    .ACTION_INTERRUPTION_FILTER_CHANGED:
                updatedTile.setNotificationPolicyState(getNotificationPolicyState());
                break;
            case Intent.ACTION_PACKAGES_SUSPENDED:
            case Intent.ACTION_PACKAGES_UNSUSPENDED:
                updatedTile.setIsPackageSuspended(getPackageSuspended(tile));
                break;
            case Intent.ACTION_MANAGED_PROFILE_AVAILABLE:
            case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
            case Intent.ACTION_USER_UNLOCKED:
                updatedTile.setIsUserQuieted(getUserQuieted(tile));
                break;
            case Intent.ACTION_LOCALE_CHANGED:
                break;
            case ACTION_BOOT_COMPLETED:
            default:
                updatedTile.setIsUserQuieted(getUserQuieted(tile)).setIsPackageSuspended(
                        getPackageSuspended(tile)).setNotificationPolicyState(
                        getNotificationPolicyState());
        }
        return updatedTile.build();
    }

    private boolean getPackageSuspended(PeopleSpaceTile tile) throws
            PackageManager.NameNotFoundException {
        boolean packageSuspended = !TextUtils.isEmpty(tile.getPackageName())
                && mPackageManager.isPackageSuspended(tile.getPackageName());
        if (DEBUG) Log.d(TAG, "Package suspended: " + packageSuspended);
        // isPackageSuspended() only throws an exception if the app has been uninstalled, and the
        // app data has also been cleared. We want to empty the layout when the app is uninstalled
        // regardless of app data clearing, which getApplicationInfoAsUser() handles.
        mPackageManager.getApplicationInfoAsUser(
                tile.getPackageName(), PackageManager.GET_META_DATA,
                PeopleSpaceUtils.getUserId(tile));
        return packageSuspended;
    }

    private boolean getUserQuieted(PeopleSpaceTile tile) {
        boolean workProfileQuieted =
                tile.getUserHandle() != null && mUserManager.isQuietModeEnabled(
                        tile.getUserHandle());
        if (DEBUG) Log.d(TAG, "Work profile quiet: " + workProfileQuieted);
        return workProfileQuieted;
    }

    private int getNotificationPolicyState() {
        NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
        boolean suppressVisualEffects =
                NotificationManager.Policy.areAllVisualEffectsSuppressed(
                        policy.suppressedVisualEffects);
        int notificationPolicyState = 0;
        // If the user sees notifications in DND, we do not need to evaluate the current DND
        // state, just always show notifications.
        if (!suppressVisualEffects) {
            if (DEBUG) Log.d(TAG, "Visual effects not suppressed.");
            return PeopleSpaceTile.SHOW_CONVERSATIONS;
        }
        switch (mNotificationManager.getCurrentInterruptionFilter()) {
            case INTERRUPTION_FILTER_ALL:
                if (DEBUG) Log.d(TAG, "All interruptions allowed");
                return PeopleSpaceTile.SHOW_CONVERSATIONS;
            case INTERRUPTION_FILTER_PRIORITY:
                if (policy.allowConversations()) {
                    if (policy.priorityConversationSenders == CONVERSATION_SENDERS_ANYONE) {
                        if (DEBUG) Log.d(TAG, "All conversations allowed");
                        // We only show conversations, so we can show everything.
                        return PeopleSpaceTile.SHOW_CONVERSATIONS;
                    } else if (policy.priorityConversationSenders
                            == NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT) {
                        if (DEBUG) Log.d(TAG, "Important conversations allowed");
                        notificationPolicyState |= PeopleSpaceTile.SHOW_IMPORTANT_CONVERSATIONS;
                    }
                }
                if (policy.allowMessages()) {
                    switch (policy.allowMessagesFrom()) {
                        case ZenModeConfig.SOURCE_CONTACT:
                            if (DEBUG) Log.d(TAG, "All contacts allowed");
                            notificationPolicyState |= PeopleSpaceTile.SHOW_CONTACTS;
                            return notificationPolicyState;
                        case ZenModeConfig.SOURCE_STAR:
                            if (DEBUG) Log.d(TAG, "Starred contacts allowed");
                            notificationPolicyState |= PeopleSpaceTile.SHOW_STARRED_CONTACTS;
                            return notificationPolicyState;
                        case ZenModeConfig.SOURCE_ANYONE:
                        default:
                            if (DEBUG) Log.d(TAG, "All messages allowed");
                            return PeopleSpaceTile.SHOW_CONVERSATIONS;
                    }
                }
                if (notificationPolicyState != 0) {
                    if (DEBUG) Log.d(TAG, "Return block state: " + notificationPolicyState);
                    return notificationPolicyState;
                }
                // If only alarms or nothing can bypass DND, the tile shouldn't show conversations.
            case INTERRUPTION_FILTER_NONE:
            case INTERRUPTION_FILTER_ALARMS:
            default:
                if (DEBUG) Log.d(TAG, "Block conversations");
                return PeopleSpaceTile.BLOCK_CONVERSATIONS;
        }
    }

    /**
     * Modifies widgets storage after a restore operation, since widget ids get remapped on restore.
     * This is guaranteed to run after the PeopleBackupHelper restore operation.
     */
    public void remapWidgets(int[] oldWidgetIds, int[] newWidgetIds) {
        if (DEBUG) {
            Log.d(TAG, "Remapping widgets, old: " + Arrays.toString(oldWidgetIds) + ". new: "
                    + Arrays.toString(newWidgetIds));
        }

        Map<String, String> widgets = new HashMap<>();
        for (int i = 0; i < oldWidgetIds.length; i++) {
            widgets.put(String.valueOf(oldWidgetIds[i]), String.valueOf(newWidgetIds[i]));
        }

        remapWidgetFiles(widgets);
        remapSharedFile(widgets);
        remapFollowupFile(widgets);

        int[] widgetIds = mAppWidgetManager.getAppWidgetIds(
                new ComponentName(mContext, PeopleSpaceWidgetProvider.class));
        Bundle b = new Bundle();
        b.putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true);
        for (int id : widgetIds) {
            if (DEBUG) Log.d(TAG, "Setting widget as restored, widget id:" + id);
            mAppWidgetManager.updateAppWidgetOptions(id, b);
        }

        updateWidgets(widgetIds);
    }

    /** Remaps widget ids in widget specific files. */
    public void remapWidgetFiles(Map<String, String> widgets) {
        if (DEBUG) Log.d(TAG, "Remapping widget files");
        Map<String, PeopleTileKey> remapped = new HashMap<>();
        for (Map.Entry<String, String> entry : widgets.entrySet()) {
            String from = String.valueOf(entry.getKey());
            String to = String.valueOf(entry.getValue());
            if (Objects.equals(from, to)) {
                continue;
            }

            SharedPreferences src = mContext.getSharedPreferences(from, Context.MODE_PRIVATE);
            PeopleTileKey key = SharedPreferencesHelper.getPeopleTileKey(src);
            if (PeopleTileKey.isValid(key)) {
                if (DEBUG) {
                    Log.d(TAG, "Moving PeopleTileKey: " + key.toString() + " from file: "
                            + from + ", to file: " + to);
                }
                remapped.put(to, key);
                SharedPreferencesHelper.clear(src);
            } else {
                if (DEBUG) Log.d(TAG, "Widget file has invalid key: " + key);
            }
        }
        for (Map.Entry<String, PeopleTileKey> entry : remapped.entrySet()) {
            SharedPreferences dest = mContext.getSharedPreferences(
                    entry.getKey(), Context.MODE_PRIVATE);
            SharedPreferencesHelper.setPeopleTileKey(dest, entry.getValue());
        }
    }

    /** Remaps widget ids in default shared storage. */
    public void remapSharedFile(Map<String, String> widgets) {
        if (DEBUG) Log.d(TAG, "Remapping shared file");
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        Map<String, ?> all = sp.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            PeopleBackupHelper.SharedFileEntryType keyType = getEntryType(entry);
            if (DEBUG) Log.d(TAG, "Remapping key:" + key);
            switch (keyType) {
                case WIDGET_ID:
                    String newId = widgets.get(key);
                    if (TextUtils.isEmpty(newId)) {
                        Log.w(TAG, "Key is widget id without matching new id, skipping: " + key);
                        break;
                    }
                    if (DEBUG) Log.d(TAG, "Key is widget id: " + key + ", replace with: " + newId);
                    try {
                        editor.putString(newId, (String) entry.getValue());
                    } catch (Exception e) {
                        Log.e(TAG, "Malformed entry value: " + entry.getValue());
                    }
                    editor.remove(key);
                    break;
                case PEOPLE_TILE_KEY:
                case CONTACT_URI:
                    Set<String> oldWidgetIds;
                    try {
                        oldWidgetIds = (Set<String>) entry.getValue();
                    } catch (Exception e) {
                        Log.e(TAG, "Malformed entry value: " + entry.getValue());
                        editor.remove(key);
                        break;
                    }
                    Set<String> newWidgets = getNewWidgets(oldWidgetIds, widgets);
                    if (DEBUG) {
                        Log.d(TAG, "Key is PeopleTileKey or contact URI: " + key
                                + ", replace values with new ids: " + newWidgets);
                    }
                    editor.putStringSet(key, newWidgets);
                    break;
                case UNKNOWN:
                    Log.e(TAG, "Key not identified:" + key);
            }
        }
        editor.apply();
    }

    /** Remaps widget ids in follow-up job file. */
    public void remapFollowupFile(Map<String, String> widgets) {
        if (DEBUG) Log.d(TAG, "Remapping follow up file");
        SharedPreferences followUp = mContext.getSharedPreferences(
                SHARED_FOLLOW_UP, Context.MODE_PRIVATE);
        SharedPreferences.Editor followUpEditor = followUp.edit();
        Map<String, ?> followUpAll = followUp.getAll();
        for (Map.Entry<String, ?> entry : followUpAll.entrySet()) {
            String key = entry.getKey();
            Set<String> oldWidgetIds;
            try {
                oldWidgetIds = (Set<String>) entry.getValue();
            } catch (Exception e) {
                Log.e(TAG, "Malformed entry value: " + entry.getValue());
                followUpEditor.remove(key);
                continue;
            }
            Set<String> newWidgets = getNewWidgets(oldWidgetIds, widgets);
            if (DEBUG) {
                Log.d(TAG, "Follow up key: " + key + ", replace with new ids: " + newWidgets);
            }
            followUpEditor.putStringSet(key, newWidgets);
        }
        followUpEditor.apply();
    }

    private Set<String> getNewWidgets(Set<String> oldWidgets, Map<String, String> widgetsMapping) {
        return oldWidgets
                .stream()
                .map(widgetsMapping::get)
                .filter(id -> !TextUtils.isEmpty(id))
                .collect(Collectors.toSet());
    }
}
