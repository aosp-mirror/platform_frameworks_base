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

package android.service.intelligence;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

// TODO(b/111276913): add javadocs / implement equals/hashcode/string
/** @hide */
@SystemApi
public final class InteractionSessionId implements Parcelable {

    /** @hide */
    public InteractionSessionId() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
    }

    public static final Parcelable.Creator<InteractionSessionId> CREATOR =
            new Parcelable.Creator<InteractionSessionId>() {

        @Override
        public InteractionSessionId createFromParcel(Parcel parcel) {
            // TODO(b/111276913): implement
            return null;
        }

        @Override
        public InteractionSessionId[] newArray(int size) {
            return new InteractionSessionId[size];
        }
    };
}
