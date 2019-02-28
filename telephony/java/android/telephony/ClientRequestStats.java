/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.TelephonyHistogram;
import android.util.SparseArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Parcelable class to store Client request statistics information.
 *
 * @hide
 */
public final class ClientRequestStats implements Parcelable {
    public static final @android.annotation.NonNull Parcelable.Creator<ClientRequestStats> CREATOR =
            new Parcelable.Creator<ClientRequestStats>() {

                public ClientRequestStats createFromParcel(Parcel in) {
                    return new ClientRequestStats(in);
                }

                public ClientRequestStats[] newArray(int size) {
                    return new ClientRequestStats[size];
                }
            };
    private static final int REQUEST_HISTOGRAM_BUCKET_COUNT = 5;
    private String mCallingPackage;
    /* completed requests wake lock time in milli seconds */
    private long mCompletedRequestsWakelockTime = 0;
    private long mCompletedRequestsCount = 0;
    private long mPendingRequestsWakelockTime = 0;
    private long mPendingRequestsCount = 0;
    private SparseArray<TelephonyHistogram> mRequestHistograms =
            new SparseArray<TelephonyHistogram>();

    public ClientRequestStats(Parcel in) {
        readFromParcel(in);
    }

    public ClientRequestStats() {
    }

    public ClientRequestStats(ClientRequestStats clientRequestStats) {
        mCallingPackage = clientRequestStats.getCallingPackage();
        mCompletedRequestsCount = clientRequestStats.getCompletedRequestsCount();
        mCompletedRequestsWakelockTime = clientRequestStats.getCompletedRequestsWakelockTime();
        mPendingRequestsCount = clientRequestStats.getPendingRequestsCount();
        mPendingRequestsWakelockTime = clientRequestStats.getPendingRequestsWakelockTime();

        List<TelephonyHistogram> list = clientRequestStats.getRequestHistograms();
        for (TelephonyHistogram entry : list) {
            mRequestHistograms.put(entry.getId(), entry);
        }
    }

    public String getCallingPackage() {
        return mCallingPackage;
    }

    public void setCallingPackage(String mCallingPackage) {
        this.mCallingPackage = mCallingPackage;
    }

    public long getCompletedRequestsWakelockTime() {
        return mCompletedRequestsWakelockTime;
    }

    public void addCompletedWakelockTime(long completedRequestsWakelockTime) {
        this.mCompletedRequestsWakelockTime += completedRequestsWakelockTime;
    }

    public long getPendingRequestsWakelockTime() {
        return mPendingRequestsWakelockTime;
    }

    public void setPendingRequestsWakelockTime(long pendingRequestsWakelockTime) {
        this.mPendingRequestsWakelockTime = pendingRequestsWakelockTime;
    }

    public long getCompletedRequestsCount() {
        return mCompletedRequestsCount;
    }

    public void incrementCompletedRequestsCount() {
        this.mCompletedRequestsCount++;
    }

    public long getPendingRequestsCount() {
        return mPendingRequestsCount;
    }

    public void setPendingRequestsCount(long pendingRequestsCount) {
        this.mPendingRequestsCount = pendingRequestsCount;
    }

    public List<TelephonyHistogram> getRequestHistograms() {
        List<TelephonyHistogram> list;
        synchronized (mRequestHistograms) {
            list = new ArrayList<>(mRequestHistograms.size());
            for (int i = 0; i < mRequestHistograms.size(); i++) {
                TelephonyHistogram entry = new TelephonyHistogram(mRequestHistograms.valueAt(i));
                list.add(entry);
            }
        }
        return list;
    }

    public void updateRequestHistograms(int requestId, int time) {
        synchronized (mRequestHistograms) {
            TelephonyHistogram entry = mRequestHistograms.get(requestId);
            if (entry == null) {
                entry = new TelephonyHistogram(TelephonyHistogram.TELEPHONY_CATEGORY_RIL,
                        requestId, REQUEST_HISTOGRAM_BUCKET_COUNT);
                mRequestHistograms.put(requestId, entry);
            }
            entry.addTimeTaken(time);
        }
    }

    @Override
    public String toString() {
        return "ClientRequestStats{" +
                "mCallingPackage='" + mCallingPackage + '\'' +
                ", mCompletedRequestsWakelockTime=" + mCompletedRequestsWakelockTime +
                ", mCompletedRequestsCount=" + mCompletedRequestsCount +
                ", mPendingRequestsWakelockTime=" + mPendingRequestsWakelockTime +
                ", mPendingRequestsCount=" + mPendingRequestsCount +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel in) {
        mCallingPackage = in.readString();
        mCompletedRequestsWakelockTime = in.readLong();
        mCompletedRequestsCount = in.readLong();
        mPendingRequestsWakelockTime = in.readLong();
        mPendingRequestsCount = in.readLong();
        ArrayList<TelephonyHistogram> requestHistograms = new ArrayList<TelephonyHistogram>();
        in.readTypedList(requestHistograms, TelephonyHistogram.CREATOR);
        for (TelephonyHistogram h : requestHistograms) {
            mRequestHistograms.put(h.getId(), h);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mCallingPackage);
        dest.writeLong(mCompletedRequestsWakelockTime);
        dest.writeLong(mCompletedRequestsCount);
        dest.writeLong(mPendingRequestsWakelockTime);
        dest.writeLong(mPendingRequestsCount);
        dest.writeTypedList(getRequestHistograms());
    }
}
