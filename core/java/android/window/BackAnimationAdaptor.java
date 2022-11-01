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

package android.window;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Object that describes how to run a remote back animation.
 *
 * @hide
 */
public class BackAnimationAdaptor implements Parcelable {

    private final IBackAnimationRunner mRunner;
    @BackNavigationInfo.BackTargetType
    private final int mSupportType;

    public BackAnimationAdaptor(IBackAnimationRunner runner, int supportType) {
        mRunner = runner;
        mSupportType = supportType;
    }

    public BackAnimationAdaptor(Parcel in) {
        mRunner = IBackAnimationRunner.Stub.asInterface(in.readStrongBinder());
        mSupportType = in.readInt();
    }

    public IBackAnimationRunner getRunner() {
        return mRunner;
    }

    @BackNavigationInfo.BackTargetType public int getSupportType() {
        return mSupportType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(mRunner);
        dest.writeInt(mSupportType);
    }

    public static final @android.annotation.NonNull Creator<BackAnimationAdaptor> CREATOR =
            new Creator<BackAnimationAdaptor>() {
        public BackAnimationAdaptor createFromParcel(Parcel in) {
            return new BackAnimationAdaptor(in);
        }

        public BackAnimationAdaptor[] newArray(int size) {
            return new BackAnimationAdaptor[size];
        }
    };
}
