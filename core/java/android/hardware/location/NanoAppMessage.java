/*
 * Copyright 2017 The Android Open Source Project
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
package android.hardware.location;

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
 * A class describing messages send to or from nanoapps through the Context Hub Service.
 *
 * The basis of the class is in the IContextHub.hal ContextHubMsg definition.
 *
 * @hide
 */
@SystemApi
public final class NanoAppMessage implements Parcelable {
    private static final int DEBUG_LOG_NUM_BYTES = 16;
    private long mNanoAppId;
    private int mMessageType;
    private byte[] mMessageBody;
    private boolean mIsBroadcasted;
    private boolean mIsReliable;
    private int mMessageSequenceNumber;

    private NanoAppMessage(long nanoAppId, int messageType, byte[] messageBody,
            boolean broadcasted, boolean isReliable, int messageSequenceNumber) {
        mNanoAppId = nanoAppId;
        mMessageType = messageType;
        mMessageBody = messageBody;
        mIsBroadcasted = broadcasted;
        mIsReliable = isReliable;
        mMessageSequenceNumber = messageSequenceNumber;
    }

    /**
     * Creates a NanoAppMessage object to send to a nanoapp.
     *
     * This factory method can be used to generate a NanoAppMessage object to be used in
     * the ContextHubClient.sendMessageToNanoApp API.
     *
     * @param targetNanoAppId the ID of the nanoapp to send the message to
     * @param messageType the nanoapp-dependent message type
     *                    the value CHRE_MESSAGE_TYPE_RPC (0x7FFFFFF5) is reserved by the
     *                    framework for RPC messages
     * @param messageBody the byte array message contents
     *
     * @return the NanoAppMessage object
     */
    public static NanoAppMessage createMessageToNanoApp(long targetNanoAppId, int messageType,
            byte[] messageBody) {
        return new NanoAppMessage(targetNanoAppId, messageType, messageBody,
                false /* broadcasted */, false /* isReliable */, 0 /* messageSequenceNumber */);
    }

    /**
     * Creates a NanoAppMessage object sent from a nanoapp.
     *
     * This factory method is intended only to be used by the Context Hub Service when delivering
     * messages from a nanoapp to clients.
     *
     * @param sourceNanoAppId the ID of the nanoapp that the message was sent from
     * @param messageType the nanoapp-dependent message type
     * @param messageBody the byte array message contents
     * @param broadcasted {@code true} if the message was broadcasted, {@code false} otherwise
     *
     * @return the NanoAppMessage object
     */
    public static NanoAppMessage createMessageFromNanoApp(long sourceNanoAppId, int messageType,
            byte[] messageBody, boolean broadcasted) {
        return new NanoAppMessage(sourceNanoAppId, messageType, messageBody, broadcasted,
                false /* isReliable */, 0 /* messageSequenceNumber */);
    }

    /**
     * Creates a NanoAppMessage object sent from a nanoapp.
     *
     * This factory method is intended only to be used by the Context Hub Service when delivering
     * messages from a nanoapp to clients.
     *
     * @param sourceNanoAppId the ID of the nanoapp that the message was sent from
     * @param messageType the nanoapp-dependent message type
     * @param messageBody the byte array message contents
     * @param broadcasted {@code true} if the message was broadcasted, {@code false} otherwise
     * @param isReliable if the NanoAppMessage is reliable
     * @param messageSequenceNumber the message sequence number of the NanoAppMessage
     *
     * @return the NanoAppMessage object
     */
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public static @NonNull NanoAppMessage createMessageFromNanoApp(long sourceNanoAppId,
            int messageType, @NonNull byte[] messageBody, boolean broadcasted, boolean isReliable,
            int messageSequenceNumber) {
        return new NanoAppMessage(sourceNanoAppId, messageType, messageBody, broadcasted,
                isReliable, messageSequenceNumber);
    }

    /**
     * @return the ID of the source or destination nanoapp
     */
    public long getNanoAppId() {
        return mNanoAppId;
    }

    /**
     * @return the type of the message that is nanoapp-dependent
     */
    public int getMessageType() {
        return mMessageType;
    }

    /**
     * @return the byte array contents of the message
     */
    public byte[] getMessageBody() {
        return mMessageBody;
    }

    /**
     * @return {@code true} if the message is broadcasted, {@code false} otherwise
     */
    public boolean isBroadcastMessage() {
        return mIsBroadcasted;
    }

    /**
     * Returns if the message is reliable. The default value is {@code false}
     * @return {@code true} if the message is reliable, {@code false} otherwise
     */
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public boolean isReliable() {
        return mIsReliable;
    }

    /**
     * Returns the message sequence number. The default value is 0
     * @return the message sequence number of the message
     */
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public int getMessageSequenceNumber() {
        return mMessageSequenceNumber;
    }

    /**
     * Sets the isReliable field of the message
     *
     * @hide
     */
    public void setIsReliable(boolean isReliable) {
        mIsReliable = isReliable;
    }

    /**
     * Sets the message sequence number of the message
     *
     * @hide
     */
    public void setMessageSequenceNumber(int messageSequenceNumber) {
        mMessageSequenceNumber = messageSequenceNumber;
    }

    private NanoAppMessage(Parcel in) {
        mNanoAppId = in.readLong();
        mIsBroadcasted = (in.readInt() == 1);
        mMessageType = in.readInt();

        int msgSize = in.readInt();
        mMessageBody = new byte[msgSize];
        in.readByteArray(mMessageBody);

        mIsReliable = (in.readInt() == 1);
        mMessageSequenceNumber = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mNanoAppId);
        out.writeInt(mIsBroadcasted ? 1 : 0);
        out.writeInt(mMessageType);

        out.writeInt(mMessageBody.length);
        out.writeByteArray(mMessageBody);

        out.writeInt(mIsReliable ? 1 : 0);
        out.writeInt(mMessageSequenceNumber);
    }

    public static final @NonNull Creator<NanoAppMessage> CREATOR =
            new Creator<NanoAppMessage>() {
                @Override
                public NanoAppMessage createFromParcel(Parcel in) {
                    return new NanoAppMessage(in);
                }

                @Override
                public NanoAppMessage[] newArray(int size) {
                    return new NanoAppMessage[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        int length = mMessageBody.length;

        StringBuilder out = new StringBuilder();
        out.append( "NanoAppMessage[type = ");
        out.append(mMessageType);
        out.append(", length = ");
        out.append(mMessageBody.length);
        out.append(" bytes, ");
        out.append(mIsBroadcasted ? "broadcast" : "unicast");
        out.append(", nanoapp = 0x");
        out.append(Long.toHexString(mNanoAppId));
        out.append(", isReliable = ");
        out.append(mIsReliable ? "true" : "false");
        out.append(", messageSequenceNumber = ");
        out.append(mMessageSequenceNumber);
        out.append("](");

        if (length > 0) {
            out.append("data = 0x");
        }
        for (int i = 0; i < Math.min(length, DEBUG_LOG_NUM_BYTES); i++) {
            out.append(HexEncoding.encodeToString(mMessageBody[i],
                                                  true /* upperCase */));

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
        if (object instanceof NanoAppMessage) {
            NanoAppMessage other = (NanoAppMessage) object;
            isEqual = (other.getNanoAppId() == mNanoAppId)
                    && (other.getMessageType() == mMessageType)
                    && (other.isBroadcastMessage() == mIsBroadcasted)
                    && Arrays.equals(other.getMessageBody(), mMessageBody)
                    && (!Flags.reliableMessage()
                            || (other.isReliable() == mIsReliable))
                    && (!Flags.reliableMessage()
                            || (other.getMessageSequenceNumber() == mMessageSequenceNumber));
        }

        return isEqual;
    }

    @Override
    public int hashCode() {
        if (!Flags.fixApiCheck()) {
            return super.hashCode();
        }

        return Objects.hash(mNanoAppId, mMessageType, mIsBroadcasted,
                Arrays.hashCode(mMessageBody), mIsReliable,
                mMessageSequenceNumber);
    }
}
