/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
/**
    @hide
 */
public final class TvRecordingInfo implements Parcelable {
    /*
     *   Indicates that getTvRecordingInfoList should return scheduled recordings.
     */
    public static final int RECORDING_SCHEDULED = 1;
    /*
     *   Indicates that getTvRecordingInfoList should return in-progress recordings.
     */
    public static final int RECORDING_IN_PROGRESS = 2;
    /*
     *   Indicates that getTvRecordingInfoList should return all recordings.
     */
    public static final int RECORDING_ALL = 3;
    /**
     *   Indicates which recordings should be returned by getTvRecordingList
     *   @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RECORDING_" }, value = {
            RECORDING_SCHEDULED,
            RECORDING_IN_PROGRESS,
            RECORDING_ALL,
    })
    public @interface TvRecordingListType {}

    private String mRecordingId;
    private int mStartPadding;
    private int mEndPadding;
    private int mRepeatDays;
    private String mName;
    private String mDescription;
    private int mScheduledStartTime;
    private int mScheduledDuration;
    private Uri mChannelUri;
    private Uri mProgramId;
    private List<String> mParentalRatings;
    private String mRecordingUri;
    private int mRecordingStartTime;
    private int mRecordingDuration;

    public TvRecordingInfo(
            @NonNull String recordingId, @NonNull int startPadding, @NonNull int endPadding,
            @NonNull int repeatDays, @NonNull int scheduledStartTime,
            @NonNull int scheduledDuration, @NonNull Uri channelUri, @Nullable Uri programId,
            @NonNull List<String> parentalRatings, @NonNull String recordingUri,
            @NonNull int recordingStartTime, @NonNull int recordingDuration) {
        mRecordingId = recordingId;
        mStartPadding = startPadding;
        mEndPadding = endPadding;
        mRepeatDays = repeatDays;
        mScheduledStartTime = scheduledStartTime;
        mScheduledDuration = scheduledDuration;
        mChannelUri = channelUri;
        mScheduledDuration = scheduledDuration;
        mChannelUri = channelUri;
        mProgramId = programId;
        mParentalRatings = parentalRatings;
        mRecordingUri = recordingUri;
        mRecordingStartTime = recordingStartTime;
        mRecordingDuration = recordingDuration;
    }
    @NonNull
    public String getRecordingId() {
        return mRecordingId;
    }
    @NonNull
    public int getStartPadding() {
        return mStartPadding;
    }
    @NonNull
    public int getEndPadding() {
        return mEndPadding;
    }
    @NonNull
    public int getRepeatDays() {
        return mRepeatDays;
    }
    @NonNull
    public String getName() {
        return mName;
    }
    @NonNull
    public void setName(String name) {
        mName = name;
    }
    @NonNull
    public String getDescription() {
        return mDescription;
    }
    @NonNull
    public void setDescription(String description) {
        mDescription = description;
    }
    @NonNull
    public int getScheduledStartTime() {
        return mScheduledStartTime;
    }
    @NonNull
    public int getScheduledDuration() {
        return mScheduledDuration;
    }
    @NonNull
    public Uri getChannelUri() {
        return mChannelUri;
    }
    @Nullable
    public Uri getProgramId() {
        return mProgramId;
    }
    @NonNull
    public List<String> getParentalRatings() {
        return mParentalRatings;
    }
    @NonNull
    public String getRecordingUri() {
        return mRecordingUri;
    }
    @NonNull
    public int getRecordingStartTime() {
        return mRecordingStartTime;
    }
    @NonNull
    public int getRecordingDuration() {
        return mRecordingDuration;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mRecordingId);
        dest.writeInt(mStartPadding);
        dest.writeInt(mEndPadding);
        dest.writeInt(mRepeatDays);
        dest.writeString(mName);
        dest.writeString(mDescription);
        dest.writeInt(mScheduledStartTime);
        dest.writeInt(mScheduledDuration);
        dest.writeString(mChannelUri == null ? null : mChannelUri.toString());
        dest.writeString(mProgramId == null ? null : mProgramId.toString());
        dest.writeStringList(mParentalRatings);
        dest.writeString(mRecordingUri);
        dest.writeInt(mRecordingDuration);
        dest.writeInt(mRecordingStartTime);
    }

    private TvRecordingInfo(Parcel in) {
        mRecordingId = in.readString();
        mStartPadding = in.readInt();
        mEndPadding = in.readInt();
        mRepeatDays = in.readInt();
        mName = in.readString();
        mDescription = in.readString();
        mScheduledStartTime = in.readInt();
        mScheduledDuration = in.readInt();
        mChannelUri = Uri.parse(in.readString());
        mProgramId = Uri.parse(in.readString());
        in.readStringList(mParentalRatings);
        mRecordingUri = in.readString();
        mRecordingDuration = in.readInt();
        mRecordingStartTime = in.readInt();
    }


    public static final @android.annotation.NonNull Parcelable.Creator<TvRecordingInfo> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                @NonNull
                public TvRecordingInfo createFromParcel(Parcel in) {
                    return new TvRecordingInfo(in);
                }

                @Override
                @NonNull
                public TvRecordingInfo[] newArray(int size) {
                    return new TvRecordingInfo[size];
                }
            };
}
