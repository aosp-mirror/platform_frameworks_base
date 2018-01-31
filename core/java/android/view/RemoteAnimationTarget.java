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

import android.annotation.IntDef;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes an activity to be animated as part of a remote animation.
 *
 * @hide
 */
public class RemoteAnimationTarget implements Parcelable {

    /**
     * The app is in the set of opening apps of this transition.
     */
    public static final int MODE_OPENING = 0;

    /**
     * The app is in the set of closing apps of this transition.
     */
    public static final int MODE_CLOSING = 1;

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_OPENING,
            MODE_CLOSING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /**
     * The {@link Mode} to describe whether this app is opening or closing.
     */
    public final @Mode int mode;

    /**
     * The id of the task this app belongs to.
     */
    public final int taskId;

    /**
     * The {@link SurfaceControl} object to actually control the transform of the app.
     */
    public final SurfaceControl leash;

    /**
     * Whether the app is translucent and may reveal apps behind.
     */
    public final boolean isTranslucent;

    /**
     * The clip rect window manager applies when clipping the app's main surface in screen space
     * coordinates. This is just a hint to the animation runner: If running a clip-rect animation,
     * anything that extends beyond these bounds will not have any effect. This implies that any
     * clip-rect animation should likely stop at these bounds.
     */
    public final Rect clipRect;

    /**
     * The insets of the main app window.
     */
    public final Rect contentInsets;

    /**
     * The index of the element in the tree in prefix order. This should be used for z-layering
     * to preserve original z-layer order in the hierarchy tree assuming no "boosting" needs to
     * happen.
     */
    public final int prefixOrderIndex;

    /**
     * The source position of the app, in screen spaces coordinates. If the position of the leash
     * is modified from the controlling app, any animation transform needs to be offset by this
     * amount.
     */
    public final Point position;

    /**
     * The bounds of the source container the app lives in, in screen space coordinates. If the crop
     * of the leash is modified from the controlling app, it needs to take the source container
     * bounds into account when calculating the crop.
     */
    public final Rect sourceContainerBounds;

    /**
     * The window configuration for the target.
     */
    public final WindowConfiguration windowConfiguration;

    public RemoteAnimationTarget(int taskId, int mode, SurfaceControl leash, boolean isTranslucent,
            Rect clipRect, Rect contentInsets, int prefixOrderIndex, Point position,
            Rect sourceContainerBounds, WindowConfiguration windowConfig) {
        this.mode = mode;
        this.taskId = taskId;
        this.leash = leash;
        this.isTranslucent = isTranslucent;
        this.clipRect = new Rect(clipRect);
        this.contentInsets = new Rect(contentInsets);
        this.prefixOrderIndex = prefixOrderIndex;
        this.position = new Point(position);
        this.sourceContainerBounds = new Rect(sourceContainerBounds);
        this.windowConfiguration = windowConfig;
    }

    public RemoteAnimationTarget(Parcel in) {
        taskId = in.readInt();
        mode = in.readInt();
        leash = in.readParcelable(null);
        isTranslucent = in.readBoolean();
        clipRect = in.readParcelable(null);
        contentInsets = in.readParcelable(null);
        prefixOrderIndex = in.readInt();
        position = in.readParcelable(null);
        sourceContainerBounds = in.readParcelable(null);
        windowConfiguration = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeInt(mode);
        dest.writeParcelable(leash, 0 /* flags */);
        dest.writeBoolean(isTranslucent);
        dest.writeParcelable(clipRect, 0 /* flags */);
        dest.writeParcelable(contentInsets, 0 /* flags */);
        dest.writeInt(prefixOrderIndex);
        dest.writeParcelable(position, 0 /* flags */);
        dest.writeParcelable(sourceContainerBounds, 0 /* flags */);
        dest.writeParcelable(windowConfiguration, 0 /* flags */);
    }

    public static final Creator<RemoteAnimationTarget> CREATOR
            = new Creator<RemoteAnimationTarget>() {
        public RemoteAnimationTarget createFromParcel(Parcel in) {
            return new RemoteAnimationTarget(in);
        }

        public RemoteAnimationTarget[] newArray(int size) {
            return new RemoteAnimationTarget[size];
        }
    };
}
