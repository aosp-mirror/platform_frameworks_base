/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.telecom;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a single voicemail stored in the voicemail content provider.
 *
 * @hide
 */
public class Voicemail implements Parcelable {
    private final Long mTimestamp;
    private final String mNumber;
    private final PhoneAccountHandle mPhoneAccount;
    private final Long mId;
    private final Long mDuration;
    private final String mSource;
    private final String mProviderData;
    private final Uri mUri;
    private final Boolean mIsRead;
    private final Boolean mHasContent;
    private final String mTranscription;

    private Voicemail(Long timestamp, String number, PhoneAccountHandle phoneAccountHandle, Long id,
            Long duration, String source, String providerData, Uri uri, Boolean isRead,
            Boolean hasContent, String transcription) {
        mTimestamp = timestamp;
        mNumber = number;
        mPhoneAccount = phoneAccountHandle;
        mId = id;
        mDuration = duration;
        mSource = source;
        mProviderData = providerData;
        mUri = uri;
        mIsRead = isRead;
        mHasContent = hasContent;
        mTranscription = transcription;
    }

    /**
     * Create a {@link Builder} for a new {@link Voicemail} to be inserted.
     * <p>
     * The number and the timestamp are mandatory for insertion.
     */
    public static Builder createForInsertion(long timestamp, String number) {
        return new Builder().setNumber(number).setTimestamp(timestamp);
    }

    /**
     * Create a {@link Builder} for a {@link Voicemail} to be updated (or deleted).
     * <p>
     * The id and source data fields are mandatory for update - id is necessary for updating the
     * database and source data is necessary for updating the server.
     */
    public static Builder createForUpdate(long id, String sourceData) {
        return new Builder().setId(id).setSourceData(sourceData);
    }

    /**
     * Builder pattern for creating a {@link Voicemail}. The builder must be created with the
     * {@link #createForInsertion(long, String)} method.
     * <p>
     * This class is <b>not thread safe</b>
     */
    public static class Builder {
        private Long mBuilderTimestamp;
        private String mBuilderNumber;
        private PhoneAccountHandle mBuilderPhoneAccount;
        private Long mBuilderId;
        private Long mBuilderDuration;
        private String mBuilderSourcePackage;
        private String mBuilderSourceData;
        private Uri mBuilderUri;
        private Boolean mBuilderIsRead;
        private boolean mBuilderHasContent;
        private String mBuilderTranscription;

        /** You should use the correct factory method to construct a builder. */
        private Builder() {
        }

        public Builder setNumber(String number) {
            mBuilderNumber = number;
            return this;
        }

        public Builder setTimestamp(long timestamp) {
            mBuilderTimestamp = timestamp;
            return this;
        }

        public Builder setPhoneAccount(PhoneAccountHandle phoneAccount) {
            mBuilderPhoneAccount = phoneAccount;
            return this;
        }

        public Builder setId(long id) {
            mBuilderId = id;
            return this;
        }

        public Builder setDuration(long duration) {
            mBuilderDuration = duration;
            return this;
        }

        public Builder setSourcePackage(String sourcePackage) {
            mBuilderSourcePackage = sourcePackage;
            return this;
        }

        public Builder setSourceData(String sourceData) {
            mBuilderSourceData = sourceData;
            return this;
        }

        public Builder setUri(Uri uri) {
            mBuilderUri = uri;
            return this;
        }

        public Builder setIsRead(boolean isRead) {
            mBuilderIsRead = isRead;
            return this;
        }

        public Builder setHasContent(boolean hasContent) {
            mBuilderHasContent = hasContent;
            return this;
        }

        public Builder setTranscription(String transcription) {
            mBuilderTranscription = transcription;
            return this;
        }

        public Voicemail build() {
            mBuilderId = mBuilderId == null ? -1 : mBuilderId;
            mBuilderTimestamp = mBuilderTimestamp == null ? 0 : mBuilderTimestamp;
            mBuilderDuration = mBuilderDuration == null ? 0: mBuilderDuration;
            mBuilderIsRead = mBuilderIsRead == null ? false : mBuilderIsRead;
            return new Voicemail(mBuilderTimestamp, mBuilderNumber, mBuilderPhoneAccount,
                    mBuilderId, mBuilderDuration, mBuilderSourcePackage, mBuilderSourceData,
                    mBuilderUri, mBuilderIsRead, mBuilderHasContent, mBuilderTranscription);
        }
    }

    /**
     * The identifier of the voicemail in the content provider.
     * <p>
     * This may be missing in the case of a new {@link Voicemail} that we plan to insert into the
     * content provider, since until it has been inserted we don't know what id it should have. If
     * none is specified, we return -1.
     */
    public long getId() {
        return mId;
    }

    /** The number of the person leaving the voicemail, empty string if unknown, null if not set. */
    public String getNumber() {
        return mNumber;
    }

    /** The phone account associated with the voicemail, null if not set. */
    public PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccount;
    }

    /** The timestamp the voicemail was received, in millis since the epoch, zero if not set. */
    public long getTimestampMillis() {
        return mTimestamp;
    }

    /** Gets the duration of the voicemail in millis, or zero if the field is not set. */
    public long getDuration() {
        return mDuration;
    }

    /**
     * Returns the package name of the source that added this voicemail, or null if this field is
     * not set.
     */
    public String getSourcePackage() {
        return mSource;
    }

    /**
     * Returns the application-specific data type stored with the voicemail, or null if this field
     * is not set.
     * <p>
     * Source data is typically used as an identifier to uniquely identify the voicemail against
     * the voicemail server. This is likely to be something like the IMAP UID, or some other
     * server-generated identifying string.
     */
    public String getSourceData() {
        return mProviderData;
    }

    /**
     * Gets the Uri that can be used to refer to this voicemail, and to make it play.
     * <p>
     * Returns null if we don't know the Uri.
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * Tells us if the voicemail message has been marked as read.
     * <p>
     * Always returns false if this field has not been set, i.e. if hasRead() returns false.
     */
    public boolean isRead() {
        return mIsRead;
    }

    /**
     * Tells us if there is content stored at the Uri.
     */
    public boolean hasContent() {
        return mHasContent;
    }

    /**
     * Returns the text transcription of this voicemail, or null if this field is not set.
     */
    public String getTranscription() {
        return mTranscription;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mTimestamp);
        dest.writeCharSequence(mNumber);
        if (mPhoneAccount == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mPhoneAccount.writeToParcel(dest, flags);
        }
        dest.writeLong(mId);
        dest.writeLong(mDuration);
        dest.writeCharSequence(mSource);
        dest.writeCharSequence(mProviderData);
        if (mUri == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mUri.writeToParcel(dest, flags);
        }
        if (mIsRead) {
            dest.writeInt(1);
        } else {
            dest.writeInt(0);
        }
        if (mHasContent) {
            dest.writeInt(1);
        } else {
            dest.writeInt(0);
        }
        dest.writeCharSequence(mTranscription);
    }

    public static final @android.annotation.NonNull Creator<Voicemail> CREATOR
            = new Creator<Voicemail>() {
        @Override
        public Voicemail createFromParcel(Parcel in) {
            return new Voicemail(in);
        }

        @Override
        public Voicemail[] newArray(int size) {
            return new Voicemail[size];
        }
    };

    private Voicemail(Parcel in) {
        mTimestamp = in.readLong();
        mNumber = (String) in.readCharSequence();
        if (in.readInt() > 0) {
            mPhoneAccount = PhoneAccountHandle.CREATOR.createFromParcel(in);
        } else {
            mPhoneAccount = null;
        }
        mId = in.readLong();
        mDuration = in.readLong();
        mSource = (String) in.readCharSequence();
        mProviderData = (String) in.readCharSequence();
        if (in.readInt() > 0) {
            mUri = Uri.CREATOR.createFromParcel(in);
        } else {
            mUri = null;
        }
        mIsRead = in.readInt() > 0 ? true : false;
        mHasContent = in.readInt() > 0 ? true : false;
        mTranscription = (String) in.readCharSequence();
    }
}
