/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A response for Timeline from broadcast signal.
 */
public final class TimelineResponse extends BroadcastInfoResponse implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_TIMELINE;

    public static final @NonNull Parcelable.Creator<TimelineResponse> CREATOR =
            new Parcelable.Creator<TimelineResponse>() {
                @Override
                public TimelineResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TimelineResponse[] newArray(int size) {
                    return new TimelineResponse[size];
                }
            };

    private final String mSelector;
    private final int mUnitsPerTick;
    private final int mUnitsPerSecond;
    private final long mWallClock;
    private final long mTicks;

    static TimelineResponse createFromParcelBody(Parcel in) {
        return new TimelineResponse(in);
    }

    public TimelineResponse(int requestId, int sequence,
            @ResponseResult int responseResult, @Nullable String selector, int unitsPerTick,
            int unitsPerSecond, long wallClock, long ticks) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mSelector = selector;
        mUnitsPerTick = unitsPerTick;
        mUnitsPerSecond = unitsPerSecond;
        mWallClock = wallClock;
        mTicks = ticks;
    }

    TimelineResponse(Parcel source) {
        super(RESPONSE_TYPE, source);
        mSelector = source.readString();
        mUnitsPerTick = source.readInt();
        mUnitsPerSecond = source.readInt();
        mWallClock = source.readLong();
        mTicks = source.readLong();
    }

    /**
     * Gets the Timeline Selector of the response.
     * The Timeline Selector is a URI that specifies the source of a Timeline
     * by indicating its type and information needed to locate the signalling
     * that conveys Time Values on it.
     */
    @Nullable
    public Uri getSelector() {
        return Uri.parse(mSelector);
    }

    /**
     * Gets the UnitsPerTick of the response.
     */
    public int getUnitsPerTick() {
        return mUnitsPerTick;
    }

    /**
     * Gets the UnitsPerSecond of the response.
     */
    public int getUnitsPerSecond() {
        return mUnitsPerSecond;
    }

    /**
     * Gets the System time (UTC) of the response.
     */
    public long getWallClock() {
        return mWallClock;
    }

    /**
     * Gets the Ticks of the response.
     * A Time Value is a measure of a moment in time for a particular Timeline.
     * Time Values are represented by an integer number of ticks (positive or negative).
     */
    public long getTicks() {
        return mTicks;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mSelector);
        dest.writeInt(mUnitsPerTick);
        dest.writeInt(mUnitsPerSecond);
        dest.writeLong(mWallClock);
        dest.writeLong(mTicks);
    }
}
