/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.contentcapture;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.contentcapture.ContentCaptureEvent;

import java.util.Arrays;
import java.util.List;

/**
 * Not needed anymore...
 *
 * @deprecated ContentCaptureService should use
 * {@code #onContentCaptureEvent(ContentCaptureSessionId, ContentCaptureEvent)} instead.
 *
 * @hide
 */
@SystemApi
@TestApi
@Deprecated
public final class ContentCaptureEventsRequest implements Parcelable {
// TODO(b/121051220): remove .java and .aidl once service implementation doesn't use it anymore

    private final ContentCaptureEvent mEvent;

    /** @hide */
    public ContentCaptureEventsRequest(@NonNull ContentCaptureEvent event) {
        mEvent = event;
    }

    /**
     * Gets the events.
     */
    @NonNull
    public List<ContentCaptureEvent> getEvents() {
        return Arrays.asList(mEvent);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mEvent, flags);
    }

    public static final Parcelable.Creator<ContentCaptureEventsRequest> CREATOR =
            new Parcelable.Creator<ContentCaptureEventsRequest>() {

        @Override
        public ContentCaptureEventsRequest createFromParcel(Parcel parcel) {
            return new ContentCaptureEventsRequest(parcel.readParcelable(null));
        }

        @Override
        public ContentCaptureEventsRequest[] newArray(int size) {
            return new ContentCaptureEventsRequest[size];
        }
    };
}
