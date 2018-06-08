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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

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

    private NanoAppMessage(
            long nanoAppId, int messageType, byte[] messageBody, boolean broadcasted) {
        mNanoAppId = nanoAppId;
        mMessageType = messageType;
        mMessageBody = messageBody;
        mIsBroadcasted = broadcasted;
    }

    /**
     * Creates a NanoAppMessage object to send to a nanoapp.
     *
     * This factory method can be used to generate a NanoAppMessage object to be used in
     * the ContextHubClient.sendMessageToNanoApp API.
     *
     * @param targetNanoAppId the ID of the nanoapp to send the message to
     * @param messageType the nanoapp-dependent message type
     * @param messageBody the byte array message contents
     *
     * @return the NanoAppMessage object
     */
    public static NanoAppMessage createMessageToNanoApp(
            long targetNanoAppId, int messageType, byte[] messageBody) {
        return new NanoAppMessage(
                targetNanoAppId, messageType, messageBody, false /* broadcasted */);
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
    public static NanoAppMessage createMessageFromNanoApp(
            long sourceNanoAppId, int messageType, byte[] messageBody, boolean broadcasted) {
        return new NanoAppMessage(sourceNanoAppId, messageType, messageBody, broadcasted);
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

    private NanoAppMessage(Parcel in) {
        mNanoAppId = in.readLong();
        mIsBroadcasted = (in.readInt() == 1);
        mMessageType = in.readInt();

        int msgSize = in.readInt();
        mMessageBody = new byte[msgSize];
        in.readByteArray(mMessageBody);
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
    }

    public static final Creator<NanoAppMessage> CREATOR =
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

    @Override
    public String toString() {
        int length = mMessageBody.length;

        String ret = "NanoAppMessage[type = " + mMessageType + ", length = " + mMessageBody.length
                + " bytes, " + (mIsBroadcasted ? "broadcast" : "unicast") + ", nanoapp = 0x"
                + Long.toHexString(mNanoAppId) + "](";
        if (length > 0) {
            ret += "data = 0x";
        }
        for (int i = 0; i < Math.min(length, DEBUG_LOG_NUM_BYTES); i++) {
            ret += Byte.toHexString(mMessageBody[i], true /* upperCase */);

            if ((i + 1) % 4 == 0) {
                ret += " ";
            }
        }
        if (length > DEBUG_LOG_NUM_BYTES) {
            ret += "...";
        }
        ret += ")";

        return ret;
    }
}
