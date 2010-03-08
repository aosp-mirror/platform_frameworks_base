/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.app.backup;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Descriptive information about a set of backed-up app data available for restore.
 * Used by IRestoreSession clients.
 *
 * @hide
 */
public class RestoreSet implements Parcelable {
    /**
     * Name of this restore set.  May be user generated, may simply be the name
     * of the handset model, e.g. "T-Mobile G1".
     */
    public String name;

    /**
     * Identifier of the device whose data this is.  This will be as unique as
     * is practically possible; for example, it might be an IMEI.
     */
    public String device;

    /**
     * Token that identifies this backup set unambiguously to the backup/restore
     * transport.  This is guaranteed to be valid for the duration of a restore
     * session, but is meaningless once the session has ended.
     */
    public long token;


    public RestoreSet() {
        // Leave everything zero / null
    }

    public RestoreSet(String _name, String _dev, long _token) {
        name = _name;
        device = _dev;
        token = _token;
    }


    // Parcelable implementation
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(name);
        out.writeString(device);
        out.writeLong(token);
    }

    public static final Parcelable.Creator<RestoreSet> CREATOR
            = new Parcelable.Creator<RestoreSet>() {
        public RestoreSet createFromParcel(Parcel in) {
            return new RestoreSet(in);
        }

        public RestoreSet[] newArray(int size) {
            return new RestoreSet[size];
        }
    };

    private RestoreSet(Parcel in) {
        name = in.readString();
        device = in.readString();
        token = in.readLong();
    }
}
