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
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public class TvProprietaryFunctionRequest extends BroadcastInfoRequest implements Parcelable {
    public static final int requestType = BroadcastInfoType.TV_PROPRIETARY_FUNCTION;

    public static final @NonNull Parcelable.Creator<TvProprietaryFunctionRequest> CREATOR =
            new Parcelable.Creator<TvProprietaryFunctionRequest>() {
                @Override
                public TvProprietaryFunctionRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TvProprietaryFunctionRequest[] newArray(int size) {
                    return new TvProprietaryFunctionRequest[size];
                }
            };

    private final String mNameSpace;
    private final String mName;
    private final String mArguments;

    public static TvProprietaryFunctionRequest createFromParcelBody(Parcel in) {
        return new TvProprietaryFunctionRequest(in);
    }

    public TvProprietaryFunctionRequest(int requestId, int option, String nameSpace,
            String name, String arguments) {
        super(requestType, requestId, option);
        mNameSpace = nameSpace;
        mName = name;
        mArguments = arguments;
    }

    protected TvProprietaryFunctionRequest(Parcel source) {
        super(requestType, source);
        mNameSpace = source.readString();
        mName = source.readString();
        mArguments = source.readString();
    }

    public String getNameSpace() {
        return mNameSpace;
    }

    public String getName() {
        return mName;
    }

    public String getArguments() {
        return mArguments;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mNameSpace);
        dest.writeString(mName);
        dest.writeString(mArguments);
    }
}
