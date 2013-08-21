/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.net;

import android.os.Parcel;
import android.net.LinkInfo;

/**
 *  Class that represents useful attributes of mobile network links
 *  such as the upload/download throughput or error rate etc.
 *  @hide
 */
public final class MobileLinkInfo extends LinkInfo
{
    // Represents TelephonyManager.NetworkType
    public int mMobileNetworkType = UNKNOWN;
    public int mRssi = UNKNOWN;
    public int mGsmErrorRate = UNKNOWN;
    public int mCdmaDbm = UNKNOWN;
    public int mCdmaEcio = UNKNOWN;
    public int mEvdoDbm = UNKNOWN;
    public int mEvdoEcio = UNKNOWN;
    public int mEvdoSnr = UNKNOWN;
    public int mLteSignalStrength = UNKNOWN;
    public int mLteRsrp = UNKNOWN;
    public int mLteRsrq = UNKNOWN;
    public int mLteRssnr = UNKNOWN;
    public int mLteCqi = UNKNOWN;

    /**
     * Implement the Parcelable interface.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags, OBJECT_TYPE_MOBILE_LINKINFO);

        dest.writeInt(mMobileNetworkType);
        dest.writeInt(mRssi);
        dest.writeInt(mGsmErrorRate);
        dest.writeInt(mCdmaDbm);
        dest.writeInt(mCdmaEcio);
        dest.writeInt(mEvdoDbm);
        dest.writeInt(mEvdoEcio);
        dest.writeInt(mEvdoSnr);
        dest.writeInt(mLteSignalStrength);
        dest.writeInt(mLteRsrp);
        dest.writeInt(mLteRsrq);
        dest.writeInt(mLteRssnr);
        dest.writeInt(mLteCqi);
    }

    /* Un-parceling helper */
    public static MobileLinkInfo createFromParcelBody(Parcel in) {

        MobileLinkInfo li = new MobileLinkInfo();

        li.initializeFromParcel(in);

        li.mMobileNetworkType = in.readInt();
        li.mRssi = in.readInt();
        li.mGsmErrorRate = in.readInt();
        li.mCdmaDbm = in.readInt();
        li.mCdmaEcio = in.readInt();
        li.mEvdoDbm = in.readInt();
        li.mEvdoEcio = in.readInt();
        li.mEvdoSnr = in.readInt();
        li.mLteSignalStrength = in.readInt();
        li.mLteRsrp = in.readInt();
        li.mLteRsrq = in.readInt();
        li.mLteRssnr = in.readInt();
        li.mLteCqi = in.readInt();

        return li;
    }
}
