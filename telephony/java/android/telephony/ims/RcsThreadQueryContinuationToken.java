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

package android.telephony.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A continuation token to provide for {@link RcsMessageStore#getRcsThreads}. Use this token to
 * break large queries into manageable chunks
 * @hide - TODO make this public
 */
public class RcsThreadQueryContinuationToken implements Parcelable {
    protected RcsThreadQueryContinuationToken(Parcel in) {
    }

    public static final Creator<RcsThreadQueryContinuationToken> CREATOR =
            new Creator<RcsThreadQueryContinuationToken>() {
                @Override
                public RcsThreadQueryContinuationToken createFromParcel(Parcel in) {
                    return new RcsThreadQueryContinuationToken(in);
                }

                @Override
                public RcsThreadQueryContinuationToken[] newArray(int size) {
                    return new RcsThreadQueryContinuationToken[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}
