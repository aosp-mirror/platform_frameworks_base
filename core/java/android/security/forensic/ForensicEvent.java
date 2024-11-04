/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.forensic;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.Flags;
import android.util.ArrayMap;

import java.util.Map;

/**
 * A class that represents a forensic event.
 * @hide
 */
@FlaggedApi(Flags.FLAG_AFL_API)
public final class ForensicEvent implements Parcelable {
    private static final String TAG = "ForensicEvent";

    @NonNull
    private final String mType;

    @NonNull
    private final Map<String, String> mKeyValuePairs;

    public static final @NonNull Parcelable.Creator<ForensicEvent> CREATOR =
            new Parcelable.Creator<>() {
                public ForensicEvent createFromParcel(Parcel in) {
                    return new ForensicEvent(in);
                }

                public ForensicEvent[] newArray(int size) {
                    return new ForensicEvent[size];
                }
            };

    public ForensicEvent(@NonNull String type, @NonNull Map<String, String> keyValuePairs) {
        mType = type;
        mKeyValuePairs = keyValuePairs;
    }

    private ForensicEvent(@NonNull Parcel in) {
        mType = in.readString();
        mKeyValuePairs = new ArrayMap<>(in.readInt());
        in.readMap(mKeyValuePairs, getClass().getClassLoader(), String.class, String.class);
    }

    public String getType() {
        return mType;
    }

    public Map<String, String> getKeyValuePairs() {
        return mKeyValuePairs;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mType);
        out.writeInt(mKeyValuePairs.size());
        out.writeMap(mKeyValuePairs);
    }

    @FlaggedApi(Flags.FLAG_AFL_API)
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "ForensicEvent{"
                + "mType=" + mType
                + ", mKeyValuePairs=" + mKeyValuePairs
                + '}';
    }
}
