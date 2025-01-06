/*
 * Copyright 2024 The Android Open Source Project
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
package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import libcore.util.HexEncoding;

import java.util.Arrays;
import java.util.Objects;

/**
 * A class describing general messages send through the Context Hub Service through {@link
 * HubEndpointSession#sendMessage}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public final class HubMessage implements Parcelable {
    private static final int DEBUG_LOG_NUM_BYTES = 16;

    private final int mMessageType;
    private final byte[] mMessageBody;

    private final boolean mResponseRequired;
    private int mMessageSequenceNumber;

    private HubMessage(int messageType, @NonNull byte[] messageBody, boolean responseRequired) {
        Objects.requireNonNull(messageBody, "messageBody cannot be null");
        mMessageType = messageType;
        mMessageBody = messageBody;
        mResponseRequired = responseRequired;
    }

    /**
     * Retrieve the message type.
     *
     * @return the type of the message
     */
    public int getMessageType() {
        return mMessageType;
    }

    /**
     * Retrieve the body of the message. The body can be an empty byte array.
     *
     * @return the byte array contents of the message
     */
    @NonNull
    public byte[] getMessageBody() {
        return mMessageBody;
    }

    /**
     * @return true if a response is required when the peer endpoint receives the message.
     */
    public boolean isResponseRequired() {
        return mResponseRequired;
    }

    /**
     * Assign a message sequence number. This should only be called by the system service.
     *
     * @hide
     */
    public void setMessageSequenceNumber(int messageSequenceNumber) {
        mMessageSequenceNumber = messageSequenceNumber;
    }

    /**
     * Returns the message sequence number. The default value is 0.
     *
     * @return the message sequence number of the message
     * @hide
     */
    public int getMessageSequenceNumber() {
        return mMessageSequenceNumber;
    }

    private HubMessage(@NonNull Parcel in) {
        mMessageType = in.readInt();

        int msgSize = in.readInt();
        mMessageBody = new byte[msgSize];
        in.readByteArray(mMessageBody);

        mResponseRequired = (in.readInt() == 1);
        mMessageSequenceNumber = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mMessageType);

        out.writeInt(mMessageBody.length);
        out.writeByteArray(mMessageBody);

        out.writeInt(mResponseRequired ? 1 : 0);
        out.writeInt(mMessageSequenceNumber);
    }

    public static final @NonNull Creator<HubMessage> CREATOR =
            new Creator<>() {
                @Override
                public HubMessage createFromParcel(Parcel in) {
                    return new HubMessage(in);
                }

                @Override
                public HubMessage[] newArray(int size) {
                    return new HubMessage[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        int length = mMessageBody.length;

        StringBuilder out = new StringBuilder();
        out.append("HubMessage[type = ").append(mMessageType);
        out.append(", length = ").append(mMessageBody.length);
        out.append(", messageSequenceNumber = ").append(mMessageSequenceNumber);
        out.append(", responseRequired = ").append(mResponseRequired);
        out.append("](");

        if (length > 0) {
            out.append("data = 0x");
        }
        for (int i = 0; i < Math.min(length, DEBUG_LOG_NUM_BYTES); i++) {
            out.append(HexEncoding.encodeToString(mMessageBody[i], true /* upperCase */));

            if ((i + 1) % 4 == 0) {
                out.append(" ");
            }
        }
        if (length > DEBUG_LOG_NUM_BYTES) {
            out.append("...");
        }
        out.append(")");

        return out.toString();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object == this) {
            return true;
        }

        boolean isEqual = false;
        if (object instanceof HubMessage other) {
            isEqual =
                    (other.getMessageType() == mMessageType)
                            && Arrays.equals(other.getMessageBody(), mMessageBody)
                            && (other.isResponseRequired() == mResponseRequired)
                            && (other.getMessageSequenceNumber() == mMessageSequenceNumber);
        }

        return isEqual;
    }

    @Override
    public int hashCode() {
        if (!Flags.fixApiCheck()) {
            return super.hashCode();
        }

        return Objects.hash(
                mMessageType,
                Arrays.hashCode(mMessageBody),
                mResponseRequired,
                mMessageSequenceNumber);
    }

    public static final class Builder {
        private int mMessageType;
        private byte[] mMessageBody;
        private boolean mResponseRequired = false;

        /**
         * Create a builder for {@link HubMessage} with a default delivery parameters.
         *
         * @param messageType the endpoint & service dependent message type
         * @param messageBody the byte array message contents
         */
        public Builder(int messageType, @NonNull byte[] messageBody) {
            mMessageType = messageType;
            mMessageBody = messageBody;
        }

        /**
         * @param responseRequired If true, message sent with this option will have a {@link
         *     android.hardware.location.ContextHubTransaction.Response} when the peer received the
         *     message. Default is false.
         */
        @NonNull
        public Builder setResponseRequired(boolean responseRequired) {
            mResponseRequired = responseRequired;
            return this;
        }

        /** Build the {@link HubMessage} object. */
        @NonNull
        public HubMessage build() {
            return new HubMessage(mMessageType, mMessageBody, mResponseRequired);
        }
    }
}
