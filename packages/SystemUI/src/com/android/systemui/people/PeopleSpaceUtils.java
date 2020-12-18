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

import static android.app.Notification.EXTRA_MESSAGES;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ArrayUtils;
import com.android.systemui.R;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    public static final String OPTIONS_PEOPLE_SPACE_TILE = "options_people_space_tile";

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
    public static List<Map.Entry<Long, PeopleSpaceTile>> getTiles(
            Context context, INotificationManager notificationManager, IPeopleManager peopleManager,
            LauncherApps launcherApps)
            throws Exception {
        boolean showOnlyPriority = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 1;
        List<ConversationChannelWrapper> conversations = notificationManager.getConversations(
                true).getList();
        List<Map.Entry<Long, PeopleSpaceTile>> tiles = getSortedTiles(peopleManager,
                conversations.stream().map(c ->
                        new PeopleSpaceTile.Builder(c.getShortcutInfo(), launcherApps).build()));
        if (!showOnlyPriority) {
            if (DEBUG) Log.d(TAG, "Add recent conversations");
            List<ConversationChannel> recentConversations =
                    peopleManager.getRecentConversations().getList();
            List<Map.Entry<Long, PeopleSpaceTile>> recentTiles =
                    getSortedTiles(peopleManager, recentConversations.stream().map(c ->
                            new PeopleSpaceTile
                                    .Builder(c.getShortcutInfo(), launcherApps)
                                    .build()));
            tiles.addAll(recentTiles);
        }
        return tiles;
    }

    /** Updates {@code appWidgetIds} with their associated conversation stored. */
    public static void updateSingleConversationWidgets(Context context, int[] appWidgetIds,
            AppWidgetManager appWidgetManager, INotificationManager notificationManager) {
        IPeopleManager peopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            List<Map.Entry<Long, PeopleSpaceTile>> tiles =
                    PeopleSpaceUtils.getTiles(context, notificationManager,
                            peopleManager, launcherApps);

            for (int appWidgetId : appWidgetIds) {
                String shortcutId = sp.getString(String.valueOf(appWidgetId), null);
                if (DEBUG) {
                    Log.d(TAG, "Set widget: " + appWidgetId + " with shortcut ID: " + shortcutId);
                }
                Optional<Map.Entry<Long, PeopleSpaceTile>> entry = tiles.stream().filter(
                        e -> e.getValue().getId().equals(shortcutId)).findFirst();
                if (!entry.isPresent() || shortcutId == null) {
                    if (DEBUG) Log.d(TAG, "Matching conversation not found for shortcut ID");
                    //TODO: Delete app widget id when crash is fixed (b/175486868)
                    continue;
                }
                PeopleSpaceTile tile =
                        augmentTileFromStorage(entry.get().getValue(), appWidgetManager,
                                appWidgetId);

                RemoteViews views = createRemoteViews(context, tile, entry.get().getKey(),
                        appWidgetId);

                // Tell the AppWidgetManager to perform an update on the current app widget.
                appWidgetManager.updateAppWidget(appWidgetId, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception updating single conversation widgets: " + e);
        }
    }

    /** Returns a list sorted by ascending last interaction time from {@code stream}. */
    private static List<Map.Entry<Long, PeopleSpaceTile>> getSortedTiles(
            IPeopleManager peopleManager, Stream<PeopleSpaceTile> stream) {
        return stream
                .filter(c -> shouldKeepConversation(c))
                .map(c -> Map.entry(getLastInteraction(peopleManager, c), c))
                .sorted((c1, c2) -> (c2.getKey().compareTo(c1.getKey())))
                .collect(Collectors.toList());
    }

    /** Augment {@link PeopleSpaceTile} with fields from stored tile. */
    @VisibleForTesting
    static PeopleSpaceTile augmentTileFromStorage(PeopleSpaceTile tile,
            AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        PeopleSpaceTile storedTile = options.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        if (storedTile == null) {
            return tile;
        }
        return tile.toBuilder()
                .setNotificationKey(storedTile.getNotificationKey())
                .setNotificationContent(storedTile.getNotificationContent())
                .setNotificationDataUri(storedTile.getNotificationDataUri())
                .build();
    }

    /** If incoming notification changed tile, store the changes in the tile options. */
    public static void storeNotificationChange(StatusBarNotification sbn,
            NotificationAction notificationAction, AppWidgetManager appWidgetManager,
            int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        PeopleSpaceTile storedTile = options.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        if (notificationAction == PeopleSpaceUtils.NotificationAction.POSTED) {
            if (DEBUG) Log.i(TAG, "Adding notification to storage, appWidgetId: " + appWidgetId);
            Notification.MessagingStyle.Message message = getLastMessagingStyleMessage(sbn);
            if (message == null) {
                if (DEBUG) Log.i(TAG, "Notification doesn't have content, skipping.");
                return;
            }
            storedTile = storedTile
                    .toBuilder()
                    .setNotificationKey(sbn.getKey())
                    .setNotificationContent(message.getText())
                    .setNotificationDataUri(message.getDataUri())
                    .build();
        } else {
            if (DEBUG) {
                Log.i(TAG, "Removing notification from storage, appWidgetId: " + appWidgetId);
            }
            storedTile = storedTile
                    .toBuilder()
                    .setNotificationKey(null)
                    .setNotificationContent(null)
                    .setNotificationDataUri(null)
                    .build();
        }
        Bundle newOptions = new Bundle();
        newOptions.putParcelable(OPTIONS_PEOPLE_SPACE_TILE, storedTile);
        appWidgetManager.updateAppWidgetOptions(appWidgetId, newOptions);
    }

    private static RemoteViews createRemoteViews(Context context, PeopleSpaceTile tile,
            long lastInteraction, int appWidgetId) throws Exception {
        // TODO: If key is null or if text and data uri are null.
        if (tile.getNotificationKey() == null) {
            return createLastInteractionRemoteViews(context, tile, lastInteraction, appWidgetId);
        }
        return createNotificationRemoteViews(context, tile, lastInteraction, appWidgetId);
    }

    private static RemoteViews createLastInteractionRemoteViews(Context context,
            PeopleSpaceTile tile, long lastInteraction, int appWidgetId)
            throws Exception {
        RemoteViews views = new RemoteViews(
                    context.getPackageName(), R.layout.people_space_large_avatar_tile);
        String status = PeopleSpaceUtils.getLastInteractionString(
                context, lastInteraction);
        views.setTextViewText(R.id.status, status);

        views = setCommonRemoteViewsFields(context, views, tile, appWidgetId);
        return views;
    }

    private static RemoteViews createNotificationRemoteViews(Context context,
            PeopleSpaceTile tile, long lastInteraction, int appWidgetId)
            throws Exception {

        RemoteViews views = new RemoteViews(
                context.getPackageName(), R.layout.people_space_small_avatar_tile);
        Uri image = tile.getNotificationDataUri();
        if (image != null) {
            //TODO: Use NotificationInlineImageCache
            views.setImageViewUri(R.id.image, image);
            views.setViewVisibility(R.id.image, View.VISIBLE);
            views.setViewVisibility(R.id.content, View.GONE);
        } else {
            CharSequence content = tile.getNotificationContent();
            views = setPunctuationRemoteViewsFields(views, content);
            views.setTextViewText(R.id.content, content);
            views.setViewVisibility(R.id.content, View.VISIBLE);
            views.setViewVisibility(R.id.image, View.GONE);
        }

        views = setCommonRemoteViewsFields(context, views, tile, appWidgetId);
        return views;
    }

    private static RemoteViews setCommonRemoteViewsFields(
            Context context, RemoteViews views, PeopleSpaceTile tile, int appWidgetId)
            throws Exception {
        views.setTextViewText(R.id.name, tile.getUserName().toString());
        views.setImageViewBitmap(
                R.id.package_icon,
                PeopleSpaceUtils.convertDrawableToBitmap(
                        context.getPackageManager().getApplicationIcon(tile.getPackageName())
                )
        );
        views.setImageViewIcon(R.id.person_icon, tile.getUserIcon());

        Intent activityIntent = new Intent(context, LaunchConversationActivity.class);
        activityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_HISTORY
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, tile.getId());
        activityIntent.putExtra(
                PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, tile.getPackageName());
        activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_UID, tile.getUid());
        views.setOnClickPendingIntent(R.id.item, PendingIntent.getActivity(
                context,
                appWidgetId,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        return views;
    }

    private static RemoteViews setPunctuationRemoteViewsFields(
            RemoteViews views, CharSequence content) {
        String punctuation = getBackgroundTextFromMessage(content.toString());
        int visibility = View.GONE;
        if (punctuation  != null) {
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
    public static Notification.MessagingStyle.Message getLastMessagingStyleMessage(
            StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
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

    /** Returns the last interaction time with the user specified by {@code PeopleSpaceTile}. */
    private static Long getLastInteraction(IPeopleManager peopleManager,
            PeopleSpaceTile tile) {
        try {
            int userId = UserHandle.getUserHandleForUid(tile.getUid()).getIdentifier();
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
            return context.getString(R.string.last_interaction_status_less_than,
                    formatter.formatMeasures(new Measure(MIN_HOUR, MeasureUnit.HOUR)));
        } else if (durationSinceLastInteraction.toDays() < ONE_DAY) {
            return context.getString(R.string.last_interaction_status, formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toHours(), MeasureUnit.HOUR)));
        } else if (durationSinceLastInteraction.toDays() < DAYS_IN_A_WEEK) {
            return context.getString(R.string.last_interaction_status, formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toDays(), MeasureUnit.DAY)));
        } else {
            return context.getString(durationSinceLastInteraction.toDays() == DAYS_IN_A_WEEK
                            ? R.string.last_interaction_status :
                            R.string.last_interaction_status_over,
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
        return tile != null && tile.getUserName().length() != 0;
    }

}
