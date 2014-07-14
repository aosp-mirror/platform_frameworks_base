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

import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Internal parcelable representation of a media route connection.
 */
class ParcelableConnectionInfo implements Parcelable {
    public AudioAttributes audioAttributes;
    public int presentationDisplayId = -1;
    // todo: volume
    public IBinder[] protocolBinders;
    public Bundle extras;

    public static final Parcelable.Creator<ParcelableConnectionInfo> CREATOR =
            new Parcelable.Creator<ParcelableConnectionInfo>() {
        @Override
        public ParcelableConnectionInfo createFromParcel(Parcel source) {
            ParcelableConnectionInfo info = new ParcelableConnectionInfo();
            if (source.readInt() != 0) {
                info.audioAttributes = AudioAttributes.CREATOR.createFromParcel(source);
            }
            info.presentationDisplayId = source.readInt();
            info.protocolBinders = source.createBinderArray();
            info.extras = source.readBundle();
            return info;
        }

        @Override
        public ParcelableConnectionInfo[] newArray(int size) {
            return new ParcelableConnectionInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (audioAttributes != null) {
            dest.writeInt(1);
            audioAttributes.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(presentationDisplayId);
        dest.writeBinderArray(protocolBinders);
        dest.writeBundle(extras);
    }
}
