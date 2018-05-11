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
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

public final class ChannelImpressions implements Parcelable {
    private static final String TAG = "ExtAssistant.CI";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final float DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT = .8f;
    static final int DEFAULT_STREAK_LIMIT = 2;
    static final String ATT_DISMISSALS = "dismisses";
    static final String ATT_VIEWS = "views";
    static final String ATT_STREAK = "streak";

    private int mDismissals = 0;
    private int mViews = 0;
    private int mStreak = 0;

    private float mDismissToViewRatioLimit;
    private int mStreakLimit;

    public ChannelImpressions() {
        mDismissToViewRatioLimit = DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT;
        mStreakLimit = DEFAULT_STREAK_LIMIT;
    }

    protected ChannelImpressions(Parcel in) {
        mDismissals = in.readInt();
        mViews = in.readInt();
        mStreak = in.readInt();
        mDismissToViewRatioLimit = in.readFloat();
        mStreakLimit = in.readInt();
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

    void updateThresholds(float dismissToViewRatioLimit, int streakLimit) {
        mDismissToViewRatioLimit = dismissToViewRatioLimit;
        mStreakLimit = streakLimit;
    }

    @VisibleForTesting
    float getDismissToViewRatioLimit() {
        return mDismissToViewRatioLimit;
    }

    @VisibleForTesting
    int getStreakLimit() {
        return mStreakLimit;
    }

    public void append(ChannelImpressions additionalImpressions) {
        if (additionalImpressions != null) {
            mViews += additionalImpressions.getViews();
            mStreak += additionalImpressions.getStreak();
            mDismissals += additionalImpressions.getDismissals();
        }
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
        return ((float) getDismissals() / getViews()) > mDismissToViewRatioLimit
                && getStreak() > mStreakLimit;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDismissals);
        dest.writeInt(mViews);
        dest.writeInt(mStreak);
        dest.writeFloat(mDismissToViewRatioLimit);
        dest.writeInt(mStreakLimit);
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
        sb.append(", thresholds=(").append(mDismissToViewRatioLimit);
        sb.append(",").append(mStreakLimit);
        sb.append(")}");
        return sb.toString();
    }

    protected void populateFromXml(XmlPullParser parser) {
        mDismissals = safeInt(parser, ATT_DISMISSALS, 0);
        mStreak = safeInt(parser, ATT_STREAK, 0);
        mViews = safeInt(parser, ATT_VIEWS, 0);
    }

    protected void writeXml(XmlSerializer out) throws IOException {
        if (mDismissals != 0) {
            out.attribute(null, ATT_DISMISSALS, String.valueOf(mDismissals));
        }
        if (mStreak != 0) {
            out.attribute(null, ATT_STREAK, String.valueOf(mStreak));
        }
        if (mViews != 0) {
            out.attribute(null, ATT_VIEWS, String.valueOf(mViews));
        }
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseInt(val, defValue);
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }
}
