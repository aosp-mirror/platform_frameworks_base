/*
** Copyright 2015, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/**
 * Response for querying available cellular networks.
 *
 * @hide
 */
public class CellNetworkScanResult implements Parcelable {

    /**
     * Possible status values.
     */
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_RADIO_NOT_AVAILABLE = 2;
    public static final int STATUS_RADIO_GENERIC_FAILURE = 3;
    public static final int STATUS_UNKNOWN_ERROR = 4;

    private final int mStatus;
    private final List<OperatorInfo> mOperators;

    /**
     * Constructor.
     *
     * @hide
     */
    public CellNetworkScanResult(int status, List<OperatorInfo> operators) {
        mStatus = status;
        mOperators = operators;
    }

    /**
     * Construct a CellNetworkScanResult from a given parcel.
     */
    private CellNetworkScanResult(Parcel in) {
        mStatus = in.readInt();
        int len = in.readInt();
        if (len > 0) {
            mOperators = new ArrayList();
            for (int i = 0; i < len; ++i) {
                mOperators.add(OperatorInfo.CREATOR.createFromParcel(in));
            }
        } else {
            mOperators = null;
        }
    }

    /**
     * @return the status of the command.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * @return the operators.
     */
    public List<OperatorInfo> getOperators() {
        return mOperators;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mStatus);
        if (mOperators != null && mOperators.size() > 0) {
            out.writeInt(mOperators.size());
            for (OperatorInfo network : mOperators) {
                network.writeToParcel(out, flags);
            }
        } else {
            out.writeInt(0);
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("CellNetworkScanResult: {");
        sb.append(" status:").append(mStatus);
        if (mOperators != null) {
            for (OperatorInfo network : mOperators) {
              sb.append(" network:").append(network);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    public static final Parcelable.Creator<CellNetworkScanResult> CREATOR
             = new Parcelable.Creator<CellNetworkScanResult>() {

        @Override
        public CellNetworkScanResult createFromParcel(Parcel in) {
             return new CellNetworkScanResult(in);
         }

         public CellNetworkScanResult[] newArray(int size) {
             return new CellNetworkScanResult[size];
         }
     };
}
