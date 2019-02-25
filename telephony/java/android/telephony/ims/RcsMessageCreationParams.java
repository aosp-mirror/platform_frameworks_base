/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.telephony.ims.RcsMessage.LOCATION_NOT_SET;

import android.annotation.CheckResult;
import android.annotation.Nullable;
import android.os.Parcel;

/**
 * The collection of parameters to be passed into
 * {@link RcsThread#addIncomingMessage(RcsIncomingMessageCreationParams)} and
 * {@link RcsThread#addOutgoingMessage(RcsOutgoingMessageCreationParams)} to create and persist
 * {@link RcsMessage}s on an {@link RcsThread}
 *
 * @hide
 */
public class RcsMessageCreationParams {
    // The globally unique id of the RcsMessage to be created.
    private final String mRcsMessageGlobalId;

    // The subscription that this message was/will be received/sent from.
    private final int mSubId;
    // The sending/receiving status of the message
    private final @RcsMessage.RcsMessageStatus int mMessageStatus;
    // The timestamp of message creation
    private final long mOriginationTimestamp;
    // The user visible content of the message
    private final String mText;
    // The latitude of the message if this is a location message
    private final double mLatitude;
    // The longitude of the message if this is a location message
    private final double mLongitude;

    /**
     * @return Returns the globally unique RCS Message ID for the {@link RcsMessage} to be created.
     * Please see 4.4.5.2 - GSMA RCC.53 (RCS Device API 1.6 Specification
     */
    @Nullable
    public String getRcsMessageGlobalId() {
        return mRcsMessageGlobalId;
    }

    /**
     * @return Returns the subscription ID that was used to send or receive the {@link RcsMessage}
     * to be created.
     */
    public int getSubId() {
        return mSubId;
    }

    /**
     * @return Returns the status for the {@link RcsMessage} to be created.
     * @see RcsMessage.RcsMessageStatus
     */
    public int getMessageStatus() {
        return mMessageStatus;
    }

    /**
     * @return Returns the origination timestamp of the {@link RcsMessage} to be created in
     * milliseconds passed after midnight, January 1, 1970 UTC. Origination is defined as when
     * the sender tapped the send button.
     */
    public long getOriginationTimestamp() {
        return mOriginationTimestamp;
    }

    /**
     * @return Returns the user visible text contained in the {@link RcsMessage} to be created
     */
    @Nullable
    public String getText() {
        return mText;
    }

    /**
     * @return Returns the latitude of the {@link RcsMessage} to be created, or
     * {@link RcsMessage#LOCATION_NOT_SET} if the message does not contain a location.
     */
    public double getLatitude() {
        return mLatitude;
    }

    /**
     * @return Returns the longitude of the {@link RcsMessage} to be created, or
     * {@link RcsMessage#LOCATION_NOT_SET} if the message does not contain a location.
     */
    public double getLongitude() {
        return mLongitude;
    }

    /**
     * The base builder for creating {@link RcsMessage}s on {@link RcsThread}s.
     *
     * @see RcsIncomingMessageCreationParams
     */
    public static class Builder {
        private String mRcsMessageGlobalId;
        private int mSubId;
        private @RcsMessage.RcsMessageStatus int mMessageStatus;
        private long mOriginationTimestamp;
        private String mText;
        private double mLatitude = LOCATION_NOT_SET;
        private double mLongitude = LOCATION_NOT_SET;

        /**
         * @hide
         */
        public Builder(long originationTimestamp, int subscriptionId) {
            mOriginationTimestamp = originationTimestamp;
            mSubId = subscriptionId;
        }

        /**
         * Sets the status of the {@link RcsMessage} to be built.
         *
         * @param rcsMessageStatus The status to be set
         * @return The same instance of {@link Builder} to chain methods
         * @see RcsMessage#setStatus(int)
         */
        @CheckResult
        public Builder setStatus(@RcsMessage.RcsMessageStatus int rcsMessageStatus) {
            mMessageStatus = rcsMessageStatus;
            return this;
        }

        /**
         * Sets the globally unique RCS message identifier for the {@link RcsMessage} to be built.
         * This function does not confirm that this message id is unique. Please see 4.4.5.2 - GSMA
         * RCC.53 (RCS Device API 1.6 Specification)
         *
         * @param rcsMessageId The ID to be set
         * @return The same instance of {@link Builder} to chain methods
         * @see RcsMessage#setRcsMessageId(String)
         */
        @CheckResult
        public Builder setRcsMessageId(String rcsMessageId) {
            mRcsMessageGlobalId = rcsMessageId;
            return this;
        }

        /**
         * Sets the text of the {@link RcsMessage} to be built.
         *
         * @param text The user visible text of the message
         * @return The same instance of {@link Builder} to chain methods
         * @see RcsMessage#setText(String)
         */
        @CheckResult
        public Builder setText(String text) {
            mText = text;
            return this;
        }

        /**
         * Sets the latitude of the {@link RcsMessage} to be built. Please see US5-24 - GSMA RCC.71
         * (RCS Universal Profile Service Definition Document)
         *
         * @param latitude The latitude of the location information associated with this message.
         * @return The same instance of {@link Builder} to chain methods
         * @see RcsMessage#setLatitude(double)
         */
        @CheckResult
        public Builder setLatitude(double latitude) {
            mLatitude = latitude;
            return this;
        }

        /**
         * Sets the longitude of the {@link RcsMessage} to be built. Please see US5-24 - GSMA RCC.71
         * (RCS Universal Profile Service Definition Document)
         *
         * @param longitude The longitude of the location information associated with this message.
         * @return The same instance of {@link Builder} to chain methods
         * @see RcsMessage#setLongitude(double)
         */
        @CheckResult
        public Builder setLongitude(double longitude) {
            mLongitude = longitude;
            return this;
        }

        /**
         * @return Builds and returns a newly created {@link RcsMessageCreationParams}
         */
        public RcsMessageCreationParams build() {
            return new RcsMessageCreationParams(this);
        }
    }

    protected RcsMessageCreationParams(Builder builder) {
        mRcsMessageGlobalId = builder.mRcsMessageGlobalId;
        mSubId = builder.mSubId;
        mMessageStatus = builder.mMessageStatus;
        mOriginationTimestamp = builder.mOriginationTimestamp;
        mText = builder.mText;
        mLatitude = builder.mLatitude;
        mLongitude = builder.mLongitude;
    }

    /**
     * @hide
     */
    RcsMessageCreationParams(Parcel in) {
        mRcsMessageGlobalId = in.readString();
        mSubId = in.readInt();
        mMessageStatus = in.readInt();
        mOriginationTimestamp = in.readLong();
        mText = in.readString();
        mLatitude = in.readDouble();
        mLongitude = in.readDouble();
    }

    /**
     * @hide
     */
    public void writeToParcel(Parcel dest) {
        dest.writeString(mRcsMessageGlobalId);
        dest.writeInt(mSubId);
        dest.writeInt(mMessageStatus);
        dest.writeLong(mOriginationTimestamp);
        dest.writeString(mText);
        dest.writeDouble(mLatitude);
        dest.writeDouble(mLongitude);
    }
}
