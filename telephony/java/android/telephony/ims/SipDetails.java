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

package android.telephony.ims;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Contains the information for SIP.
 */
public final class SipDetails implements Parcelable {
    /**
     * Define a SIP method type related to this information.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "METHOD_", value = {
            METHOD_UNKNOWN,
            METHOD_REGISTER,
            METHOD_PUBLISH,
            METHOD_SUBSCRIBE
    })
    public @interface Method {}

    public static final int METHOD_UNKNOWN = 0;

    /**
     * Indicates information related to the SIP registration method.
     * See RFC 3261 for details.
     */
    public static final int METHOD_REGISTER = 1;
    /**
     * Indicates information related to the SIP publication method.
     * See RFC 3903 for details.
     */
    public static final int METHOD_PUBLISH = 2;
    /**
     * Indicates information related to the SIP subscription method.
     * See RFC 3856 for details.
     */
    public static final int METHOD_SUBSCRIBE = 3;

    /**
     * Builder for creating {@link SipDetails} instances.
     * @hide
     */
    public static final class Builder {
        private int mMethod;
        // Command Sequence value defined in RFC3261 Section 8.1.1.5
        private int mCseq = 0;
        private int mResponseCode = 0;
        private String mResponsePhrase = "";
        private int mReasonHeaderCause = 0;
        private String mReasonHeaderText = "";
        private @Nullable String mCallId;

        /**
         * Build a new instance of {@link SipDetails}.
         *
         * @param method Indicates which SIP method this information is for.
         */
        public Builder(@Method int method) {
            this.mMethod = method;
        }

        /**
         * Sets the value of the CSeq header field for this SIP method.
         * The CSeq header field serves as a way to identify and order transactions.
         * It consists of a sequence number and a method.
         * The method MUST match that of the request.
         * Ref RFC3261 Section 8.1.1.5.
         * @param cSeq The value of the CSeq header field.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setCSeq(int cSeq) {
            this.mCseq = cSeq;
            return this;
        }

        /**
         * Sets the SIP response code and reason response for this SIP method.
         * Ref RFC3261 Section 21.
         * @param responseCode The SIP response code sent from the network for the
         * operation token specified.
         * @param responsePhrase The optional reason response from the network. If
         * there is a reason header included in the response, that should take
         * precedence over the reason provided in the status line. If the network
         * provided no reason with the SIP code, the string should be empty.
         * @return The same instance of the builder.
         */
        public Builder setSipResponseCode(int responseCode,
                @NonNull String responsePhrase) {
            this.mResponseCode = responseCode;
            this.mResponsePhrase = responsePhrase;
            return this;
        }


        /**
         * Sets the detailed information of reason header for this SIP method.
         * Ref RFC3326.
         * @param reasonHeaderCause The “cause” parameter of the “reason”
         * header included in the SIP message.
         * @param reasonHeaderText The “text” parameter of the “reason”
         * header included in the SIP message.
         * @return The same instance of the builder.
         */
        public Builder setSipResponseReasonHeader(int reasonHeaderCause,
                @NonNull String reasonHeaderText) {
            this.mReasonHeaderCause = reasonHeaderCause;
            this.mReasonHeaderText = reasonHeaderText;
            return this;
        }


        /**
         * Sets the value of the Call-ID header field for this SIP method.
         * Ref RFC3261 Section 8.1.1.4.
         * @param callId The value of the Call-ID header field.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setCallId(@NonNull String callId) {
            this.mCallId = callId;
            return this;
        }

        /**
         * @return a new SipDetails from this Builder.
         */
        public @NonNull SipDetails build() {
            return new SipDetails(this);
        }
    }

    private final int mMethod;
    private final int mCseq;
    private final int mResponseCode;
    private final @NonNull String mResponsePhrase;
    private final int mReasonHeaderCause;
    private final @NonNull String mReasonHeaderText;
    private final @Nullable String mCallId;

    private SipDetails(Builder builder) {
        mMethod = builder.mMethod;
        mCseq = builder.mCseq;
        mResponseCode = builder.mResponseCode;
        mResponsePhrase = builder.mResponsePhrase;
        mReasonHeaderCause = builder.mReasonHeaderCause;
        mReasonHeaderText = builder.mReasonHeaderText;
        mCallId = builder.mCallId;
    }

    /**
     * Get the method type of this instance.
     * @return The method type associated with this SIP information.
     */
    public @Method int getMethod() {
        return mMethod;
    }

    /**
     * Get the value of CSeq header field.
     * The CSeq header field serves as a way to identify and order transactions.
     * @return The command sequence value associated with this SIP information.
     */
    public int getCSeq() {
        return mCseq;
    }

    /**
     * Get the value of response code from the SIP response.
     * The SIP response code sent from the network for the operation token specified.
     * @return The SIP response code associated with this SIP information.
     */
    public int getResponseCode() {
        return mResponseCode;
    }

    /**
     * Get the value of reason from the SIP response.
     * The optional reason response from the network. If
     * there is a reason header included in the response, that should take
     * precedence over the reason provided in the status line.
     * @return The optional reason response associated with this SIP information. If the network
     *          provided no reason with the SIP code, the string should be empty.
     */
    public @NonNull String getResponsePhrase() {
        return mResponsePhrase;
    }

    /**
     * Get the "cause" parameter of the "reason" header.
     * @return The "cause" parameter of the reason header. If the SIP message from the network
     *          does not have a reason header, it should be 0.
     */
    public int getReasonHeaderCause() {
        return mReasonHeaderCause;
    }

    /**
     * Get the "text" parameter of the "reason" header in the SIP message.
     * @return The "text" parameter of the reason header. If the SIP message from the network
     *          does not have a reason header, it can be empty.
     */
    public @NonNull String getReasonHeaderText() {
        return mReasonHeaderText;
    }

    /**
     * Get the value of the Call-ID header field for this SIP method.
     * @return The Call-ID value associated with this SIP information. If the Call-ID value is
     *          not set when ImsService notifies the framework, this value will be null.
     */
    public @Nullable String getCallId() {
        return mCallId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMethod);
        dest.writeInt(mCseq);
        dest.writeInt(mResponseCode);
        dest.writeString(mResponsePhrase);
        dest.writeInt(mReasonHeaderCause);
        dest.writeString(mReasonHeaderText);
        dest.writeString(mCallId);
    }

    public static final @NonNull Creator<SipDetails> CREATOR =
            new Creator<SipDetails>() {
                @Override
                public SipDetails createFromParcel(Parcel source) {
                    return new SipDetails(source);
                }

                @Override
                public SipDetails[] newArray(int size) {
                    return new SipDetails[size];
                }
            };

    /**
     * Construct a SipDetails object from the given parcel.
     */
    private SipDetails(Parcel in) {
        mMethod = in.readInt();
        mCseq = in.readInt();
        mResponseCode = in.readInt();
        mResponsePhrase = in.readString();
        mReasonHeaderCause = in.readInt();
        mReasonHeaderText = in.readString();
        mCallId = in.readString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SipDetails that = (SipDetails) o;
        return mMethod == that.mMethod
                && mCseq == that.mCseq
                && mResponseCode == that.mResponseCode
                && TextUtils.equals(mResponsePhrase, that.mResponsePhrase)
                && mReasonHeaderCause == that.mReasonHeaderCause
                && TextUtils.equals(mReasonHeaderText, that.mReasonHeaderText)
                && TextUtils.equals(mCallId, that.mCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMethod, mCseq, mResponseCode, mResponsePhrase, mReasonHeaderCause,
                mReasonHeaderText, mCallId);
    }

    @Override
    public String toString() {
        return "SipDetails { methodType= " + mMethod + ", cSeq=" + mCseq
                + ", ResponseCode=" + mResponseCode + ", ResponseCPhrase=" + mResponsePhrase
                + ", ReasonHeaderCause=" + mReasonHeaderCause
                + ", ReasonHeaderText=" + mReasonHeaderText + ", callId=" + mCallId + "}";
    }
}
