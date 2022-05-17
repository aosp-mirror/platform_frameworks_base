/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.games;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;


/** @hide */
@VisibleForTesting
public final class GameSessionActivityResult implements Parcelable {

    public static final Creator<GameSessionActivityResult> CREATOR =
            new Creator<GameSessionActivityResult>() {
                @Override
                public GameSessionActivityResult createFromParcel(Parcel in) {
                    int resultCode = in.readInt();
                    Intent data = in.readParcelable(Intent.class.getClassLoader(), Intent.class);
                    return new GameSessionActivityResult(resultCode, data);
                }

                @Override
                public GameSessionActivityResult[] newArray(int size) {
                    return new GameSessionActivityResult[size];
                }
            };

    private final int mResultCode;
    @Nullable
    private final Intent mData;

    public GameSessionActivityResult(int resultCode, @Nullable Intent data) {
        mResultCode = resultCode;
        mData = data;
    }

    public int getResultCode() {
        return mResultCode;
    }

    @Nullable
    public Intent getData() {
        return mData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
        dest.writeParcelable(mData, flags);
    }
}
