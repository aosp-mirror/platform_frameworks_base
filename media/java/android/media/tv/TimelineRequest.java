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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A request for Timeline from broadcast signal.
 */
public final class TimelineRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_TIMELINE;

    public static final @NonNull Parcelable.Creator<TimelineRequest> CREATOR =
            new Parcelable.Creator<TimelineRequest>() {
                @Override
                public TimelineRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TimelineRequest[] newArray(int size) {
                    return new TimelineRequest[size];
                }
            };

    private final int mIntervalMillis;

    static TimelineRequest createFromParcelBody(Parcel in) {
        return new TimelineRequest(in);
    }

    public TimelineRequest(int requestId, @RequestOption int option, int intervalMillis) {
        super(REQUEST_TYPE, requestId, option);
        mIntervalMillis = intervalMillis;
    }

    TimelineRequest(Parcel source) {
        super(REQUEST_TYPE, source);
        mIntervalMillis = source.readInt();
    }

    /**
     * Gets the interval of TIS sending response to TIAS in millisecond.
     */
    public int getIntervalMillis() {
        return mIntervalMillis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mIntervalMillis);
    }
}
