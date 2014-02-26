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

package android.media.session;

import android.media.session.IMediaController;
import android.os.Parcel;
import android.os.Parcelable;

public class MediaSessionToken implements Parcelable {
    private IMediaController mBinder;

    /**
     * @hide
     */
    MediaSessionToken(IMediaController binder) {
        mBinder = binder;
    }

    private MediaSessionToken(Parcel in) {
        mBinder = IMediaController.Stub.asInterface(in.readStrongBinder());
    }

    /**
     * @hide
     */
    IMediaController getBinder() {
        return mBinder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mBinder.asBinder());
    }

    public static final Parcelable.Creator<MediaSessionToken> CREATOR
            = new Parcelable.Creator<MediaSessionToken>() {
        @Override
        public MediaSessionToken createFromParcel(Parcel in) {
            return new MediaSessionToken(in);
        }

        @Override
        public MediaSessionToken[] newArray(int size) {
            return new MediaSessionToken[size];
        }
    };
}
