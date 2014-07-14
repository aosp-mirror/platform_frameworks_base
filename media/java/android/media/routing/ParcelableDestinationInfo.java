/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.routing;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Internal parcelable representation of a media destination.
 */
class ParcelableDestinationInfo implements Parcelable {
    public String id;
    public CharSequence name;
    public CharSequence description;
    public int iconResourceId;
    public Bundle extras;

    public static final Parcelable.Creator<ParcelableDestinationInfo> CREATOR =
            new Parcelable.Creator<ParcelableDestinationInfo>() {
        @Override
        public ParcelableDestinationInfo createFromParcel(Parcel source) {
            ParcelableDestinationInfo info = new ParcelableDestinationInfo();
            info.id = source.readString();
            info.name = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            info.description = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            info.iconResourceId = source.readInt();
            info.extras = source.readBundle();
            return info;
        }

        @Override
        public ParcelableDestinationInfo[] newArray(int size) {
            return new ParcelableDestinationInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        TextUtils.writeToParcel(name, dest, flags);
        TextUtils.writeToParcel(description, dest, flags);
        dest.writeInt(iconResourceId);
        dest.writeBundle(extras);
    }
}
