/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing the result of a WPS request
 * @hide
 */
public class WpsResult implements Parcelable {

    public enum Status {
        SUCCESS,
        FAILURE,
        IN_PROGRESS,
    }

    public Status status;

    public String pin;

    public WpsResult() {
        status = Status.FAILURE;
        pin = null;
    }

    public WpsResult(Status s) {
        status = s;
        pin = null;
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append(" status: ").append(status.toString());
        sbuf.append('\n');
        sbuf.append(" pin: ").append(pin);
        sbuf.append("\n");
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    public WpsResult(WpsResult source) {
        if (source != null) {
            status = source.status;
            pin = source.pin;
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(status.name());
        dest.writeString(pin);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final @android.annotation.NonNull Creator<WpsResult> CREATOR =
        new Creator<WpsResult>() {
            public WpsResult createFromParcel(Parcel in) {
                WpsResult result = new WpsResult();
                result.status = Status.valueOf(in.readString());
                result.pin = in.readString();
                return result;
            }

            public WpsResult[] newArray(int size) {
                return new WpsResult[size];
            }
        };
}
