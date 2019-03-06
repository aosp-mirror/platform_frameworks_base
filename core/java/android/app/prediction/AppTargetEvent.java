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
package android.app.prediction;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A representation of an app target event.
 * @hide
 */
@SystemApi
@TestApi
public final class AppTargetEvent implements Parcelable {

    /**
     * @hide
     */
    @IntDef({ACTION_LAUNCH, ACTION_DISMISS, ACTION_PIN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActionType {}

    /**
     * Event type constant indicating an app target has been launched.
     */
    public static final int ACTION_LAUNCH = 1;

    /**
     * Event type constant indicating an app target has been dismissed.
     */
    public static final int ACTION_DISMISS = 2;

    /**
     * Event type constant indicating an app target has been pinned.
     */
    public static final int ACTION_PIN = 3;

    private final AppTarget mTarget;
    private final String mLocation;
    private final int mAction;

    private AppTargetEvent(@Nullable AppTarget target, @Nullable String location,
            @ActionType int actionType) {
        mTarget = target;
        mLocation = location;
        mAction = actionType;
    }

    private AppTargetEvent(Parcel parcel) {
        mTarget = parcel.readParcelable(null);
        mLocation = parcel.readString();
        mAction = parcel.readInt();
    }

    /**
     * Returns the app target.
     */
    @Nullable
    public AppTarget getTarget() {
        return mTarget;
    }

    /**
     * Returns the launch location.
     */
    @Nullable
    public String getLaunchLocation() {
        return mLocation;
    }

    /**
     * Returns the action type.
     */
    public @ActionType int getAction() {
        return mAction;
    }

    @Override
    public boolean equals(Object o) {
        if (!getClass().equals(o != null ? o.getClass() : null)) return false;

        AppTargetEvent other = (AppTargetEvent) o;
        return mTarget.equals(other.mTarget)
                && mLocation.equals(other.mLocation)
                && mAction == other.mAction;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mTarget, 0);
        dest.writeString(mLocation);
        dest.writeInt(mAction);
    }

    /**
     * @see Creator
     */
    public static final @android.annotation.NonNull Creator<AppTargetEvent> CREATOR =
            new Creator<AppTargetEvent>() {
                public AppTargetEvent createFromParcel(Parcel parcel) {
                    return new AppTargetEvent(parcel);
                }

                public AppTargetEvent[] newArray(int size) {
                    return new AppTargetEvent[size];
                }
            };

    /**
     * A builder for app target events.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final class Builder {
        private AppTarget mTarget;
        private String mLocation;
        private @ActionType int mAction;

        public Builder(@Nullable AppTarget target, @ActionType int actionType) {
            mTarget = target;
            mAction = actionType;
        }

        /**
         * Sets the launch location.
         */
        @NonNull
        public Builder setLaunchLocation(@Nullable String location) {
            mLocation = location;
            return this;
        }

        /**
         * Builds a new event instance.
         */
        @NonNull
        public AppTargetEvent build() {
            return new AppTargetEvent(mTarget, mLocation, mAction);
        }
    }
}
