/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.os.Parcelable;
import android.annotation.SystemApi;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the Results of a batched set of wifi scans where the firmware performs many
 * scans and stores the timestamped results without waking the main processor each time.
 * @hide
 * @removed
 */
@Deprecated
@SystemApi
public class BatchedScanResult implements Parcelable {
    private static final String TAG = "BatchedScanResult";

    /** Inidcates this scan was interrupted and may only have partial results. */
    public boolean truncated;

    /** The result of this particular scan. */
    public final List<ScanResult> scanResults = new ArrayList<ScanResult>();


    public BatchedScanResult() {
    }

    public BatchedScanResult(BatchedScanResult source) {
        truncated = source.truncated;
        for (ScanResult s : source.scanResults) scanResults.add(new ScanResult(s));
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("BatchedScanResult: ").
                append("truncated: ").append(String.valueOf(truncated)).
                append("scanResults: [");
        for (ScanResult s : scanResults) {
            sb.append(" <").append(s.toString()).append("> ");
        }
        sb.append(" ]");
        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(truncated ? 1 : 0);
        dest.writeInt(scanResults.size());
        for (ScanResult s : scanResults) {
            s.writeToParcel(dest, flags);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<BatchedScanResult> CREATOR =
        new Creator<BatchedScanResult>() {
            public BatchedScanResult createFromParcel(Parcel in) {
                BatchedScanResult result = new BatchedScanResult();
                result.truncated = (in.readInt() == 1);
                int count = in.readInt();
                while (count-- > 0) {
                    result.scanResults.add(ScanResult.CREATOR.createFromParcel(in));
                }
                return result;
            }

            public BatchedScanResult[] newArray(int size) {
                return new BatchedScanResult[size];
            }
        };
}
