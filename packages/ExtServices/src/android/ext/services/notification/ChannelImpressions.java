/**
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

public final class ChannelImpressions implements Parcelable {
    private static final String TAG = "ExtAssistant.CI";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final double DISMISS_TO_VIEW_RATIO_LIMIT = .8;
    static final int STREAK_LIMIT = 2;

    private int mDismissals = 0;
    private int mViews = 0;
    private int mStreak = 0;

    public ChannelImpressions() {
    }

    public ChannelImpressions(int dismissals, int views) {
        mDismissals = dismissals;
        mViews = views;
    }

    protected ChannelImpressions(Parcel in) {
        mDismissals = in.readInt();
        mViews = in.readInt();
        mStreak = in.readInt();
    }

    public int getStreak() {
        return mStreak;
    }

    public int getDismissals() {
        return mDismissals;
    }

    public int getViews() {
        return mViews;
    }

    public void incrementDismissals() {
        mDismissals++;
        mStreak++;
    }

    public void incrementViews() {
        mViews++;
    }

    public void resetStreak() {
        mStreak = 0;
    }

    public boolean shouldTriggerBlock() {
        if (getViews() == 0) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, "should trigger? " + getDismissals() + " " + getViews() + " " + getStreak());
        }
        return ((double) getDismissals() / getViews()) > DISMISS_TO_VIEW_RATIO_LIMIT
                && getStreak() > STREAK_LIMIT;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDismissals);
        dest.writeInt(mViews);
        dest.writeInt(mStreak);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ChannelImpressions> CREATOR = new Creator<ChannelImpressions>() {
        @Override
        public ChannelImpressions createFromParcel(Parcel in) {
            return new ChannelImpressions(in);
        }

        @Override
        public ChannelImpressions[] newArray(int size) {
            return new ChannelImpressions[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ChannelImpressions that = (ChannelImpressions) o;

        if (mDismissals != that.mDismissals) return false;
        if (mViews != that.mViews) return false;
        return mStreak == that.mStreak;
    }

    @Override
    public int hashCode() {
        int result = mDismissals;
        result = 31 * result + mViews;
        result = 31 * result + mStreak;
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ChannelImpressions{");
        sb.append("mDismissals=").append(mDismissals);
        sb.append(", mViews=").append(mViews);
        sb.append(", mStreak=").append(mStreak);
        sb.append('}');
        return sb.toString();
    }
}
