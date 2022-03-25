/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A request for DSM-CC from broadcast signal.
 */
public final class DsmccRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_DSMCC;

    public static final @NonNull Parcelable.Creator<DsmccRequest> CREATOR =
            new Parcelable.Creator<DsmccRequest>() {
                @Override
                public DsmccRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public DsmccRequest[] newArray(int size) {
                    return new DsmccRequest[size];
                }
            };

    private final Uri mUri;

    static DsmccRequest createFromParcelBody(Parcel in) {
        return new DsmccRequest(in);
    }

    public DsmccRequest(int requestId, @RequestOption int option, @NonNull Uri uri) {
        super(REQUEST_TYPE, requestId, option);
        mUri = uri;
    }

    DsmccRequest(Parcel source) {
        super(REQUEST_TYPE, source);
        String uriString = source.readString();
        mUri = uriString == null ? null : Uri.parse(uriString);
    }

    /**
     * Gets the URI for DSM-CC object.
     */
    @NonNull
    public Uri getUri() {
        return mUri;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        String uriString = mUri == null ? null : mUri.toString();
        dest.writeString(uriString);
    }
}
