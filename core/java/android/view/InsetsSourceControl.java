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

package android.view;

import android.graphics.Point;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.InsetsState.InternalInsetType;

/**
 * Represents a parcelable object to allow controlling a single {@link InsetsSource}.
 * @hide
 */
public class InsetsSourceControl implements Parcelable {

    private final @InternalInsetType int mType;
    private final SurfaceControl mLeash;

    public InsetsSourceControl(@InternalInsetType int type, SurfaceControl leash) {
        mType = type;
        mLeash = leash;
    }

    public int getType() {
        return mType;
    }

    public SurfaceControl getLeash() {
        return mLeash;
    }

    public InsetsSourceControl(Parcel in) {
        mType = in.readInt();
        mLeash = in.readParcelable(null /* loader */);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeParcelable(mLeash, 0 /* flags*/);
    }

    public static final Creator<InsetsSourceControl> CREATOR
            = new Creator<InsetsSourceControl>() {
        public InsetsSourceControl createFromParcel(Parcel in) {
            return new InsetsSourceControl(in);
        }

        public InsetsSourceControl[] newArray(int size) {
            return new InsetsSourceControl[size];
        }
    };
}
