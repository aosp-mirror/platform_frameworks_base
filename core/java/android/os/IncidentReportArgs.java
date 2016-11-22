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
import android.content.Intent;
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

    /**
     * Construct an incident report args with no fields.
     */
    public IncidentReportArgs() {
    }

    /**
     * Construct an incdent report args from the given parcel.
     */
    public IncidentReportArgs(Parcel in) {
        readFromParcel(in);
    }

    public int describeContents() {
        return 0;
    }

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
        sb.append(" headers)");
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
     * Add this section to the incident report.
     */
    public void addSection(int section) {
        if (!mAll) {
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

    /**
     * Parses an incident report config as described in the system setting.
     *
     * @see IncidentManager#reportIncident
     */
    public static IncidentReportArgs parseSetting(String setting)
            throws IllegalArgumentException {
        if (setting == null || setting.length() == 0) {
            return null;
        }
        setting = setting.trim();
        if (setting.length() == 0 || "disabled".equals(setting)) {
            return null;
        }

        final IncidentReportArgs args = new IncidentReportArgs();

        if ("all".equals(setting)) {
            args.setAll(true);
            return args;
        } else if ("none".equals(setting)) {
            return args;
        }

        final String[] splits = setting.split(",");
        final int N = splits.length;
        for (int i=0; i<N; i++) {
            final String str = splits[i].trim();
            if (str.length() == 0) {
                continue;
            }
            int section;
            try {
                section = Integer.parseInt(str);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Malformed setting. Bad integer at section"
                        + " index " + i + ": section='" + str + "' setting='" + setting + "'");
            }
            if (section < 1) {
                throw new IllegalArgumentException("Malformed setting. Illegal section at"
                        + " index " + i + ": section='" + str + "' setting='" + setting + "'");
            }
            args.addSection(section);
        }

        return args;
    }
}

