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
 * A request for Stream Event from broadcast signal.
 */
public final class StreamEventRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_STREAM_EVENT;

    public static final @NonNull Parcelable.Creator<StreamEventRequest> CREATOR =
            new Parcelable.Creator<StreamEventRequest>() {
                @Override
                public StreamEventRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public StreamEventRequest[] newArray(int size) {
                    return new StreamEventRequest[size];
                }
            };

    private final Uri mTargetUri;
    private final String mEventName;

    static StreamEventRequest createFromParcelBody(Parcel in) {
        return new StreamEventRequest(in);
    }

    public StreamEventRequest(int requestId, @RequestOption int option, @NonNull Uri targetUri,
            @NonNull String eventName) {
        super(REQUEST_TYPE, requestId, option);
        this.mTargetUri = targetUri;
        this.mEventName = eventName;
    }

    StreamEventRequest(Parcel source) {
        super(REQUEST_TYPE, source);
        String uriString = source.readString();
        mTargetUri = uriString == null ? null : Uri.parse(uriString);
        mEventName = source.readString();
    }

    /**
     * Gets the URI for the DSM-CC Object or the event description file describing the event.
     */
    @NonNull
    public Uri getTargetUri() {
        return mTargetUri;
    }

    /**
     * Gets the name of the event.
     */
    @NonNull
    public String getEventName() {
        return mEventName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        String uriString = mTargetUri == null ? null : mTargetUri.toString();
        dest.writeString(uriString);
        dest.writeString(mEventName);
    }
}
