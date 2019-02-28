/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
@SystemApi
public final class TelecomAnalytics implements Parcelable {
    public static final @android.annotation.NonNull Parcelable.Creator<TelecomAnalytics> CREATOR =
            new Parcelable.Creator<TelecomAnalytics> () {

                @Override
                public TelecomAnalytics createFromParcel(Parcel in) {
                    return new TelecomAnalytics(in);
                }

                @Override
                public TelecomAnalytics[] newArray(int size) {
                    return new TelecomAnalytics[size];
                }
            };

    public static final class SessionTiming extends TimedEvent<Integer> implements Parcelable {
        public static final @android.annotation.NonNull Parcelable.Creator<SessionTiming> CREATOR =
                new Parcelable.Creator<SessionTiming> () {

                    @Override
                    public SessionTiming createFromParcel(Parcel in) {
                        return new SessionTiming(in);
                    }

                    @Override
                    public SessionTiming[] newArray(int size) {
                        return new SessionTiming[size];
                    }
                };

        public static final int ICA_ANSWER_CALL = 1;
        public static final int ICA_REJECT_CALL = 2;
        public static final int ICA_DISCONNECT_CALL = 3;
        public static final int ICA_HOLD_CALL = 4;
        public static final int ICA_UNHOLD_CALL = 5;
        public static final int ICA_MUTE = 6;
        public static final int ICA_SET_AUDIO_ROUTE = 7;
        public static final int ICA_CONFERENCE = 8;

        public static final int CSW_HANDLE_CREATE_CONNECTION_COMPLETE = 100;
        public static final int CSW_SET_ACTIVE = 101;
        public static final int CSW_SET_RINGING = 102;
        public static final int CSW_SET_DIALING = 103;
        public static final int CSW_SET_DISCONNECTED = 104;
        public static final int CSW_SET_ON_HOLD = 105;
        public static final int CSW_REMOVE_CALL = 106;
        public static final int CSW_SET_IS_CONFERENCED = 107;
        public static final int CSW_ADD_CONFERENCE_CALL = 108;

        private int mId;
        private long mTime;

        public SessionTiming(int id, long time) {
            this.mId = id;
            this.mTime = time;
        }

        private SessionTiming(Parcel in) {
            mId = in.readInt();
            mTime = in.readLong();
        }

        @Override
        public Integer getKey() {
            return mId;
        }

        @Override
        public long getTime() {
            return mTime;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(mId);
            out.writeLong(mTime);
        }
    }

    private List<SessionTiming> mSessionTimings;
    private List<ParcelableCallAnalytics> mCallAnalytics;

    public TelecomAnalytics(List<SessionTiming> sessionTimings,
            List<ParcelableCallAnalytics> callAnalytics) {
        this.mSessionTimings = sessionTimings;
        this.mCallAnalytics = callAnalytics;
    }

    private TelecomAnalytics(Parcel in) {
        mSessionTimings = new ArrayList<>();
        in.readTypedList(mSessionTimings, SessionTiming.CREATOR);
        mCallAnalytics = new ArrayList<>();
        in.readTypedList(mCallAnalytics, ParcelableCallAnalytics.CREATOR);
    }

    public List<SessionTiming> getSessionTimings() {
        return mSessionTimings;
    }

    public List<ParcelableCallAnalytics> getCallAnalytics() {
        return mCallAnalytics;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(mSessionTimings);
        out.writeTypedList(mCallAnalytics);
    }
}
