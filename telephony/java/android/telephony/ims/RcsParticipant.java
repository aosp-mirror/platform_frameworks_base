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
import android.os.Parcelable;

/**
 * RcsParticipant is an RCS capable contact that can participate in {@link RcsThread}s.
 * @hide - TODO(sahinc) make this public
 */
public class RcsParticipant implements Parcelable {
    /**
     * Returns the row id of this participant.
     *
     * TODO(sahinc) implement
     * @hide
     */
    public int getId() {
        return 12345;
    }

    public static final Creator<RcsParticipant> CREATOR = new Creator<RcsParticipant>() {
        @Override
        public RcsParticipant createFromParcel(Parcel in) {
            return new RcsParticipant(in);
        }

        @Override
        public RcsParticipant[] newArray(int size) {
            return new RcsParticipant[size];
        }
    };

    protected RcsParticipant(Parcel in) {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {

    }
}
