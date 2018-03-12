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
 * limitations under the License
 */

package android.view;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.view.WindowManager.TransitionType;

/**
 * Defines which animation types should be overridden by which remote animation.
 *
 * @hide
 */
public class RemoteAnimationDefinition implements Parcelable {

    private final SparseArray<RemoteAnimationAdapter> mTransitionAnimationMap;

    public RemoteAnimationDefinition() {
        mTransitionAnimationMap = new SparseArray<>();
    }

    /**
     * Registers a remote animation for a specific transition.
     *
     * @param transition The transition type. Must be one of WindowManager.TRANSIT_* values.
     * @param adapter The adapter that described how to run the remote animation.
     */
    public void addRemoteAnimation(@TransitionType int transition, RemoteAnimationAdapter adapter) {
        mTransitionAnimationMap.put(transition, adapter);
    }

    /**
     * Checks whether a remote animation for specific transition is defined.
     *
     * @param transition The transition type. Must be one of WindowManager.TRANSIT_* values.
     * @return Whether this definition has defined a remote animation for the specified transition.
     */
    public boolean hasTransition(@TransitionType int transition) {
        return mTransitionAnimationMap.get(transition) != null;
    }

    /**
     * Retrieves the remote animation for a specific transition.
     *
     * @param transition The transition type. Must be one of WindowManager.TRANSIT_* values.
     * @return The remote animation adapter for the specified transition.
     */
    public @Nullable RemoteAnimationAdapter getAdapter(@TransitionType int transition) {
        return mTransitionAnimationMap.get(transition);
    }

    public RemoteAnimationDefinition(Parcel in) {
        mTransitionAnimationMap = in.readSparseArray(null /* loader */);
    }

    /**
     * To be called by system_server to keep track which pid is running the remote animations inside
     * this definition.
     */
    public void setCallingPid(int pid) {
        for (int i = mTransitionAnimationMap.size() - 1; i >= 0; i--) {
            mTransitionAnimationMap.valueAt(i).setCallingPid(pid);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeSparseArray((SparseArray) mTransitionAnimationMap);
    }

    public static final Creator<RemoteAnimationDefinition> CREATOR =
            new Creator<RemoteAnimationDefinition>() {
        public RemoteAnimationDefinition createFromParcel(Parcel in) {
            return new RemoteAnimationDefinition(in);
        }

        public RemoteAnimationDefinition[] newArray(int size) {
            return new RemoteAnimationDefinition[size];
        }
    };
}
