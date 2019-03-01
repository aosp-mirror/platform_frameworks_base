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

import static android.view.RemoteAnimationTargetProto.CLIP_RECT;
import static android.view.RemoteAnimationTargetProto.CONTENT_INSETS;
import static android.view.RemoteAnimationTargetProto.IS_TRANSLUCENT;
import static android.view.RemoteAnimationTargetProto.LEASH;
import static android.view.RemoteAnimationTargetProto.MODE;
import static android.view.RemoteAnimationTargetProto.POSITION;
import static android.view.RemoteAnimationTargetProto.PREFIX_ORDER_INDEX;
import static android.view.RemoteAnimationTargetProto.SOURCE_CONTAINER_BOUNDS;
import static android.view.RemoteAnimationTargetProto.START_BOUNDS;
import static android.view.RemoteAnimationTargetProto.START_LEASH;
import static android.view.RemoteAnimationTargetProto.TASK_ID;
import static android.view.RemoteAnimationTargetProto.WINDOW_CONFIGURATION;

import android.annotation.IntDef;
import android.annotation.UnsupportedAppUsage;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
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

    /**
     * The app is in the set of resizing apps (eg. mode change) of this transition.
     */
    public static final int MODE_CHANGING = 2;

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_OPENING,
            MODE_CLOSING,
            MODE_CHANGING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /**
     * The {@link Mode} to describe whether this app is opening or closing.
     */
    @UnsupportedAppUsage
    public final @Mode int mode;

    /**
     * The id of the task this app belongs to.
     */
    @UnsupportedAppUsage
    public final int taskId;

    /**
     * The {@link SurfaceControl} object to actually control the transform of the app.
     */
    @UnsupportedAppUsage
    public final SurfaceControl leash;

    /**
     * The {@link SurfaceControl} for the starting state of a target if this transition is
     * MODE_CHANGING, {@code null)} otherwise. This is relative to the app window.
     */
    @UnsupportedAppUsage
    public final SurfaceControl startLeash;

    /**
     * Whether the app is translucent and may reveal apps behind.
     */
    @UnsupportedAppUsage
    public final boolean isTranslucent;

    /**
     * The clip rect window manager applies when clipping the app's main surface in screen space
     * coordinates. This is just a hint to the animation runner: If running a clip-rect animation,
     * anything that extends beyond these bounds will not have any effect. This implies that any
     * clip-rect animation should likely stop at these bounds.
     */
    @UnsupportedAppUsage
    public final Rect clipRect;

    /**
     * The insets of the main app window.
     */
    @UnsupportedAppUsage
    public final Rect contentInsets;

    /**
     * The index of the element in the tree in prefix order. This should be used for z-layering
     * to preserve original z-layer order in the hierarchy tree assuming no "boosting" needs to
     * happen.
     */
    @UnsupportedAppUsage
    public final int prefixOrderIndex;

    /**
     * The source position of the app, in screen spaces coordinates. If the position of the leash
     * is modified from the controlling app, any animation transform needs to be offset by this
     * amount.
     */
    @UnsupportedAppUsage
    public final Point position;

    /**
     * The bounds of the source container the app lives in, in screen space coordinates. If the crop
     * of the leash is modified from the controlling app, it needs to take the source container
     * bounds into account when calculating the crop.
     */
    @UnsupportedAppUsage
    public final Rect sourceContainerBounds;

    /**
     * The starting bounds of the source container in screen space coordinates. This is {@code null}
     * if the animation target isn't MODE_CHANGING. Since this is the starting bounds, it's size
     * should be equivalent to the size of the starting thumbnail. Note that sourceContainerBounds
     * is the end bounds of a change transition.
     */
    @UnsupportedAppUsage
    public final Rect startBounds;

    /**
     * The window configuration for the target.
     */
    @UnsupportedAppUsage
    public final WindowConfiguration windowConfiguration;

    /**
     * Whether the task is not presented in Recents UI.
     */
    @UnsupportedAppUsage
    public boolean isNotInRecents;

    public RemoteAnimationTarget(int taskId, int mode, SurfaceControl leash, boolean isTranslucent,
            Rect clipRect, Rect contentInsets, int prefixOrderIndex, Point position,
            Rect sourceContainerBounds, WindowConfiguration windowConfig, boolean isNotInRecents,
            SurfaceControl startLeash, Rect startBounds) {
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
        this.isNotInRecents = isNotInRecents;
        this.startLeash = startLeash;
        this.startBounds = startBounds == null ? null : new Rect(startBounds);
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
        isNotInRecents = in.readBoolean();
        startLeash = in.readParcelable(null);
        startBounds = in.readParcelable(null);
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
        dest.writeBoolean(isNotInRecents);
        dest.writeParcelable(startLeash, 0 /* flags */);
        dest.writeParcelable(startBounds, 0 /* flags */);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mode="); pw.print(mode);
        pw.print(" taskId="); pw.print(taskId);
        pw.print(" isTranslucent="); pw.print(isTranslucent);
        pw.print(" clipRect="); clipRect.printShortString(pw);
        pw.print(" contentInsets="); contentInsets.printShortString(pw);
        pw.print(" prefixOrderIndex="); pw.print(prefixOrderIndex);
        pw.print(" position="); position.printShortString(pw);
        pw.print(" sourceContainerBounds="); sourceContainerBounds.printShortString(pw);
        pw.println();
        pw.print(prefix); pw.print("windowConfiguration="); pw.println(windowConfiguration);
        pw.print(prefix); pw.print("leash="); pw.println(leash);
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(TASK_ID, taskId);
        proto.write(MODE, mode);
        leash.writeToProto(proto, LEASH);
        proto.write(IS_TRANSLUCENT, isTranslucent);
        clipRect.writeToProto(proto, CLIP_RECT);
        contentInsets.writeToProto(proto, CONTENT_INSETS);
        proto.write(PREFIX_ORDER_INDEX, prefixOrderIndex);
        position.writeToProto(proto, POSITION);
        sourceContainerBounds.writeToProto(proto, SOURCE_CONTAINER_BOUNDS);
        windowConfiguration.writeToProto(proto, WINDOW_CONFIGURATION);
        if (startLeash != null) {
            startLeash.writeToProto(proto, START_LEASH);
        }
        if (startBounds != null) {
            startBounds.writeToProto(proto, START_BOUNDS);
        }
        proto.end(token);
    }

    public static final @android.annotation.NonNull Creator<RemoteAnimationTarget> CREATOR
            = new Creator<RemoteAnimationTarget>() {
        public RemoteAnimationTarget createFromParcel(Parcel in) {
            return new RemoteAnimationTarget(in);
        }

        public RemoteAnimationTarget[] newArray(int size) {
            return new RemoteAnimationTarget[size];
        }
    };
}
