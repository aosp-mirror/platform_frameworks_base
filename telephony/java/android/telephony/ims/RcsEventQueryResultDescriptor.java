/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony.ims;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Contains the raw data backing a {@link RcsEventQueryResult}.
 *
 * @hide - used only for internal communication with the ircs service
 */
public class RcsEventQueryResultDescriptor implements Parcelable {
    private final RcsQueryContinuationToken mContinuationToken;
    private final List<RcsEventDescriptor> mEvents;

    public RcsEventQueryResultDescriptor(
            RcsQueryContinuationToken continuationToken,
            List<RcsEventDescriptor> events) {
        mContinuationToken = continuationToken;
        mEvents = events;
    }

    protected RcsEventQueryResult getRcsEventQueryResult() {
        List<RcsEvent> rcsEvents = mEvents.stream()
                .map(RcsEventDescriptor::createRcsEvent)
                .collect(Collectors.toList());

        return new RcsEventQueryResult(mContinuationToken, rcsEvents);
    }

    protected RcsEventQueryResultDescriptor(Parcel in) {
        mContinuationToken = in.readParcelable(RcsQueryContinuationToken.class.getClassLoader());
        mEvents = new LinkedList<>();
        in.readList(mEvents, null);
    }

    public static final Creator<RcsEventQueryResultDescriptor> CREATOR =
            new Creator<RcsEventQueryResultDescriptor>() {
        @Override
        public RcsEventQueryResultDescriptor createFromParcel(Parcel in) {
            return new RcsEventQueryResultDescriptor(in);
        }

        @Override
        public RcsEventQueryResultDescriptor[] newArray(int size) {
            return new RcsEventQueryResultDescriptor[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mContinuationToken, flags);
        dest.writeList(mEvents);
    }
}
