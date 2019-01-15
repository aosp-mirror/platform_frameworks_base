/*
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

package com.google.android.startop.iorap;

import android.os.Parcelable;
import android.os.Parcel;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Provide a hint to iorapd that an activity has transitioned state.<br /><br />
 *
 * Knowledge of when an activity starts/stops can be used by iorapd to increase system
 * performance (e.g. by launching perfetto tracing to record an io profile, or by
 * playing back an ioprofile via readahead) over the long run.<br /><br />
 *
 * /@see com.google.android.startop.iorap.IIorap#onActivityHintEvent<br /><br />
 *
 * Once an activity hint is in {@link #TYPE_STARTED} it must transition to another type.
 * All other states could be terminal, see below: <br /><br />
 *
 * <pre>
 *
 *          ┌──────────────────────────────────────┐
 *          │                                      ▼
 *        ┌─────────┐     ╔════════════════╗     ╔═══════════╗
 *    ──▶ │ STARTED │ ──▶ ║   COMPLETED    ║ ──▶ ║ CANCELLED ║
 *        └─────────┘     ╚════════════════╝     ╚═══════════╝
 *                          │
 *                          │
 *                          ▼
 *                        ╔════════════════╗
 *                        ║ POST_COMPLETED ║
 *                        ╚════════════════╝
 *
 * </pre> <!-- system/iorap/docs/binder/ActivityHint.dot -->
 *
 * @hide
 */
public class ActivityHintEvent implements Parcelable {

    public static final int TYPE_STARTED = 0;
    public static final int TYPE_CANCELLED = 1;
    public static final int TYPE_COMPLETED = 2;
    public static final int TYPE_POST_COMPLETED = 3;
    private static final int TYPE_MAX = TYPE_POST_COMPLETED;

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_STARTED,
            TYPE_CANCELLED,
            TYPE_COMPLETED,
            TYPE_POST_COMPLETED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    @Type public final int type;
    public final ActivityInfo activityInfo;

    public ActivityHintEvent(@Type int type, ActivityInfo activityInfo) {
        this.type = type;
        this.activityInfo = activityInfo;
        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkTypeInRange(type, TYPE_MAX);
        Objects.requireNonNull(activityInfo, "activityInfo");
    }

    @Override
    public String toString() {
        return String.format("{type: %d, activityInfo: %s}", type, activityInfo);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof ActivityHintEvent) {
            return equals((ActivityHintEvent) other);
        }
        return false;
    }

    private boolean equals(ActivityHintEvent other) {
        return type == other.type &&
                Objects.equals(activityInfo, other.activityInfo);
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        activityInfo.writeToParcel(out, flags);
    }

    private ActivityHintEvent(Parcel in) {
        this.type = in.readInt();
        this.activityInfo = ActivityInfo.CREATOR.createFromParcel(in);
        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ActivityHintEvent> CREATOR
            = new Parcelable.Creator<ActivityHintEvent>() {
        public ActivityHintEvent createFromParcel(Parcel in) {
            return new ActivityHintEvent(in);
        }

        public ActivityHintEvent[] newArray(int size) {
            return new ActivityHintEvent[size];
        }
    };
    //</editor-fold>
}
