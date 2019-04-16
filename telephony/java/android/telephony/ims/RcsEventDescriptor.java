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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PROTECTED;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * @hide - used only for internal communication with the ircs service
 */
public abstract class RcsEventDescriptor implements Parcelable {
    protected final long mTimestamp;

    RcsEventDescriptor(long timestamp) {
        mTimestamp = timestamp;
    }

    /**
     * Creates an RcsEvent based on this RcsEventDescriptor. Overriding this method practically
     * allows an injection point for RcsEvent dependencies outside of the values contained in the
     * descriptor.
     */
    @VisibleForTesting(visibility = PROTECTED)
    public abstract RcsEvent createRcsEvent();

    RcsEventDescriptor(Parcel in) {
        mTimestamp = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mTimestamp);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
