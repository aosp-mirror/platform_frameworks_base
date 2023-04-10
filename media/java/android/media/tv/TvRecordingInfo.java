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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to describe the meta information for a TV recording. It can be retrieved by
 * the {@link android.media.tv.interactive.TvInteractiveAppService} by using getTvRecordingInfo
 * or getTvRecordingInfoList. It can then be updated to the TV app using setTvRecordingInfo.
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

    public static final int SUNDAY = 1;
    public static final int MONDAY = 1 << 1;
    public static final int TUESDAY = 1 << 2;
    public static final int WEDNESDAY = 1 << 3;
    public static final int THURSDAY = 1 << 4;
    public static final int FRIDAY = 1 << 5;
    public static final int SATURDAY = 1 << 6;

    /**
     * The days of the week defined in {@link java.time.DayOfWeek} are not used here because they
     * map to integers that increment by 1. The intended use case in {@link #getRepeatDays()} uses
     * a bitfield, which requires integers that map to 2^n.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY})
    public @interface DaysOfWeek {}

    private String mRecordingId;
    private long mStartPaddingMillis;
    private long mEndPaddingMillis;
    private int mRepeatDays;
    private String mName;
    private String mDescription;
    private long mScheduledStartTimeMillis;
    private long mScheduledDurationMillis;
    private Uri mChannelUri;
    private Uri mProgramUri;
    private List<TvContentRating> mContentRatings;
    private Uri mRecordingUri;
    private long mRecordingStartTimeMillis;
    private long mRecordingDurationMillis;

    public TvRecordingInfo(
            @NonNull String recordingId, long startPadding, long endPadding, int repeatDays,
            @NonNull String name, @NonNull String description, long scheduledStartTime,
            long scheduledDuration, @NonNull Uri channelUri, @Nullable Uri programUri,
            @NonNull List<TvContentRating> contentRatings, @Nullable Uri recordingUri,
            long recordingStartTime, long recordingDuration) {
        mRecordingId = recordingId;
        mStartPaddingMillis = startPadding;
        mEndPaddingMillis = endPadding;
        mRepeatDays = repeatDays;
        mName = name;
        mDescription = description;
        mScheduledStartTimeMillis = scheduledStartTime;
        mScheduledDurationMillis = scheduledDuration;
        mChannelUri = channelUri;
        mProgramUri = programUri;
        mContentRatings = contentRatings;
        mRecordingUri = recordingUri;
        mRecordingStartTimeMillis = recordingStartTime;
        mRecordingDurationMillis = recordingDuration;
    }

    /**
     * Returns the ID of this recording. This ID is created and maintained by the TV app.
     */
    @NonNull
    public String getRecordingId() {
        return mRecordingId;
    }

    /**
     * Returns the start padding duration of this recording in milliseconds since the epoch.
     *
     * <p> A positive value should cause the recording to start earlier than the specified time.
     * This should cause the actual duration of the recording to increase. A negative value should
     * cause the recording to start later than the specified time. This should cause the actual
     * duration of the recording to decrease.
     */
    public long getStartPaddingMillis() {
        return mStartPaddingMillis;
    }

    /**
     * Returns the ending padding duration of this recording in milliseconds since the epoch.
     *
     * <p> A positive value should cause the recording to end later than the specified time.
     * This should cause the actual duration of the recording to increase. A negative value should
     * cause the recording to end earlier than the specified time. This should cause the actual
     * duration of the recording to decrease.
     */
    public long getEndPaddingMillis() {
        return mEndPaddingMillis;
    }

    /**
     * Returns the days of the week for which this recording should be repeated for.
     *
     * <p> This information is represented in the form of a bitfield, with each bit
     * representing the day which the recording should be repeated.
     *
     * <p> The bitfield corresponds to each day of the week with the following format:
     * <ul>
     * <li>{@link #SUNDAY}    - 0x01 (00000001)</li>
     * <li>{@link #MONDAY}    - 0x02 (00000010)</li>
     * <li>{@link #TUESDAY}   - 0x04 (00000100)</li>
     * <li>{@link #WEDNESDAY} - 0x08 (00001000)</li>
     * <li>{@link #THURSDAY}  - 0x10 (00010000)</li>
     * <li>{@link #FRIDAY}    - 0x20 (00100000)</li>
     * <li>{@link #SATURDAY}  - 0x40 (01000000)</li>
     * </ul>
     *
     * <p> You can specify multiple days to repeat the recording by performing a bitwise 'OR' on the
     * bitfield. For example, for a recording to repeat on Sunday and Mondays, this function should
     * return 0x03 (00000011).
     *
     * <p> A value of 0x00 indicates that the recording will not be repeated.
     *
     * <p> This format comes from the <a href="
     * https://www.oipf.tv/docs/OIPF-T1-R2_Specification-Volume-5-Declarative-Application-Environment-v2_3-2014-01-24.pdf
     * ">Open IPTV Forum Release 2 Specification</a>. It is described in Volume 5, section 7.10.1.1.
     */
    @DaysOfWeek
    public int getRepeatDays() {
        return mRepeatDays;
    }

    /**
     * Returns the name of the scheduled recording.
     *
     * <p> This is set with {@link TvRecordingInfo#setName(String)} and sent to tv app with
     * {@link android.media.tv.interactive.TvInteractiveAppService.Session#setTvRecordingInfo(String, TvRecordingInfo)}
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Sets the name of the scheduled recording.
     *
     * <p> Updates to the {@link TvRecordingInfo} can be sent to the TV app with
     * {@link android.media.tv.interactive.TvInteractiveAppService.Session#setTvRecordingInfo(String, TvRecordingInfo)}
     */
    public void setName(@NonNull String name) {
        mName = name;
    }

    /**
     * Returns the description of the scheduled recording.
     *
     * <p> This is set with {@link TvRecordingInfo#setDescription(String)} and sent to tv app with
     * {@link android.media.tv.interactive.TvInteractiveAppService.Session#setTvRecordingInfo(String, TvRecordingInfo)}
     */
    @NonNull
    public String getDescription() {
        return mDescription;
    }

    /**
     * Sets the description of the scheduled recording.
     *
     * <p> Updates to the {@link TvRecordingInfo} can be sent to the TV app with
     * {@link android.media.tv.interactive.TvInteractiveAppService.Session#setTvRecordingInfo(String, TvRecordingInfo)}
     */
    public void setDescription(@NonNull String description) {
        mDescription = description;
    }

    /**
     * Returns the scheduled start time of the recording in milliseconds since the epoch.
     */
    @IntRange(from = 0)
    public long getScheduledStartTimeMillis() {
        return mScheduledStartTimeMillis;
    }

    /**
     * Returns the scheduled duration of the recording in milliseconds since the epoch.
     */
    @IntRange(from = 0)
    public long getScheduledDurationMillis() {
        return mScheduledDurationMillis;
    }

    /**
     * Returns the uri of the broadcast channel where the recording will take place.
     */
    @NonNull
    public Uri getChannelUri() {
        return mChannelUri;
    }

    /**
     * Returns the uri of the scheduled program or series.
     *
     * <p> For recordings scheduled using scheduleRecording, this value may be null. A non-null
     * programUri implies the started recording should be of that specific program, whereas a null
     * programUri does not impose such a requirement and the recording can span across
     * multiple TV programs.
     */
    @Nullable
    public Uri getProgramUri() {
        return mProgramUri;
    }

    /**
     * Returns a list of content ratings for the program(s) in this recording.
     *
     * <p> Returns an empty list if no content rating information is available.
     */
    @NonNull
    public List<TvContentRating> getContentRatings() {
        return mContentRatings;
    }

    /**
     * The uri of the recording in local storage.
     *
     * <p> Could be null in the event that the recording has not been completed.
     */
    @Nullable
    public Uri getRecordingUri() {
        return mRecordingUri;
    }

    /**
     * The real start time of the recording, including any padding, in milliseconds since the epoch.
     *
     * <p> This may not be the same as the value of {@link #getScheduledStartTimeMillis()} due to a
     * recording starting late, or due to start/end padding.
     *
     * <p> Returns -1 for recordings that have not yet started.
     */
    @IntRange(from = -1)
    public long getRecordingStartTimeMillis() {
        return mRecordingStartTimeMillis;
    }

    /**
     * The real duration of the recording, including any padding, in milliseconds since the epoch.
     *
     * <p> This may not be the same as the value of {@link #getScheduledDurationMillis()} due to a
     * recording starting late, or due to start/end padding.
     *
     * <p> Returns -1 for recordings that have not yet started.
     */
    @IntRange(from = -1)
    public long getRecordingDurationMillis() {
        return mRecordingDurationMillis;
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
        dest.writeLong(mStartPaddingMillis);
        dest.writeLong(mEndPaddingMillis);
        dest.writeInt(mRepeatDays);
        dest.writeString(mName);
        dest.writeString(mDescription);
        dest.writeLong(mScheduledStartTimeMillis);
        dest.writeLong(mScheduledDurationMillis);
        dest.writeString(mChannelUri == null ? null : mChannelUri.toString());
        dest.writeString(mProgramUri == null ? null : mProgramUri.toString());
        List<String> flattenedContentRatings = new ArrayList<String>();
        mContentRatings.forEach((rating) -> flattenedContentRatings.add(rating.flattenToString()));
        dest.writeList(mContentRatings);
        dest.writeString(mRecordingUri == null ? null : mProgramUri.toString());
        dest.writeLong(mRecordingDurationMillis);
        dest.writeLong(mRecordingStartTimeMillis);
    }

    private TvRecordingInfo(Parcel in) {
        mRecordingId = in.readString();
        mStartPaddingMillis = in.readLong();
        mEndPaddingMillis = in.readLong();
        mRepeatDays = in.readInt();
        mName = in.readString();
        mDescription = in.readString();
        mScheduledStartTimeMillis = in.readLong();
        mScheduledDurationMillis = in.readLong();
        mChannelUri = Uri.parse(in.readString());
        mProgramUri = Uri.parse(in.readString());
        mContentRatings = new ArrayList<TvContentRating>();
        List<String> flattenedContentRatings = new ArrayList<String>();
        in.readStringList(flattenedContentRatings);
        flattenedContentRatings.forEach((rating) ->
                mContentRatings.add(TvContentRating.unflattenFromString(rating)));
        mRecordingUri = Uri.parse(in.readString());
        mRecordingDurationMillis = in.readLong();
        mRecordingStartTimeMillis = in.readLong();
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
