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

/**
 * Internal parcelable representation of a media route.
 */
class ParcelableRouteInfo implements Parcelable {
    public String id;
    public int selectorIndex; // index of selector within list used for discovery
    public int features;
    public String[] protocols;
    public Bundle extras;

    public static final Parcelable.Creator<ParcelableRouteInfo> CREATOR =
            new Parcelable.Creator<ParcelableRouteInfo>() {
        @Override
        public ParcelableRouteInfo createFromParcel(Parcel source) {
            ParcelableRouteInfo info = new ParcelableRouteInfo();
            info.id = source.readString();
            info.selectorIndex = source.readInt();
            info.features = source.readInt();
            info.protocols = source.createStringArray();
            info.extras = source.readBundle();
            return info;
        }

        @Override
        public ParcelableRouteInfo[] newArray(int size) {
            return new ParcelableRouteInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeInt(selectorIndex);
        dest.writeInt(features);
        dest.writeStringArray(protocols);
        dest.writeBundle(extras);
    }
}
