/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.ArrayList;

/** @hide */
public class SyncStatusInfo implements Parcelable {
    private static final String TAG = "Sync";

    static final int VERSION = 4;

    private static final int MAX_EVENT_COUNT = 10;

    public final int authorityId;
    public long totalElapsedTime;
    public int numSyncs;
    public int numSourcePoll;
    public int numSourceServer;
    public int numSourceLocal;
    public int numSourceUser;
    public int numSourcePeriodic;
    public long lastSuccessTime;
    public int lastSuccessSource;
    public long lastFailureTime;
    public int lastFailureSource;
    public String lastFailureMesg;
    public long initialFailureTime;
    public boolean pending;
    public boolean initialize;
    
  // Warning: It is up to the external caller to ensure there are
  // no race conditions when accessing this list
  private ArrayList<Long> periodicSyncTimes;

    private final ArrayList<Long> mLastEventTimes = new ArrayList<>();
    private final ArrayList<String> mLastEvents = new ArrayList<>();

    public SyncStatusInfo(int authorityId) {
        this.authorityId = authorityId;
    }

    public int getLastFailureMesgAsInt(int def) {
        final int i = ContentResolver.syncErrorStringToInt(lastFailureMesg);
        if (i > 0) {
            return i;
        } else {
            Log.d(TAG, "Unknown lastFailureMesg:" + lastFailureMesg);
            return def;
        }
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(VERSION);
        parcel.writeInt(authorityId);
        parcel.writeLong(totalElapsedTime);
        parcel.writeInt(numSyncs);
        parcel.writeInt(numSourcePoll);
        parcel.writeInt(numSourceServer);
        parcel.writeInt(numSourceLocal);
        parcel.writeInt(numSourceUser);
        parcel.writeLong(lastSuccessTime);
        parcel.writeInt(lastSuccessSource);
        parcel.writeLong(lastFailureTime);
        parcel.writeInt(lastFailureSource);
        parcel.writeString(lastFailureMesg);
        parcel.writeLong(initialFailureTime);
        parcel.writeInt(pending ? 1 : 0);
        parcel.writeInt(initialize ? 1 : 0);
        if (periodicSyncTimes != null) {
            parcel.writeInt(periodicSyncTimes.size());
            for (long periodicSyncTime : periodicSyncTimes) {
                parcel.writeLong(periodicSyncTime);
            }
        } else {
            parcel.writeInt(-1);
        }
        parcel.writeInt(mLastEventTimes.size());
        for (int i = 0; i < mLastEventTimes.size(); i++) {
            parcel.writeLong(mLastEventTimes.get(i));
            parcel.writeString(mLastEvents.get(i));
        }
        parcel.writeInt(numSourcePeriodic);
    }

    public SyncStatusInfo(Parcel parcel) {
        int version = parcel.readInt();
        if (version != VERSION && version != 1) {
            Log.w("SyncStatusInfo", "Unknown version: " + version);
        }
        authorityId = parcel.readInt();
        totalElapsedTime = parcel.readLong();
        numSyncs = parcel.readInt();
        numSourcePoll = parcel.readInt();
        numSourceServer = parcel.readInt();
        numSourceLocal = parcel.readInt();
        numSourceUser = parcel.readInt();
        lastSuccessTime = parcel.readLong();
        lastSuccessSource = parcel.readInt();
        lastFailureTime = parcel.readLong();
        lastFailureSource = parcel.readInt();
        lastFailureMesg = parcel.readString();
        initialFailureTime = parcel.readLong();
        pending = parcel.readInt() != 0;
        initialize = parcel.readInt() != 0;
        if (version == 1) {
            periodicSyncTimes = null;
        } else {
            final int count = parcel.readInt();
            if (count < 0) {
                periodicSyncTimes = null;
            } else {
                periodicSyncTimes = new ArrayList<Long>();
                for (int i = 0; i < count; i++) {
                    periodicSyncTimes.add(parcel.readLong());
                }
            }
            if (version >= 3) {
                mLastEventTimes.clear();
                mLastEvents.clear();
                final int nEvents = parcel.readInt();
                for (int i = 0; i < nEvents; i++) {
                    mLastEventTimes.add(parcel.readLong());
                    mLastEvents.add(parcel.readString());
                }
            }
        }
        if (version < 4) {
            // Before version 4, numSourcePeriodic wasn't persisted.
            numSourcePeriodic = numSyncs - numSourceLocal - numSourcePoll - numSourceServer
                    - numSourceUser;
            if (numSourcePeriodic < 0) { // Sanity check.
                numSourcePeriodic = 0;
            }
        } else {
            numSourcePeriodic = parcel.readInt();
        }
    }

    public SyncStatusInfo(SyncStatusInfo other) {
        authorityId = other.authorityId;
        totalElapsedTime = other.totalElapsedTime;
        numSyncs = other.numSyncs;
        numSourcePoll = other.numSourcePoll;
        numSourceServer = other.numSourceServer;
        numSourceLocal = other.numSourceLocal;
        numSourceUser = other.numSourceUser;
        numSourcePeriodic = other.numSourcePeriodic;
        lastSuccessTime = other.lastSuccessTime;
        lastSuccessSource = other.lastSuccessSource;
        lastFailureTime = other.lastFailureTime;
        lastFailureSource = other.lastFailureSource;
        lastFailureMesg = other.lastFailureMesg;
        initialFailureTime = other.initialFailureTime;
        pending = other.pending;
        initialize = other.initialize;
        if (other.periodicSyncTimes != null) {
            periodicSyncTimes = new ArrayList<Long>(other.periodicSyncTimes);
        }
        mLastEventTimes.addAll(other.mLastEventTimes);
        mLastEvents.addAll(other.mLastEvents);
    }

    public void setPeriodicSyncTime(int index, long when) {
        // The list is initialized lazily when scheduling occurs so we need to make sure
        // we initialize elements < index to zero (zero is ignore for scheduling purposes)
        ensurePeriodicSyncTimeSize(index);
        periodicSyncTimes.set(index, when);
    }

    public long getPeriodicSyncTime(int index) {
        if (periodicSyncTimes != null && index < periodicSyncTimes.size()) {
            return periodicSyncTimes.get(index);
        } else {
            return 0;
        }
    }

    public void removePeriodicSyncTime(int index) {
        if (periodicSyncTimes != null && index < periodicSyncTimes.size()) {
            periodicSyncTimes.remove(index);
        }
    }

    /** */
    public void addEvent(String message) {
        if (mLastEventTimes.size() >= MAX_EVENT_COUNT) {
            mLastEventTimes.remove(MAX_EVENT_COUNT - 1);
            mLastEvents.remove(MAX_EVENT_COUNT - 1);
        }
        mLastEventTimes.add(0, System.currentTimeMillis());
        mLastEvents.add(0, message);
    }

    /** */
    public int getEventCount() {
        return mLastEventTimes.size();
    }

    /** */
    public long getEventTime(int i) {
        return mLastEventTimes.get(i);
    }

    /** */
    public String getEvent(int i) {
        return mLastEvents.get(i);
    }

    public static final Creator<SyncStatusInfo> CREATOR = new Creator<SyncStatusInfo>() {
        public SyncStatusInfo createFromParcel(Parcel in) {
            return new SyncStatusInfo(in);
        }

        public SyncStatusInfo[] newArray(int size) {
            return new SyncStatusInfo[size];
        }
    };

    private void ensurePeriodicSyncTimeSize(int index) {
        if (periodicSyncTimes == null) {
            periodicSyncTimes = new ArrayList<Long>(0);
        }

        final int requiredSize = index + 1;
        if (periodicSyncTimes.size() < requiredSize) {
            for (int i = periodicSyncTimes.size(); i < requiredSize; i++) {
                periodicSyncTimes.add((long) 0);
            }
        }
    }
}