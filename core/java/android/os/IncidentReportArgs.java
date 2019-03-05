/*
 * Copyright (C) 2005 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;

import java.util.ArrayList;

/**
 * The arguments for an incident report.
 * {@hide}
 */
@SystemApi
@TestApi
public final class IncidentReportArgs implements Parcelable {

    private final IntArray mSections = new IntArray();
    private final ArrayList<byte[]> mHeaders = new ArrayList<byte[]>();
    private boolean mAll;
    private int mPrivacyPolicy;
    private String mReceiverPkg;
    private String mReceiverCls;

    /**
     * Construct an incident report args with no fields.
     */
    public IncidentReportArgs() {
        mPrivacyPolicy = IncidentManager.PRIVACY_POLICY_AUTO;
    }

    /**
     * Construct an incdent report args from the given parcel.
     */
    public IncidentReportArgs(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mAll ? 1 : 0);

        int N = mSections.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            out.writeInt(mSections.get(i));
        }

        N = mHeaders.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            out.writeByteArray(mHeaders.get(i));
        }

        out.writeInt(mPrivacyPolicy);

        out.writeString(mReceiverPkg);

        out.writeString(mReceiverCls);
    }

    public void readFromParcel(Parcel in) {
        mAll = in.readInt() != 0;

        mSections.clear();
        int N = in.readInt();
        for (int i=0; i<N; i++) {
            mSections.add(in.readInt());
        }

        mHeaders.clear();
        N = in.readInt();
        for (int i=0; i<N; i++) {
            mHeaders.add(in.createByteArray());
        }

        mPrivacyPolicy = in.readInt();

        mReceiverPkg = in.readString();

        mReceiverCls = in.readString();
    }

    public static final Parcelable.Creator<IncidentReportArgs> CREATOR
            = new Parcelable.Creator<IncidentReportArgs>() {
        public IncidentReportArgs createFromParcel(Parcel in) {
            return new IncidentReportArgs(in);
        }

        public IncidentReportArgs[] newArray(int size) {
            return new IncidentReportArgs[size];
        }
    };

    /**
     * Print this report as a string.
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Incident(");
        if (mAll) {
            sb.append("all");
        } else {
            final int N = mSections.size();
            if (N > 0) {
                sb.append(mSections.get(0));
            }
            for (int i=1; i<N; i++) {
                sb.append(" ");
                sb.append(mSections.get(i));
            }
        }
        sb.append(", ");
        sb.append(mHeaders.size());
        sb.append(" headers), ");
        sb.append("privacy: ").append(mPrivacyPolicy);
        sb.append("receiver pkg: ").append(mReceiverPkg);
        sb.append("receiver cls: ").append(mReceiverCls);
        return sb.toString();
    }

    /**
     * Set this incident report to include all fields.
     */
    public void setAll(boolean all) {
        mAll = all;
        if (all) {
            mSections.clear();
        }
    }

    /**
     * Set this incident report privacy policy spec.
     */
    public void setPrivacyPolicy(int privacyPolicy) {
        switch (privacyPolicy) {
            case IncidentManager.PRIVACY_POLICY_LOCAL:
            case IncidentManager.PRIVACY_POLICY_EXPLICIT:
            case IncidentManager.PRIVACY_POLICY_AUTO:
                mPrivacyPolicy = privacyPolicy;
                break;
            default:
                mPrivacyPolicy = IncidentManager.PRIVACY_POLICY_AUTO;
        }
    }

    /**
     * Add this section to the incident report. Skip if the input is smaller than 2 since section
     * id are only valid for positive integer as Protobuf field id. Here 1 is reserved for Header.
     */
    public void addSection(int section) {
        if (!mAll && section > 1) {
            mSections.add(section);
        }
    }

    /**
     * Returns whether the incident report will include all fields.
     */
    public boolean isAll() {
        return mAll;
    }

    /**
     * Returns whether this section will be included in the incident report.
     */
    public boolean containsSection(int section) {
        return mAll || mSections.indexOf(section) >= 0;
    }

    public int sectionCount() {
        return mSections.size();
    }

    public void addHeader(byte[] header) {
        mHeaders.add(header);
    }
}

