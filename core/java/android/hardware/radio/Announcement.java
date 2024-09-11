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

package android.hardware.radio;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * @hide
 */
@SystemApi
public final class Announcement implements Parcelable {

    /** DAB alarm, RDS emergency program type (PTY 31). */
    public static final int TYPE_EMERGENCY = 1;
    /** DAB warning. */
    public static final int TYPE_WARNING = 2;
    /** DAB road traffic, RDS TA, HD Radio transportation. */
    public static final int TYPE_TRAFFIC = 3;
    /** Weather. */
    public static final int TYPE_WEATHER = 4;
    /** News. */
    public static final int TYPE_NEWS = 5;
    /** DAB event, special event. */
    public static final int TYPE_EVENT = 6;
    /** DAB sport report, RDS sports. */
    public static final int TYPE_SPORT = 7;
    /** All others. */
    public static final int TYPE_MISC = 8;
    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
        TYPE_EMERGENCY,
        TYPE_WARNING,
        TYPE_TRAFFIC,
        TYPE_WEATHER,
        TYPE_NEWS,
        TYPE_EVENT,
        TYPE_SPORT,
        TYPE_MISC,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Listener of announcement list events.
     */
    public interface OnListUpdatedListener {
        /**
         * An event called whenever a list of active announcements change.
         *
         * <p>The entire list is sent each time a new announcement appears or any ends broadcasting.
         *
         * @param activeAnnouncements a full list of active announcements
         */
        void onListUpdated(Collection<Announcement> activeAnnouncements);
    }

    @NonNull private final ProgramSelector mSelector;
    @Type private final int mType;
    @NonNull private final Map<String, String> mVendorInfo;

    /** @hide */
    public Announcement(@NonNull ProgramSelector selector, @Type int type,
            @NonNull Map<String, String> vendorInfo) {
        mSelector = Objects.requireNonNull(selector, "Program selector cannot be null");
        mType = type;
        mVendorInfo = Objects.requireNonNull(vendorInfo, "Vendor info cannot be null");
    }

    private Announcement(@NonNull Parcel in) {
        mSelector = in.readTypedObject(ProgramSelector.CREATOR);
        mType = in.readInt();
        mVendorInfo = Utils.readStringMap(in);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mSelector, 0);
        dest.writeInt(mType);
        Utils.writeStringMap(dest, mVendorInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Announcement> CREATOR =
            new Parcelable.Creator<Announcement>() {
        public Announcement createFromParcel(Parcel in) {
            return new Announcement(in);
        }

        public Announcement[] newArray(int size) {
            return new Announcement[size];
        }
    };

    public @NonNull ProgramSelector getSelector() {
        return mSelector;
    }

    public @Type int getType() {
        return mType;
    }

    public @NonNull Map<String, String> getVendorInfo() {
        return mVendorInfo;
    }
}
