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
public class BackAnimationAdapter implements Parcelable {
    private final IBackAnimationRunner mRunner;

    public BackAnimationAdapter(IBackAnimationRunner runner) {
        mRunner = runner;
    }

    public BackAnimationAdapter(Parcel in) {
        mRunner = IBackAnimationRunner.Stub.asInterface(in.readStrongBinder());
    }

    public IBackAnimationRunner getRunner() {
        return mRunner;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(mRunner);
    }

    public static final @android.annotation.NonNull Creator<BackAnimationAdapter> CREATOR =
            new Creator<BackAnimationAdapter>() {
        public BackAnimationAdapter createFromParcel(Parcel in) {
            return new BackAnimationAdapter(in);
        }

        public BackAnimationAdapter[] newArray(int size) {
            return new BackAnimationAdapter[size];
        }
    };
}
