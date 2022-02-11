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

package android.view;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.util.Set;

/**
 * Holds information about how to execute task transition animations.
 *
 * This class is intended to be used with IWindowManager.setTaskTransitionSpec methods when
 * we want more customization over the way default task transitions are executed.
 *
 * @hide
 */
public class TaskTransitionSpec implements Parcelable {
    /**
     * The background color to use during task animations (override the default background color)
     */
    public final int backgroundColor;

    /**
     * TEMPORARY FIELD (b/202383002)
     * TODO: Remove once we use surfaceflinger rounded corners on tasks rather than taskbar overlays
     *  or when shell transitions are fully enabled
     *
     * A set of {@InsetsState.InternalInsetsType}s we want to use as the source to set the bounds
     * of the task during the animation. Used to make sure that task animate above the taskbar.
     * Will also be used to crop to the frame size of the inset source to the inset size to prevent
     * the taskbar rounded corners overlay from being invisible during task transition animation.
     */
    public final Set<Integer> animationBoundInsets;

    public TaskTransitionSpec(
            int backgroundColor, Set<Integer> animationBoundInsets) {
        this.backgroundColor = backgroundColor;
        this.animationBoundInsets = animationBoundInsets;
    }

    public TaskTransitionSpec(Parcel in) {
        this.backgroundColor = in.readInt();

        int animationBoundInsetsSize = in.readInt();
        this.animationBoundInsets = new ArraySet<>(animationBoundInsetsSize);
        for (int i = 0; i < animationBoundInsetsSize; i++) {
            this.animationBoundInsets.add(in.readInt());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(backgroundColor);

        dest.writeInt(animationBoundInsets.size());
        for (int insetType : animationBoundInsets) {
            dest.writeInt(insetType);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TaskTransitionSpec>
            CREATOR = new Parcelable.Creator<TaskTransitionSpec>() {
                public TaskTransitionSpec createFromParcel(Parcel in) {
                    return new TaskTransitionSpec(in);
                }

                public TaskTransitionSpec[] newArray(int size) {
                    return new TaskTransitionSpec[size];
                }
            };
}
