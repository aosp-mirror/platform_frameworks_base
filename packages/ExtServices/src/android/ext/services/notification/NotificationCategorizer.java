/**
 * Copyright (C) 2018 The Android Open Source Project
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
package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import android.annotation.IntDef;
import android.app.Notification;
import android.media.AudioAttributes;
import android.os.Process;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Default categorizer for incoming notifications; used to determine what notifications
 * should be silenced.
 */
// TODO: stop using @hide methods
public class NotificationCategorizer {

    protected static final int CATEGORY_MIN = -3;
    protected static final int CATEGORY_EVERYTHING_ELSE = -2;
    protected static final int CATEGORY_ONGOING = -1;
    protected static final int CATEGORY_SYSTEM_LOW = 0;
    protected static final int CATEGORY_EVENT = 1;
    protected static final int CATEGORY_REMINDER = 2;
    protected static final int CATEGORY_SYSTEM = 3;
    protected static final int CATEGORY_PEOPLE = 4;
    protected static final int CATEGORY_ALARM = 5;
    protected static final int CATEGORY_CALL = 6;
    protected static final int CATEGORY_HIGH = 7;

    /** @hide */
    @IntDef(prefix = { "CATEGORY_" }, value = {
            CATEGORY_MIN, CATEGORY_EVERYTHING_ELSE, CATEGORY_ONGOING, CATEGORY_CALL,
            CATEGORY_SYSTEM_LOW, CATEGORY_EVENT, CATEGORY_REMINDER, CATEGORY_SYSTEM,
            CATEGORY_PEOPLE, CATEGORY_ALARM, CATEGORY_HIGH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Category {}

    public boolean shouldSilence(NotificationEntry entry) {
        return shouldSilence(getCategory(entry));
    }

    @VisibleForTesting
    boolean shouldSilence(int category) {
        return category < CATEGORY_EVENT;
    }

    public int getCategory(NotificationEntry entry) {
        if (entry.getChannel() == null) {
            return CATEGORY_EVERYTHING_ELSE;
        }
        if (entry.getChannel().getImportance() == IMPORTANCE_MIN) {
            return CATEGORY_MIN;
        }
        if (entry.isCategory(Notification.CATEGORY_REMINDER)) {
            return CATEGORY_REMINDER;
        }
        if (entry.isCategory(Notification.CATEGORY_EVENT)) {
            return CATEGORY_EVENT;
        }
        if (entry.isCategory(Notification.CATEGORY_ALARM)
                || entry.isAudioAttributesUsage(AudioAttributes.USAGE_ALARM)) {
            return CATEGORY_ALARM;
        }
        // TODO: check for default phone app
        if (entry.isCategory(Notification.CATEGORY_CALL)) {
            return CATEGORY_CALL;
        }
        if (entry.involvesPeople()) {
            return CATEGORY_PEOPLE;
        }
        // TODO: is from signature app
        if (entry.getSbn().getUid() < Process.FIRST_APPLICATION_UID) {
            if (entry.getImportance() >= IMPORTANCE_DEFAULT) {
                return CATEGORY_SYSTEM;
            } else {
                return CATEGORY_SYSTEM_LOW;
            }
        }
        if (entry.getChannel().getImportance() == IMPORTANCE_HIGH) {
            return CATEGORY_HIGH;
        }
        if (entry.isOngoing()) {
            return CATEGORY_ONGOING;
        }
        return CATEGORY_EVERYTHING_ELSE;
    }
}
