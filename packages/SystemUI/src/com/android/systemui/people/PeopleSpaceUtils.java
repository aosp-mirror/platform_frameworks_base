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

import android.app.INotificationManager;
import android.app.PendingIntent;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;

import com.android.systemui.R;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
        PackageManager mPackageManager = context.getPackageManager();
        IPeopleManager mPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        LauncherApps mLauncherApps = context.getSystemService(LauncherApps.class);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Intent activityIntent = new Intent(context, LaunchConversationActivity.class);
        activityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_HISTORY
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            List<Map.Entry<Long, PeopleSpaceTile>> shortcutInfos =
                    PeopleSpaceUtils.getTiles(
                            context, notificationManager,
                            mPeopleManager, mLauncherApps);
            for (int appWidgetId : appWidgetIds) {
                String shortcutId = sp.getString(String.valueOf(appWidgetId), null);
                if (DEBUG) {
                    Log.d(TAG, "Set widget: " + appWidgetId + " with shortcut ID: " + shortcutId);
                }

                Optional<Map.Entry<Long, PeopleSpaceTile>> entry = shortcutInfos.stream().filter(
                        e -> e.getValue().getId().equals(shortcutId)).findFirst();
                if (!entry.isPresent() || shortcutId == null) {
                    if (DEBUG) Log.d(TAG, "Matching conversation not found for shortcut ID");
                    //TODO: Delete app widget id when crash is fixed (b/175486868)
                    continue;
                }
                PeopleSpaceTile tile = entry.get().getValue();
                RemoteViews views = new RemoteViews(context.getPackageName(),
                        getLayout(tile));

                String status = PeopleSpaceUtils.getLastInteractionString(context,
                        entry.get().getKey());
                views.setTextViewText(R.id.status, status);
                views.setTextViewText(R.id.name, tile.getUserName().toString());

                activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, tile.getId());
                activityIntent.putExtra(
                        PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, tile.getPackageName());
                activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_UID, tile.getUid());
                views.setOnClickPendingIntent(R.id.item, PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        activityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

                views.setImageViewBitmap(
                        R.id.package_icon,
                        PeopleSpaceUtils.convertDrawableToBitmap(
                                mPackageManager.getApplicationIcon(tile.getPackageName())
                        )
                );
                views.setImageViewIcon(R.id.person_icon, tile.getUserIcon());
                // Tell the AppWidgetManager to perform an update on the current app widget.
                appWidgetManager.updateAppWidget(appWidgetId, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve conversations to set tiles");
        }
    }

    /** Returns the layout ID for the {@code tile}. */
    private static int getLayout(PeopleSpaceTile tile) {
        return tile.getNotification() == null ? R.layout.people_space_large_avatar_tile :
                R.layout.people_space_small_avatar_tile;
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
