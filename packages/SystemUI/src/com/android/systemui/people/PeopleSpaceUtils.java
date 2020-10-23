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
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Utils class for People Space. */
public class PeopleSpaceUtils {
    private static final String TAG = "PeopleSpaceUtils";

    /** Turns on debugging information about People Space. */
    public static final boolean DEBUG = true;

    /** Returns a list of {@link ShortcutInfo} corresponding to user's conversations. */
    public static List<ShortcutInfo> getShortcutInfos(
            Context context,
            INotificationManager notificationManager,
            IPeopleManager peopleManager
    ) throws Exception {
        boolean showAllConversations = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 0;
        List<ConversationChannelWrapper> conversations =
                notificationManager.getConversations(
                        !showAllConversations /* priority only */).getList();
        List<ShortcutInfo> shortcutInfos = conversations.stream().filter(
                c -> shouldKeepConversation(c)).map(
                    c -> c.getShortcutInfo()).collect(Collectors.toList());
        if (showAllConversations) {
            List<ConversationChannel> recentConversations =
                    peopleManager.getRecentConversations().getList();
            List<ShortcutInfo> recentShortcuts = recentConversations.stream().map(
                    c -> c.getShortcutInfo()).collect(Collectors.toList());
            shortcutInfos.addAll(recentShortcuts);
        }
        return shortcutInfos;
    }

    /** Converts {@code drawable} to a {@link Bitmap}. */
    public static Bitmap convertDrawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        // We use max below because the drawable might have no intrinsic width/height (e.g. if the
        // drawable is a solid color).
        Bitmap bitmap =
                Bitmap.createBitmap(
                        Math.max(drawable.getIntrinsicWidth(), 1),
                        Math.max(drawable.getIntrinsicHeight(), 1),
                        Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /** Returns a readable representation of {@code lastInteraction}. */
    public static String getLastInteractionString(long lastInteraction) {
        long now = System.currentTimeMillis();
        Duration durationSinceLastInteraction = Duration.ofMillis(
                now - lastInteraction);
        MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.WIDE);
        if (durationSinceLastInteraction.toDays() >= 1) {
            return
                    formatter
                            .formatMeasures(new Measure(durationSinceLastInteraction.toDays(),
                                    MeasureUnit.DAY));
        } else if (durationSinceLastInteraction.toHours() >= 1) {
            return formatter.formatMeasures(new Measure(durationSinceLastInteraction.toHours(),
                    MeasureUnit.HOUR));
        } else if (durationSinceLastInteraction.toMinutes() >= 1) {
            return formatter.formatMeasures(new Measure(durationSinceLastInteraction.toMinutes(),
                    MeasureUnit.MINUTE));
        } else {
            return formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toMillis() / 1000,
                            MeasureUnit.SECOND));
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
    public static boolean shouldKeepConversation(ConversationChannelWrapper conversation) {
        ShortcutInfo shortcutInfo = conversation.getShortcutInfo();
        return shortcutInfo != null && shortcutInfo.getLabel().length() != 0;
    }

}
