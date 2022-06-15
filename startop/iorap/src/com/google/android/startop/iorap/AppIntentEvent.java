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
 * Notifications for iorapd specifying when a system-wide intent defaults change.<br /><br />
 *
 * Intent defaults provide a mechanism for an app to register itself as an automatic handler.
 * For example the camera app might be registered as the default handler for
 * {@link android.provider.MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA} intent. Subsequently,
 * if an arbitrary other app requests for a still image camera photo to be taken, the system
 * will launch the respective default camera app to be launched to handle that request.<br /><br />
 *
 * In some cases iorapd might need to know default intents, e.g. for boot-time pinning of
 * applications that resolve from the default intent. If the application would now be resolved
 * differently, iorapd would unpin the old application and pin the new application.<br /><br />
 *
 * @hide
 */
public class AppIntentEvent implements Parcelable {

    /** @see android.content.Intent#CATEGORY_DEFAULT */
    public static final int TYPE_DEFAULT_INTENT_CHANGED = 0;
    private static final int TYPE_MAX = 0;

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_DEFAULT_INTENT_CHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    @Type public final int type;

    public final ActivityInfo oldActivityInfo;
    public final ActivityInfo newActivityInfo;

    // TODO: Probably need the corresponding action here as well.

    public static AppIntentEvent createDefaultIntentChanged(ActivityInfo oldActivityInfo,
            ActivityInfo newActivityInfo) {
        return new AppIntentEvent(TYPE_DEFAULT_INTENT_CHANGED, oldActivityInfo,
                newActivityInfo);
    }

    private AppIntentEvent(@Type int type, ActivityInfo oldActivityInfo,
            ActivityInfo newActivityInfo) {
        this.type = type;
        this.oldActivityInfo = oldActivityInfo;
        this.newActivityInfo = newActivityInfo;

        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkTypeInRange(type, TYPE_MAX);
        Objects.requireNonNull(oldActivityInfo, "oldActivityInfo");
        Objects.requireNonNull(oldActivityInfo, "newActivityInfo");
    }

    @Override
    public String toString() {
        return String.format("{oldActivityInfo: %s, newActivityInfo: %s}", oldActivityInfo,
                newActivityInfo);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof AppIntentEvent) {
            return equals((AppIntentEvent) other);
        }
        return false;
    }

    private boolean equals(AppIntentEvent other) {
        return type == other.type &&
                Objects.equals(oldActivityInfo, other.oldActivityInfo) &&
                Objects.equals(newActivityInfo, other.newActivityInfo);
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        oldActivityInfo.writeToParcel(out, flags);
        newActivityInfo.writeToParcel(out, flags);
    }

    private AppIntentEvent(Parcel in) {
        this.type = in.readInt();
        this.oldActivityInfo = ActivityInfo.CREATOR.createFromParcel(in);
        this.newActivityInfo = ActivityInfo.CREATOR.createFromParcel(in);

        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<AppIntentEvent> CREATOR
            = new Parcelable.Creator<AppIntentEvent>() {
        public AppIntentEvent createFromParcel(Parcel in) {
            return new AppIntentEvent(in);
        }

        public AppIntentEvent[] newArray(int size) {
            return new AppIntentEvent[size];
        }
    };
    //</editor-fold>
}
