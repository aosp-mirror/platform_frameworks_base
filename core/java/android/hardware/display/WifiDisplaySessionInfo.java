/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class contains information regarding a wifi display session
 * (such as session id, source ip address, etc.). This is needed for
 * Wifi Display Certification process.
 * <p>
 * This object is immutable.
 * </p>
 *
 * @hide
 */
public final class WifiDisplaySessionInfo implements Parcelable {
    private final boolean mClient;
    private final int mSessionId;
    private final String mGroupId;
    private final String mPassphrase;
    private final String mIP;

    public static final Creator<WifiDisplaySessionInfo> CREATOR =
            new Creator<WifiDisplaySessionInfo>() {
        @Override
        public WifiDisplaySessionInfo createFromParcel(Parcel in) {
            boolean client = (in.readInt() != 0);
            int session = in.readInt();
            String group = in.readString();
            String pp = in.readString();
            String ip = in.readString();

            return new WifiDisplaySessionInfo(client, session, group, pp, ip);
        }

        @Override
        public WifiDisplaySessionInfo[] newArray(int size) {
            return new WifiDisplaySessionInfo[size];
        }
    };

    public WifiDisplaySessionInfo() {
        this(true, 0, "", "", "");
    }

    public WifiDisplaySessionInfo(
            boolean client, int session, String group, String pp, String ip) {
        mClient = client;
        mSessionId = session;
        mGroupId = group;
        mPassphrase = pp;
        mIP = ip;
    }

    public boolean isClient() {
        return mClient;
    }

    public int getSessionId() {
        return mSessionId;
    }

    public String getGroupId() {
        return mGroupId;
    }

    public String getPassphrase() {
        return mPassphrase;
    }

    public String getIP() {
        return mIP;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mClient ? 1 : 0);
        dest.writeInt(mSessionId);
        dest.writeString(mGroupId);
        dest.writeString(mPassphrase);
        dest.writeString(mIP);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // For debugging purposes only.
    @Override
    public String toString() {
        return "WifiDisplaySessionInfo:"
                +"\n    Client/Owner: " + (mClient ? "Client":"Owner")
                +"\n    GroupId: " + mGroupId
                +"\n    Passphrase: " + mPassphrase
                +"\n    SessionId: " + mSessionId
                +"\n    IP Address: " + mIP
                ;
    }
}
