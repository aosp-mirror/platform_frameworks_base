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

package com.android.server.people.data;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.text.format.DateFormat;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/** An event representing the interaction with a specific conversation or app. */
public class Event {

    public static final int TYPE_SHORTCUT_INVOCATION = 1;

    public static final int TYPE_NOTIFICATION_POSTED = 2;

    public static final int TYPE_NOTIFICATION_OPENED = 3;

    public static final int TYPE_SHARE_TEXT = 4;

    public static final int TYPE_SHARE_IMAGE = 5;

    public static final int TYPE_SHARE_VIDEO = 6;

    public static final int TYPE_SHARE_OTHER = 7;

    public static final int TYPE_SMS_OUTGOING = 8;

    public static final int TYPE_SMS_INCOMING = 9;

    public static final int TYPE_CALL_OUTGOING = 10;

    public static final int TYPE_CALL_INCOMING = 11;

    public static final int TYPE_CALL_MISSED = 12;

    public static final int TYPE_IN_APP_CONVERSATION = 13;

    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_SHORTCUT_INVOCATION,
            TYPE_NOTIFICATION_POSTED,
            TYPE_NOTIFICATION_OPENED,
            TYPE_SHARE_TEXT,
            TYPE_SHARE_IMAGE,
            TYPE_SHARE_VIDEO,
            TYPE_SHARE_OTHER,
            TYPE_SMS_OUTGOING,
            TYPE_SMS_INCOMING,
            TYPE_CALL_OUTGOING,
            TYPE_CALL_INCOMING,
            TYPE_CALL_MISSED,
            TYPE_IN_APP_CONVERSATION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}

    public static final Set<Integer> NOTIFICATION_EVENT_TYPES = new ArraySet<>();
    public static final Set<Integer> SHARE_EVENT_TYPES = new ArraySet<>();
    public static final Set<Integer> SMS_EVENT_TYPES = new ArraySet<>();
    public static final Set<Integer> CALL_EVENT_TYPES = new ArraySet<>();
    public static final Set<Integer> ALL_EVENT_TYPES = new ArraySet<>();

    static {
        NOTIFICATION_EVENT_TYPES.add(TYPE_NOTIFICATION_POSTED);
        NOTIFICATION_EVENT_TYPES.add(TYPE_NOTIFICATION_OPENED);

        SHARE_EVENT_TYPES.add(TYPE_SHARE_TEXT);
        SHARE_EVENT_TYPES.add(TYPE_SHARE_IMAGE);
        SHARE_EVENT_TYPES.add(TYPE_SHARE_VIDEO);
        SHARE_EVENT_TYPES.add(TYPE_SHARE_OTHER);

        SMS_EVENT_TYPES.add(TYPE_SMS_INCOMING);
        SMS_EVENT_TYPES.add(TYPE_SMS_OUTGOING);

        CALL_EVENT_TYPES.add(TYPE_CALL_INCOMING);
        CALL_EVENT_TYPES.add(TYPE_CALL_OUTGOING);
        CALL_EVENT_TYPES.add(TYPE_CALL_MISSED);

        ALL_EVENT_TYPES.add(TYPE_SHORTCUT_INVOCATION);
        ALL_EVENT_TYPES.add(TYPE_IN_APP_CONVERSATION);
        ALL_EVENT_TYPES.addAll(NOTIFICATION_EVENT_TYPES);
        ALL_EVENT_TYPES.addAll(SHARE_EVENT_TYPES);
        ALL_EVENT_TYPES.addAll(SMS_EVENT_TYPES);
        ALL_EVENT_TYPES.addAll(CALL_EVENT_TYPES);
    }

    private final long mTimestamp;

    private final int mType;

    private final int mDurationSeconds;

    Event(long timestamp, @EventType int type) {
        mTimestamp = timestamp;
        mType = type;
        mDurationSeconds = 0;
    }

    private Event(@NonNull Builder builder) {
        mTimestamp = builder.mTimestamp;
        mType = builder.mType;
        mDurationSeconds = builder.mDurationSeconds;
    }

    public long getTimestamp() {
        return mTimestamp;
    }

    public @EventType int getType() {
        return mType;
    }

    /**
     * Gets the duration of the event in seconds. It is only available for these events:
     * <ul>
     *     <li>{@link #TYPE_CALL_INCOMING}
     *     <li>{@link #TYPE_CALL_OUTGOING}
     *     <li>{@link #TYPE_IN_APP_CONVERSATION}
     * </ul>
     * <p>For the other event types, it always returns {@code 0}.
     */
    public int getDurationSeconds() {
        return mDurationSeconds;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Event)) {
            return false;
        }
        Event other = (Event) obj;
        return mTimestamp == other.mTimestamp
                && mType == other.mType
                && mDurationSeconds == other.mDurationSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTimestamp, mType, mDurationSeconds);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Event {");
        sb.append("timestamp=").append(DateFormat.format("yyyy-MM-dd HH:mm:ss", mTimestamp));
        sb.append(", type=").append(mType);
        if (mDurationSeconds > 0) {
            sb.append(", durationSeconds=").append(mDurationSeconds);
        }
        sb.append("}");
        return sb.toString();
    }

    /** Builder class for {@link Event} objects. */
    static class Builder {

        private final long mTimestamp;

        private final int mType;

        private int mDurationSeconds;

        Builder(long timestamp, @EventType int type) {
            mTimestamp = timestamp;
            mType = type;
        }

        Builder setDurationSeconds(int durationSeconds) {
            mDurationSeconds = durationSeconds;
            return this;
        }

        Event build() {
            return new Event(this);
        }
    }
}
