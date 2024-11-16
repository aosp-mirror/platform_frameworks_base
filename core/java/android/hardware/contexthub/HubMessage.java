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
 * A class describing general messages send through the Context Hub Service.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public final class HubMessage implements Parcelable {
    private static final int DEBUG_LOG_NUM_BYTES = 16;

    private final int mMessageType;
    private final byte[] mMessageBody;

    private final DeliveryParams mDeliveryParams;
    private int mMessageSequenceNumber;

    /**
     * Configurable options for message delivery. This option can be passed into {@link
     * HubEndpointSession#sendMessage} to specify the behavior of message delivery.
     */
    public static class DeliveryParams {
        private boolean mResponseRequired;

        private DeliveryParams(boolean responseRequired) {
            mResponseRequired = responseRequired;
        }

        /** Get the acknowledgement requirement. */
        public boolean isResponseRequired() {
            return mResponseRequired;
        }

        /**
         * Set the response requirement for a message. Message sent with this option will have a
         * {@link android.hardware.location.ContextHubTransaction.Response} when the peer received
         * the message. Default is false.
         */
        @NonNull
        public DeliveryParams setResponseRequired(boolean required) {
            mResponseRequired = required;
            return this;
        }

        /** Construct a default delivery option. */
        @NonNull
        public static DeliveryParams makeBasic() {
            return new DeliveryParams(false);
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            out.append("DeliveryParams[");
            out.append("responseRequired = ").append(mResponseRequired);
            out.append("]");
            return out.toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(mResponseRequired);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }

            if (obj instanceof DeliveryParams other) {
                return other.mResponseRequired == mResponseRequired;
            }

            return false;
        }
    }

    private HubMessage(int messageType, byte[] messageBody, DeliveryParams deliveryParams) {
        mMessageType = messageType;
        mMessageBody = messageBody;
        mDeliveryParams = deliveryParams;
    }

    /**
     * Creates a HubMessage object to send to through an endpoint.
     *
     * @param messageType the endpoint & service dependent message type
     * @param messageBody the byte array message contents
     * @return the HubMessage object
     */
    @NonNull
    public static HubMessage createMessage(int messageType, @NonNull byte[] messageBody) {
        return new HubMessage(messageType, messageBody, DeliveryParams.makeBasic());
    }

    /**
     * Creates a HubMessage object to send to through an endpoint.
     *
     * @param messageType the endpoint & service dependent message type
     * @param messageBody the byte array message contents
     * @param deliveryParams The message delivery parameters. See {@link HubMessage.DeliveryParams}
     *     for more details.
     * @return the HubMessage object
     */
    @NonNull
    public static HubMessage createMessage(
            int messageType, @NonNull byte[] messageBody, @NonNull DeliveryParams deliveryParams) {
        return new HubMessage(messageType, messageBody, deliveryParams);
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
     * Retrieve the {@link DeliveryParams} object specifying the behavior of message delivery.
     *
     * @hide
     */
    public DeliveryParams getDeliveryParams() {
        return mDeliveryParams;
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

        mDeliveryParams = DeliveryParams.makeBasic();
        mDeliveryParams.setResponseRequired(in.readInt() == 1);
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

        out.writeInt(mDeliveryParams.isResponseRequired() ? 1 : 0);
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
        out.append(", deliveryParams = ").append(mDeliveryParams);
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
                            && (other.getDeliveryParams().equals(mDeliveryParams))
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
                mDeliveryParams,
                mMessageSequenceNumber);
    }
}
