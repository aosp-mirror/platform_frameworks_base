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
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SatelliteSessionStats is used to represent the usage stats of the satellite service.
 *
 * @hide
 */
public final class SatelliteSessionStats implements Parcelable {
    private int mCountOfSuccessfulUserMessages;
    private int mCountOfUnsuccessfulUserMessages;
    private int mCountOfTimedOutUserMessagesWaitingForConnection;
    private int mCountOfTimedOutUserMessagesWaitingForAck;
    private int mCountOfUserMessagesInQueueToBeSent;
    private long mLatencyOfSuccessfulUserMessages;

    private Map<Integer, SatelliteSessionStats> datagramStats;
    private long mMaxLatency;
    private long mLastMessageLatency;

    public SatelliteSessionStats() {
        this.datagramStats = new HashMap<>();
    }

    /**
     * SatelliteSessionStats constructor
     *
     * @param builder Builder to create SatelliteSessionStats object/
     */
    public SatelliteSessionStats(@NonNull Builder builder) {
        mCountOfSuccessfulUserMessages = builder.mCountOfSuccessfulUserMessages;
        mCountOfUnsuccessfulUserMessages = builder.mCountOfUnsuccessfulUserMessages;
        mCountOfTimedOutUserMessagesWaitingForConnection =
                builder.mCountOfTimedOutUserMessagesWaitingForConnection;
        mCountOfTimedOutUserMessagesWaitingForAck =
                builder.mCountOfTimedOutUserMessagesWaitingForAck;
        mCountOfUserMessagesInQueueToBeSent = builder.mCountOfUserMessagesInQueueToBeSent;
        mLatencyOfSuccessfulUserMessages = builder.mLatencyOfSuccessfulUserMessages;
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
        out.writeLong(mLatencyOfSuccessfulUserMessages);
        out.writeLong(mMaxLatency);
        out.writeLong(mLastMessageLatency);

        if (datagramStats != null && !datagramStats.isEmpty()) {
            out.writeInt(datagramStats.size());
            for (Map.Entry<Integer, SatelliteSessionStats> entry : datagramStats.entrySet()) {
                out.writeInt(entry.getKey());
                out.writeParcelable(entry.getValue(), flags);
            }
        } else {
            out.writeInt(0);
        }
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
        if (datagramStats != null) {
            sb.append(" ====== SatelliteSessionStatsWrapper Info =============");
            for (Map.Entry<Integer, SatelliteSessionStats> entry : datagramStats.entrySet()) {
                Integer key = entry.getKey();
                SatelliteSessionStats value = entry.getValue();
                sb.append("\n");
                sb.append("Key:");
                sb.append(key);
                sb.append(", SatelliteSessionStats:[");
                value.getPrintableCounters(sb);
                sb.append(",");
                sb.append(" LatencyOfSuccessfulUserMessages:");
                sb.append(value.mLatencyOfSuccessfulUserMessages);
                sb.append(",");
                sb.append(" mMaxLatency:");
                sb.append(value.mMaxLatency);
                sb.append(",");
                sb.append(" mLastMessageLatency:");
                sb.append(value.mLastMessageLatency);
                sb.append("]");
                sb.append("\n");
            }
            sb.append(" ============== ================== ===============");
            sb.append("\n");
            sb.append("\n");
        } else {
            sb.append("\n");
            getPrintableCounters(sb);
        }
        sb.append("\n");
        return sb.toString();
    }

    private void getPrintableCounters(StringBuilder sb) {
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
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SatelliteSessionStats that = (SatelliteSessionStats) o;
        return mCountOfSuccessfulUserMessages == that.mCountOfSuccessfulUserMessages
                && mLatencyOfSuccessfulUserMessages == that.mLatencyOfSuccessfulUserMessages
                && mCountOfUnsuccessfulUserMessages == that.mCountOfUnsuccessfulUserMessages
                && mCountOfTimedOutUserMessagesWaitingForConnection
                == that.mCountOfTimedOutUserMessagesWaitingForConnection
                && mCountOfTimedOutUserMessagesWaitingForAck
                == that.mCountOfTimedOutUserMessagesWaitingForAck
                && mCountOfUserMessagesInQueueToBeSent == that.mCountOfUserMessagesInQueueToBeSent;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCountOfSuccessfulUserMessages, mLatencyOfSuccessfulUserMessages,
                mCountOfUnsuccessfulUserMessages, mCountOfTimedOutUserMessagesWaitingForConnection,
                mCountOfTimedOutUserMessagesWaitingForAck, mCountOfUserMessagesInQueueToBeSent);
    }

    public int getCountOfSuccessfulUserMessages() {
        return mCountOfSuccessfulUserMessages;
    }

    public void incrementSuccessfulUserMessageCount() {
        mCountOfSuccessfulUserMessages++;
    }

    public int getCountOfUnsuccessfulUserMessages() {
        return mCountOfUnsuccessfulUserMessages;
    }

    public void incrementUnsuccessfulUserMessageCount() {
        mCountOfUnsuccessfulUserMessages++;
    }

    public int getCountOfTimedOutUserMessagesWaitingForConnection() {
        return mCountOfTimedOutUserMessagesWaitingForConnection;
    }

    public void incrementTimedOutUserMessagesWaitingForConnection() {
        mCountOfTimedOutUserMessagesWaitingForConnection++;
    }

    public int getCountOfTimedOutUserMessagesWaitingForAck() {
        return mCountOfTimedOutUserMessagesWaitingForAck;
    }

    public void incrementTimedOutUserMessagesWaitingForAck() {
        mCountOfTimedOutUserMessagesWaitingForAck++;
    }

    public int getCountOfUserMessagesInQueueToBeSent() {
        return mCountOfUserMessagesInQueueToBeSent;
    }

    public void incrementUserMessagesInQueueToBeSent() {
        mCountOfUserMessagesInQueueToBeSent++;
    }

    public long getLatencyOfAllSuccessfulUserMessages() {
        return mLatencyOfSuccessfulUserMessages;
    }

    public void updateLatencyOfAllSuccessfulUserMessages(long messageLatency) {
        mLatencyOfSuccessfulUserMessages += messageLatency;
    }

    public void recordSuccessfulOutgoingDatagramStats(
            @SatelliteManager.DatagramType int datagramType, long latency) {
        try {
            datagramStats.putIfAbsent(datagramType, new SatelliteSessionStats.Builder().build());
            SatelliteSessionStats data = datagramStats.get(datagramType);
            data.incrementSuccessfulUserMessageCount();
            if (data.mMaxLatency < latency) {
                data.mMaxLatency = latency;
            }
            data.mLastMessageLatency = latency;
            data.updateLatencyOfAllSuccessfulUserMessages(latency);
        } catch (Exception e) {
            Log.e("SatelliteSessionStats",
                    "Error while recordSuccessfulOutgoingDatagramStats: " + e.getMessage());
        }
    }

    public void resetCountOfUserMessagesInQueueToBeSent() {
        for (Map.Entry<Integer, SatelliteSessionStats> entry : datagramStats.entrySet()) {
            SatelliteSessionStats statsPerDatagramType = entry.getValue();
            statsPerDatagramType.mCountOfUserMessagesInQueueToBeSent = 0;
        }
    }

    public int getCountOfSuccessfulOutgoingDatagram(
            @SatelliteManager.DatagramType int datagramType) {
        SatelliteSessionStats data = datagramStats.getOrDefault(datagramType,
                new SatelliteSessionStats());
        return data.getCountOfSuccessfulUserMessages();
    }

    public long getMaxLatency() {
        return this.mMaxLatency;
    }

    public Long getLatencyOfAllSuccessfulUserMessages(
            @SatelliteManager.DatagramType int datagramType) {
        SatelliteSessionStats data = datagramStats.getOrDefault(datagramType,
                new SatelliteSessionStats());
        return data.getLatencyOfAllSuccessfulUserMessages();
    }


    public long getLastMessageLatency() {
        return this.mLastMessageLatency;
    }

    public void addCountOfUnsuccessfulUserMessages(@SatelliteManager.DatagramType int datagramType,
            @SatelliteManager.SatelliteResult int resultCode) {
        try {
            datagramStats.putIfAbsent(datagramType, new SatelliteSessionStats.Builder().build());
            SatelliteSessionStats data = datagramStats.get(datagramType);
            data.incrementUnsuccessfulUserMessageCount();
            if (resultCode == SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE) {
                data.incrementTimedOutUserMessagesWaitingForConnection();
            } else if (resultCode == SatelliteManager.SATELLITE_RESULT_MODEM_TIMEOUT) {
                data.incrementTimedOutUserMessagesWaitingForAck();
            }
        } catch (Exception e) {
            Log.e("SatelliteSessionStats",
                    "Error while addCountOfUnsuccessfulUserMessages: " + e.getMessage());
        }
    }

    public void updateCountOfUserMessagesInQueueToBeSent(
            @SatelliteManager.DatagramType int datagramType) {
        try {
            datagramStats.putIfAbsent(datagramType, new SatelliteSessionStats.Builder().build());
            SatelliteSessionStats data = datagramStats.get(datagramType);
            data.incrementUserMessagesInQueueToBeSent();
        } catch (Exception e) {
            Log.e("SatelliteSessionStats",
                    "Error while addCountOfUserMessagesInQueueToBeSent: " + e.getMessage());
        }
    }

    public int getCountOfUnsuccessfulUserMessages(@SatelliteManager.DatagramType int datagramType) {
        SatelliteSessionStats data = datagramStats.get(datagramType);
        return data.getCountOfUnsuccessfulUserMessages();
    }

    public int getCountOfTimedOutUserMessagesWaitingForConnection(
            @SatelliteManager.DatagramType int datagramType) {
        SatelliteSessionStats data = datagramStats.get(datagramType);
        return data.getCountOfTimedOutUserMessagesWaitingForConnection();
    }

    public int getCountOfTimedOutUserMessagesWaitingForAck(
            @SatelliteManager.DatagramType int datagramType) {
        SatelliteSessionStats data = datagramStats.get(datagramType);
        return data.getCountOfTimedOutUserMessagesWaitingForAck();
    }

    public int getCountOfUserMessagesInQueueToBeSent(
            @SatelliteManager.DatagramType int datagramType) {
        SatelliteSessionStats data = datagramStats.get(datagramType);
        return data.getCountOfUserMessagesInQueueToBeSent();
    }

    public void clear() {
        datagramStats.clear();
    }

    public Map<Integer, SatelliteSessionStats> getSatelliteSessionStats() {
        return datagramStats;
    }

    public void setSatelliteSessionStats(Map<Integer, SatelliteSessionStats> sessionStats) {
        this.datagramStats = sessionStats;
    }

    private void readFromParcel(Parcel in) {
        mCountOfSuccessfulUserMessages = in.readInt();
        mCountOfUnsuccessfulUserMessages = in.readInt();
        mCountOfTimedOutUserMessagesWaitingForConnection = in.readInt();
        mCountOfTimedOutUserMessagesWaitingForAck = in.readInt();
        mCountOfUserMessagesInQueueToBeSent = in.readInt();
        mLatencyOfSuccessfulUserMessages = in.readLong();
        mMaxLatency = in.readLong();
        mLastMessageLatency = in.readLong();

        int size = in.readInt();
        datagramStats = new HashMap<>();
        for (int i = 0; i < size; i++) {
            Integer key = in.readInt();
            SatelliteSessionStats value = in.readParcelable(
                    SatelliteSessionStats.class.getClassLoader());
            datagramStats.put(key, value);
        }
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
        private long mLatencyOfSuccessfulUserMessages;

        private long mMaxLatency;
        private long mLastMessageLatency;
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

        @NonNull
        public Builder setLatencyOfSuccessfulUserMessages(long latency) {
            mLatencyOfSuccessfulUserMessages = latency;
            return this;
        }

        @NonNull
        public Builder setMaxLatency(long maxLatency) {
            mMaxLatency = maxLatency;
            return this;
        }

        @NonNull
        public Builder setLastLatency(long lastLatency) {
            mLastMessageLatency = lastLatency;
            return this;
        }

        /** Returns SatelliteSessionStats object. */
        @NonNull
        public SatelliteSessionStats build() {
            return new SatelliteSessionStats(this);
        }
    }
}