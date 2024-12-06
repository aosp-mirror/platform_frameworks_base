/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * This class is used to provide a token count response for the
 * {@link OnDeviceIntelligenceManager#requestTokenInfo} outcome receiver.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public final class TokenInfo implements Parcelable {
    private final long mCount;
    private final PersistableBundle mInfoParams;

    /**
     * Construct a token count using the count value and associated params.
     */
    public TokenInfo(long count, @NonNull PersistableBundle persistableBundle) {
        this.mCount = count;
        mInfoParams = persistableBundle;
    }

    /**
     * Construct a token count using the count value.
     */
    public TokenInfo(long count) {
        this.mCount = count;
        this.mInfoParams = new PersistableBundle();
    }

    /**
     * Returns the token count associated with a request payload.
     */
    public long getCount() {
        return mCount;
    }

    /**
     * Returns the params representing token info.
     */
    @NonNull
    public PersistableBundle getInfoParams() {
        return mInfoParams;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mCount);
        dest.writePersistableBundle(mInfoParams);
    }

    public static final @NonNull Parcelable.Creator<TokenInfo> CREATOR
            = new Parcelable.Creator<>() {
        @Override
        public TokenInfo[] newArray(int size) {
            return new TokenInfo[size];
        }

        @Override
        public TokenInfo createFromParcel(@NonNull Parcel in) {
            return new TokenInfo(in.readLong(), in.readPersistableBundle());
        }
    };
}
