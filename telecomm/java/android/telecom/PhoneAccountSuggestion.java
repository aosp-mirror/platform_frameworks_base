/*
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

package android.telecom;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

public final class PhoneAccountSuggestion implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {REASON_NONE, REASON_INTRA_CARRIER, REASON_FREQUENT,
            REASON_USER_SET, REASON_OTHER}, prefix = { "REASON_" })
    public @interface SuggestionReason {}

    /**
     * Indicates that this account is not suggested for use, but is still available.
     */
    public static final int REASON_NONE = 0;

    /**
     * Indicates that the {@link PhoneAccountHandle} is suggested because the number we're calling
     * is on the same carrier, and therefore may have lower rates.
     */
    public static final int REASON_INTRA_CARRIER = 1;

    /**
     * Indicates that the {@link PhoneAccountHandle} is suggested because the user uses it
     * frequently for the number that we are calling.
     */
    public static final int REASON_FREQUENT = 2;

    /**
     * Indicates that the {@link PhoneAccountHandle} is suggested because the user explicitly
     * specified that it be used for the number we are calling.
     */
    public static final int REASON_USER_SET = 3;

    /**
     * Indicates that the {@link PhoneAccountHandle} is suggested for a reason not otherwise
     * enumerated here.
     */
    public static final int REASON_OTHER = 4;

    private PhoneAccountHandle mHandle;
    private int mReason;
    private boolean mShouldAutoSelect;

    /**
     * Creates a new instance of {@link PhoneAccountSuggestion}. This constructor is intended for
     * use by apps implementing a {@link PhoneAccountSuggestionService}, and generally should not be
     * used by dialer apps other than for testing purposes.
     *
     * @param handle The {@link PhoneAccountHandle} for this suggestion.
     * @param reason The reason for this suggestion
     * @param shouldAutoSelect Whether the dialer should automatically place the call using this
     *                         account. See {@link #shouldAutoSelect()}.
     */
    public PhoneAccountSuggestion(@NonNull PhoneAccountHandle handle, @SuggestionReason int reason,
            boolean shouldAutoSelect) {
        this.mHandle = handle;
        this.mReason = reason;
        this.mShouldAutoSelect = shouldAutoSelect;
    }

    private PhoneAccountSuggestion(Parcel in) {
        mHandle = in.readParcelable(PhoneAccountHandle.class.getClassLoader());
        mReason = in.readInt();
        mShouldAutoSelect = in.readByte() != 0;
    }

    public static final @android.annotation.NonNull Creator<PhoneAccountSuggestion> CREATOR =
            new Creator<PhoneAccountSuggestion>() {
                @Override
                public PhoneAccountSuggestion createFromParcel(Parcel in) {
                    return new PhoneAccountSuggestion(in);
                }

                @Override
                public PhoneAccountSuggestion[] newArray(int size) {
                    return new PhoneAccountSuggestion[size];
                }
            };

    /**
     * @return The {@link PhoneAccountHandle} for this suggestion.
     */
    @NonNull public PhoneAccountHandle getPhoneAccountHandle() {
        return mHandle;
    }

    /**
     * @return The reason for this suggestion
     */
    public @SuggestionReason int getReason() {
        return mReason;
    }

    /**
     * Suggests whether the dialer should automatically place the call using this account without
     * user interaction. This may be set on multiple {@link PhoneAccountSuggestion}s, and the dialer
     * is free to choose which one to use.
     * @return {@code true} if the hint is to auto-select, {@code false} otherwise.
     */
    public boolean shouldAutoSelect() {
        return mShouldAutoSelect;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mHandle, flags);
        dest.writeInt(mReason);
        dest.writeByte((byte) (mShouldAutoSelect ? 1 : 0));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhoneAccountSuggestion that = (PhoneAccountSuggestion) o;
        return mReason == that.mReason
                && mShouldAutoSelect == that.mShouldAutoSelect
                && Objects.equals(mHandle, that.mHandle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHandle, mReason, mShouldAutoSelect);
    }
}
