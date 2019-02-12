/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.os;

import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper class for sending data from Android OS to StatsD.
 *
 * @hide
 */
public final class StatsLogEventWrapper implements Parcelable {
    static final boolean DEBUG = false;
    static final String TAG = "StatsLogEventWrapper";

    // Keep in sync with FieldValue.h enums
    private static final int EVENT_TYPE_UNKNOWN = 0;
    private static final int EVENT_TYPE_INT = 1; /* int32_t */
    private static final int EVENT_TYPE_LONG = 2; /* int64_t */
    private static final int EVENT_TYPE_FLOAT = 3;
    private static final int EVENT_TYPE_DOUBLE = 4;
    private static final int EVENT_TYPE_STRING = 5;
    private static final int EVENT_TYPE_STORAGE = 6;

    List<Integer> mTypes = new ArrayList<>();
    List<Object> mValues = new ArrayList<>();
    int mTag;
    long mElapsedTimeNs;
    long mWallClockTimeNs;
    WorkSource mWorkSource = null;

    public StatsLogEventWrapper(int tag, long elapsedTimeNs, long wallClockTimeNs) {
        this.mTag = tag;
        this.mElapsedTimeNs = elapsedTimeNs;
        this.mWallClockTimeNs = wallClockTimeNs;
    }

    /**
     * Boilerplate for Parcel.
     */
    public static final Parcelable.Creator<StatsLogEventWrapper> CREATOR = new
            Parcelable.Creator<StatsLogEventWrapper>() {
                public StatsLogEventWrapper createFromParcel(Parcel in) {
                    return new StatsLogEventWrapper(in);
                }

                public StatsLogEventWrapper[] newArray(int size) {
                    return new StatsLogEventWrapper[size];
                }
            };

    private StatsLogEventWrapper(Parcel in) {
        readFromParcel(in);
    }

    /**
     * Set work source if any.
     */
    public void setWorkSource(WorkSource ws) {
        if (ws.getWorkChains() == null || ws.getWorkChains().size() == 0) {
            Slog.w(TAG, "Empty worksource!");
            return;
        }
        mWorkSource = ws;
    }

    /**
     * Write a int value.
     */
    public void writeInt(int val) {
        mTypes.add(EVENT_TYPE_INT);
        mValues.add(val);
    }

    /**
     * Write a long value.
     */
    public void writeLong(long val) {
        mTypes.add(EVENT_TYPE_LONG);
        mValues.add(val);
    }

    /**
     * Write a string value.
     */
    public void writeString(String val) {
        mTypes.add(EVENT_TYPE_STRING);
        // use empty string for null
        mValues.add(val == null ? "" : val);
    }

    /**
     * Write a float value.
     */
    public void writeFloat(float val) {
        mTypes.add(EVENT_TYPE_FLOAT);
        mValues.add(val);
    }

    /**
     * Write a storage value.
     */
    public void writeStorage(byte[] val) {
        mTypes.add(EVENT_TYPE_STORAGE);
        mValues.add(val);
    }

    /**
     * Write a boolean value.
     */
    public void writeBoolean(boolean val) {
        mTypes.add(EVENT_TYPE_INT);
        mValues.add(val ? 1 : 0);
    }

    public void writeToParcel(Parcel out, int flags) {
        if (DEBUG) {
            Slog.d(TAG,
                    "Writing " + mTag + " " + mElapsedTimeNs + " " + mWallClockTimeNs + " and "
                            + mTypes.size() + " elements.");
        }
        out.writeInt(mTag);
        out.writeLong(mElapsedTimeNs);
        out.writeLong(mWallClockTimeNs);
        if (mWorkSource != null) {
            ArrayList<android.os.WorkSource.WorkChain> workChains = mWorkSource.getWorkChains();
            // number of chains
            out.writeInt(workChains.size());
            for (int i = 0; i < workChains.size(); i++) {
                android.os.WorkSource.WorkChain wc = workChains.get(i);
                if (wc.getSize() == 0) {
                    Slog.w(TAG, "Empty work chain.");
                    out.writeInt(0);
                    continue;
                }
                if (wc.getUids().length != wc.getTags().length
                        || wc.getUids().length != wc.getSize()) {
                    Slog.w(TAG, "Malformated work chain.");
                    out.writeInt(0);
                    continue;
                }
                // number of nodes
                out.writeInt(wc.getSize());
                for (int j = 0; j < wc.getSize(); j++) {
                    out.writeInt(wc.getUids()[j]);
                    out.writeString(wc.getTags()[j] == null ? "" : wc.getTags()[j]);
                }
            }
        } else {
            // no chains
            out.writeInt(0);
        }
        out.writeInt(mTypes.size());
        for (int i = 0; i < mTypes.size(); i++) {
            out.writeInt(mTypes.get(i));
            switch (mTypes.get(i)) {
                case EVENT_TYPE_INT:
                    out.writeInt((int) mValues.get(i));
                    break;
                case EVENT_TYPE_LONG:
                    out.writeLong((long) mValues.get(i));
                    break;
                case EVENT_TYPE_FLOAT:
                    out.writeFloat((float) mValues.get(i));
                    break;
                case EVENT_TYPE_DOUBLE:
                    out.writeDouble((double) mValues.get(i));
                    break;
                case EVENT_TYPE_STRING:
                    out.writeString((String) mValues.get(i));
                    break;
                case EVENT_TYPE_STORAGE:
                    out.writeByteArray((byte[]) mValues.get(i));
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Reads from parcel and appropriately fills member fields.
     */
    public void readFromParcel(Parcel in) {
        mTypes = new ArrayList<>();
        mValues = new ArrayList<>();
        mWorkSource = null;

        mTag = in.readInt();
        mElapsedTimeNs = in.readLong();
        mWallClockTimeNs = in.readLong();

        // Clear any data.
        if (DEBUG) {
            Slog.d(TAG, "Reading " + mTag + " " + mElapsedTimeNs + " " + mWallClockTimeNs);
        }
        // Set up worksource if present.
        int numWorkChains = in.readInt();
        if (numWorkChains > 0) {
            mWorkSource = new WorkSource();
            for (int i = 0; i < numWorkChains; i++) {
                android.os.WorkSource.WorkChain workChain = mWorkSource.createWorkChain();
                int workChainSize = in.readInt();
                for (int j = 0; j < workChainSize; j++) {
                    int uid = in.readInt();
                    String tag = in.readString();
                    workChain.addNode(uid, tag);
                }
            }
        }

        // Do the rest of the types.
        int numTypes = in.readInt();
        if (DEBUG) {
            Slog.d(TAG, "Reading " + numTypes + " elements");
        }
        for (int i = 0; i < numTypes; i++) {
            int type = in.readInt();
            mTypes.add(type);
            switch (type) {
                case EVENT_TYPE_INT:
                    mValues.add(in.readInt());
                    break;
                case EVENT_TYPE_LONG:
                    mValues.add(in.readLong());
                    break;
                case EVENT_TYPE_FLOAT:
                    mValues.add(in.readFloat());
                    break;
                case EVENT_TYPE_DOUBLE:
                    mValues.add(in.readDouble());
                    break;
                case EVENT_TYPE_STRING:
                    mValues.add(in.readString());
                    break;
                case EVENT_TYPE_STORAGE:
                    mValues.add(in.createByteArray());
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Boilerplate for Parcel.
     */
    public int describeContents() {
        return 0;
    }
}
