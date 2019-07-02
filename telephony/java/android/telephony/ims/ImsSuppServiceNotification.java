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
 * limitations under the License
 */


package android.telephony.ims;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;


/**
 * Parcelable object to handle IMS supplementary service notifications.
 *
 * @hide
 */
@SystemApi
public final class ImsSuppServiceNotification implements Parcelable {
    private static final String TAG = "ImsSuppServiceNotification";

    /** Type of notification: 0 = MO; 1 = MT */
    public final int notificationType;
    /** TS 27.007 7.17 "code1" or "code2" */
    public final int code;
    /** TS 27.007 7.17 "index" - Not used currently*/
    public final int index;
    /** TS 27.007 7.17 "type" (MT only) - Not used currently */
    public final int type;
    /** TS 27.007 7.17 "number" (MT only) */
    public final String number;
    /** List of forwarded numbers, if any */
    public final String[] history;


    public ImsSuppServiceNotification(int notificationType, int code, int index, int type,
            String number, String[] history) {
        this.notificationType = notificationType;
        this.code = code;
        this.index = index;
        this.type = type;
        this.number = number;
        this.history = history;
    }

    /** @hide */
    public ImsSuppServiceNotification(Parcel in) {
        notificationType = in.readInt();
        code = in.readInt();
        index = in.readInt();
        type = in.readInt();
        number = in.readString();
        history = in.createStringArray();
    }

    @Override
    public String toString() {
        return "{ notificationType=" + notificationType +
                ", code=" + code +
                ", index=" + index +
                ", type=" + type +
                ", number=" + number +
                ", history=" + Arrays.toString(history) +
                " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(notificationType);
        out.writeInt(code);
        out.writeInt(index);
        out.writeInt(type);
        out.writeString(number);
        out.writeStringArray(history);
    }

    public static final @android.annotation.NonNull Creator<ImsSuppServiceNotification> CREATOR =
            new Creator<ImsSuppServiceNotification>() {
        @Override
        public ImsSuppServiceNotification createFromParcel(Parcel in) {
            return new ImsSuppServiceNotification(in);
        }

        @Override
        public ImsSuppServiceNotification[] newArray(int size) {
            return new ImsSuppServiceNotification[size];
        }
    };
}
