/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package com.android.ims;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;


/**
 * Parcelable object to handle IMS supplementary service notifications.
 *
 * @hide
 */
public class ImsSuppServiceNotification implements Parcelable {
    private static final String TAG = "ImsSuppServiceNotification";

    /** Type of notification: 0 = MO; 1 = MT */
    public int notificationType;
    /** TS 27.007 7.17 "code1" or "code2" */
    public int code;
    /** TS 27.007 7.17 "index" - Not used currently*/
    public int index;
    /** TS 27.007 7.17 "type" (MT only) - Not used currently */
    public int type;
    /** TS 27.007 7.17 "number" (MT only) */
    public String number;
    /** List of forwarded numbers, if any */
    public String[] history;

    public ImsSuppServiceNotification() {
    }

    public ImsSuppServiceNotification(Parcel in) {
        readFromParcel(in);
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

    private void readFromParcel(Parcel in) {
        notificationType = in.readInt();
        code = in.readInt();
        index = in.readInt();
        type = in.readInt();
        number = in.readString();
        history = in.createStringArray();
    }

    public static final Creator<ImsSuppServiceNotification> CREATOR =
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
