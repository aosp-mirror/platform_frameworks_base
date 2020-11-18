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
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.util.Log;

import com.android.systemui.R;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public static List<Map.Entry<Long, ShortcutInfo>> getShortcutInfos(Context context,
            INotificationManager notificationManager, IPeopleManager peopleManager)
            throws Exception {
        boolean showAllConversations = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 0;
        List<ConversationChannelWrapper> conversations = notificationManager.getConversations(
                true).getList();
        List<Map.Entry<Long, ShortcutInfo>> shortcutInfos = getSortedShortcutInfos(peopleManager,
                conversations.stream().map(c -> c.getShortcutInfo()));
        if (showAllConversations) {
            List<ConversationChannel> recentConversations =
                    peopleManager.getRecentConversations().getList();
            List<Map.Entry<Long, ShortcutInfo>> recentShortcutInfos = getSortedShortcutInfos(
                    peopleManager, recentConversations.stream().map(c -> c.getShortcutInfo()));
            shortcutInfos.addAll(recentShortcutInfos);
        }
        return shortcutInfos;
    }

    /** Returns a list sorted by ascending last interaction time from {@code stream}. */
    private static List<Map.Entry<Long, ShortcutInfo>> getSortedShortcutInfos(
            IPeopleManager peopleManager, Stream<ShortcutInfo> stream) {
        return stream
                .filter(c -> shouldKeepConversation(c))
                .map(c -> Map.entry(getLastInteraction(peopleManager, c), c))
                .sorted((c1, c2) -> (c2.getKey().compareTo(c1.getKey())))
                .collect(Collectors.toList());
    }

    /** Returns the last interaction time with the user specified by {@code shortcutInfo}. */
    private static Long getLastInteraction(IPeopleManager peopleManager,
            ShortcutInfo shortcutInfo) {
        try {
            int userId = UserHandle.getUserHandleForUid(shortcutInfo.getUserId()).getIdentifier();
            String pkg = shortcutInfo.getPackage();
            return peopleManager.getLastInteraction(pkg, userId, shortcutInfo.getId());
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
     *         <li>Have a non-null {@link ShortcutInfo}
     *         <li>Have an associated label in the {@link ShortcutInfo}
     *     </ul>
     * </li>
     */
    public static boolean shouldKeepConversation(ShortcutInfo shortcutInfo) {
        return shortcutInfo != null && shortcutInfo.getLabel().length() != 0;
    }

}
