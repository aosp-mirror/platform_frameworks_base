/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * When the resource for the presence subscribe event has been terminated, the method
 * SubscribeResponseCallback#onResourceTerminated wil be called with a list of
 * RcsContactTerminatedReason.
 * @hide
 */
public final class RcsContactTerminatedReason implements Parcelable {
    private final Uri mContactUri;
    private final String mReason;

    public RcsContactTerminatedReason(Uri contact, String reason) {
        mContactUri = contact;
        mReason = reason;
    }

    private RcsContactTerminatedReason(Parcel in) {
        mContactUri = in.readParcelable(Uri.class.getClassLoader(), android.net.Uri.class);
        mReason = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mContactUri, flags);
        out.writeString(mReason);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<RcsContactTerminatedReason> CREATOR =
            new Creator<RcsContactTerminatedReason>() {
                @Override
                public RcsContactTerminatedReason createFromParcel(Parcel in) {
                    return new RcsContactTerminatedReason(in);
                }

                @Override
                public RcsContactTerminatedReason[] newArray(int size) {
                    return new RcsContactTerminatedReason[size];
                }
            };

    public Uri getContactUri() {
        return mContactUri;
    }

    public String getReason() {
        return mReason;
    }
}
