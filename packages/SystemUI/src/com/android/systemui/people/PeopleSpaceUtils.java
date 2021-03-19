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

import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.Notification.EXTRA_MESSAGES;
import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_AUDIO;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.app.people.ConversationStatus.ACTIVITY_LOCATION;
import static android.app.people.ConversationStatus.ACTIVITY_NEW_STORY;
import static android.app.people.ConversationStatus.ACTIVITY_UPCOMING_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_VIDEO;
import static android.app.people.ConversationStatus.AVAILABILITY_AVAILABLE;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH;

import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ArrayUtils;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.people.widget.AppWidgetOptionsHelper;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;
import com.android.systemui.people.widget.PeopleTileKey;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utils class for People Space. */
public class PeopleSpaceUtils {
    /** Turns on debugging information about People Space. */
    public static final boolean DEBUG = true;
    private static final String TAG = "PeopleSpaceUtils";
    private static final int DAYS_IN_A_WEEK = 7;
    private static final int MIN_HOUR = 1;
    private static final int ONE_DAY = 1;
    public static final String PACKAGE_NAME = "package_name";
    public static final String USER_ID = "user_id";
    public static final String SHORTCUT_ID = "shortcut_id";

    public static final String EMPTY_STRING = "";
    public static final int INVALID_USER_ID = -1;

    public static final PeopleTileKey EMPTY_KEY =
            new PeopleTileKey(EMPTY_STRING, INVALID_USER_ID, EMPTY_STRING);
    public static final int SMALL_LAYOUT = 0;
    public static final int MEDIUM_LAYOUT = 1;
    @VisibleForTesting
    static final int REQUIRED_WIDTH_FOR_MEDIUM = 146;
    private static final int AVATAR_SIZE_FOR_MEDIUM = 56;
    private static final int DEFAULT_WIDTH = 146;
    private static final int DEFAULT_HEIGHT = 92;

    private static final Pattern DOUBLE_EXCLAMATION_PATTERN = Pattern.compile("[!][!]+");
    private static final Pattern DOUBLE_QUESTION_PATTERN = Pattern.compile("[?][?]+");
    private static final Pattern ANY_DOUBLE_MARK_PATTERN = Pattern.compile("[!?][!?]+");
    private static final Pattern MIXED_MARK_PATTERN = Pattern.compile("![?].*|.*[?]!");

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

    /** Returns a list of map entries corresponding to user's conversations. */
    public static List<PeopleSpaceTile> getTiles(
            Context context, INotificationManager notificationManager, IPeopleManager peopleManager,
            LauncherApps launcherApps, NotificationEntryManager notificationEntryManager)
            throws Exception {
        List<ConversationChannelWrapper> conversations =
                notificationManager.getConversations(
                        false).getList();
        // Add priority conversations to tiles list.
        Stream<ShortcutInfo> priorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() != null
                        && c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());
        List<PeopleSpaceTile> tiles = getSortedTiles(peopleManager, launcherApps,
                priorityConversations);

        // Sort and then add recent and non priority conversations to tiles list.
        if (DEBUG) Log.d(TAG, "Add recent conversations");
        Stream<ShortcutInfo> nonPriorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() == null
                        || !c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());

        List<ConversationChannel> recentConversationsList =
                peopleManager.getRecentConversations().getList();
        Stream<ShortcutInfo> recentConversations = recentConversationsList
                .stream()
                .map(c -> c.getShortcutInfo());

        Stream<ShortcutInfo> mergedStream = Stream.concat(nonPriorityConversations,
                recentConversations);
        List<PeopleSpaceTile> recentTiles =
                getSortedTiles(peopleManager, launcherApps, mergedStream);
        tiles.addAll(recentTiles);

        tiles = augmentTilesFromVisibleNotifications(context, tiles, notificationEntryManager);
        return tiles;
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
            int appWidgetId) {
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
        editor.putString(String.valueOf(appWidgetId), key.getShortcutId());

        // Don't overwrite existing widgets with the same key.
        Set<String> storedWidgetIds = new HashSet<>(
                sp.getStringSet(key.toString(), new HashSet<>()));
        storedWidgetIds.add(String.valueOf(appWidgetId));
        editor.putStringSet(key.toString(), storedWidgetIds);
        editor.apply();
    }

    /** Removes stored data when tile is deleted. */
    public static void removeSharedPreferencesStorageForTile(Context context, PeopleTileKey key,
            int widgetId) {
        // Delete widgetId mapping to key.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sp.edit();
        Set<String> storedWidgetIds = new HashSet<>(
                sp.getStringSet(key.toString(), new HashSet<>()));
        storedWidgetIds.remove(String.valueOf(widgetId));
        editor.putStringSet(key.toString(), storedWidgetIds);
        editor.remove(String.valueOf(widgetId));
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

    /** Augments a single {@link PeopleSpaceTile} with notification content, if one is present. */
    public static PeopleSpaceTile augmentSingleTileFromVisibleNotifications(Context context,
            PeopleSpaceTile tile, NotificationEntryManager notificationEntryManager) {
        List<PeopleSpaceTile> augmentedTile = augmentTilesFromVisibleNotifications(
                context, Arrays.asList(tile), notificationEntryManager);
        return augmentedTile.get(0);
    }

    static List<PeopleSpaceTile> augmentTilesFromVisibleNotifications(Context context,
            List<PeopleSpaceTile> tiles, NotificationEntryManager notificationEntryManager) {
        if (notificationEntryManager == null) {
            Log.w(TAG, "NotificationEntryManager is null");
            return tiles;
        }
        Map<PeopleTileKey, NotificationEntry> visibleNotifications = notificationEntryManager
                .getVisibleNotifications()
                .stream()
                .filter(entry -> entry.getRanking() != null
                        && entry.getRanking().getConversationShortcutInfo() != null)
                .collect(Collectors.toMap(PeopleTileKey::new, e -> e,
                        // Handle duplicate keys to avoid crashes.
                        (e1, e2) -> e1.getSbn().getNotification().when
                                > e2.getSbn().getNotification().when ? e1 : e2));
        if (DEBUG) {
            Log.d(TAG, "Number of visible notifications:" + visibleNotifications.size());
        }
        return tiles
                .stream()
                .map(entry -> augmentTileFromVisibleNotifications(
                        context, entry, visibleNotifications))
                .collect(Collectors.toList());
    }

    static PeopleSpaceTile augmentTileFromVisibleNotifications(Context context,
            PeopleSpaceTile tile, Map<PeopleTileKey, NotificationEntry> visibleNotifications) {
        PeopleTileKey key = new PeopleTileKey(
                tile.getId(), getUserId(tile), tile.getPackageName());

        if (!visibleNotifications.containsKey(key)) {
            if (DEBUG) Log.d(TAG, "No existing notifications for key:" + key.toString());
            return tile;
        }
        if (DEBUG) Log.d(TAG, "Augmenting tile from visible notifications, key:" + key.toString());
        return augmentTileFromNotification(context, tile, visibleNotifications.get(key).getSbn());
    }

    /** Augments {@code tile} with the notification content from {@code sbn}. */
    public static PeopleSpaceTile augmentTileFromNotification(Context context, PeopleSpaceTile tile,
            StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            if (DEBUG) Log.d(TAG, "Notification is null");
            return tile;
        }
        boolean isMissedCall = Objects.equals(notification.category, CATEGORY_MISSED_CALL);
        Notification.MessagingStyle.Message message = getLastMessagingStyleMessage(notification);

        if (!isMissedCall && message == null) {
            if (DEBUG) Log.d(TAG, "Notification has no content");
            return tile;
        }

        // If it's a missed call notification and it doesn't include content, use fallback value,
        // otherwise, use notification content.
        boolean hasMessageText = message != null && !TextUtils.isEmpty(message.getText());
        CharSequence content = (isMissedCall && !hasMessageText)
                ? context.getString(R.string.missed_call) : message.getText();
        Uri dataUri = message != null ? message.getDataUri() : null;

        return tile
                .toBuilder()
                .setNotificationKey(sbn.getKey())
                .setNotificationCategory(notification.category)
                .setNotificationContent(content)
                .setNotificationDataUri(dataUri)
                .build();
    }

    /** Creates a {@link RemoteViews} for {@code tile}. */
    public static RemoteViews createRemoteViews(Context context,
            PeopleSpaceTile tile, int appWidgetId, Bundle options) {
        int layoutSize = getLayoutSize(context, options);
        RemoteViews viewsForTile = getViewForTile(context, tile, layoutSize);
        int maxAvatarSize = getMaxAvatarSize(context, options, layoutSize);
        RemoteViews views = setCommonRemoteViewsFields(context, viewsForTile, tile, maxAvatarSize);
        return setLaunchIntents(context, views, tile, appWidgetId);
    }

    /**
     * The prioritization for the {@code tile} content is missed calls, followed by notification
     * content, then birthdays, then the most recent status, and finally last interaction.
     */
    private static RemoteViews getViewForTile(Context context, PeopleSpaceTile tile,
            int layoutSize) {
        if (Objects.equals(tile.getNotificationCategory(), CATEGORY_MISSED_CALL)) {
            if (DEBUG) Log.d(TAG, "Create missed call view");
            return createMissedCallRemoteViews(context, tile, layoutSize);
        }

        if (tile.getNotificationKey() != null) {
            if (DEBUG) Log.d(TAG, "Create notification view");
            return createNotificationRemoteViews(context, tile, layoutSize);
        }

        // TODO: Add sorting when we expose timestamp of statuses.
        List<ConversationStatus> statusesForEntireView =
                tile.getStatuses() == null ? Arrays.asList() : tile.getStatuses().stream().filter(
                        c -> isStatusValidForEntireStatusView(c)).collect(Collectors.toList());
        ConversationStatus birthdayStatus = getBirthdayStatus(tile, statusesForEntireView);
        if (birthdayStatus != null) {
            if (DEBUG) Log.d(TAG, "Create birthday view");
            return createStatusRemoteViews(context, birthdayStatus, layoutSize);
        }

        if (!statusesForEntireView.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG,
                        "Create status view for: " + statusesForEntireView.get(0).getActivity());
            }
            return createStatusRemoteViews(context, statusesForEntireView.get(0), layoutSize);
        }

        return createLastInteractionRemoteViews(context, tile, layoutSize);
    }

    /** Calculates the best layout relative to the size in {@code options}. */
    private static int getLayoutSize(Context context, Bundle options) {
        int display = context.getResources().getConfiguration().orientation;
        int width = display == Configuration.ORIENTATION_PORTRAIT
                ? options.getInt(OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH) : options.getInt(
                OPTION_APPWIDGET_MAX_WIDTH, DEFAULT_WIDTH);
        int height = display == Configuration.ORIENTATION_PORTRAIT ? options.getInt(
                OPTION_APPWIDGET_MAX_HEIGHT, DEFAULT_HEIGHT)
                : options.getInt(OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT);
        // Small layout used below a certain minimum width with any height.
        if (width < REQUIRED_WIDTH_FOR_MEDIUM) {
            if (DEBUG) Log.d(TAG, "Small view for width: " + width + " height: " + height);
            return SMALL_LAYOUT;
        }
        if (DEBUG) Log.d(TAG, "Medium view for width: " + width + " height: " + height);
        return MEDIUM_LAYOUT;
    }

    /** Returns the max avatar size for {@code layoutSize} under the current {@code options}. */
    private static int getMaxAvatarSize(Context context, Bundle options, int layoutSize) {
        int avatarHeightSpace = AVATAR_SIZE_FOR_MEDIUM;
        int avatarWidthSpace = AVATAR_SIZE_FOR_MEDIUM;

        if (layoutSize == SMALL_LAYOUT) {
            int display = context.getResources().getConfiguration().orientation;
            int width = display == Configuration.ORIENTATION_PORTRAIT
                    ? options.getInt(OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH) : options.getInt(
                    OPTION_APPWIDGET_MAX_WIDTH, DEFAULT_WIDTH);
            int height = display == Configuration.ORIENTATION_PORTRAIT ? options.getInt(
                    OPTION_APPWIDGET_MAX_HEIGHT, DEFAULT_HEIGHT)
                    : options.getInt(OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT);
            avatarHeightSpace = height - (8 + 4 + 18 + 8);
            avatarWidthSpace = width - (4 + 4);
        }
        if (DEBUG) Log.d(TAG, "Height: " + avatarHeightSpace + " width: " + avatarWidthSpace);
        return Math.min(avatarHeightSpace, avatarWidthSpace);
    }

    @Nullable
    private static ConversationStatus getBirthdayStatus(PeopleSpaceTile tile,
            List<ConversationStatus> statuses) {
        Optional<ConversationStatus> birthdayStatus = statuses.stream().filter(
                c -> c.getActivity() == ACTIVITY_BIRTHDAY).findFirst();
        if (birthdayStatus.isPresent()) {
            return birthdayStatus.get();
        }
        if (!TextUtils.isEmpty(tile.getBirthdayText())) {
            return new ConversationStatus.Builder(tile.getId(), ACTIVITY_BIRTHDAY).build();
        }

        return null;
    }

    /**
     * Returns whether a {@code status} should have its own entire templated view.
     *
     * <p>A status may still be shown on the view (for example, as a new story ring) even if it's
     * not valid to compose an entire view.
     */
    private static boolean isStatusValidForEntireStatusView(ConversationStatus status) {
        switch (status.getActivity()) {
            // Birthday & Anniversary don't require text provided or icon provided.
            case ACTIVITY_BIRTHDAY:
            case ACTIVITY_ANNIVERSARY:
                return true;
            default:
                // For future birthday, location, new story, video, music, game, and other, the
                // app must provide either text or an icon.
                return !TextUtils.isEmpty(status.getDescription())
                        || status.getIcon() != null;
        }
    }

    private static RemoteViews createStatusRemoteViews(Context context, ConversationStatus status,
            int layoutSize) {
        int layout = layoutSize == SMALL_LAYOUT ? R.layout.people_space_small_view
                : R.layout.people_space_small_avatar_tile;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        CharSequence statusText = status.getDescription();
        if (TextUtils.isEmpty(statusText)) {
            statusText = getStatusTextByType(context, status.getActivity());
        }
        views.setViewVisibility(R.id.subtext, View.GONE);
        views.setViewVisibility(R.id.text_content, View.VISIBLE);
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, typedValue, true);
        int secondaryTextColor = context.getColor(typedValue.resourceId);
        views.setInt(R.id.text_content, "setTextColor", secondaryTextColor);
        views.setTextViewText(R.id.text_content, statusText);
        Icon statusIcon = status.getIcon();
        if (statusIcon != null) {
            views.setImageViewIcon(R.id.image, statusIcon);
            views.setBoolean(R.id.content_background, "setClipToOutline", true);
        } else {
            views.setViewVisibility(R.id.content_background, View.GONE);
        }
        // TODO: Set status pre-defined icons
        views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_person);
        ensurePredefinedIconVisibleOnSmallView(views, layoutSize);
        return views;
    }

    private static String getStatusTextByType(Context context, int activity) {
        switch (activity) {
            case ACTIVITY_BIRTHDAY:
                return context.getString(R.string.birthday_status);
            case ACTIVITY_UPCOMING_BIRTHDAY:
                return context.getString(R.string.upcoming_birthday_status);
            case ACTIVITY_ANNIVERSARY:
                return context.getString(R.string.anniversary_status);
            case ACTIVITY_LOCATION:
                return context.getString(R.string.location_status);
            case ACTIVITY_NEW_STORY:
                return context.getString(R.string.new_story_status);
            case ACTIVITY_VIDEO:
                return context.getString(R.string.video_status);
            case ACTIVITY_AUDIO:
                return context.getString(R.string.audio_status);
            case ACTIVITY_GAME:
                return context.getString(R.string.game_status);
            default:
                return EMPTY_STRING;
        }
    }

    private static RemoteViews setCommonRemoteViewsFields(Context context, RemoteViews views,
            PeopleSpaceTile tile, int maxAvatarSize) {
        try {
            boolean isAvailable =
                    tile.getStatuses() != null && tile.getStatuses().stream().anyMatch(
                            c -> c.getAvailability() == AVAILABILITY_AVAILABLE);
            if (isAvailable) {
                views.setViewVisibility(R.id.availability, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.availability, View.GONE);
            }
            boolean hasNewStory =
                    tile.getStatuses() != null && tile.getStatuses().stream().anyMatch(
                            c -> c.getActivity() == ACTIVITY_NEW_STORY);
            views.setTextViewText(R.id.name, tile.getUserName().toString());
            views.setBoolean(R.id.content_background, "setClipToOutline", true);
            Icon icon = tile.getUserIcon();
            PeopleStoryIconFactory storyIcon = new PeopleStoryIconFactory(context,
                    context.getPackageManager(),
                    IconDrawableFactory.newInstance(context, false),
                    maxAvatarSize);
            Drawable drawable = icon.loadDrawable(context);
            Drawable personDrawable = storyIcon.getPeopleTileDrawable(drawable,
                    tile.getPackageName(), getUserId(tile), tile.isImportantConversation(),
                    hasNewStory);
            Bitmap bitmap = convertDrawableToBitmap(personDrawable);
            views.setImageViewBitmap(R.id.person_icon, bitmap);

            return views;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set common fields: " + e);
        }
        return views;
    }

    private static RemoteViews setLaunchIntents(Context context, RemoteViews views,
            PeopleSpaceTile tile, int appWidgetId) {
        try {
            Intent activityIntent = new Intent(context, LaunchConversationActivity.class);
            activityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, tile.getId());
            activityIntent.putExtra(
                    PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, tile.getPackageName());
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_USER_HANDLE,
                    tile.getUserHandle());
            activityIntent.putExtra(
                    PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY, tile.getNotificationKey());
            views.setOnClickPendingIntent(R.id.item, PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
            return views;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add launch intents: " + e);
        }

        return views;
    }

    private static RemoteViews createMissedCallRemoteViews(Context context,
            PeopleSpaceTile tile, int layoutSize) {
        int layout = layoutSize == SMALL_LAYOUT ? R.layout.people_space_small_view
                : R.layout.people_space_small_avatar_tile;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setViewVisibility(R.id.subtext, View.GONE);
        views.setViewVisibility(R.id.text_content, View.VISIBLE);
        views.setTextViewText(R.id.text_content, tile.getNotificationContent());
        views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_phone_missed);
        ensurePredefinedIconVisibleOnSmallView(views, layoutSize);
        views.setBoolean(R.id.content_background, "setClipToOutline", true);
        return views;
    }

    private static void ensurePredefinedIconVisibleOnSmallView(RemoteViews views, int layoutSize) {
        if (layoutSize == SMALL_LAYOUT) {
            views.setViewVisibility(R.id.name, View.GONE);
            views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
        }
    }

    private static RemoteViews createNotificationRemoteViews(Context context,
            PeopleSpaceTile tile, int layoutSize) {
        int layout = layoutSize == SMALL_LAYOUT ? R.layout.people_space_small_view
                : R.layout.people_space_small_avatar_tile;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        if (layoutSize != MEDIUM_LAYOUT) {
            views.setViewVisibility(R.id.name, View.GONE);
            views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
        }
        Uri image = tile.getNotificationDataUri();
        ensurePredefinedIconVisibleOnSmallView(views, layoutSize);
        if (image != null) {
            // TODO: Use NotificationInlineImageCache
            views.setImageViewUri(R.id.image, image);
            views.setViewVisibility(R.id.content_background, View.VISIBLE);
            views.setBoolean(R.id.content_background, "setClipToOutline", true);
            views.setViewVisibility(R.id.text_content, View.GONE);
            views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_photo_camera);
        } else {
            CharSequence content = tile.getNotificationContent();
            views = setPunctuationRemoteViewsFields(views, content);
            views.setTextViewText(R.id.text_content, tile.getNotificationContent());
            // TODO: Measure max lines from height.
            views.setInt(R.id.text_content, "setMaxLines", 2);
            TypedValue typedValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
            int primaryTextColor = context.getColor(typedValue.resourceId);
            views.setInt(R.id.text_content, "setTextColor", primaryTextColor);
            views.setViewVisibility(R.id.text_content, View.VISIBLE);
            views.setViewVisibility(R.id.content_background, View.GONE);
            views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_message);
        }
        // TODO: Set subtext as Group Sender name once storing the name in PeopleSpaceTile.
        views.setViewVisibility(R.id.subtext, View.GONE);
        return views;
    }

    private static RemoteViews createLastInteractionRemoteViews(Context context,
            PeopleSpaceTile tile, int layoutSize) {
        int layout = layoutSize == SMALL_LAYOUT ? R.layout.people_space_small_view
                : R.layout.people_space_large_avatar_tile;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        if (layoutSize == SMALL_LAYOUT) {
            views.setViewVisibility(R.id.name, View.VISIBLE);
            views.setViewVisibility(R.id.predefined_icon, View.GONE);
        }
        String status = PeopleSpaceUtils.getLastInteractionString(
                context, tile.getLastInteractionTimestamp());
        views.setTextViewText(R.id.last_interaction, status);
        return views;
    }

    private static RemoteViews setPunctuationRemoteViewsFields(
            RemoteViews views, CharSequence content) {
        String punctuation = getBackgroundTextFromMessage(content.toString());
        int visibility = View.GONE;
        if (punctuation != null) {
            visibility = View.VISIBLE;
        }
        views.setTextViewText(R.id.punctuation1, punctuation);
        views.setTextViewText(R.id.punctuation2, punctuation);
        views.setTextViewText(R.id.punctuation3, punctuation);
        views.setTextViewText(R.id.punctuation4, punctuation);
        views.setTextViewText(R.id.punctuation5, punctuation);
        views.setTextViewText(R.id.punctuation6, punctuation);

        views.setViewVisibility(R.id.punctuation1, visibility);
        views.setViewVisibility(R.id.punctuation2, visibility);
        views.setViewVisibility(R.id.punctuation3, visibility);
        views.setViewVisibility(R.id.punctuation4, visibility);
        views.setViewVisibility(R.id.punctuation5, visibility);
        views.setViewVisibility(R.id.punctuation6, visibility);

        return views;
    }

    /** Gets character for tile background decoration based on notification content. */
    @VisibleForTesting
    static String getBackgroundTextFromMessage(String message) {
        if (!ANY_DOUBLE_MARK_PATTERN.matcher(message).find()) {
            return null;
        }
        if (MIXED_MARK_PATTERN.matcher(message).find()) {
            return "!?";
        }
        Matcher doubleQuestionMatcher = DOUBLE_QUESTION_PATTERN.matcher(message);
        if (!doubleQuestionMatcher.find()) {
            return "!";
        }
        Matcher doubleExclamationMatcher = DOUBLE_EXCLAMATION_PATTERN.matcher(message);
        if (!doubleExclamationMatcher.find()) {
            return "?";
        }
        // If we have both "!!" and "??", return the one that comes first.
        if (doubleQuestionMatcher.start() < doubleExclamationMatcher.start()) {
            return "?";
        }
        return "!";
    }

    /** Gets the most recent {@link Notification.MessagingStyle.Message} from the notification. */
    @VisibleForTesting
    public static Notification.MessagingStyle.Message getLastMessagingStyleMessage(
            Notification notification) {
        if (notification == null) {
            return null;
        }
        if (Notification.MessagingStyle.class.equals(notification.getNotificationStyle())
                && notification.extras != null) {
            final Parcelable[] messages = notification.extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                List<Notification.MessagingStyle.Message> sortedMessages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
                sortedMessages.sort(Collections.reverseOrder(
                        Comparator.comparing(Notification.MessagingStyle.Message::getTimestamp)));
                return sortedMessages.get(0);
            }
        }
        return null;
    }

    /** Returns a list sorted by ascending last interaction time from {@code stream}. */
    private static List<PeopleSpaceTile> getSortedTiles(IPeopleManager peopleManager,
            LauncherApps launcherApps,
            Stream<ShortcutInfo> stream) {
        return stream
                .filter(Objects::nonNull)
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

    /** Returns a readable status describing the {@code lastInteraction}. */
    public static String getLastInteractionString(Context context, long lastInteraction) {
        if (lastInteraction == 0L) {
            Log.e(TAG, "Could not get valid last interaction");
            return context.getString(R.string.basic_status);
        }
        long now = System.currentTimeMillis();
        Duration durationSinceLastInteraction = Duration.ofMillis(now - lastInteraction);
        MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.WIDE);
        if (durationSinceLastInteraction.toHours() < MIN_HOUR) {
            return context.getString(R.string.timestamp, formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toMinutes(), MeasureUnit.MINUTE)));
        } else if (durationSinceLastInteraction.toDays() < ONE_DAY) {
            return context.getString(R.string.timestamp, formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toHours(),
                            MeasureUnit.HOUR)));
        } else if (durationSinceLastInteraction.toDays() < DAYS_IN_A_WEEK) {
            return context.getString(R.string.timestamp, formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toHours(),
                            MeasureUnit.DAY)));
        } else {
            return context.getString(durationSinceLastInteraction.toDays() == DAYS_IN_A_WEEK
                            ? R.string.timestamp : R.string.over_timestamp,
                    formatter.formatMeasures(
                            new Measure(durationSinceLastInteraction.toDays() / DAYS_IN_A_WEEK,
                                    MeasureUnit.WEEK)));
        }
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
        if (DEBUG) Log.d(TAG, "Widget: " + appWidgetId + ", " + tile.getUserName());
        RemoteViews views = createRemoteViews(context, tile, appWidgetId, options);

        // Tell the AppWidgetManager to perform an update on the current app widget.
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /** Updates tile in app widget options and the current view. */
    public static void updateAppWidgetOptionsAndView(AppWidgetManager appWidgetManager,
            Context context, int appWidgetId, PeopleSpaceTile tile) {
        Bundle options = AppWidgetOptionsHelper.setPeopleTile(appWidgetManager, appWidgetId, tile);
        updateAppWidgetViews(appWidgetManager, context, appWidgetId, tile, options);
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

    /**
     * Returns a {@link RemoteViews} preview of a Conversation's People Tile. Returns null if one
     * is not available.
     */
    public static RemoteViews getPreview(Context context, IPeopleManager peopleManager,
            LauncherApps launcherApps, NotificationEntryManager notificationEntryManager,
            String shortcutId, UserHandle userHandle, String packageName) {
        peopleManager = (peopleManager != null) ? peopleManager : IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        launcherApps = (launcherApps != null) ? launcherApps
                : context.getSystemService(LauncherApps.class);
        if (peopleManager == null || launcherApps == null) {
            return null;
        }

        ConversationChannel channel;
        try {
            channel = peopleManager.getConversation(
                    packageName, userHandle.getIdentifier(), shortcutId);
        } catch (Exception e) {
            Log.w(TAG, "Exception getting tiles: " + e);
            return null;
        }
        PeopleSpaceTile tile = PeopleSpaceUtils.getTile(channel, launcherApps);

        if (tile == null) {
            if (DEBUG) Log.i(TAG, "No tile was returned");
            return null;
        }
        PeopleSpaceTile augmentedTile = augmentSingleTileFromVisibleNotifications(
                context, tile, notificationEntryManager);

        if (DEBUG) Log.i(TAG, "Returning tile preview for shortcutId: " + shortcutId);
        Bundle bundle = new Bundle();
        return PeopleSpaceUtils.createRemoteViews(context, augmentedTile, 0, bundle);
    }

    /** Returns the userId associated with a {@link PeopleSpaceTile} */
    public static int getUserId(PeopleSpaceTile tile) {
        return tile.getUserHandle().getIdentifier();
    }
}