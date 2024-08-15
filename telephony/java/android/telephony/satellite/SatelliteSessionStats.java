/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * SatelliteSessionStats is used to represent the usage stats of the satellite service.
 * @hide
 */
public class SatelliteSessionStats implements Parcelable {
    private int mCountOfSuccessfulUserMessages;
    private int mCountOfUnsuccessfulUserMessages;
    private int mCountOfTimedOutUserMessagesWaitingForConnection;
    private int mCountOfTimedOutUserMessagesWaitingForAck;
    private int mCountOfUserMessagesInQueueToBeSent;

    /**
     * SatelliteSessionStats constructor
     * @param  builder Builder to create SatelliteSessionStats object/
     */
    public SatelliteSessionStats(@NonNull Builder builder) {
        mCountOfSuccessfulUserMessages = builder.mCountOfSuccessfulUserMessages;
        mCountOfUnsuccessfulUserMessages = builder.mCountOfUnsuccessfulUserMessages;
        mCountOfTimedOutUserMessagesWaitingForConnection =
                builder.mCountOfTimedOutUserMessagesWaitingForConnection;
        mCountOfTimedOutUserMessagesWaitingForAck =
                builder.mCountOfTimedOutUserMessagesWaitingForAck;
        mCountOfUserMessagesInQueueToBeSent = builder.mCountOfUserMessagesInQueueToBeSent;
    }

    private SatelliteSessionStats(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mCountOfSuccessfulUserMessages);
        out.writeInt(mCountOfUnsuccessfulUserMessages);
        out.writeInt(mCountOfTimedOutUserMessagesWaitingForConnection);
        out.writeInt(mCountOfTimedOutUserMessagesWaitingForAck);
        out.writeInt(mCountOfUserMessagesInQueueToBeSent);
    }

    @NonNull
    public static final Creator<SatelliteSessionStats> CREATOR = new Parcelable.Creator<>() {

        @Override
        public SatelliteSessionStats createFromParcel(Parcel in) {
            return new SatelliteSessionStats(in);
        }

        @Override
        public SatelliteSessionStats[] newArray(int size) {
            return new SatelliteSessionStats[size];
        }
    };

    @Override
    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("countOfSuccessfulUserMessages:");
        sb.append(mCountOfSuccessfulUserMessages);
        sb.append(",");

        sb.append("countOfUnsuccessfulUserMessages:");
        sb.append(mCountOfUnsuccessfulUserMessages);
        sb.append(",");

        sb.append("countOfTimedOutUserMessagesWaitingForConnection:");
        sb.append(mCountOfTimedOutUserMessagesWaitingForConnection);
        sb.append(",");

        sb.append("countOfTimedOutUserMessagesWaitingForAck:");
        sb.append(mCountOfTimedOutUserMessagesWaitingForAck);
        sb.append(",");

        sb.append("countOfUserMessagesInQueueToBeSent:");
        sb.append(mCountOfUserMessagesInQueueToBeSent);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SatelliteSessionStats that = (SatelliteSessionStats) o;
        return mCountOfSuccessfulUserMessages == that.mCountOfSuccessfulUserMessages
                && mCountOfUnsuccessfulUserMessages == that.mCountOfUnsuccessfulUserMessages
                && mCountOfTimedOutUserMessagesWaitingForConnection
                == that.mCountOfTimedOutUserMessagesWaitingForConnection
                && mCountOfTimedOutUserMessagesWaitingForAck
                == that.mCountOfTimedOutUserMessagesWaitingForAck
                && mCountOfUserMessagesInQueueToBeSent
                == that.mCountOfUserMessagesInQueueToBeSent;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCountOfSuccessfulUserMessages, mCountOfUnsuccessfulUserMessages,
                mCountOfTimedOutUserMessagesWaitingForConnection,
                mCountOfTimedOutUserMessagesWaitingForAck,
                mCountOfUserMessagesInQueueToBeSent);
    }

    public int getCountOfSuccessfulUserMessages() {
        return mCountOfSuccessfulUserMessages;
    }

    public int getCountOfUnsuccessfulUserMessages() {
        return mCountOfUnsuccessfulUserMessages;
    }

    public int getCountOfTimedOutUserMessagesWaitingForConnection() {
        return mCountOfTimedOutUserMessagesWaitingForConnection;
    }

    public int getCountOfTimedOutUserMessagesWaitingForAck() {
        return mCountOfTimedOutUserMessagesWaitingForAck;
    }

    public int getCountOfUserMessagesInQueueToBeSent() {
        return mCountOfUserMessagesInQueueToBeSent;
    }

    private void readFromParcel(Parcel in) {
        mCountOfSuccessfulUserMessages = in.readInt();
        mCountOfUnsuccessfulUserMessages = in.readInt();
        mCountOfTimedOutUserMessagesWaitingForConnection = in.readInt();
        mCountOfTimedOutUserMessagesWaitingForAck = in.readInt();
        mCountOfUserMessagesInQueueToBeSent = in.readInt();
    }

    /**
     * A builder class to create {@link SatelliteSessionStats} data object.
     */
    public static final class Builder {
        private int mCountOfSuccessfulUserMessages;
        private int mCountOfUnsuccessfulUserMessages;
        private int mCountOfTimedOutUserMessagesWaitingForConnection;
        private int mCountOfTimedOutUserMessagesWaitingForAck;
        private int mCountOfUserMessagesInQueueToBeSent;

        /**
         * Sets countOfSuccessfulUserMessages value of {@link SatelliteSessionStats}
         * and then returns the Builder class.
         */
        @NonNull
        public Builder setCountOfSuccessfulUserMessages(int count) {
            mCountOfSuccessfulUserMessages = count;
            return this;
        }

        /**
         * Sets countOfUnsuccessfulUserMessages value of {@link SatelliteSessionStats}
         * and then returns the Builder class.
         */
        @NonNull
        public Builder setCountOfUnsuccessfulUserMessages(int count) {
            mCountOfUnsuccessfulUserMessages = count;
            return this;
        }

        /**
         * Sets countOfTimedOutUserMessagesWaitingForConnection value of
         * {@link SatelliteSessionStats} and then returns the Builder class.
         */
        @NonNull
        public Builder setCountOfTimedOutUserMessagesWaitingForConnection(int count) {
            mCountOfTimedOutUserMessagesWaitingForConnection = count;
            return this;
        }

        /**
         * Sets countOfTimedOutUserMessagesWaitingForAck value of {@link SatelliteSessionStats}
         * and then returns the Builder class.
         */
        @NonNull
        public Builder setCountOfTimedOutUserMessagesWaitingForAck(int count) {
            mCountOfTimedOutUserMessagesWaitingForAck = count;
            return this;
        }

        /**
         * Sets countOfUserMessagesInQueueToBeSent value of {@link SatelliteSessionStats}
         * and then returns the Builder class.
         */
        @NonNull
        public Builder setCountOfUserMessagesInQueueToBeSent(int count) {
            mCountOfUserMessagesInQueueToBeSent = count;
            return this;
        }

        /** Returns SatelliteSessionStats object. */
        @NonNull
        public SatelliteSessionStats build() {
            return new SatelliteSessionStats(this);
        }
    }
}
