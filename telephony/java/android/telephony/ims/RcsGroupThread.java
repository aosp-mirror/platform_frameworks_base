/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.telephony.ims;

import android.os.Parcel;

/**
 * RcsGroupThread represents a single RCS conversation thread where {@link RcsParticipant}s can join
 * or leave.
 * @hide - TODO(sahinc) make this public
 */
public class RcsGroupThread extends RcsThread {
    public static final Creator<RcsGroupThread> CREATOR = new Creator<RcsGroupThread>() {
        @Override
        public RcsGroupThread createFromParcel(Parcel in) {
            return new RcsGroupThread(in);
        }

        @Override
        public RcsGroupThread[] newArray(int size) {
            return new RcsGroupThread[size];
        }
    };

    protected RcsGroupThread(Parcel in) {
        super(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(RCS_GROUP_TYPE);
        super.writeToParcel(dest, flags);
    }
}
