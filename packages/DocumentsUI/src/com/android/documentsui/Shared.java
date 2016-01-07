/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Time;

import java.util.ArrayList;
import java.util.List;

/** @hide */
public final class Shared {
    /** Intent action name to pick a copy destination. */
    public static final String ACTION_PICK_COPY_DESTINATION =
            "com.android.documentsui.PICK_COPY_DESTINATION";

    /**
     * Extra boolean flag for {@link ACTION_PICK_COPY_DESTINATION}, which
     * specifies if the destination directory needs to create new directory or not.
     */
    public static final String EXTRA_DIRECTORY_COPY = "com.android.documentsui.DIRECTORY_COPY";

    public static final boolean DEBUG = true;
    public static final String TAG = "Documents";
    public static final String EXTRA_STACK = "com.android.documentsui.STACK";

    /**
     * Generates a formatted quantity string.
     */
    public static final String getQuantityString(Context context, int resourceId, int quantity) {
        return context.getResources().getQuantityString(resourceId, quantity, quantity);
    }

    public static String formatTime(Context context, long when) {
        // TODO: DateUtils should make this easier
        Time then = new Time();
        then.set(when);
        Time now = new Time();
        now.setToNow();

        int flags = DateUtils.FORMAT_NO_NOON | DateUtils.FORMAT_NO_MIDNIGHT
                | DateUtils.FORMAT_ABBREV_ALL;

        if (then.year != now.year) {
            flags |= DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE;
        } else if (then.yearDay != now.yearDay) {
            flags |= DateUtils.FORMAT_SHOW_DATE;
        } else {
            flags |= DateUtils.FORMAT_SHOW_TIME;
        }

        return DateUtils.formatDateTime(context, when, flags);
    }

    /**
     * A convenient way to transform any list into a (parcelable) ArrayList.
     * Uses cast if possible, else creates a new list with entries from {@code list}.
     */
    public static <T> ArrayList<T> asArrayList(List<T> list) {
        return list instanceof ArrayList
            ? (ArrayList<T>) list
            : new ArrayList<T>(list);
    }
}
