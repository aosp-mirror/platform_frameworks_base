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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * Object that describes how to run a remote back animation.
 *
 * @hide
 */
public class BackAnimationAdapter implements Parcelable {
    private final IBackAnimationRunner mRunner;
    private int[] mSupportedAnimators;

    public BackAnimationAdapter(IBackAnimationRunner runner) {
        mRunner = runner;
    }

    public BackAnimationAdapter(Parcel in) {
        mRunner = IBackAnimationRunner.Stub.asInterface(in.readStrongBinder());
        mSupportedAnimators = new int[in.readInt()];
        in.readIntArray(mSupportedAnimators);
    }

    public IBackAnimationRunner getRunner() {
        return mRunner;
    }

    /** Update the latest animators in the system. */
    public void updateSupportedAnimators(@NonNull ArrayList<Integer> animators) {
        final int size = animators.size();
        mSupportedAnimators = new int[size];
        for (int i = size - 1; i >= 0; --i) {
            mSupportedAnimators[i] = animators.get(i);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(mRunner);
        dest.writeInt(mSupportedAnimators.length);
        dest.writeIntArray(mSupportedAnimators);
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

    /**
     * Check if the back type is animatable.
     */
    public boolean isAnimatable(@BackNavigationInfo.BackTargetType int backType) {
        if (mSupportedAnimators == null) {
            return false;
        }
        for (int i = mSupportedAnimators.length - 1; i >= 0; --i) {
            if (backType == mSupportedAnimators[i]) {
                return true;
            }
        }
        return false;
    }
}
