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
package android.app.search;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A representation of an search target event.
 *
 * There are two types of events. First type of event correspends to the user interaction
 * that happens on the search surface. (e.g., {@link #ACTION_TAP}. Second type of events
 * correspends to the lifecycle event of the search surface {@link #ACTION_SURFACE_VISIBLE}.
 *
 * @hide
 */
@SystemApi
public final class SearchTargetEvent implements Parcelable {

    /**
     * @hide
     */
    @IntDef(prefix = {"ACTION_"}, value = {
            ACTION_SURFACE_VISIBLE,
            ACTION_TAP,
            ACTION_LONGPRESS,
            ACTION_LAUNCH_TOUCH,
            ACTION_LAUNCH_KEYBOARD_FOCUS,
            ACTION_DRAGNDROP,
            ACTION_SURFACE_INVISIBLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActionType {}

    /**
     * @hide
     */
    @IntDef(prefix = {"FLAG_"}, value = {
            FLAG_IME_SHOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlagType {}

    @NonNull
    private final List<String> mTargetIds;
    @Nullable
    private final String mLocation;
    @ActionType
    private final int mAction;
    @FlagType
    private int mFlags;

    /**
     * IME was shown when event happened.
     */
    public static final int FLAG_IME_SHOWN = 1 << 0;


    /**
     * Search container was opened.
     */
    public static final int ACTION_SURFACE_VISIBLE = 1;

    /**
     * Constants that define tapping without closing the search surface.
     */
    public static final int ACTION_TAP = 3;

    /**
     * Constants that define long pressing without closing the search surface.
     */
    public static final int ACTION_LONGPRESS = 4;

    /**
     * Constants that define tapping on the touch target to launch.
     */
    public static final int ACTION_LAUNCH_TOUCH = 5;

    /**
     * Constants that define tapping on the soft keyboard confirm or search to launch.
     */
    public static final int ACTION_LAUNCH_KEYBOARD_FOCUS = 6;

    /**
     * Searcheable item was draged and dropped to another surface.
     */
    public static final int ACTION_DRAGNDROP = 7;

    /**
     * Search container was closed.
     */
    public static final int ACTION_SURFACE_INVISIBLE = 8;

    private SearchTargetEvent(@NonNull List<String> targetIds,
            @Nullable String location,
            @ActionType int actionType,
            @FlagType int flags) {
        mTargetIds = Objects.requireNonNull(targetIds);
        mLocation = location;
        mAction = actionType;
        mFlags = flags;
    }

    private SearchTargetEvent(Parcel parcel) {
        mTargetIds = new ArrayList<>();
        parcel.readStringList(mTargetIds);
        mLocation = parcel.readString();
        mAction = parcel.readInt();
        mFlags = parcel.readInt();
    }

    /**
     * Returns the primary search target with interaction.
     */
    @NonNull
    public String getTargetId() {
        return mTargetIds.get(0);
    }

    /**
     * Returns the list of search targets with visualization change.
     */
    @NonNull
    public List<String> getTargetIds() {
        return mTargetIds;
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
    @ActionType
    public int getAction() {
        return mAction;
    }

    public int getFlags() {
        return mFlags;
    }

    @Override
    public int hashCode() {
        return mTargetIds.get(0).hashCode() + mAction;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!getClass().equals(o != null ? o.getClass() : null)) return false;

        SearchTargetEvent other = (SearchTargetEvent) o;
        return mTargetIds.equals(other.mTargetIds)
                && mAction == other.mAction
                && mFlags == other.mFlags
                && mLocation == null ? other.mLocation == null : mLocation.equals(other.mLocation);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStringList(mTargetIds);
        dest.writeString(mLocation);
        dest.writeInt(mAction);
        dest.writeInt(mFlags);
    }

    public static final @NonNull Creator<SearchTargetEvent> CREATOR =
            new Creator<SearchTargetEvent>() {
                public SearchTargetEvent createFromParcel(Parcel parcel) {
                    return new SearchTargetEvent(parcel);
                }

                public SearchTargetEvent[] newArray(int size) {
                    return new SearchTargetEvent[size];
                }
            };

    /**
     * A builder for search target events.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        @NonNull
        private List<String> mTargetIds;
        @Nullable
        private String mLocation;
        @ActionType
        private int mAction;
        private int mFlags;

        /**
         * @param id The target id that is associated with this event.
         * @param actionType The event type
         */
        public Builder(@NonNull String id, @ActionType int actionType) {
            mTargetIds = new ArrayList<>();
            mTargetIds.add(id);
            mAction = actionType;
        }

        /**
         * @param ids The target ids that is associated with this event.
         * @param actionType The event type
         */
        public Builder(@NonNull List<String> ids, @ActionType int actionType) {
            mTargetIds = ids;
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
         * Sets the launch location.
         */
        @NonNull
        public Builder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Builds a new event instance.
         */
        @NonNull
        public SearchTargetEvent build() {
            return new SearchTargetEvent(mTargetIds, mLocation, mAction, mFlags);
        }
    }
}
