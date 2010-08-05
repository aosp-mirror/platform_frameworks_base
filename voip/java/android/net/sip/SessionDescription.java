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

package android.net.sip;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Abstract class of a session description.
 * @hide
 */
public abstract class SessionDescription implements Parcelable {
    /** @hide */
    public static final Parcelable.Creator<SessionDescription> CREATOR =
            new Parcelable.Creator<SessionDescription>() {
                public SessionDescription createFromParcel(Parcel in) {
                    return new SessionDescriptionImpl(in);
                }

                public SessionDescription[] newArray(int size) {
                    return new SessionDescriptionImpl[size];
                }
            };

    /**
     * Gets the type of the session description; e.g., "SDP".
     *
     * @return the session description type
     */
    public abstract String getType();

    /**
     * Gets the raw content of the session description.
     *
     * @return the content of the session description
     */
    public abstract byte[] getContent();

    /** @hide */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(getType());
        out.writeByteArray(getContent());
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    private static class SessionDescriptionImpl extends SessionDescription {
        private String mType;
        private byte[] mContent;

        SessionDescriptionImpl(Parcel in) {
            mType = in.readString();
            mContent = in.createByteArray();
        }

        @Override
        public String getType() {
            return mType;
        }

        @Override
        public byte[] getContent() {
            return mContent;
        }
    }
}
