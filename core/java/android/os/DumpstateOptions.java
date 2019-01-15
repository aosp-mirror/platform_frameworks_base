/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * Options passed to dumpstate service.
 *
 * @hide
 */
public final class DumpstateOptions implements Parcelable {
    // If true the caller can get callbacks with per-section
    // progress details.
    private final boolean mGetSectionDetails;
    // Name of the caller.
    private final String mName;

    public DumpstateOptions(Parcel in) {
        mGetSectionDetails = in.readBoolean();
        mName = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
     public void writeToParcel(Parcel out, int flags) {
        out.writeBoolean(mGetSectionDetails);
        out.writeString(mName);
    }

    public static final Parcelable.Creator<DumpstateOptions> CREATOR =
            new Parcelable.Creator<DumpstateOptions>() {
        public DumpstateOptions createFromParcel(Parcel in) {
            return new DumpstateOptions(in);
        }

        public DumpstateOptions[] newArray(int size) {
            return new DumpstateOptions[size];
        }
    };
}
