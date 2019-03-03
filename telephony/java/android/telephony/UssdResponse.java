/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Represents the Ussd response, including
 * the message and the return code.
 * @hide
 */
public final class UssdResponse implements Parcelable {
    private CharSequence mReturnMessage;
    private String mUssdRequest;


    /**
     * Implement the Parcelable interface
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUssdRequest);
        TextUtils.writeToParcel(mReturnMessage, dest, 0);
    }

    public String getUssdRequest() {
        return mUssdRequest;
    }

    public CharSequence getReturnMessage() {
        return mReturnMessage;
    }

    /**
     * Implement the Parcelable interface
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * * Initialize the object from the request and return message.
     */
    public UssdResponse(String ussdRequest, CharSequence returnMessage) {
        mUssdRequest = ussdRequest;
        mReturnMessage = returnMessage;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<UssdResponse> CREATOR = new Creator<UssdResponse>() {

        @Override
        public UssdResponse createFromParcel(Parcel in) {
            String request = in.readString();
            CharSequence message = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            return new UssdResponse(request, message);
        }

        @Override
        public UssdResponse[] newArray(int size) {
            return new UssdResponse[size];
        }
    };
}
